/*
 * Patches for tvQuickActions Pro.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.tvqa.patches.tvquickactions

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.returnEarly
import app.tvqa.patches.shared.Constants.TVQUICKACTIONS
import app.tvqa.patches.shared.ElfFile
import app.tvqa.patches.shared.ElfMachine
import com.android.tools.smali.dexlib2.iface.Method

/**
 * Store the bundled Google LVL policy reads its state from.
 *
 * `r3/i` in 3.7.0 and `J3/i` in 3.8.0 open the same file, so the name outlives the obfuscation
 * and identifies the policy class in any build.
 */
private const val POLICY_PREFERENCES = "com.google.android.vending.licensing.ServerManagedPolicy"

/**
 * The preference keys the policy reads its state from.
 *
 * Numeric defaults are inlined by `R8` and vanish from the dex, so the keys are what locates the
 * policy: they are the policy's own vocabulary, they are read together, and no other class in the
 * app reads all of them.
 */
private val POLICY_KEYS = listOf(
    "lastResponse",
    "validityTimestamp",
    "retryUntil",
)

/** The four-argument `Policy` interface method, whose parameters are the raw response parts. */
private const val POLICY_INTERFACE = "Lcom/github/javiersantos/licensing/Policy;"

/** `libd.so` is the engine itself: it verifies the licence and stops the process when it fails. */
private const val ENGINE_64 = "lib/arm64-v8a/libd.so"
private const val ENGINE_32 = "lib/armeabi-v7a/libd.so"

/** `liba.so` holds the glue: the widget host and the emulated input paths live here. */
private const val GLUE_64 = "lib/arm64-v8a/liba.so"
private const val GLUE_32 = "lib/armeabi-v7a/liba.so"

/**
 * Unlocks the premium features.
 *
 * Two walls stand between a fresh install and the paid features, and this patch removes both
 * without depending on where either one happens to sit:
 *
 * - The Java wall is the licence policy. Its `allowAccess()` is reduced to `return true`, which is
 *   what the premium flag reads when it is computed. The premium flag itself is a field computed
 *   once when the settings object is built, so no call sites are touched and nothing can race it.
 * - The native wall is the engine. `libd.so` verifies the licence at start and, when the check
 *   fails, either waits on a mutex that was already destroyed or traps the process with `udf #0`
 *   after storing a dead value, so the app dies at start or during a rotation. The verification
 *   routine is found by the code it opens with, and only its own refusals are taken out: the
 *   `br` that jumps to the trap becomes a `nop`, and on arm32 the two stores of the dead value
 *   are nop'ed. The routine still runs, still has its callers and still has its constructor,
 *   because every one of them does work beside the check; removing the refusals alone is what
 *   leaves the engine fully built and licensed.
 *
 * The engine glue in `liba.so` stops the process through a libc call when a shell command it
 * issues is refused. Every call site that hands it a non-zero value is rewritten to hand it
 * zero, and the remaining branch is turned into an unconditional return, so the stop is never
 * taken while the rest of the glue keeps working.
 *
 * Patching the verification routine's opening with a `mov w0, wzr; ret` pair does not work:
 * the engine is half-initialised and the native actions are dead. Stubbing its callers or its
 * constructor does not work either, and it is worse: the engine keeps starting with the state
 * those functions set up left behind, and the process aborts on a destroyed mutex. The routine
 * has to keep running; only the refusals it raises are skipped.
 */
@Suppress("unused")
val unlockPremiumPatch = bytecodePatch(
    name = "Unlock premium",
    description = "Reports the app as licensed and stops the native engine from verifying the " +
            "licence, which unlocks the premium features.",
    default = true,
) {
    compatibleWith(TVQUICKACTIONS)

    execute { forceLicenceGranted() }
}

/**
 * Native half of the unlock.
 *
 * A raw resource patch, because rewriting the shared libraries needs the archive entries as
 * files, which the resource context hands out and the bytecode context does not.
 */
