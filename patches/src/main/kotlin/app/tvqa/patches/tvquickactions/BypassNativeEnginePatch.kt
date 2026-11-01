/*
 * Patches for tvQuickActions Pro.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.tvqa.patches.tvquickactions

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.returnEarly
import app.tvqa.patches.shared.Constants.TVQUICKACTIONS
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * The native methods of the engine class (`p0/e` in 3.8.0, `p0/d` in 3.7.0). The names are
 * bound to the JNI symbols of liba.so and libd.so, so they survive obfuscation.
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
val bypassNativeEnginePatch = bytecodePatch(
    name = "Bypass native engine",
    description = "Drops the System.loadLibrary(\"d\") call in App.onCreate, whose library aborts " +
            "the process when the APK is not signed with the original certificate, and stubs the " +
            "engine methods that library provided so the app does not die with " +
            "UnsatisfiedLinkError. Features that need the engine stay unavailable.",
    default = true
) {
    compatibleWith(TVQUICKACTIONS)

    execute {
        // 1. Neutralise the library load. The const-string that held the library name is left
        //    behind, where it does nothing.
        AppLoadLibraryFingerprint.matchOrNull()?.let { match ->
            val call = match.instructionMatches.first()
            val register = call.instruction.registersUsed.firstOrNull() ?: 0

            match.method.replaceInstruction(
                call.index,
                if (register <= 0xF) "const/4 v$register, 0x0" else "const/16 v$register, 0x0"
            )
        }

        // 2. The engine class declares the native methods; every other method of that class is a
        //    thin wrapper around one of them, and every wrapper throws UnsatisfiedLinkError now
        //    that the library never loads. Make each wrapper return a neutral value instead.
        //    Only wrappers are touched: the native declarations are left as they are.
        val engineTypes = mutableSetOf<String>()
        classDefForEach { classDef ->
            if (classDef.methods.any { method -> method.name in ENGINE_NATIVE_METHODS }) {
                engineTypes += classDef.type
            }
        }

        for (type in engineTypes) {
            methodLoop@ for (method in mutableClassDefBy(type).methods) {
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
}
