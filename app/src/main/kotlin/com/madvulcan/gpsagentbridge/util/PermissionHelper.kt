package com.madvulcan.gpsagentbridge.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Permission-check helpers. The actual permission *requests* happen in the Compose
 * layer using `rememberLauncherForActivityResult`; this file only contains the
 * read-side checks used by the service + onboarding flow.
 */

/** True if we have at least one location permission. Doesn't distinguish fine vs coarse. */
fun hasAnyLocationPermission(context: Context): Boolean {
    return hasFineLocation(context) || hasCoarseLocation(context)
}

/** True iff ACCESS_FINE_LOCATION is granted. */
fun hasFineLocation(context: Context): Boolean {
    return isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
}

/** True iff ACCESS_COARSE_LOCATION is granted. */
fun hasCoarseLocation(context: Context): Boolean {
    return isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
}

/**
 * True iff we have fine location *and* background location.
 * Background location can only be requested after foreground location is granted,
 * and on Android 11+ it must be requested separately.
 */
fun hasForegroundLocationPermission(context: Context): Boolean {
    if (!hasFineLocation(context)) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    return isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
}

/**
 * Returns a tri-state for background location:
 *  - NOT_REQUESTED (foreground granted but background not yet asked)
 *  - DENIED (user rejected background location)
 *  - GRANTED
 *
 * Used by the onboarding flow to show the right CTA.
 */
enum class BackgroundLocationState {
    NOT_REQUESTED,   // no foreground yet, or foreground granted + background not asked
    DENIED,          // background permission explicitly denied
    GRANTED,         // background permission granted ("allow all the time")
}

fun backgroundLocationState(context: Context): BackgroundLocationState {
    if (!hasFineLocation(context)) return BackgroundLocationState.NOT_REQUESTED
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return BackgroundLocationState.GRANTED
    return when {
        isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
            BackgroundLocationState.GRANTED
        else -> BackgroundLocationState.DENIED
    }
}

/** POST_NOTIFICATIONS — required on Android 13+ for the foreground service notification. */
fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
}

private fun isGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
