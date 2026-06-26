package com.madvulcan.gpsagentbridge.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.madvulcan.gpsagentbridge.MainActivity
import com.madvulcan.gpsagentbridge.R
import com.madvulcan.gpsagentbridge.data.SettingsRepository
import com.madvulcan.gpsagentbridge.location.AdaptivePollingController
import com.madvulcan.gpsagentbridge.location.LocationEngine
import com.madvulcan.gpsagentbridge.location.StreamingStateHolder
import com.madvulcan.gpsagentbridge.location.StreamStatus
import com.madvulcan.gpsagentbridge.location.TransmissionEngine
import com.madvulcan.gpsagentbridge.net.UdpSender
import com.madvulcan.gpsagentbridge.receiver.ScreenStateReceiver
import com.madvulcan.gpsagentbridge.util.hasForegroundLocationPermission
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GpsStreamingService : Service() {

    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var udpSender: UdpSender
    @Inject lateinit var locationEngine: LocationEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: TransmissionEngine
    private lateinit var adaptiveController: AdaptivePollingController

    private var streamingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: ScreenStateReceiver? = null

    override fun onCreate() {
        super.onCreate()
        engine = TransmissionEngine(sender = udpSender)
        adaptiveController = AdaptivePollingController(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startStreaming()
        }
        return START_STICKY
    }

    private fun startStreaming() {
        if (!hasForegroundLocationPermission(this)) {
            Log.w(TAG, "missing location permission — refusing to start")
            stopSelf()
            return
        }

        startForeground(NOTIF_ID, buildNotification(engine.state.value), buildForegroundServiceType())
        acquireWakeLock()

        engine.startStreaming()
        adaptiveController.reset()
        StreamingStateHolder.bind(engine)

        registerScreenReceiver()

        scope.launch {
            settingsRepo.settings.collectLatest { s ->
                engine.setSettings(s)
            }
        }

        scope.launch {
            engine.state.collectLatest { state ->
                StreamingStateHolder.publish(state)
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIF_ID, buildNotification(state))
            }
        }

        // Watch adaptive polling interval → restart location collection on change
        scope.launch {
            adaptiveController.desiredInterval.collectLatest { interval ->
                locationEngine.updateInterval(interval)
                Log.d(TAG, "Adaptive polling: interval = ${interval / 1000}s")
                restartLocationStream()
            }
        }

        startLocationStream()
    }

    private fun startLocationStream() {
        if (streamingJob?.isActive == true) return

        streamingJob = scope.launch {
            try {
                locationEngine.stream().collect { fix ->
                    engine.onFix(fix)
                    adaptiveController.onFix(fix)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "location stream failed", t)
                engine.onGpsLost()
            }
        }
    }

    private fun restartLocationStream() {
        streamingJob?.cancel()
        streamingJob = null
        startLocationStream()
    }

    private fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        engine.stop()
        StreamingStateHolder.unbind()
        releaseWakeLock()
        unregisterScreenReceiver()
        udpSender.close()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        scope.cancel()
    }

    // Screen receiver

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = ScreenStateReceiver { isOn ->
            val intervalChanged = adaptiveController.onScreenStateChanged(isOn)
            if (intervalChanged) {
                locationEngine.updateInterval(adaptiveController.desiredInterval.value)
                restartLocationStream()
                Log.d(TAG, "Screen ${if (isOn) "on" else "off"}: interval -> ${adaptiveController.desiredInterval.value / 1000}s")
            }
        }
        registerReceiver(screenReceiver, ScreenStateReceiver.filter())
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            unregisterReceiver(it)
            screenReceiver = null
        }
    }

    // Notification

    private fun buildNotification(state: com.madvulcan.gpsagentbridge.location.TransmissionState): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val (title, text) = when (state.status) {
            StreamStatus.IDLE -> getString(R.string.notif_title_idle) to getString(R.string.notif_text_default)
            StreamStatus.WAITING_FOR_FIX,
            StreamStatus.CONNECTING -> getString(R.string.notif_title_waiting) to getString(R.string.notif_text_default)
            StreamStatus.TRACKING,
            StreamStatus.TRANSMITTING -> {
                val title = getString(R.string.notif_title_streaming)
                val text = if (state.currentFix != null) {
                    val ago = formatLastTx(this, state.lastTransmissionMillis)
                    getString(R.string.notif_text_streaming, state.currentFix.latitude, state.currentFix.longitude, ago)
                } else {
                    getString(R.string.notif_text_default)
                }
                title to text
            }
            StreamStatus.ERROR -> getString(R.string.notif_title_idle) to (state.lastError ?: "error")
        }

        return NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildForegroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0
    }

    // Wake lock

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).also {
            it.acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val TAG = "GpsStreamingService"
        private const val NOTIF_ID = 0xAB01
        private const val WAKE_LOCK_TAG = "gps-agent-bridge::streaming"

        const val ACTION_START = "com.madvulcan.gpsagentbridge.START"
        const val ACTION_STOP = "com.madvulcan.gpsagentbridge.STOP"

        fun start(context: Context) {
            val intent = Intent(context, GpsStreamingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GpsStreamingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

internal fun formatLastTx(context: Context, lastTxMillis: Long): String {
    if (lastTxMillis == 0L) return context.getString(R.string.never)
    val deltaSec = (System.currentTimeMillis() - lastTxMillis) / 1000
    return when {
        deltaSec < 5 -> context.getString(R.string.just_now)
        deltaSec < 60 -> context.getString(R.string.seconds_ago, deltaSec.toInt())
        else -> context.getString(R.string.minutes_ago, (deltaSec / 60).toInt())
    }
}
