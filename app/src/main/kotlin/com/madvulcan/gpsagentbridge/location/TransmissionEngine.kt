package com.madvulcan.gpsagentbridge.location

import kotlin.math.*
import com.madvulcan.gpsagentbridge.data.ServerTarget
import com.madvulcan.gpsagentbridge.data.Settings
import com.madvulcan.gpsagentbridge.nmea.GpsFix
import com.madvulcan.gpsagentbridge.nmea.NmeaGenerator
import com.madvulcan.gpsagentbridge.net.SendResult
import com.madvulcan.gpsagentbridge.net.UdpSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Streamable state surfaced to both the foreground service notification and the
 * Compose UI. Every value here is updated by the [TransmissionEngine].
 */
data class TransmissionState(
    val status: StreamStatus = StreamStatus.IDLE,
    val currentFix: GpsFix? = null,
    val lastTransmittedFix: GpsFix? = null,
    val lastTransmissionMillis: Long = 0L,
    val transmissionsToday: Int = 0,
    val fixesRejectedAccuracy: Int = 0,
    val perTargetStats: Map<String, TargetStat> = emptyMap(),
    val lastError: String? = null,
) {
    /**
     * Distance (meters) between current fix and last transmitted fix.
     * Null if we have no last transmitted fix yet.
     */
    val distanceSinceLastTx: Float? get() {
        val cur = currentFix ?: return null
        val last = lastTransmittedFix ?: return null
        return TransmissionEngine.distanceMeters(cur, last)
    }
}

enum class StreamStatus {
    IDLE,
    CONNECTING,
    WAITING_FOR_FIX,   // <-- new state per review of desktop repo
    TRACKING,
    TRANSMITTING,
    ERROR,
}

/** Per-target running statistics, surfaced in the UI as "(ok)" / "(fail)". */
data class TargetStat(
    val target: ServerTarget,
    val successesToday: Int = 0,
    val failuresToday: Int = 0,
    val lastSuccessMillis: Long = 0L,
    val lastError: String? = null,
) {
    val totalAttempts: Int get() = successesToday + failuresToday
    val isOk: Boolean get() = lastError == null && (lastSuccessMillis > 0 || totalAttempts == 0)
}

/**
 * The brains of the app. Implements the §4.1 distance-based trigger logic plus
 * the §4.2 state machine (with the added WAITING_FOR_FIX state).
 *
 * Inputs:
 *  - Raw [GpsFix] stream from [LocationEngine]
 *  - Current [Settings] (distance threshold, max interval, min accuracy, targets, dry run)
 *
 * Outputs:
 *  - [state]: observable [TransmissionState] for UI + notification
 *  - UDP datagrams via [UdpSender] (one per transmission event, sent to all targets)
 *
 * Pseudocode (from the requirements doc):
 * ```
 * ON every GPS fix:
 *   1. accuracy < minAccuracy? → skip, increment fixesRejectedAccuracy
 *   2. distance from lastTx >= distanceThreshold? → transmit, reset maxIntervalTimer
 *   3. maxIntervalTimer elapsed? → transmit, reset maxIntervalTimer
 *   4. else → no transmit
 *   5. update UI state (always)
 * ```
 */
