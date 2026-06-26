package com.madvulcan.gpsagentbridge.ui.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.madvulcan.gpsagentbridge.R
import com.madvulcan.gpsagentbridge.ui.theme.StatusError
import com.madvulcan.gpsagentbridge.ui.theme.StatusStreaming
import com.madvulcan.gpsagentbridge.util.BackgroundLocationState
import com.madvulcan.gpsagentbridge.util.BatteryOptimizationHelper
import com.madvulcan.gpsagentbridge.util.backgroundLocationState
import com.madvulcan.gpsagentbridge.util.hasAnyLocationPermission
import com.madvulcan.gpsagentbridge.util.hasNotificationPermission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current

    var hasLocation by remember { mutableStateOf(hasAnyLocationPermission(context)) }
    var bgState by remember { mutableStateOf(backgroundLocationState(context)) }
    var batteryOpt by remember { mutableStateOf(BatteryOptimizationHelper.isBatteryOptimizationEnabled(context)) }
    var hasNotif by remember { mutableStateOf(hasNotificationPermission(context)) }

    // Re-check on every recomposition trigger
    LaunchedEffect(Unit) {
        hasLocation = hasAnyLocationPermission(context)
        bgState = backgroundLocationState(context)
        batteryOpt = BatteryOptimizationHelper.isBatteryOptimizationEnabled(context)
        hasNotif = hasNotificationPermission(context)
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasLocation = hasAnyLocationPermission(context)
        bgState = backgroundLocationState(context)
    }

    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        bgState = backgroundLocationState(context)
    }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOpt = BatteryOptimizationHelper.isBatteryOptimizationEnabled(context)
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        hasNotif = hasNotificationPermission(context)
    }

    val allDone = hasLocation && bgState == BackgroundLocationState.GRANTED && !batteryOpt && hasNotif

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Step 1: Location permission ----
            OnboardingCard(
                title = stringResource(R.string.onboarding_step_location),
                description = stringResource(R.string.onboarding_step_location_desc),
                isDone = hasLocation && bgState == BackgroundLocationState.GRANTED,
                actionLabel = if (!hasLocation) "Grant foreground location"
                              else if (bgState != BackgroundLocationState.GRANTED) "Grant background location"
                              else null,
                onAction = {
                    if (!hasLocation) {
                        locationLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                    } else if (bgState != BackgroundLocationState.GRANTED) {
                        bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                },
            )

            // ---- Step 2: Notification permission ----
            OnboardingCard(
                title = "Allow notifications",
                description = "The streaming service needs to show a persistent notification. Without this permission, the service can't run in the background and GPS updates will stop. On Android 13+, apps must ask for notification permission explicitly.",
                isDone = hasNotif,
                actionLabel = if (!hasNotif) "Allow notifications" else null,
                onAction = {
                    // On Android 13+ we can request the permission directly
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )

            // ---- Step 3: Battery optimization ----
            OnboardingCard(
                title = stringResource(R.string.onboarding_step_battery),
                description = stringResource(R.string.onboarding_step_battery_desc),
                isDone = !batteryOpt,
                actionLabel = if (batteryOpt) "Disable battery optimization" else null,
                onAction = {
                    batteryLauncher.launch(
                        BatteryOptimizationHelper.ignoreOptimizationIntent(context)
                    )
                },
            )

            // ---- Done ----
            OnboardingCard(
                title = stringResource(R.string.onboarding_step_done),
                description = stringResource(R.string.onboarding_step_done_desc),
                isDone = allDone,
                actionLabel = null,
                onAction = {},
            )

            Spacer(Modifier.weight(1f))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Finish", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun OnboardingCard(
    title: String,
    description: String,
    isDone: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isDone) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isDone) StatusStreaming else StatusError,
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