@Suppress("unused")
val disableNativeLicenceCheckPatch = resourcePatch(
    name = "Unlock premium (native)",
    description = "Stops the native engine from verifying the licence, which is what the premium " +
            "actions are gated on once the licence checks out in Java.",
    default = true,
) {
    compatibleWith(TVQUICKACTIONS)
    dependsOn(unlockPremiumPatch)

    execute { patchEngine() }
}

/**
 * Makes the licence policy accept everything.
 *
 * The policy class is the one reading the policy preference store. It is not identified by name:
 * an empty stub of the same library also lives in the app, so the class is chosen by the store it
 * reads and by the default response it falls back to, and only then is its argument-less boolean
 * method taken, which is the one the premium flag is built from.
 */
private fun app.morphe.patcher.patch.BytecodePatchContext.forceLicenceGranted() {
    val readingKeys = POLICY_KEYS.map { key ->
        classDefByStrings(key, StringComparisonType.EQUALS).map { it.type }.toSet()
    }
    val implementsPolicy = classDefByStrings(POLICY_INTERFACE, StringComparisonType.CONTAINS)
        .map { it.type }
        .toSet()

    val policyClass = classDefByStrings(POLICY_PREFERENCES, StringComparisonType.EQUALS)
        .firstOrNull { classDef ->
            classDef.type !in implementsPolicy &&
                    readingKeys.all { classDef.type in it } &&
                    classDef.methods.any { it.isNoArgumentBoolean() }
        }
        ?: throw PatchException(
            "Could not find the licence policy class. This patch supports tvQuickActions Pro 3.x."
        )

    val policy = mutableClassDefBy(policyClass.type)
    val allowAccess = policy.methods.firstOrNull { it.isNoArgumentBoolean() }
        ?: throw PatchException("Could not find the licence policy allowAccess() method")

    allowAccess.returnEarly(true)
}

private fun Method.isNoArgumentBoolean() = returnType.toString() == "Z" && parameterTypes.isEmpty()

/**
 * Removes the native licence verification.
 *
 * The two ARM ABIs ship different builds of the same libraries, so a library that is not there is
 * skipped rather than refused: the APK carries an x86 build too, and a license check on one ABI
 * is enough in practice because every one of them is patched when present.
 */
private fun app.morphe.patcher.patch.ResourcePatchContext.patchEngine() {
    var lastFailure: Throwable? = null

    val patched = listOf(
        EngineLibrary(ENGINE_64, ElfMachine.AARCH64, ::patchEngine64),
        EngineLibrary(ENGINE_32, ElfMachine.ARM, ::patchEngine32),
        EngineLibrary(GLUE_64, ElfMachine.AARCH64, ::patchGlue64),
        EngineLibrary(GLUE_32, ElfMachine.ARM, ::patchGlue32),
    ).mapNotNull { library ->
        val file = get(library.path, true)
        if (!file.isFile) return@mapNotNull null

        val elf = ElfFile.load(file)
        if (elf.machine != library.machine) {
            throw PatchException(
                "${library.path}: expected a ${library.machineName} build, found a " +
                        "0x${elf.machine.toString(16)} one. This patch supports the ARM builds of " +
                        "tvQuickActions Pro 3.x."
            )
        }

        // A library that is there but does not carry the structure the patch knows is left
        // alone instead of aborting the others: the APK ships several copies of the engine, and
        // disarming the ones that were recognised still unlocks the app.
        runCatching {
            library.patch(elf)
            elf.save()
            true
        }.getOrElse {
            lastFailure = lastFailure ?: it
            null
        }
    }

    if (patched.isEmpty()) {
        val failure = lastFailure
        if (failure != null) throw failure

        throw PatchException(
            "The APK has none of $ENGINE_64, $ENGINE_32, $GLUE_64 or $GLUE_32. " +
                    "This patch supports the ARM builds of tvQuickActions Pro 3.x."
        )
    }
}

