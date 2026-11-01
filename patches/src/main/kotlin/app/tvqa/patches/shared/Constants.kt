/*
 * Patches for tvQuickActions Pro.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.tvqa.patches.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * tvQuickActions Pro.
     *
     * 3.8.0 is the release these patches were developed and tested against.
     *
     * 3.7.0 exposes the same licence policy class and the same Application class, and it does
     * not load the native engine at all, so both patches are expected to work there too
     * (the "Skip native engine load" patch simply finds nothing to do).
     */
    val TVQUICKACTIONS = Compatibility(
        name = "tvQuickActions Pro",
        packageName = "dev.vodik7.tvquickactions",
        // The original file is distributed as a plain APK, not as an APKM/XAPK bundle.
        apkFileType = ApkFileType.APK,
        // Background colour of the app icon, used by Morphe Manager.
        appIconColor = 0xFF1565C0,
        targets = listOf(
            AppTarget(version = "3.8.0"),
            AppTarget(version = "3.7.0"),
        )
    )
}
