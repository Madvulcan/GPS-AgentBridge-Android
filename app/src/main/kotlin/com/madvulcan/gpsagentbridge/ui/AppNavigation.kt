package com.madvulcan.gpsagentbridge.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.madvulcan.gpsagentbridge.ui.screens.AboutScreen
import com.madvulcan.gpsagentbridge.ui.screens.MainScreen
import com.madvulcan.gpsagentbridge.ui.screens.OnboardingScreen
import com.madvulcan.gpsagentbridge.ui.screens.SettingsScreen

/**
 * Top-level navigation graph. Routes:
 *  - "main"       — primary status screen with START/STOP button
 *  - "settings"   — destination servers + behavior toggles
 *  - "about"      — version + description
 *  - "onboarding" — first-run permission/battery setup (also reachable from settings)
 */
@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainScreen(
                onOpenSettings = { nav.navigate("settings") },
                onOpenOnboarding = { nav.navigate("onboarding") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenAbout = { nav.navigate("about") },
                onOpenOnboarding = { nav.navigate("onboarding") },
            )
        }
        composable("about") {
            AboutScreen(onBack = { nav.popBackStack() })
        }
        composable("onboarding") {
            OnboardingScreen(onDone = { nav.popBackStack() })
        }
    }
}
