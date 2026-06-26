package com.madvulcan.gpsagentbridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Receives screen on/off broadcasts and notifies the [AdaptivePollingController].
 *
 * The service registers this receiver dynamically (not in manifest) so it only
 * runs while streaming. Screen-off events trigger slower GPS polling to save battery.
 */
class ScreenStateReceiver(
    private val onScreenStateChanged: (isOn: Boolean) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> onScreenStateChanged(false)
            Intent.ACTION_SCREEN_ON -> onScreenStateChanged(true)
        }
    }

    companion object {
        /** Create and return a filter matching screen on/off actions. */
        fun filter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
    }
}
