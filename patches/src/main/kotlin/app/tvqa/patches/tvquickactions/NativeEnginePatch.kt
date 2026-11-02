/*
 * Patches for tvQuickActions Pro.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.tvqa.patches.tvquickactions

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.tvqa.patches.shared.Constants.TVQUICKACTIONS_3_8
import java.io.File

/**
 * One architecture's copy of the engine library, and what has to be changed in it.
 *
 * The library aborts the process on an APK that is not signed with the developer's certificate.
 * The abort comes from the library's own code, so the file is edited directly:
 *
 * - On 64-bit ARM, constructor 2 of `INIT_ARRAY` destroys a global mutex, and every thread that
 *   locks that mutex afterwards dies with
 *   `FORTIFY: pthread_mutex_lock called on a destroyed mutex`. Index 1 of the same array guards
 *   itself with a variable it sets on its first run, so pointing index 2 at it makes that
 *   constructor a no-op while the other eleven keep initialising the engine.
 * - On both architectures, the verification routine is salted with traps that are meant to fire
 *   when the APK is not the developer's: `udf #0` (SIGILL) and stores of the marker `0xdead` to a
 *   null pointer (SIGSEGV). They sit inside the routine's own arithmetic, which continues on both
 *   sides of them, so each is replaced by a no-op and the routine keeps working.
 *
 * Replacing the whole routine with an early return was tried first and it hangs on Android TV 10:
 * the routine does more than verify, and the engine waits for what it sets up.
 */
private class LibraryPatch(
    val path: String,
    /** `e_machine` of the ELF file, which also tells the two formats apart. */
    val machine: Long,
    /** An instruction that identifies the build, checked but never changed. */
    val guardAddress: Long,
    val guardWidth: Int,
    val guardExpected: Long,
    /** Constructor slot to redirect, as (address of the INIT_ARRAY slot, expected, replacement). */
    val constructorSlot: Long? = null,
    val constructorExpected: Long? = null,
    val constructorReplacement: Long? = null,
    val traps: List<Trap>,
)

/** An instruction to neutralise: where it is, what it is now, and what it becomes. */
private class Trap(val address: Long, val expected: Long, val width: Int, val replacement: Long)

private const val NOP_64 = 0xD503201FL // nop
private const val NOP_16 = 0x0000BF00L // nop.n

/** 64-bit ARM. */
private val ARM64 = LibraryPatch(
    path = "lib/arm64-v8a/libd.so",
    machine = 0xB7,
    guardAddress = 0x6e2b80,
    guardWidth = 4,
    guardExpected = 0xA9BA7BFDL, // stp x29, x30, [sp, #-96]!
    constructorSlot = 0xbf96e8L + 8 * 2,
    constructorExpected = 0x68fb4cL,
    constructorReplacement = 0xb60cd8L,
    traps = listOf(
        Trap(0x6e2e1c, 0x00000000L, 4, NOP_64), // udf #0
        Trap(0x6e2e30, 0x00000000L, 4, NOP_64), // udf #0
        Trap(0x6e33f8, 0xB900014BL, 4, NOP_64), // str w11, [x10], where x10 was just zeroed
        Trap(0x6e3414, 0xB900014DL, 4, NOP_64), // str w13, [x10], where x10 was just zeroed
    ),
)

/**
 * 32-bit ARM, which is what Android TV 10 boxes commonly run.
 *
 * This build's verification routine carries a single trap. Its constructors were left alone: on
 * the 64-bit build the one that had to be disabled was found with a device tombstone, and there
 * is no equivalent evidence for this build yet.
 */
private val ARM32 = LibraryPatch(
    path = "lib/armeabi-v7a/libd.so",
    machine = 0x28,
    guardAddress = 0x356ad8,
    guardWidth = 2,
    guardExpected = 0xB5F0L, // push {r4, r5, r6, r7, lr}
    traps = listOf(
        Trap(0x356fe8, 0x6001L, 2, NOP_16), // str r1, [r0], where r0 was just zeroed
    ),
)

