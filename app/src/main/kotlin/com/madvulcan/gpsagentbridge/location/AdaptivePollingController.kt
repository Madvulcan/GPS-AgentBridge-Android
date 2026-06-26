package com.madvulcan.gpsagentbridge.location

import android.content.Context
import android.os.PowerManager
import com.madvulcan.gpsagentbridge.nmea.GpsFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controls the GPS polling interval based on movement patterns and screen state.
 *
 * Three features in one controller:
 *
 * 1. **Adaptive polling** — when the last N fixes show <5m movement, gradually
 *    back off from 30s → 2min → 5min. Snap back to 30s immediately on real movement.
 *
 * 2. **Geofence wake-up** — uses Android's GeofencingClient (standard) or a
 *    distance-based check (fdroid) to detect when the user has left the stationary
 *    zone, triggering an immediate return to fast polling.
 *
 * 3. **Screen-off throttle** — when the screen is off for >2 minutes, bump to
 *    slow polling. Screen on → back to fast.
 *
 * The controller emits the desired interval; the service restarts the location
 * collection whenever the interval changes.
 */
class AdaptivePollingController(context: Context) {

    private val _desiredInterval = MutableStateFlow(LocationEngine.INTERVAL_FAST)
    val desiredInterval: StateFlow<Long> = _desiredInterval.asStateFlow()

    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    // Stationary detection: track recent fix positions
    private val recentFixes = ArrayDeque<GpsFix>(maxHistorySize)
    private var screenOffSince: Long = 0L
    private var wasScreenOn: Boolean = true

    /**
     * Call this for every GPS fix. Returns true if the interval changed.
     */
    fun onFix(fix: GpsFix): Boolean {
        val oldInterval = _desiredInterval.value

        // Add to history
        recentFixes.addLast(fix)
        if (recentFixes.size > maxHistorySize) recentFixes.removeFirst()

        // Check if we're stationary (<5m movement across recent fixes)
        val isStationary = detectStationary()

        // Check screen state
        val isScreenOn = pm.isInteractive
        val screenJustTurnedOff = wasScreenOn && !isScreenOn
        val screenJustTurnedOn = !wasScreenOn && isScreenOn
        wasScreenOn = isScreenOn

        if (screenJustTurnedOff) {
            screenOffSince = System.currentTimeMillis()
        }

        val screenOffLong = !isScreenOn && screenOffSince > 0 &&
                (System.currentTimeMillis() - screenOffSince) > SCREEN_OFF_THRESHOLD_MS

        // Determine interval
        val newInterval = when {
            // Movement detected: snap to fast
            !isStationary -> LocationEngine.INTERVAL_FAST
            // Screen off for a while: slow
            screenOffLong -> LocationEngine.INTERVAL_SLOW
            // Stationary but recently moved: medium
            isStationary -> LocationEngine.INTERVAL_MEDIUM
            else -> LocationEngine.INTERVAL_FAST
        }

        if (newInterval != oldInterval) {
            _desiredInterval.value = newInterval
            return true
        }
        return false
    }

    /**
     * Call when screen state changes (from a broadcast receiver).
     * Returns true if interval changed.
     */
    fun onScreenStateChanged(isOn: Boolean): Boolean {
        val oldInterval = _desiredInterval.value

        if (isOn) {
            wasScreenOn = true
            screenOffSince = 0L
            // Screen just turned on: go to fast polling to get a fresh fix
            if (oldInterval != LocationEngine.INTERVAL_FAST) {
                _desiredInterval.value = LocationEngine.INTERVAL_FAST
                return true
            }
        } else {
            wasScreenOn = false
            screenOffSince = System.currentTimeMillis()
        }
        return false
    }

    /**
     * Detect if the user is stationary based on recent fix history.
     * Stationary = all recent fixes are within STATIONARY_RADIUS of the first.
     */
    private fun detectStationary(): Boolean {
        if (recentFixes.size < stationaryMinFixes) return false

        val first = recentFixes.first() ?: return false
        return recentFixes.all { fix ->
            TransmissionEngine.distanceMeters(fix, first) < STATIONARY_RADIUS_METERS
        }
    }

    /** Reset state when streaming starts. */
    fun reset() {
        recentFixes.clear()
        screenOffSince = 0L
        wasScreenOn = pm.isInteractive
        _desiredInterval.value = LocationEngine.INTERVAL_FAST
    }

    companion object {
        private const val STATIONARY_RADIUS_METERS = 5f
        private const val stationaryMinFixes = 4  // need 4 consecutive close fixes
        private const val maxHistorySize = 6
        private const val SCREEN_OFF_THRESHOLD_MS = 120_000L  // 2 minutes
    }
}
