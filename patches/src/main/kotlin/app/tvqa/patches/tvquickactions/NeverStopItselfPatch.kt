/*
 * Patches for tvQuickActions Pro.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.tvqa.patches.tvquickactions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.registersUsed
import app.tvqa.patches.shared.Constants.TVQUICKACTIONS
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** The broadcast the receiver answers to when its "stop recent apps" action runs. */
private const val STOP_RECENT_APPS = "dev.vodik7.tvquickactions.STOP_RECENT_APPS"

/** The app's own package, which must never appear in the list it is about to kill. */
private const val OWN_PACKAGE = "dev.vodik7.tvquickactions"

private const val RECENT_APPS_LIST = "Ljava/util/concurrent/CopyOnWriteArrayList;"

/**
 * "Stop recent apps" runs `am force-stop` over ADB for every app in the recents list. The app is
 * in that list itself, so with an ADB connection up (which is what makes the command work at all)
 * the action kills the app that issued it: it restarts, the trigger fires again, and the app
 * loops every eight seconds.
 *
 * The app only excludes the *foreground* app, and only when the "Don't kill foreground app"
 * preference is on, so that is not enough on its own - and a trigger that fires while the app is
 * in the background still kills it. This patch removes the app from the list unconditionally.
 */
@Suppress("unused")
val neverStopItselfPatch = bytecodePatch(
    name = "Never stop itself",
    description = "Keeps the app out of the list its own Stop recent apps action kills. Without " +
            "it, that action - and any trigger or button mapping that runs it - stops the app " +
            "itself over ADB, which makes the app restart in a loop every few seconds. The app " +
            "only skips the foreground app, and only when the matching preference is enabled, " +
            "so this covers the case the preference does not.",
    default = true
) {
    compatibleWith(TVQUICKACTIONS)

    execute {
        val receiver = classDefByStrings(STOP_RECENT_APPS).firstOrNull()
            ?: throw PatchException("Could not find the recent apps receiver")

        val stopRecentApps = mutableClassDefBy(receiver.type).methods.firstOrNull { method ->
            method.implementation?.instructions?.any { instruction ->
                (instruction.stringConstant() == STOP_RECENT_APPS)
            } == true
        } ?: throw PatchException("Could not find the code that stops recent apps")

        val instructions = stopRecentApps.implementation!!.instructions.toList()
        val actionIndex = instructions.indexOfFirst { it.stringConstant() == STOP_RECENT_APPS }

        // The list of apps to stop is built, the app itself is filtered out of it, and then it is
        // iterated. The package is removed from the list just before that iteration.
        val iteration = instructions.withIndex().firstOrNull { (index, instruction) ->
            index > actionIndex && (instruction.methodCall()?.let {
                it.name == "iterator" && it.definingClass == RECENT_APPS_LIST
            } == true)
        } ?: throw PatchException("Could not find where the recent apps are iterated")

        val list = iteration.value.registersUsed.firstOrNull()
            ?: throw PatchException("Could not find the register holding the recent apps")

        // The register the iteration call does not use, which the code before it has finished
        // with: it held the collection the previous call removed, and is overwritten by the
        // move-result below in the original code path anyway.
        val scratch = if (list == 3) 4 else 3
        if (scratch >= stopRecentApps.implementation!!.registerCount) {
            throw PatchException("Could not find a register to remove the app with")
        }

        stopRecentApps.addInstructions(
            iteration.index,
            """
                const-string v$scratch, "$OWN_PACKAGE"
                invoke-virtual {v$list, v$scratch}, $RECENT_APPS_LIST->remove(Ljava/lang/Object;)Z
                move-result v$scratch
            """.trimIndent()
        )
    }
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.stringConstant() =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.methodCall() =
    (this as? ReferenceInstruction)?.reference as? MethodReference
