package com.madvulcan.gpsagentbridge.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.madvulcan.gpsagentbridge.nmea.GpsFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wraps [FusedLocationProviderClient] in a coroutine-friendly [Flow] of [GpsFix] values.
 *
 * Design decisions:
 *  - We use FusedLocationProvider (not raw LocationManager) because it fuses GPS + Wi-Fi
 *    + cell + accelerometer and is significantly more battery-efficient — especially in
 *    the stationary case, which is the whole point of this app.
 *  - Interval is set to 30 seconds. This is the *internal* polling cadence; the
 *    transmission engine decides whether to actually send based on distance/interval.
 *    30s keeps the UI fresh and gives the transmission engine enough samples to detect
 *    movement promptly while still letting the system throttle us during Doze.
 *  - [Priority.PRIORITY_HIGH_ACCURACY] is used because we explicitly want raw GPS fixes
 *    when available — the whole point is GPS-grade accuracy for NMEA relay. If battery
 *    is a concern, the user can lower their system location accuracy setting.
 *  - The callback is unregistered when the flow is cancelled (e.g. when the foreground
 *    service stops).
 *
 * Note: ACCESS_FINE_LOCATION is checked at the service level — if not granted, the
 * caller should not invoke [stream].
 */
class LocationEngine(private val context: Context) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    /**
     * Emits a [GpsFix] for every fused-location update. Cold flow — registers the
     * callback on collection start, unregisters on cancellation.
     */
    @SuppressLint("MissingPermission") // caller is responsible
    fun stream(): Flow<GpsFix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MILLIS)
            .setWaitForAccurateLocation(false) // don't hold off first emit
            .setMaxUpdateDelayMillis(0)        // no batching — emit immediately
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    trySend(loc.toGpsFix())
                }
            }
        }

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { e -> close(e) }

        awaitClose {
            client.removeLocationUpdates(callback)
        }
    }

    /**
     * Returns the most recent cached location, if any. Useful for the UI to show
     * *something* before the first real fix arrives.
     */
    @SuppressLint("MissingPermission")
    suspend fun lastKnown(): GpsFix? {
        return try {
            val loc = client.lastLocation.awaitResultOrNull()
            loc?.toGpsFix()
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun Location.toGpsFix(): GpsFix {
        return GpsFix(
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = if (hasAltitude()) altitude else 0.0,
            accuracyMeters = if (hasAccuracy()) accuracy else 9999f,
            speedMetersPerSec = if (hasSpeed()) speed else 0f,
            bearingDegrees = if (hasBearing()) bearing else 0f,
            timestampMillis = time,
            satellitesUsed = extras?.getInt("satellites", 8) ?: 8,
            hdop = if (hasAccuracy()) (accuracy / 4.0f).coerceAtLeast(0.5f) else 1.0f,
        )
    }

    companion object {
        /** Internal polling cadence. Not the transmission cadence. */
        private const val INTERVAL_MILLIS = 30_000L

        /** Don't deliver updates more often than this, even if GPS is pinging fast. */
        private const val MIN_INTERVAL_MILLIS = 5_000L
    }
}

/**
 * Tiny helper because the Google API client returns Task<T> and we want to await
 * without wrapping in suspendCancellableCoroutine ourselves.
 */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResultOrNull(): T? {
    return try {
        com.google.android.gms.tasks.Tasks.await(this)
    } catch (t: Throwable) {
        null
    }
}