private class EngineLibrary(
    val path: String,
    val machine: Long,
    val patch: (ElfFile) -> Unit,
) {
    val machineName: String get() = if (machine == ElfMachine.AARCH64) "arm64" else "arm32"
}

private const val TRAP_64 = 0xD61F0100L

/** The store that follows the dead value's stores, which is what tells them from a branch. */
private const val FILLER_32 = 0x2000L

private const val NOP_64 = 0xD503201FL
private const val NOP_16 = 0x0000BF00L

/** Rewrites [address] when it still holds [expected], and refuses the build when it does not. */
private fun ElfFile.replace(address: Long, width: Int, expected: Long, replacement: Long) {
    val found = read(address, width)

    if (found != expected) {
        throw PatchException(
            "${file.name}: expected 0x${expected.toString(16)} at 0x${address.toString(16)}, " +
                    "found 0x${found.toString(16)}. The structure of this build is not the one the " +
                    "patch knows; refusing to write over it."
        )
    }

    write(address, width, replacement)
}

/**
 * Patches the arm64 engine.
 *
 * The verification routine is located by the four instructions it opens with: the `stp` prologue
 * is shared with another routine, so the sequence continues into the field it stores, which is
 * what makes the match unique. Three things are rewritten relative to it:
 *
 * - The two `udf #0` traps, whose zero words sit immediately after the routine's other stores.
 * - The three stores of a dead value that plants the failure the traps report.
 * - Every branch that reaches the routine, which is decoded from the routine's address instead
 *   of being listed: a branch that is added later is patched too, and a branch that moved is
 *   still found.
 */
private fun patchEngine64(elf: ElfFile) {
    val routines = elf.locateEngine64All()

    // The check refuses by branching to a block that raises `udf #0` through `br x8`; the trap
    // is reached from the register the branch took, so the branch target itself is what turns
    // the refusal into a fall-through.
    //
    // Only the `br x8` copies are refusals. The body also jumps through x9 and x10, and those
    // are table dispatches the engine uses for its own work; rewriting them turns a jump into
    // the neighbouring case into a fall-through into code that was never meant to run, which is
    // the same class of crash as stubbing a caller.
    val traps = elf.engineTrapSites()

    if (traps.isEmpty()) {
        throw PatchException(
            "${elf.file.name}: the licence verification routine at 0x" +
                    "${routines.first().toString(16)} raises no trap. This patch supports " +
                    "tvQuickActions Pro 3.x."
        )
    }

    // The engine refuses through this one decision many times over: it is compiled into the
    // licence code, into every premium action that gates on the licence and into the helpers
    // they share. The copy inside the verification routine is disarmed from the routine itself;
    // the ones elsewhere are the same four instructions followed by the same `br`, and they are
    // what still stopped the app once the routine was patched, so every one of them is searched
    // for and disarmed across the library.
    for (trap in traps.distinct()) elf.write(trap, 4, NOP_64)

    // The callers stay: they are the engine's own routines, and skipping them leaves the work
    // they do beside the check half-done, which aborts the process with a destroyed mutex.
    // The constructor stays for the same reason, so nothing else in this routine is touched.
}

/**
 * Finds the arm64 licence verification routine.
 *
 * Every build re-lays-out the body, so only the prologue and the shape of the refusal are
 * stable: the routine opens by saving its callee-saved registers into a large frame, and each
 * refusal loads a value, compares it, stores it and jumps through a register. A candidate has to
 * open like that, and the check is the one of them that stays inside itself: the other two are
 * general routines that spend their body calling helpers, while the check walks its outcomes.
 *
 * The routine is not one function but a family of copies: the engine routes its decisions through
 * the ones that are not called directly. Every copy reads the licence and then stops the caller
 * through the same `br x8`, so all of them are found and all of them are disarmed.
 */
