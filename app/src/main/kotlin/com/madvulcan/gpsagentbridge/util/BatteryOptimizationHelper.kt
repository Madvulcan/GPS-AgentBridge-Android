package com.madvulcan.gpsagentbridge.util

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization helpers.
 *
 * On Android 6+, apps are subject to Doze mode and App Standby, which can drastically
 * limit background location updates. The standard "fix" is to ask the user to disable
 * battery optimization for the app — `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission
 * (which is implicit, not user-granted) lets the app open a system dialog to do so.
 *
 * Per the desktop repo's AgentInstructions.md:
 *   "If the user's phone kills the app in the background, GPS streaming will stop.
 *    This is the #1 issue users encounter."
 *
 * So we expose both a check ([isBatteryOptimizationEnabled]) and a launcher
 * ([batteryOptimizationSettingsIntent]) for use by the onboarding flow.
 */
object BatteryOptimizationHelper {

    /**
     * True iff the app is *currently* being aggressive-battery-managed.
     *
     * On most devices this is "is not on the ignore-battery-optimization list".
     * We also check for "is in power-save mode", which on some ROMs behaves like
     * a global battery optimization.
     */
    @SuppressLint("BatteryLife")
    fun isBatteryOptimizationEnabled(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        if (pm.isPowerSaveMode) return true
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Intent that opens the system "ignore battery optimization" prompt for our app.
     * The caller must launch this via `startActivityForResult` (or the Compose
     * `rememberLauncherForActivityResult` equivalent).
     */
    fun ignoreOptimizationIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Intent that opens the per-app battery settings page (a more general fallback
     * if the above intent isn't accepted on some OEM ROMs).
     */
    fun appBatterySettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Returns true if the app appears to have been killed by the system recently.
     * Heuristic: if our foreground service is not running but the user expects it to be.
     * Used by the UI to suggest re-launching after a suspected kill.
     */
    fun isForegroundServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        // The deprecated getRunningServices only returns the caller's own services on
        // Android 8+, which is exactly what we want here.
        @Suppress("DEPRECATION")
        val services = am.getRunningServices(Int.MAX_VALUE) ?: return false
        return services.any { it.service.className == serviceClass.name }
    }
}
