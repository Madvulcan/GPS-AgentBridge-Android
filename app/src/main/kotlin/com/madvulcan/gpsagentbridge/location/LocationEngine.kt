package com.madvulcan.gpsagentbridge.location

import com.madvulcan.gpsagentbridge.nmea.GpsFix
import kotlinx.coroutines.flow.Flow

interface LocationEngine {
    /**
     * Stream GPS fixes at the current polling interval.
     * The interval may change dynamically via [updateInterval].
     */
    fun stream(): Flow<GpsFix>

    /**
     * Get the most recent cached location, if any.
     */
    suspend fun lastKnown(): GpsFix?

    /**
     * Update the polling interval dynamically (for adaptive battery savings).
     * Implementations should re-register their location listener with the new interval.
     * [intervalMillis] is the desired interval between GPS polls.
     */
    fun updateInterval(intervalMillis: Long)

    companion object {
        const val INTERVAL_FAST = 30_000L       // 30s — active tracking
        const val INTERVAL_MEDIUM = 120_000L    // 2 min — settling
        const val INTERVAL_SLOW = 300_000L      // 5 min — stationary / screen off
    }
}

fun android.location.Location.toGpsFix(): GpsFix {
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
