package com.madvulcan.gpsagentbridge.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.PowerManager
import com.madvulcan.gpsagentbridge.nmea.GpsFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controls the GPS polling state based on movement patterns, screen state,
 * and significant motion detection.
 *
 * Four states:
 *
 * 1. **ACTIVE** — GPS polling at 30s. Used when moving or screen on.
 * 2. **SETTLING** — GPS polling at 2min. Used when recently stationary but screen still on.
 * 3. **IDLE** — GPS polling at 5min. Screen off but not yet in deep sleep.
 * 4. **SLEEP** — GPS **OFF**, significant motion sensor armed. Maximum battery savings.
 *    Entered when stationary + screen off for >5 min. Exits on motion detected or screen on.
 *
 * The significant motion sensor is a hardware trigger that costs <0.01%/hour.
 * It fires when the device is physically moved (picked up, walked with, car door closes).
 * When it fires, we snap back to ACTIVE polling immediately.
 */
class AdaptivePollingController(context: Context) {

    enum class PollingState(val intervalMillis: Long) {
        ACTIVE(LocationEngine.INTERVAL_FAST),      // 30s — moving or screen on
        SETTLING(LocationEngine.INTERVAL_MEDIUM),   // 2min — stationary, screen on
        IDLE(LocationEngine.INTERVAL_SLOW),         // 5min — stationary, screen off
        SLEEP(0L);                                   // GPS OFF — motion sensor armed

        val isGpsOff: Boolean get() = this == SLEEP
    }

    private val _state = MutableStateFlow(PollingState.ACTIVE)
    val state: StateFlow<PollingState> = _state.asStateFlow()

    /** Convenience: the current GPS interval (0 = GPS off). */
    val desiredInterval: Long get() = _state.value.intervalMillis

    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val significantMotionSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    // Stationary detection
    private val recentFixes = ArrayDeque<GpsFix>(maxHistorySize)
    private var screenOffSince: Long = 0L
    private var wasScreenOn: Boolean = true
    private var stationarySince: Long = 0L

    // Motion sensor listener (only registered in SLEEP state)
    private var motionListener: android.hardware.TriggerEventListener? = null

    /** Callback for the service to restart/stop GPS when state changes. */
    var onStateChanged: ((newState: PollingState) -> Unit)? = null

    /**
     * Call this for every GPS fix. Returns true if the polling state changed.
     */
    fun onFix(fix: GpsFix): Boolean {
        // In SLEEP state, we shouldn't be getting fixes — ignore them
        if (_state.value == PollingState.SLEEP) return false

        val oldState = _state.value

        recentFixes.addLast(fix)
        if (recentFixes.size > maxHistorySize) recentFixes.removeFirst()

        val isStationary = detectStationary()
        if (isStationary && stationarySince == 0L) {
            stationarySince = System.currentTimeMillis()
        } else if (!isStationary) {
            stationarySince = 0L
        }

        // Check screen state
        val isScreenOn = pm.isInteractive
        val screenJustTurnedOff = wasScreenOn && !isScreenOn
        wasScreenOn = isScreenOn

        if (screenJustTurnedOff) {
            screenOffSince = System.currentTimeMillis()
        }

        val screenOffDuration = if (!isScreenOn && screenOffSince > 0)
            System.currentTimeMillis() - screenOffSince else 0L

        // Determine new state
        val newState = when {
            // Moving: always active
            !isStationary -> PollingState.ACTIVE
            // Stationary + screen off >5min + motion sensor available: sleep
            isScreenOn.not() && screenOffDuration > SCREEN_OFF_SLEEP_THRESHOLD_MS
                    && significantMotionSensor != null -> PollingState.SLEEP
            // Stationary + screen off >2min: idle (5min polling)
            isScreenOn.not() && screenOffDuration > SCREEN_OFF_IDLE_THRESHOLD_MS -> PollingState.IDLE
            // Stationary + screen on: settling (2min polling)
            isStationary -> PollingState.SETTLING
            else -> PollingState.ACTIVE
        }

        return applyState(newState, oldState)
    }

    /**
     * Call when screen state changes (from ScreenStateReceiver).
     * Returns true if polling state changed.
     */
    fun onScreenStateChanged(isOn: Boolean): Boolean {
        val oldState = _state.value

        if (isOn) {
            wasScreenOn = true
            screenOffSince = 0L
            // Screen on: always wake up to active
            return applyState(PollingState.ACTIVE, oldState)
        } else {
            wasScreenOn = false
            screenOffSince = System.currentTimeMillis()
        }
        return false
    }

    /**
     * Called when the significant motion sensor fires.
     * Snap immediately to ACTIVE polling.
     */
    fun onSignificantMotion(): Boolean {
        val oldState = _state.value
        // Re-arm the motion sensor for next time
        return applyState(PollingState.ACTIVE, oldState)
    }

    private fun applyState(newState: PollingState, oldState: PollingState): Boolean {
        if (newState == oldState) return false

        // Leaving SLEEP: unregister motion sensor, service will restart GPS
        if (oldState == PollingState.SLEEP) {
            unregisterMotionSensor()
        }

        // Entering SLEEP: arm motion sensor, service will stop GPS
        if (newState == PollingState.SLEEP) {
            registerMotionSensor()
        }

        _state.value = newState
        onStateChanged?.invoke(newState)
        return true
    }

    /**
     * Register the significant motion sensor trigger.
     * This is a one-shot sensor — it fires once and then auto-disables.
     * We re-register it each time we enter SLEEP state.
     */
    private fun registerMotionSensor() {
        val sensor = significantMotionSensor ?: return
        unregisterMotionSensor()

        motionListener = object : android.hardware.TriggerEventListener() {
            override fun onTrigger(event: android.hardware.TriggerEvent?) {
                // Significant motion detected — snap to ACTIVE
                onSignificantMotion()
            }
        }

        sensorManager.requestTriggerSensor(motionListener, sensor)
    }

    private fun unregisterMotionSensor() {
        motionListener?.let { listener ->
            significantMotionSensor?.let { sensor ->
                sensorManager.cancelTriggerSensor(listener, sensor)
            }
        }
        motionListener = null
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
        stationarySince = 0L
        wasScreenOn = pm.isInteractive
        unregisterMotionSensor()
        _state.value = PollingState.ACTIVE
    }

    /** Clean up when streaming stops. */
    fun shutdown() {
        unregisterMotionSensor()
    }

    companion object {
        private const val STATIONARY_RADIUS_METERS = 5f
        private const val stationaryMinFixes = 4
        private const val maxHistorySize = 6
        private const val SCREEN_OFF_IDLE_THRESHOLD_MS = 120_000L    // 2 min → 5min polling
        private const val SCREEN_OFF_SLEEP_THRESHOLD_MS = 300_000L   // 5 min → GPS off + motion sensor
    }
}
