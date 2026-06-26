package com.madvulcan.gpsagentbridge.location

import android.annotation.SuppressLint
import android.content.Context
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
import java.util.concurrent.atomic.AtomicLong

class FusedLocationEngine(context: Context) : LocationEngine {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    private val currentInterval = AtomicLong(LocationEngine.INTERVAL_FAST)

    @SuppressLint("MissingPermission")
    override fun stream(): Flow<GpsFix> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    trySend(loc.toGpsFix())
                }
            }
        }

        // Start with current interval
        requestUpdates(callback, currentInterval.get())

        awaitClose {
            client.removeLocationUpdates(callback)
        }
    }

    @SuppressLint("MissingPermission")
    override fun updateInterval(intervalMillis: Long) {
        currentInterval.set(intervalMillis)
        // Note: The actual re-registration happens when stream() is re-collected.
        // For FusedLocationProvider, we need to remove and re-add the callback.
        // This is handled by the service restarting the collection when interval changes.
    }

    @SuppressLint("MissingPermission")
    override suspend fun lastKnown(): GpsFix? {
        return try {
            val loc = com.google.android.gms.tasks.Tasks.await(client.lastLocation)
            loc?.toGpsFix()
        } catch (t: Throwable) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(callback: LocationCallback, intervalMillis: Long) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(minOf(intervalMillis, 5_000L))
            .setWaitForAccurateLocation(false)
            .setMaxUpdateDelayMillis(0)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }
}
