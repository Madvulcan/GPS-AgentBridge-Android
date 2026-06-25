package com.madvulcan.gpsagentbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.madvulcan.gpsagentbridge.ui.AppNavigation
import com.madvulcan.gpsagentbridge.ui.theme.GpsAgentBridgeTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host for the Compose UI. All navigation is handled by
 * androidx.navigation.compose — see [AppNavigation].
 *
 * Hilt-android-entrypoint so we can inject ViewModels.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GpsAgentBridgeTheme {
                AppNavigation()
            }
        }
    }
}