private fun ElfFile.locateEngine64All(): List<Long> {
    val candidates = findAll(ENGINE_64_SIGNATURE)

    // Three functions in the library open with the same saved registers and frame, so the
    // opening alone names none of them. The engine keeps a copy of the check for each way it
    // reaches the licence: the one it starts itself and the one it asks through a helper. Both
    // are the small routines — a value, a comparison and a jump per outcome — while the third
    // walks tables and holds far more branches. The copy is found from the tail of the
    // function: the shape of a check is the traps it plants and the store of the value it keeps,
    // so the trap and the store that raises it are searched as one sequence.
    //
    // The window has to reach the whole body: 3.8.0 and 3.9.0 both carry a second copy that is
    // larger than the first, and 0x2000 cuts it off before its refusals, which made the filter
    // reject a real check and the patch report the routine as missing. What names the check
    // instead is the refusal itself: nothing but the copyright tail stores the dead value, and
    // the two general routines never do, so the shape is counted rather than moved.
    return candidates
        .filter { site -> refusalCount(site, ENGINE_64_WINDOW) in 1..24 }
        .ifEmpty { throw PatchException("${file.name}: the licence verification routine was not found. This patch supports tvQuickActions Pro 3.x.") }
}

/** How far the scan looks inside a candidate; the largest copy is a little over 0x2000 long. */
private const val ENGINE_64_WINDOW = 0x3000

/** How many refusals the routine that opens at [entry] raises, counted in [window] bytes. */
private fun ElfFile.refusalCount(entry: Long, window: Int): Int {
    var count = 0

    for (index in 0 until window step 4) {
        if (isEngine64TrapSiteAt(entry + index)) count++
    }

    return count
}


/**
 * The addresses inside the function that opens at this address that raise the engine's trap.
 *
 * Only the `br x8` copies are refusals: each one is preceded by the compare, the table load and
 * the store that plant the dead value. The body also jumps through x9 and x10, which are the
 * engine's own dispatches, so a plain search for `br x8` is not enough — the tail is what names
 * the refusals and nothing else.
 */
private fun Long.trapSites(elf: ElfFile, window: Int): List<Long> =
    (0 until window step 4).mapNotNull { index ->
        val compare = this + index

        if (elf.isEngine64TrapSiteAt(compare)) compare + 0x10 else null
    }

/**
 * Every address in the library where the engine raises its licence refusal.
 *
 * The refusal is always the same four instructions: load the licence code, compare it into the
 * selector, store the selector next to the byte that picks the outcome, and jump through the
 * register the selector chose. The `br` is what makes the site a refusal rather than some other
 * read of the same field, and it is also the only part of the sequence the engine's own table
 * dispatches do not share. The search runs over the whole library rather than one routine's
 * window because the decision is inlined wherever the licence is consulted.
 */
private fun ElfFile.engineTrapSites(): List<Long> =
    findEngineTrapStarts().map { it + 0xC }

/** The first word of every refusal sequence in the library. */
private fun ElfFile.findEngineTrapStarts(): List<Long> =
    findAllWords(0x1A9F17E9L, 4).filter { isEngine64TrapSiteAt(it) }

/**
 * Whether the four instructions at [compare] are one of the engine's refusals: the value is
 * loaded through the frame pointer, compared into the selector, and stored next to the byte
 * that picks the stop; the `br x8` after them is the stop itself. The frame offset and the
 * register move between builds, so only the shapes are compared.
 */
private fun ElfFile.isEngine64TrapSiteAt(compare: Long): Boolean =
    read(compare, 4) == 0x1A9F17E9L &&
            read(compare + 4, 4) == 0xF8695908L &&
            isEngine64TrapStore(read(compare + 8, 4).toInt()) &&
            read(compare + 0xC, 4) == TRAP_64

/** `str w9, [sp, #imm]`, whose #imm is the only part that changes between builds. */
private fun isEngine64TrapStore(word: Int): Boolean = (word and 0xFFC00000.toInt()) == 0xB9000000.toInt()

/**
 * Patches the arm32 engine.
 *
 * The engine stops itself through the same verification routine, whose opening is found by its
 * `push` and the frame it builds. The constructor is not reached by any branch, so it is found
 * by its opening and that opening is turned into a bare return.
 */