/**
 * The engine library refuses to run on an APK that is not signed with the developer's
 * certificate, which is what makes **Web Widget Overlay** and **button emulation** unusable: the
 * app dies as the library loads. Neither dropping the `System.loadLibrary("d")` call nor stubbing
 * the Java wrappers keeps the engine, because the abort comes from the library's own code.
 *
 * This patch edits the library itself, for both the 64-bit and the 32-bit ARM build, so the
 * engine loads and keeps working.
 */
@Suppress("unused")
val nativeEnginePatch = resourcePatch(
    name = "Unlock native engine",
    description = "Patches the native engine library (libd.so) so that it loads on a re-signed " +
            "APK. On 64-bit ARM the anti-tamper constructor that destroys a global mutex is made " +
            "a no-op, and on both architectures the trap instructions the verification routine " +
            "plants inside its own code - udf #0 (SIGILL) and stores of 0xdead to a null pointer " +
            "(SIGSEGV) - are replaced by no-ops, leaving the routine's work intact. This is what " +
            "makes Web Widget Overlay and button emulation work.",
    default = true
) {
    compatibleWith(TVQUICKACTIONS_3_8)
    dependsOn(unlockPremiumPatch)

    execute {
        val patched = listOf(ARM64, ARM32).filter { target ->
            val lib = get(target.path)

            if (lib.isFile) {
                patchLibrary(lib, target)
                true
            } else {
                false
            }
        }

        if (patched.isEmpty()) {
            throw PatchException(
                "The APK has neither lib/arm64-v8a/libd.so nor lib/armeabi-v7a/libd.so. " +
                        "This patch supports the ARM builds only."
            )
        }
    }
}

private fun patchLibrary(lib: File, target: LibraryPatch) {
    val data = lib.readBytes()
    val is64Bit = elfIs64Bit(data, lib.name)
    val segments = segments(data, is64Bit)

    if (target.machine != u16(data, 0x12)) {
        throw PatchException(
            "${target.path}: expected a 0x${target.machine.toString(16)} ELF, found " +
                    "0x${u16(data, 0x12).toString(16)}. Patch tvQuickActions Pro 3.8.0."
        )
    }

    // The build is identified by one instruction of the verification routine, which is never
    // changed. A library that does not match is refused rather than corrupted.
    if (u(data, segments.offsetOf(target.guardAddress), target.guardWidth) != target.guardExpected) {
        throw PatchException(
            "${target.path} is not the library this patch was written for. " +
                    "Patch tvQuickActions Pro 3.8.0."
        )
    }

    if (target.constructorSlot != null) {
        redirectConstructor(data, segments, is64Bit, target)
    }

    for (trap in target.traps) {
        val offset = segments.offsetOf(trap.address)
        val found = u(data, offset, trap.width)

        if (found != trap.expected) {
            throw PatchException(
                "${target.path}: expected a trap at 0x${trap.address.toString(16)}, found " +
                        "0x${found.toString(16)}. Patch tvQuickActions Pro 3.8.0."
            )
        }

        w(data, offset, trap.width, trap.replacement)
    }

    lib.writeBytes(data)
}

/**
 * `INIT_ARRAY` is filled at load time from relocations, so a constructor is replaced by its
 * pointer: in a 64-bit library the pointer is a relocation addend, in a 32-bit one it already
 * sits in the table.
 */
