package com.madvulcan.gpsagentbridge.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.madvulcan.gpsagentbridge.data.SettingsRepository
import com.madvulcan.gpsagentbridge.service.GpsStreamingService
import com.madvulcan.gpsagentbridge.util.hasForegroundLocationPermission
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Auto-starts the streaming service when the device finishes booting, if the user
 * has enabled "Auto-start on boot" in settings.
 *
 * Registered in AndroidManifest.xml for:
 *  - BOOT_COMPLETED          (normal boot)
 *  - LOCKED_BOOT_COMPLETED   (direct-boot — for users with FBE, before first unlock)
 *  - MY_PACKAGE_REPLACED     (app update — preserve the running state)
 *
 * Note: On Android 10+, starting a foreground service from the background is restricted.
 * BOOT_COMPLETED is on the exemption list, so we can call startForegroundService() here.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepo: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        // We can't do async work directly in onReceive without goAsync, but Hilt's
        // BroadcastReceiver injection only works during onReceive — so use goAsync
        // to keep the process alive long enough for the coroutine to complete.
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        scope.launch {
            try {
                val settings = settingsRepo.settings.first()
                if (!settings.autoStartOnBoot) {
                    Log.d(TAG, "auto-start disabled in settings — skipping")
                    return@launch
                }
                if (!hasForegroundLocationPermission(context)) {
                    Log.w(TAG, "auto-start enabled but location permission not granted — skipping")
                    return@launch
                }
                Log.i(TAG, "auto-starting streaming service (action=$action)")
                GpsStreamingService.start(context)
            } catch (t: Throwable) {
                Log.e(TAG, "auto-start failed", t)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