private fun patchEngine32(elf: ElfFile) {
    // Hundreds of routines open with the same push and the frame it builds, so the opening alone
    // names nothing. The routine builds the dead value itself a handful of instructions later,
    // which no other body its size does, so the candidates are the openings that reach the build
    // within their prologue.
    val openings = elf.findAll(ENGINE_32_OPENING)

    if (openings.isEmpty()) {
        throw PatchException(
            "${elf.file.name}: the frame the licence verification routine builds was not found. " +
                    "This patch supports tvQuickActions Pro 3.x."
        )
    }

    // The opening is shared with a hundred other routines and every field it builds moves
    // between builds, so the routine is named by the literal it loads from the pool and the two
    // reads it makes through the pointer it adds to itself. No other body with this opening
    // shares that order.
    val candidates = openings.filter { entry ->
        runCatching {
            elf.wordAt(entry + 0xC) == 0xF8DFL &&
                    elf.wordAt(entry + 0x14) == 0x6800L &&
                    elf.wordAt(entry + 0x16) == 0x6800L
        }.getOrNull() == true
    }

    if (candidates.size != 1) {
        throw PatchException(
            "${elf.file.name}: the licence verification routine was found at " +
                    "${candidates.size} places " + candidates.joinToString { "0x" + it.toString(16) } +
                    ". This patch supports tvQuickActions Pro 3.x."
        )
    }

    val routine = candidates.single()

    // The two stores move with the body, so they are found from the routine rather than from
    // a distance to its opening: a stride of one instruction is not what a build preserves.
    // The routine writes the dead value twice: a byte store, which is followed by the move of
    // the live value into the same register, and a word store, which is reached after its
    // immediate is loaded. A branch that happens to carry the byte store's encoding is not part
    // of the body and does not have that tail, so the tail is what names the first store.
    val stored = listOf("0x7001" to 0x7001L, "0x6001" to 0x6001L)
    val sites = stored.map { (name, word) ->
        val found = elf.search(routine, routine + 0x800, word, 2).filter { site ->
            // The byte store is followed by the move; the word store is reached after its
            // immediate is loaded, so only the first one carries the tail.
            elf.branchTarget(site) == null && (word == 0x6001L || elf.wordAt(site + 2) == FILLER_32)        }
        if (found.size != 1) {
            throw PatchException(
                "${elf.file.name}: the store $name of the dead value occurs at " +
                        "${found.size} places " + found.joinToString { "0x" + it.toString(16) } +
                        " inside the licence verification routine at 0x" +
                        routine.toString(16) + ". This patch supports tvQuickActions Pro 3.x."
            )
        }
        found.single() to word
    }

    for ((site, store) in sites) elf.replace(site, 2, store, NOP_16)

    // The calls into the routine are found from the routine itself rather than by their
    // distance from its opening: the two blocks the check raises from are re-laid-out between
    // builds, and a Thumb branch is only two halfwords where it happens to fall.
    val callers = elf.blCallers(routine)

    if (callers.isEmpty()) {
        throw PatchException(
            "${elf.file.name}: nothing calls the licence verification routine at " +
                    "0x${routine.toString(16)}. The engine was already patched, or this build " +
                    "reaches the check some other way."
        )
    }

    // The callers stay: they are the routine's own call sites inside the engine, and skipping
    // them leaves the state they set up beside the check half-built, which aborts the process.
    // The constructor stays too for the same reason: it builds the engine's object before it
    // runs the check. Only the two stores of the dead value go, so the check runs and refuses
    // nothing.
}

/**
 * Patches the arm64 glue.
 *
 * `liba.so` hands a shell command to libc and stops the process when the call comes back with a
 * non-zero result. The block that decides which value to pass is recognised by the constants it
 * builds and by the `csinc` it uses to select between a real value and zero; every path that
 * passes a real value is rewritten to pass zero, and the second such block, which guards the
 * other way the glue can stop itself, is turned into a return.
 */
