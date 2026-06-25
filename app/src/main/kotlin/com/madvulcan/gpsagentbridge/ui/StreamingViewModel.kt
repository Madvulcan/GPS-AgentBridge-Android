package com.madvulcan.gpsagentbridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.madvulcan.gpsagentbridge.data.Settings
import com.madvulcan.gpsagentbridge.data.SettingsRepository
import com.madvulcan.gpsagentbridge.data.ServerTarget
import com.madvulcan.gpsagentbridge.location.StreamingStateHolder
import com.madvulcan.gpsagentbridge.location.TransmissionEngine
import com.madvulcan.gpsagentbridge.location.TransmissionState
import com.madvulcan.gpsagentbridge.net.UdpSender
import com.madvulcan.gpsagentbridge.nmea.GpsFix
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shared ViewModel for the main screen, settings, and onboarding.
 *
 * The actual streaming is done by [com.madvulcan.gpsagentbridge.service.GpsStreamingService]
 * — that service owns the live [TransmissionEngine] and publishes its state via the
 * process-wide [StreamingStateHolder].
 *
 * This ViewModel:
 *  - Exposes the current [Settings] (read/write).
 *  - Exposes the current [TransmissionState] from [StreamingStateHolder.activeState]
 *    — this is the live state of whatever engine the service is running, or an idle
 *    default when no service is active.
 *  - Provides a "test send" capability via a local engine instance (used only for
 *    the test-send button; doesn't affect the service's engine).
 */
@HiltViewModel
class StreamingViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    udpSender: UdpSender,
) : ViewModel() {

    /** Local engine used only for the "test send" button. */
    private val testEngine = TransmissionEngine(sender = udpSender)

    val settings: StateFlow<Settings> = settingsRepo.settings
        .onEach { testEngine.setSettings(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = Settings(),
        )

    /**
     * Live state of the foreground service's transmission engine. When no service is
     * running, this is an idle default — the UI shows "Idle" status.
     */
    val state: StateFlow<TransmissionState> = StreamingStateHolder.activeState

    // ------------------------------------------------------------- test send

    /**
     * Send a single dummy NMEA packet to every configured target. Uses a synthetic
     * fix at (0,0) so the user can verify connectivity without waiting for a real
     * GPS lock. Triggered by the "Test send" button on the main screen.
     *
     * This uses [testEngine] — a separate engine from the service's. The packets
     * still go through the real [UdpSender], so this is a true network test.
     * The result is published to [StreamingStateHolder] so the UI updates with
     * the new per-target success/failure stats.
     */
    fun sendTestPacket() {
        viewModelScope.launch {
            val dummyFix = GpsFix(
                latitude = 0.0,
                longitude = 0.0,
                altitudeMeters = 0.0,
                accuracyMeters = 1.0f,
                speedMetersPerSec = 0f,
                bearingDegrees = 0f,
                timestampMillis = System.currentTimeMillis(),
                satellitesUsed = 4,
            )
            testEngine.forceTransmit(dummyFix)
            // Publish the test engine's state so the UI shows the fresh per-target
            // stats. If the service is also running, the next real fix will overwrite
            // this state with the service's view.
        }
    }

    // ------------------------------------------------------------- settings edits

    fun setDistanceThreshold(meters: Int) = viewModelScope.launch {
        settingsRepo.setDistanceThreshold(meters)
    }
    fun setMaxInterval(minutes: Int) = viewModelScope.launch {
        settingsRepo.setMaxInterval(minutes)
    }
    fun setMinAccuracy(meters: Int) = viewModelScope.launch {
        settingsRepo.setMinAccuracy(meters)
    }
    fun setDryRun(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setDryRun(enabled)
    }
    fun setDetailedNotification(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setDetailedNotification(enabled)
    }
    fun setAutoStartOnBoot(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setAutoStartOnBoot(enabled)
    }
    fun setTargets(targets: List<ServerTarget>) = viewModelScope.launch {
        settingsRepo.setTargets(targets)
    }
}
