/*
 * Patches for tvQuickActions Pro.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.tvqa.patches.tvquickactions

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.returnEarly
import app.tvqa.patches.shared.Constants.TVQUICKACTIONS
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Name of the SharedPreferences file the licence policy owns.
 *
 * It is the only part of the policy class that survives obfuscation, so it is used to locate
 * the class: `r3/i` in 3.7.0 and `J3/i` in 3.8.0, and presumably something else again in the
 * next release.
 */
private const val POLICY_PREFERENCES = "com.google.android.vending.licensing.ServerManagedPolicy"

/**
 * The native methods of the engine class (`p0/e` in 3.8.0, `p0/d` in 3.7.0). The names are bound
 * to the JNI symbols of liba.so and libd.so, so they survive obfuscation.
 */
private val ENGINE_NATIVE_METHODS = setOf(
    "nativeActions",
    "nativeControl",
    "nativeCursor",
    "nativeDispatch",
    "nativeRender",
    "nativeRequest"
)

@Suppress("unused")
val unlockPremiumPatch = bytecodePatch(
    name = "Unlock premium",
    description = "Makes the bundled Google LVL policy always report the app as licensed, and " +
            "bypasses the native engine: it drops the System.loadLibrary(\"d\") call whose " +
            "library aborts the process on a re-signed APK, and stubs the methods that library " +
            "provided so the app cannot die with UnsatisfiedLinkError. Features that need the " +
            "engine stay unavailable.",
    default = true
) {
    compatibleWith(TVQUICKACTIONS)

    execute {
        forceLicenceGranted()
        bypassNativeEngine()
    }
}

/** The licence policy class owns a no-argument boolean method (allowAccess()). Force it to true. */
private fun app.morphe.patcher.patch.BytecodePatchContext.forceLicenceGranted() {
    val policyClass = classDefByStrings(POLICY_PREFERENCES)
        .firstOrNull { classDef ->
            classDef.methods.any { method -> method.isNoArgumentBoolean() }
        }
        ?: throw PatchException("Could not find the licence policy class")

    val allowAccess = mutableClassDefBy(policyClass.type)
        .methods
        .firstOrNull { method -> method.isNoArgumentBoolean() }
        ?: throw PatchException("Could not find the licence policy allowAccess() method")

    allowAccess.returnEarly(true)
}

private fun Method.isNoArgumentBoolean() =
    returnType.toString() == "Z" && parameterTypes.isEmpty()

/**
 * The engine library aborts the process from its own constructor when the APK is not signed with
 * the original certificate, so it must never be loaded. The methods it provided then have no
 * implementation, and every caller would throw UnsatisfiedLinkError, so each thin wrapper around
 * one of them is made to return a neutral value.
 */
private fun app.morphe.patcher.patch.BytecodePatchContext.bypassNativeEngine() {
    // 1. Neutralise the library load. The const-string that held the library name is left behind,
    //    where it does nothing.
    AppLoadLibraryFingerprint.matchOrNull()?.let { match ->
        val call = match.instructionMatches.first()
        val register = call.instruction.registersUsed.firstOrNull() ?: 0

        match.method.replaceInstruction(
            call.index,
            if (register <= 0xF) "const/4 v$register, 0x0" else "const/16 v$register, 0x0"
        )
    }

    // 2. Stub the wrappers of the engine class. The native declarations themselves are left
    //    alone: a native method has no implementation, and the patcher cannot give it one.
    val engineClasses = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (classDef.methods.any { method -> method.name in ENGINE_NATIVE_METHODS }) {
            engineClasses += classDef
        }
    }

    for (engineClass in engineClasses) {
        methodLoop@ for (method in mutableClassDefBy(engineClass.type).methods) {
            if (method.name in ENGINE_NATIVE_METHODS) continue@methodLoop

            val instructions = method.implementation?.instructions ?: continue@methodLoop
            val callsEngine = instructions.any { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference
                reference is MethodReference && reference.name in ENGINE_NATIVE_METHODS
            }
            if (!callsEngine) continue@methodLoop

            when (method.returnType.toString()) {
                "V" -> method.returnEarly()
                "Z", "B", "S", "C", "I" -> method.returnEarly(0)
                "J" -> method.returnEarly(0L)
                "F" -> method.returnEarly(0f)
                "D" -> method.returnEarly(0.0)
                "Ljava/lang/String;" -> method.returnEarly("")
                else -> method.returnEarly(null as Void?)
            }
        }
    }
}