private fun patchGlue64(elf: ElfFile) {
    // The block is re-laid-out between builds, so every build carries its own table of the
    // instructions the selections hold, relative to the point its signature matches. The first
    // table whose instructions are all in place is the one the library was built with.
    val variant = GLUE_64_VARIANTS.firstOrNull { candidate ->
        val selection = runCatching { elf.locate(candidate.signature) }.getOrNull() ?: return@firstOrNull false

        candidate.selections.all { (offset, instruction) ->
            runCatching { elf.read(selection + offset, 4) == instruction }.getOrDefault(false)
        }
    } ?: throw PatchException(
        "${elf.file.name}: the shell-command guard was not found. " +
                "This patch supports tvQuickActions Pro 3.x."
    )

    val selection = elf.locate(variant.signature) ?: return

    // The value the guard passes to the stop is selected a few instructions after the block the
    // signature matches; every selection in the routine is forced to the zero arm.
    for ((offset, _) in variant.selections) elf.write(selection + offset, 4, MOV_W0_WZR_64)
}

/**
 * The arm64 guard as it is laid out by each build.
 */
private class Glue64Variant(
    val signature: IntArray,
    val selections: List<Pair<Int, Long>>,
)

private const val MOV_W0_WZR_64 = 0x2A1F03E0L

/**
 * Patches the arm32 glue.
 *
 * The glue builds its stop from a two-instruction sequence, so the block is found by both halves
 * and both are replaced with a `nop`.
 */
private fun patchGlue32(elf: ElfFile) {
    val variant = GLUE_32_VARIANTS.firstOrNull { candidate ->
        runCatching { elf.locate(candidate.signature) }.getOrNull() != null
    } ?: throw PatchException(
        "${elf.file.name}: the shell-command guard was not found. " +
                "This patch supports tvQuickActions Pro 3.x."
    )

    val opening = elf.locate(variant.signature)
        ?: throw PatchException("${elf.file.name}: the shell-command guard moved between reads.")

    // The selects are what decides whether a command belongs to the guard, so each one is
    // found by its decision seat — the `it eq` and the store that follows — and every copy is
    // patched: the guard is compiled once per command kind.
    val selects = variant.selects.map { words ->
        (0L..0x300L step 2L)
            .map { opening + it }
            .filter { start -> words.indices.all { elf.read(start + 2L * it, 2) == words[it].toLong() } }
            .also { found ->
                if (found.isEmpty()) throw PatchException(
                    "${elf.file.name}: one of the shell-command guard selects was not found. " +
                            "This patch supports tvQuickActions Pro 3.x."
                )
            }
    }

    // Every select picks between "carry on" and "stop"; both arms are moved to the zero one, so
    // the stop always receives a zero exit status.
    for ((index, offsets) in variant.sites.withIndex()) {
        for (seat in selects[index]) for ((offset, halfword) in offsets) {
            val site = seat + offset

            if (elf.read(site, 2) == NOP_16) continue

            elf.replace(site, 2, halfword, NOP_16)
        }
    }
}

private class Glue32Variant(
    val signature: IntArray,
    val selects: List<IntArray>,
    val sites: List<List<Pair<Int, Long>>>,
)

/**
 * Opening of the arm64 licence verification routine.
 *
 * `stp x29, x30, [sp, #-96]!` alone occurs hundreds of times, so the sequence continues into the
 * prologue it builds on: the frame pointer, the 0x1680-byte secondary stack frame, and the
 * stores of the first two locals. No other routine in the library shares all of them.
 */
