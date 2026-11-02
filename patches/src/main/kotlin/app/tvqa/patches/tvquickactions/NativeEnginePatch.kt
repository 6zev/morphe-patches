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

/** The native engine library of the 64-bit ARM build. */
private const val LIBD_PATH = "lib/arm64-v8a/libd.so"

/**
 * `INIT_ARRAY` of the library, and the two constructors that matter.
 *
 * Index 2 (`0x68fb4c`) is an anti-tamper constructor: it destroys a global mutex, after which
 * any thread that locks that mutex dies with
 * `FORTIFY: pthread_mutex_lock called on a destroyed mutex`.
 *
 * Index 1 (`0xb60cd8`) guards itself behind a variable it sets on its first run, so calling it
 * again returns immediately. Pointing index 2 at it turns that constructor into a no-op while
 * leaving the other eleven constructors - and with them the engine's own initialisation - alone.
 */
private const val INIT_ARRAY_VADDR = 0xbf96e8L
private const val ANTI_TAMPER_CTOR = 0x68fb4cL
private const val IDEMPOTENT_CTOR = 0xb60cd8L

/**
 * The verification routine of the library, and the two instructions that open it.
 *
 * Its failure paths are a minefield: two `udf #0` (SIGILL) and two deliberate writes of `0xdead`
 * to a null pointer (SIGSEGV). It is reached from four direct call sites and from eight entries
 * of function-pointer tables, and every caller ignores its return value, so the function as a
 * whole is replaced by one that returns 0.
 */
private const val CHECK_FUNCTION = 0x6e2b80L
private const val CHECK_FUNCTION_PROLOGUE = 0xA9BA7BFDL // stp x29, x30, [sp, #-96]!
private const val CHECK_FUNCTION_SECOND = 0xA9016FFCL // stp x28, x27, [sp, #16]
private const val MOV_W0_ZERO = 0x2A1F03E0L // mov w0, wzr
private const val RET = 0xD65F03C0L // ret

// AArch64 ELF constants.
private const val EM_AARCH64 = 0xB7L
private const val PT_LOAD = 1L
private const val PT_DYNAMIC = 2L
private const val DT_RELA = 7L
private const val DT_RELASZ = 8L
private const val DT_RELAENT = 9L
private const val DT_NULL = 0L

/**
 * The engine library (`libd.so`) aborts the process on an APK that is not signed with the
 * developer's certificate. That abort comes from the library's own constructors and from a
 * verification routine the engine runs periodically on a worker thread, so neither removing the
 * `System.loadLibrary("d")` call nor stubbing the Java wrappers can keep the engine working.
 *
 * This patch edits the library itself, which then loads and keeps working, and that is what makes
 * **Web Widget Overlay** and **button emulation** available.
 */
@Suppress("unused")
val nativeEnginePatch = resourcePatch(
    name = "Unlock native engine",
    description = "Patches the native engine of the 64-bit ARM build (libd.so) so that it loads " +
            "on a re-signed APK: the anti-tamper constructor that destroys a global mutex and " +
            "the library's verification routine, whose failure paths are deliberate SIGILL and " +
            "SIGSEGV instructions, are neutralised. This is what makes Web Widget Overlay and " +
            "button emulation work.",
    default = true
) {
    compatibleWith(TVQUICKACTIONS_3_8)
    dependsOn(unlockPremiumPatch)

    execute {
        val lib = get(LIBD_PATH)
        if (!lib.isFile) {
            throw PatchException(
                "The APK has no $LIBD_PATH. This patch supports the 64-bit ARM build only."
            )
        }

        patchLibd(lib)
    }
}

private fun patchLibd(lib: File) {
    val data = lib.readBytes()

    if (data.size < 64 ||
        data[0] != 0x7F.toByte() || data[1] != 'E'.code.toByte() ||
        data[2] != 'L'.code.toByte() || data[3] != 'F'.code.toByte()
    ) {
        throw PatchException("${lib.name} is not an ELF file")
    }
    if (data[4].toInt() != 2 || data[5].toInt() != 1) {
        throw PatchException("${lib.name} is not a 64-bit little-endian ELF file")
    }
    if (u16(data, 0x12) != EM_AARCH64) {
        throw PatchException("${lib.name} is not an AArch64 library")
    }

    val segments = programHeaders(data)
    neutraliseAntiTamperConstructor(data, segments)
    neutraliseCheckFunction(data, segments)

    lib.writeBytes(data)
}

