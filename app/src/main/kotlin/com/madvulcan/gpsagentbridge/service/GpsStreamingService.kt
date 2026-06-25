package com.madvulcan.gpsagentbridge.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.madvulcan.gpsagentbridge.MainActivity
import com.madvulcan.gpsagentbridge.R
import com.madvulcan.gpsagentbridge.data.SettingsRepository
import com.madvulcan.gpsagentbridge.location.LocationEngine
import com.madvulcan.gpsagentbridge.location.StreamingStateHolder
import com.madvulcan.gpsagentbridge.location.StreamStatus
import com.madvulcan.gpsagentbridge.location.TransmissionEngine
import com.madvulcan.gpsagentbridge.net.UdpSender
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

/**
 * Foreground service that owns the streaming lifecycle.
 *
 * Why a foreground service (and not just a coroutine):
 *  - Android 10+ (API 29+) requires a foreground service of type "location" to receive
 *    background location updates.
 *  - Android 14+ (API 34+) requires the foregroundServiceType="location" attribute to
 *    be set in the manifest AND passed to startForeground() at runtime.
 *  - A foreground service shows a persistent notification, which is the OS's way of
 *    telling the user "this app is using your location in the background."
 *
 * Lifecycle:
 *  - startService(Intent action=START) → start streaming
 *  - startService(Intent action=STOP)  → stop streaming (but keep service alive briefly)
 *  - User swipes app away → service keeps running (it's foreground)
 *  - User taps STOP in the app → service calls stopSelf()
 *
 * The service is started from [com.madvulcan.gpsagentbridge.ui.MainScreen] when the
 * user taps START, and from [com.madvulcan.gpsagentbridge.boot.BootReceiver] when the
 * device reboots (if auto-start is enabled).
 */
@AndroidEntryPoint
class GpsStreamingService : Service() {

    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var udpSender: UdpSender
    @Inject lateinit var locationEngine: LocationEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: TransmissionEngine

    override fun onCreate() {
        super.onCreate()
        engine = TransmissionEngine(sender = udpSender)
    }
    private var streamingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null  // not a bound service

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startStreaming()
        }
        return START_STICKY  // if the OS kills us, restart with the original intent
    }

    // ------------------------------------------------------------------ start/stop

    private fun startStreaming() {
        // Bail out cleanly if location permission has been revoked since last run.
        if (!hasForegroundLocationPermission(this)) {
            Log.w(TAG, "missing location permission — refusing to start")
            stopSelf()
            return
        }

        // Promote to foreground FIRST — Android requires this within 5 seconds of
        // startForegroundService() (Android 8+) / startService (Android 14+ with FGS).
        startForeground(NOTIF_ID, buildNotification(engine.state.value), buildForegroundServiceType())

        // Acquire a partial wake lock so GPS checks aren't skipped during doze.
        // This is "Should" priority per the doc — we keep it lightweight.
        acquireWakeLock()

        engine.startStreaming()
        // Wire the engine's state into the process-wide holder so the UI can read it.
        StreamingStateHolder.bind(engine)

        // Collect settings and feed them into the engine.
        scope.launch {
            settingsRepo.settings.collectLatest { s ->
                engine.setSettings(s)
            }
        }

        // Collect engine state → update both the notification AND the process-wide holder.
        scope.launch {
            engine.state.collectLatest { state ->
                StreamingStateHolder.publish(state)
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIF_ID, buildNotification(state))
            }
        }

        // If we're already streaming, don't start another collection job.
        if (streamingJob?.isActive == true) return

        streamingJob = scope.launch {
            try {
                locationEngine.stream().collect { fix ->
                    engine.onFix(fix)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "location stream failed", t)
                engine.onGpsLost()
            }
        }
    }

    private fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        engine.stop()
        StreamingStateHolder.unbind()
        releaseWakeLock()
        udpSender.close()
        // Cancel the notification so the user sees the service is gone.
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        scope.cancel()
    }

    // ------------------------------------------------------------------ notification

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
        // Android 10+ requires foregroundServiceType="location" in the manifest.
        // Android 14+ also requires it to be passed to startForeground() at runtime.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0
    }

    // ------------------------------------------------------------------ wake lock

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).also {
            // Hold for 12 hours max as a safety net; the service releases it on stop.
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

        /** Convenience for callers — starts the streaming service. */
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

        /** Convenience for callers — stops the streaming service. */
        fun stop(context: Context) {
            val intent = Intent(context, GpsStreamingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

/** Formats "last tx" time for notification text. Pure function, in companion for testability. */
internal fun formatLastTx(context: Context, lastTxMillis: Long): String {
    if (lastTxMillis == 0L) return context.getString(R.string.never)
    val deltaSec = (System.currentTimeMillis() - lastTxMillis) / 1000
    return when {
        deltaSec < 5 -> context.getString(R.string.just_now)
        deltaSec < 60 -> context.getString(R.string.seconds_ago, deltaSec.toInt())
        else -> context.getString(R.string.minutes_ago, (deltaSec / 60).toInt())
    }
}