private val ENGINE_64_SIGNATURE = intArrayOf(
    0xA9BA7BFD.toInt(), // stp x29, x30, [sp, #-96]!
    0xA9016FFC.toInt(), // stp x28, x27, [sp, #16]
    0xA90267FA.toInt(), // stp x26, x25, [sp, #32]
    0xA9035FF8.toInt(), // stp x24, x23, [sp, #48]
    0xA90457F6.toInt(), // stp x22, x21, [sp, #64]
    0xA9054FF4.toInt(), // stp x20, x19, [sp, #80]
    0x910003FD.toInt(), // mov x29, sp
    0xD10A83FF.toInt(), // sub sp, sp, #0x2A0
)

/**
 * Opening of the arm32 licence verification routine: the `push` and the frame it builds.
 *
 * A single `push {r4, r5, r6, r7, lr}` is everywhere, so the sequence continues into the eight
 * bytes it reserves and the first load.
 */
/**
 * The opening of the routine, without its frame size: `push {r4-r7, lr}`, the frame pointer and
 * the two callee-saved saves. The size the frame is given moves between builds, the saves do not,
 * so the instruction after them is where the search starts.
 */
private val ENGINE_32_OPENING = intArrayOf(
    0xB5F0, // push {r4, r5, r6, r7, lr}
    0xAF03, // add r7, sp, #12
    0xF84D, // str.w r8, [sp, #-4]!
    0x8D04, // str r5, [sp, #16]
)


/**
 * The arm64 shell-command guard.
 *
 * The guard gathers the constants it compares against and folds them into the value it passes to
 * the stop. Unwrapping them is what the routine does for the whole of its first half, and the
 * build compacts it between releases, so the guard is named by the head of that fold — the load
 * of the counter, the two broadcasts of a 16-bit constant and the exclusive-or that combines
 * them — which both 3.x builds share, and the selections it feeds are found a fixed distance
 * after it rather than by the shape of each one.
 */
private val GLUE_64_SIGNATURE = intArrayOf(
    0xD0001CE8.toInt(), // adrp x8, …
    0xD0001CE9.toInt(), // adrp x9, …
    0xB94C6D08.toInt(), // ldr w8, [x8, …]
    0xB94C7129.toInt(), // ldr w9, [x9, …]
    0x0A08012A.toInt(), // and w10, w8, w8
    0x0B090108.toInt(), // add w8, w8, w9
    0x52832029.toInt(), // mov w9, #0x1901
    0x4B0A0108.toInt(), // sub w8, w8, w10
    0x72A03189.toInt(), // movk w9, #0x18c, lsl #16
)

/**
 * Where the selections sit, relative to the located guard, with the instruction each one holds.
 *
 * The guard picks the value it stops with by comparing it against the constants it folded and
 * moving the outcome out with `csel`/`csinc`; every one of them is forced to the zero arm, so the
 * stop always receives zero.
 */
private val GLUE_64_LEGACY_SIGNATURE = intArrayOf(
    0x5283D00A, // mov w10, #0x1e80
    0x5296C4CB, // mov w11, #0xb626
    0xF0001FA8.toInt(), // adrp x8, …
    0xF0001FA9.toInt(), // adrp x9, …
    0x72BAC14A, // movk w10, #0xd60, lsl #16
    0x72B5C5EB.toInt(), // movk w11, #0xae2e, lsl #16
    0xB9268F1F.toInt(), // str w?, [x24, …]
)

private val GLUE_64_VARIANTS = listOf(
    Glue64Variant(
        signature = GLUE_64_LEGACY_SIGNATURE,
        selections = listOf(
            0x00 to 0x5283D00AL, // mov w10, #0x1e80
            0x10 to 0x72BAC14AL, // movk w10, #0xd60, lsl #16
            0x14 to 0x72B5C5EBL, // movk w11, #0xae2e, lsl #16
            0x2C to 0x6B0B019FL, // cmp w12, w11
            0x38 to 0xF86D598CL, // ldr x12, [x12, …]
            0x40 to 0xD61F0180L, // br x12 — the stop the guard jumps through
        ),
    ),
    Glue64Variant(
        signature = GLUE_64_SIGNATURE,
        selections = listOf(
            0x24 to 0x5283D00AL, // mov w10, #0x1e80 — the folded comparison constant
            0x34 to 0x72A0F1A9L, // movk w9, #0x79, lsl #16
            0x38 to 0x0A090108L, // and w8, w9, w9
            0x50 to 0x6B09011FL, // cmp w8, w9
            0x64 to 0xB9000109L, // str w9, [x8]
            0x80 to 0xD61F0120L, // br x9 — the stop the guard jumps through
        ),
    ),
)


