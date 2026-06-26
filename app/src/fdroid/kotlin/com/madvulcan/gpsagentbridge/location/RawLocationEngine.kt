package com.madvulcan.gpsagentbridge.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Looper
import com.madvulcan.gpsagentbridge.nmea.GpsFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicLong

class RawLocationEngine(context: Context) : LocationEngine {
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val currentInterval = AtomicLong(LocationEngine.INTERVAL_FAST)

    @SuppressLint("MissingPermission")
    override fun stream(): Flow<GpsFix> = callbackFlow {
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                trySend(location.toGpsFix())
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }

        val interval = currentInterval.get()

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            interval,
            0f,
            listener,
            Looper.getMainLooper()
        )

        try {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                interval,
                0f,
                listener,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            // NETWORK_PROVIDER may not be available
        }

        awaitClose {
            locationManager.removeUpdates(listener)
        }
    }

    @SuppressLint("MissingPermission")
    override fun updateInterval(intervalMillis: Long) {
        currentInterval.set(intervalMillis)
        // Re-registration handled by service restarting the collection
    }

    @SuppressLint("MissingPermission")
    override suspend fun lastKnown(): GpsFix? {
        return try {
            val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                LocationManager.NETWORK_PROVIDER
            } else {
                return null
            }
            locationManager.getLastKnownLocation(provider)?.toGpsFix()
        } catch (t: Throwable) {
            null
        }
    }
}