private fun redirectConstructor(
    data: ByteArray,
    segments: List<Segment>,
    is64Bit: Boolean,
    target: LibraryPatch,
) {
    val slot = target.constructorSlot!!
    val expected = target.constructorExpected!!
    val replacement = target.constructorReplacement!!

    if (!is64Bit) {
        val offset = segments.offsetOf(slot)

        if (u(data, offset, 4) != expected) {
            throw PatchException("${target.path}: INIT_ARRAY slot is not the expected constructor")
        }

        w(data, offset, 4, replacement)
        return
    }

    val dynamic = segments.firstOrNull { it.type == PT_DYNAMIC }
        ?: throw PatchException("${target.path} has no PT_DYNAMIC segment")

    var rela = -1L
    var relaSize = 0L
    var relaEntrySize = 24L

    var offset = dynamic.offset.toInt()
    val end = offset + dynamic.fileSize.toInt()
    while (offset < end) {
        val tag = u(data, offset, 8)
        if (tag == DT_NULL) break

        val value = u(data, offset + 8, 8)
        when (tag) {
            DT_RELA -> rela = value
            DT_RELASZ -> relaSize = value
            DT_RELAENT -> relaEntrySize = value
        }
        offset += 16
    }

    if (rela < 0 || relaSize <= 0) {
        throw PatchException("${target.path} has no relocation table")
    }

    var found = false
    var entry = rela
    while (entry < rela + relaSize) {
        if (u(data, entry.toInt(), 8) == slot) {
            val addendOffset = (entry + 16).toInt()

            if (u(data, addendOffset, 8) != expected) {
                throw PatchException("${target.path}: INIT_ARRAY slot is not the expected constructor")
            }

            w(data, addendOffset, 8, replacement)
            found = true
        }
        entry += relaEntrySize
    }

    if (!found) throw PatchException("${target.path}: could not find the constructor slot")
}

private class Segment(val type: Long, val offset: Long, val vaddr: Long, val fileSize: Long)

private fun elfIs64Bit(data: ByteArray, name: String): Boolean {
    if (data.size < 64 ||
        data[0] != 0x7F.toByte() || data[1] != 'E'.code.toByte() ||
        data[2] != 'L'.code.toByte() || data[3] != 'F'.code.toByte()
    ) {
        throw PatchException("$name is not an ELF file")
    }
    if (data[5].toInt() != 1) throw PatchException("$name is not little-endian")

    return when (data[4].toInt()) {
        1 -> false
        2 -> true
        else -> throw PatchException("$name is neither a 32-bit nor a 64-bit ELF file")
    }
}

private fun segments(data: ByteArray, is64Bit: Boolean): List<Segment> {
    val headerOffset: Long
    val headerSize: Long
    val headerCount: Long

    if (is64Bit) {
        headerOffset = u(data, 0x20, 8)
        headerSize = u(data, 0x36, 2)
        headerCount = u(data, 0x38, 2)
    } else {
        headerOffset = u(data, 0x1C, 4)
        headerSize = u(data, 0x2A, 2)
        headerCount = u(data, 0x2C, 2)
    }

    return (0 until headerCount).map { index ->
        val offset = (headerOffset + index * headerSize).toInt()

        if (is64Bit) {
            Segment(
                type = u(data, offset, 4),
                offset = u(data, offset + 8, 8),
                vaddr = u(data, offset + 16, 8),
                fileSize = u(data, offset + 32, 8),
            )
        } else {
            Segment(
                type = u(data, offset, 4),
                offset = u(data, offset + 4, 4),
                vaddr = u(data, offset + 8, 4),
                fileSize = u(data, offset + 16, 4),
            )
        }
    }
}

private fun List<Segment>.offsetOf(vaddr: Long): Int {
    val segment = firstOrNull { it.type == PT_LOAD && vaddr >= it.vaddr && vaddr < it.vaddr + it.fileSize }
        ?: throw PatchException("0x${vaddr.toString(16)} is not inside a loadable segment")

    return (segment.offset + (vaddr - segment.vaddr)).toInt()
}

private fun u(data: ByteArray, offset: Int, width: Int): Long {
    var value = 0L

    for (byte in 0 until width) {
        value = value or ((data[offset + byte].toLong() and 0xFF) shl (8 * byte))
    }

    return value
}

private fun w(data: ByteArray, offset: Int, width: Int, value: Long) {
    for (byte in 0 until width) {
        data[offset + byte] = ((value shr (8 * byte)) and 0xFF).toByte()
    }
}

private fun u16(data: ByteArray, offset: Int) = u(data, offset, 2)

// ELF constants.
private const val PT_LOAD = 1L
private const val PT_DYNAMIC = 2L
private const val DT_NULL = 0L
private const val DT_RELA = 7L
private const val DT_RELASZ = 8L
private const val DT_RELAENT = 9L