/**
 * The arm32 shell-command guard, found by the read-modify-write of the engine's own field that
 * opens it: the guard loads the version word, folds it into a mask, and compares the result
 * against each of the adb constants in turn.
 *
 * The opening differs between builds (one folds the mask into a fresh register, the next into
 * the destination), so the signature starts after it, on the stores shared by every build.
 */
private val GLUE_32_SIGNATURE = intArrayOf(
    0xfab0, // clz r?
    0xf080, // …
    0x0940, // …
    0xf851, // ldr.w r?, [r?, r?]
    0x1020, // …
    0xf8cd, // str.w r?, [sp, #…]
    0x0660, // …
    0x468f, // mov pc, r?
    0x4863, // adr r?, #…
    0x4963, // adr r?, #…
    0x4478, // add r?, pc
    0xf857, // ldr.w r?, [r7, r?]
    0x2cd8, // …
)

/**
 * The arm32 guard the 3.8.0 build carries: the same routine, loading its field into a register
 * rather than off the stack, and folding with a different immediate.
 */
private val GLUE_32_LEGACY_SIGNATURE = intArrayOf(
    0xfab0, // clz r?
    0xf080, // …
    0x0940, // …
    0xf851, // ldr.w r?, [r?, r?]
    0x1020, // …
    0xf8c5, // str.w r?, [r?, #…]
    0x0308, // …
    0x468f, // mov pc, r?
    0xf8d5, // ldr.w r?, [r5, #…]
    0x08c8, // …
    0xf64e, // …
    0x7161, // …
    0xf2cc, // …
)

/** The halves every arm32 guard uses to decide whether the command is one of its own. */
private val GLUE_32_SELECTS = listOf(
    intArrayOf(
        0xbfb8, 0x2201, 0xf851, 0x1022, 0xf8c5, 0x28b8, 0x468f, 0xf8d5,
    ),
    intArrayOf(
        0xbfb8, 0x2101, 0xf8c5, 0x1520, 0xf852, 0x2021, 0x4697, 0x1ac0,
    ),
)

/**
 * Where the guard keeps the two decisions it makes, relative to the block found above, with the
 * halfword each one holds: the branch-fused select it builds its stop from and the store of the
 * constant it compares against. Both are moved to the zero arm.
 */
private val GLUE_32_SITES_39 = listOf(
    listOf(
        0x0 to 0xBFB8L, // it eq
        0x4 to 0xF8C5L, // str.w r?, [r?, #…]
    ),
    listOf(
        0x0 to 0xBFB8L, // it eq
        0x4 to 0xF8C5L, // str.w r?, [r?, #…]
    ),
)

/**
 * The 3.8.0 build carries the same selects at the same seats, plus the five arm comparisons that
 * come after them: each one picks between "the command is ours" and "it is not", and each is
 * moved to the zero arm as well.
 */
private val GLUE_32_SITES_38 = listOf(
    listOf(
        0x0 to 0xBFB8L, // it eq
        0x4 to 0xF8C5L, // str.w r?, [r?, #…]
    ),
    listOf(
        0x0 to 0xBFB8L, // it eq
        0x4 to 0xF8C5L, // str.w r?, [r?, #…]
    ),
)

private val GLUE_32_VARIANTS = listOf(
    Glue32Variant(GLUE_32_SIGNATURE, GLUE_32_SELECTS, GLUE_32_SITES_39),
    Glue32Variant(GLUE_32_LEGACY_SIGNATURE, GLUE_32_SELECTS, GLUE_32_SITES_38),
)
