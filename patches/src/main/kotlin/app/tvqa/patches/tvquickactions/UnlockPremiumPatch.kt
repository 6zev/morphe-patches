/*
 * Patches for tvQuickActions Pro.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.tvqa.patches.tvquickactions

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.returnEarly
import app.tvqa.patches.shared.Constants.TVQUICKACTIONS
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

/**
 * Unlocks the premium features.
 *
 * The wall between a fresh install and the paid features is the Java licence policy. Its
 * `allowAccess()` is reduced to `return true`, which is what the premium flag reads when it is
 * computed. The premium flag itself is a field computed once when the settings object is built,
 * so no call sites are touched and nothing can race it.
 *
 * The native engine is left exactly as shipped. Disarming its refusals - first the verification
 * routine's branches, then every copy in the library - does not unlock anything: the engine keeps
 * its own state, and the app either dies on a destroyed mutex at start or hangs on the splash
 * until the system kills it. The Java grant alone is what the library has been shown to accept.
 */
@Suppress("unused")
val unlockPremiumPatch = bytecodePatch(
    name = "Unlock premium",
    description = "Reports the app as licensed, which unlocks the premium features.",
    default = true,
) {
    compatibleWith(TVQUICKACTIONS)

    execute { forceLicenceGranted() }
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
