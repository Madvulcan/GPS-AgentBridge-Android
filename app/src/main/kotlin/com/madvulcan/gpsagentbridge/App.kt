package com.madvulcan.gpsagentbridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Registered in AndroidManifest.xml as android:name=".App".
 *
 * Responsibilities:
 *  - Hilt bootstrap (@HiltAndroidApp triggers codegen for the dependency graph).
 *  - Notification channel registration (required before any foreground service notification
 *    can be posted on Android 8.0+, which is our minSdk).
 */
@HiltAndroidApp
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel()
    }

    private fun registerNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        val channel = NotificationChannel(
            getString(R.string.notif_channel_id),
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW, // low = no sound, but visible
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