private fun neutraliseAntiTamperConstructor(data: ByteArray, segments: List<Segment>) {
    val dynamic = segments.firstOrNull { it.type == PT_DYNAMIC }
        ?: throw PatchException("The library has no PT_DYNAMIC segment")

    var rela = -1L
    var relaSize = 0L
    var relaEntrySize = 24L
    var initArray = -1L

    var offset = dynamic.offset.toInt()
    val end = offset + dynamic.fileSize.toInt()
    while (offset < end) {
        val tag = u64(data, offset)
        if (tag == DT_NULL) break

        val value = u64(data, offset + 8)
        when (tag) {
            DT_RELA -> rela = value
            DT_RELASZ -> relaSize = value
            DT_RELAENT -> relaEntrySize = value
            25L -> initArray = value
        }
        offset += 16
    }

    if (initArray != INIT_ARRAY_VADDR || rela < 0 || relaSize <= 0) {
        throw PatchException(
            "This is not the libd.so this patch was written for. " +
                    "Patch tvQuickActions Pro 3.8.0."
        )
    }

    // INIT_ARRAY is filled by R_AARCH64_RELATIVE relocations, so its slot for the anti-tamper
    // constructor is found by its address and rewritten by its addend.
    val slot = initArray + 8 * 2
    var found = false

    var entry = rela
    while (entry < rela + relaSize) {
        if (u64(data, entry.toInt()) == slot) {
            val addendOffset = (entry + 16).toInt()
            if (u64(data, addendOffset) != ANTI_TAMPER_CTOR) {
                throw PatchException("${LIBD_PATH}: INIT_ARRAY[2] is not the expected constructor")
            }
            w64(data, addendOffset, IDEMPOTENT_CTOR)
            found = true
        }
        entry += relaEntrySize
    }

    if (!found) throw PatchException("${LIBD_PATH}: could not find the anti-tamper constructor")
}

private fun neutraliseCheckFunction(data: ByteArray, segments: List<Segment>) {
    val offset = segments.offsetOf(CHECK_FUNCTION)

    if (u32(data, offset) != CHECK_FUNCTION_PROLOGUE ||
        u32(data, offset + 4) != CHECK_FUNCTION_SECOND
    ) {
        throw PatchException(
            "This is not the libd.so this patch was written for. " +
                    "Patch tvQuickActions Pro 3.8.0."
        )
    }

    w32(data, offset, MOV_W0_ZERO)
    w32(data, offset + 4, RET)
}

private class Segment(val type: Long, val offset: Long, val vaddr: Long, val fileSize: Long)

private fun programHeaders(data: ByteArray): List<Segment> {
    val headerOffset = u64(data, 0x20)
    val headerSize = u16(data, 0x36)
    val headerCount = u16(data, 0x38)

    return (0 until headerCount).map { index ->
        val offset = (headerOffset + index * headerSize).toInt()
        Segment(
            type = u32(data, offset),
            offset = u64(data, offset + 8),
            vaddr = u64(data, offset + 16),
            fileSize = u64(data, offset + 32),
        )
    }
}

private fun List<Segment>.offsetOf(vaddr: Long): Int {
    val segment = firstOrNull { it.type == PT_LOAD && vaddr >= it.vaddr && vaddr < it.vaddr + it.fileSize }
        ?: throw PatchException("0x${vaddr.toString(16)} is not inside a loadable segment")

    return (segment.offset + (vaddr - segment.vaddr)).toInt()
}

private fun u16(data: ByteArray, offset: Int): Long =
    (data[offset].toLong() and 0xFF) or ((data[offset + 1].toLong() and 0xFF) shl 8)

private fun u32(data: ByteArray, offset: Int): Long =
    u16(data, offset) or (u16(data, offset + 2) shl 16)

private fun u64(data: ByteArray, offset: Int): Long =
    u32(data, offset) or (u32(data, offset + 4) shl 32)

private fun w32(data: ByteArray, offset: Int, value: Long) {
    data[offset] = (value and 0xFF).toByte()
    data[offset + 1] = ((value shr 8) and 0xFF).toByte()
    data[offset + 2] = ((value shr 16) and 0xFF).toByte()
    data[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

private fun w64(data: ByteArray, offset: Int, value: Long) {
    w32(data, offset, value and 0xFFFFFFFFL)
    w32(data, offset + 4, (value ushr 32) and 0xFFFFFFFFL)
}
