/*
 * Patches for tvQuickActions Pro.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.tvqa.patches.tvquickactions

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.registersUsed
import app.tvqa.patches.shared.Constants.TVQUICKACTIONS

@Suppress("unused")
val skipNativeEngineLoadPatch = bytecodePatch(
    name = "Skip native engine load",
    description = "Neutralises the System.loadLibrary(\"d\") call in App.onCreate. That library " +
            "aborts the process when the APK is not signed with the original certificate, which " +
            "is what makes every re-signed build crash on startup. The native engine becomes " +
            "unavailable, so features that depend on it (web widget overlay, web style button " +
            "emulation) stop working.",
    default = true
) {
    compatibleWith(TVQUICKACTIONS)

    execute {
        val match = AppLoadLibraryFingerprint.matchOrNull()

        // Releases before 3.8.0 never load the engine from onCreate, so there is nothing to do.
        if (match != null) {
            val call = match.instructionMatches.first()
            val register = call.instruction.registersUsed.firstOrNull() ?: 0

            // Replace the call itself with a harmless instruction. The const-string that held
            // the library name is left behind, where it does nothing.
            match.method.replaceInstruction(
                call.index,
                if (register <= 0xF) "const/4 v$register, 0x0" else "const/16 v$register, 0x0"
            )
        }
    }
}
