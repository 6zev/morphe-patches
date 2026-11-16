/*
 * Patches for tvQuickActions Pro.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.tvqa.patches.tvquickactions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.tvqa.patches.shared.Constants.TVQUICKACTIONS_3_8
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Builds the `/keyevent` request URL, and is the only place a dialpad key code is decided. */
private const val KEYCODE_CLASS = "Lw5/x;"
private const val KEYCODE_METHOD = "P"

/** The engine's key-code entry point. */
private const val ENGINE_ACTIONS_CLASS = "Lp0/e;"
private const val ENGINE_KEYCODE = "v"

/**
 * The app's own event injection: builds a `KeyEvent` DOWN/UP pair and hands it to
 * `InputManager.injectInputEvent` through `InputManagerGlobal`, which it opens by reflection, so
 * it needs nothing but the app process itself.
 */
private const val INJECT_CLASS = "LK1/o;"
private const val INJECT_KEYCODE = "R"

/**
 * The dialpad key code, which is the only input that still goes through the engine.
 *
 * With "Use ADB speed up" the key code is sent to the app's localhost server, and that path
 * returns before reaching the fallback. When the server is down the request dies on a closed
 * port, and the app falls back to `p0/e.v()` - the engine's `nativeActions` JNI call. On a
 * re-signed APK the engine does not come up properly, so `nativeActions` silently does nothing:
 * the dialpad of a remote stays dead while pointer input, which goes through the app's own
 * `InputManager` injection, keeps working. This is exactly the split users report.
 *
 * The engine is not needed for this at all - the app already injects key events itself, for its
 * own server endpoints (`h3`-side BACK/ENTER) - so the fallback is redirected at
 * `K1/o.R(int, int)`, which builds the DOWN/UP pair and injects it. That keeps the dialpad
 * working regardless of the engine's state, and regardless of whether the speed-up server is up.
 */
@Suppress("unused")
val dialpadWithoutEnginePatch = bytecodePatch(
    name = "Dialpad without the engine",
    description = "Injects dialpad key codes through the app's own InputManager path instead of " +
            "the native engine. The engine's nativeActions entry point silently does nothing on a " +
            "re-signed APK, so a remote's dialpad was dead while pointer input kept working. " +
            "The key code is now sent with KeyEvent + injectInputEvent, which the app already " +
            "uses itself and which needs neither the engine nor the ADB speed-up server.",
    default = true
) {
    compatibleWith(TVQUICKACTIONS_3_8)

    execute {
        val keycodeClass = mutableClassDefBy(KEYCODE_CLASS)
        val method = keycodeClass.methods.firstOrNull { candidate ->
            candidate.name == KEYCODE_METHOD &&
                candidate.returnType.toString() == "V" &&
                candidate.parameterTypes.map { it.toString() } ==
                listOf("Landroid/content/Context;", "Ldev/vodik7/tvquickactions/T;", "I",
                    "Ldev/vodik7/tvquickactions/util/z2;")
        } ?: throw PatchException("Could not find $KEYCODE_CLASS->$KEYCODE_METHOD")

        val instructions = method.implementation?.instructions?.toList()
            ?: throw PatchException("$KEYCODE_CLASS->$KEYCODE_METHOD has no body")

        val engineCall = instructions.indexOfFirst { it.isCall(ENGINE_ACTIONS_CLASS, ENGINE_KEYCODE) }
        if (engineCall < 0) throw PatchException("Could not find the engine key-code fallback")

        // The key code is the third parameter, i.e. p2 in this static method.
        //
        // The two instructions are written as one replacement rather than a replace plus an
        // insert: a branch target (`:cond_2`) sits on the engine call, and an insert at that
        // index lands *before* the label, i.e. on a path nothing reaches. Replacing in place
        // keeps the label on the first of the two instructions, which is the one the branch
        // needs to run.
        method.replaceInstruction(
            engineCall,
            "const/4 v0, 0x0"
        )
        method.addInstruction(
            engineCall + 1,
            "invoke-static {p2, v0}, $INJECT_CLASS->$INJECT_KEYCODE(II)V"
        )
    }
}

private fun Instruction.isCall(owner: String, name: String): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false

    return reference.definingClass == owner && reference.name == name
}
