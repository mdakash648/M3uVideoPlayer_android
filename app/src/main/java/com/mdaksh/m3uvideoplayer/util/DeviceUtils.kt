package com.mdaksh.m3uvideoplayer.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Small device-capability helpers shared by the player.
 *
 * The player needs to know whether it's running on a TV / Firestick so it can:
 *  - disable the left-side brightness gesture (a TV panel's brightness isn't app-controllable), and
 *  - lean on D-pad/remote key handling instead of touch gestures.
 */
object DeviceUtils {

    /** True on Android TV / Firestick / leanback devices (no user-controllable screen brightness). */
    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("android.hardware.type.television")
    }
}