class TransmissionEngine(
    private val sender: UdpSender,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val _state = MutableStateFlow(TransmissionState())
    val state: StateFlow<TransmissionState> = _state.asStateFlow()

    /** Mutable working variables — not part of the public state. */
    private var settings: Settings = Settings()
    private var lastTxTimestamp: Long = 0L
    private var dayBucket: Int = -1  // yyyyMMdd in local time, for "today" rollover

    /**
     * Must be called before [onFix] to provide the current settings.
     * Updates propagate immediately.
     */
    fun setSettings(s: Settings) {
        settings = s
    }

    /**
     * Reset internal counters when streaming starts. Sets status to WAITING_FOR_FIX
     * until the first valid fix arrives.
     */
    fun startStreaming() {
        lastTxTimestamp = 0L
        // Don't reset transmissionsToday on every start — only on day rollover.
        rolloverDayIfNeeded(force = false)
        _state.value = _state.value.copy(
            status = StreamStatus.WAITING_FOR_FIX,
            lastError = null,
        )
    }

    /** Called by the service when GPS is lost for an extended period. */
    fun onGpsLost() {
        _state.value = _state.value.copy(status = StreamStatus.WAITING_FOR_FIX)
    }

    /** Called by the service when streaming is stopped by the user. */
    fun stop() {
        _state.value = _state.value.copy(status = StreamStatus.IDLE)
    }

    /**
     * The main entry point — called for every accepted [GpsFix] from the location engine.
     *
     * Returns true if a transmission was sent (useful for tests + logging).
     */
    suspend fun onFix(fix: GpsFix): Boolean {
        rolloverDayIfNeeded(force = false)

        // Step 1: accuracy gate — bad fixes never enter the distance calculation.
        if (fix.accuracyMeters > settings.minAccuracyMeters) {
            _state.value = _state.value.copy(
                status = StreamStatus.TRACKING,
                currentFix = fix,
                fixesRejectedAccuracy = _state.value.fixesRejectedAccuracy + 1,
            )
            return false
        }

        // Step 2: update current fix in state (always, on accepted fixes)
        _state.value = _state.value.copy(
            status = StreamStatus.TRACKING,
            currentFix = fix,
        )

        // Step 3: distance threshold
        val lastTx = _state.value.lastTransmittedFix
        val shouldTransmit = when {
            // First fix ever: transmit (FR-05: first fix transmits immediately)
            lastTx == null -> true
            // Distance exceeded
            distanceMeters(fix, lastTx) >= settings.distanceThresholdMeters -> true
            // Max interval elapsed
            (now() - lastTxTimestamp) >= settings.maxIntervalMillis -> true
            else -> false
        }

        if (!shouldTransmit) return false

        return transmit(fix)
    }

    /**
     * Force a transmission now (used by the "test send" button).
     * Bypasses the accuracy gate and distance/interval checks.
     */
    suspend fun forceTransmit(fix: GpsFix): Boolean {
        return transmit(fix)
    }

    /** Internal: build NMEA, send to all targets, update state. */
    private suspend fun transmit(fix: GpsFix): Boolean {
        _state.value = _state.value.copy(status = StreamStatus.TRANSMITTING)

        val payload = NmeaGenerator.generate(fix).toByteArray(Charsets.US_ASCII)

        // Skip the actual network call in dry-run mode — but still update state
        // so the UI behaves identically.
        val results: List<SendResult> = if (settings.dryRun) {
            settings.targets.map { SendResult(target = it, bytesSent = payload.size) }
        } else {
            sender.sendToAll(payload, settings.targets)
        }

        val nowMs = now()
        lastTxTimestamp = nowMs

        // Update per-target stats.
        val newStats = _state.value.perTargetStats.toMutableMap()
        for (r in results) {
            val key = r.target.stableId
            val existing = newStats[key] ?: TargetStat(target = r.target)
            newStats[key] = if (r.isSuccess) {
                existing.copy(
                    successesToday = existing.successesToday + 1,
                    lastSuccessMillis = nowMs,
                    lastError = null,
                )
            } else {
                existing.copy(
                    failuresToday = existing.failuresToday + 1,
                    lastError = r.error?.message,
                )
            }
        }

        _state.value = _state.value.copy(
            status = StreamStatus.TRACKING,
            lastTransmittedFix = fix,
            lastTransmissionMillis = nowMs,
            transmissionsToday = _state.value.transmissionsToday + 1,
            perTargetStats = newStats,
            lastError = results.firstOrNull { !it.isSuccess }?.error?.message,
        )

        return results.any { it.isSuccess }
    }

    /**
     * Roll over the daily counters at local midnight.
     * Computed from the [now] clock for testability.
     */
    private fun rolloverDayIfNeeded(force: Boolean) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now() }
        val today = cal.get(java.util.Calendar.YEAR) * 10_000 +
                (cal.get(java.util.Calendar.MONTH) + 1) * 100 +
                cal.get(java.util.Calendar.DAY_OF_MONTH)

        if (force || today != dayBucket) {
            dayBucket = today
            _state.value = _state.value.copy(
                transmissionsToday = 0,
                fixesRejectedAccuracy = 0,
                perTargetStats = emptyMap(),
            )
        }
    }

    companion object {
        /**
         * Haversine distance between two fixes, in meters.
         * Pure-Kotlin implementation (no Android Location dependency),
         * making the engine unit-testable without mocking.
         */
        fun distanceMeters(a: GpsFix, b: GpsFix): Float {
            val R = 6371000.0 // Earth radius in meters
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLat = Math.toRadians(b.latitude - a.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
            return (2 * R * atan2(sqrt(h), sqrt(1 - h))).toFloat()
        }
    }
}
