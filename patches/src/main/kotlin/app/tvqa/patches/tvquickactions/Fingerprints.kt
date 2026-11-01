/*
 * Patches for tvQuickActions Pro.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.tvqa.patches.tvquickactions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

/**
 * The `System.loadLibrary("d")` call of the Application class.
 *
 * `dev.vodik7.tvquickactions.App` keeps its name in every release, so the class can be
 * addressed directly. Only 3.8.0 and newer load the native engine from `onCreate`, so the
 * patch that uses this fingerprint tolerates a missing match.
 */
object AppLoadLibraryFingerprint : Fingerprint(
    definingClass = "Ldev/vodik7/tvquickactions/App;",
    name = "onCreate",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/lang/System;",
            name = "loadLibrary"
        )
    )
)
