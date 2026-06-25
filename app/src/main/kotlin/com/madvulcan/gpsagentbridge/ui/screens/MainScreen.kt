package com.madvulcan.gpsagentbridge.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.madvulcan.gpsagentbridge.R
import com.madvulcan.gpsagentbridge.data.ServerTarget
import com.madvulcan.gpsagentbridge.location.StreamStatus
import com.madvulcan.gpsagentbridge.location.TargetStat
import com.madvulcan.gpsagentbridge.location.TransmissionState
import com.madvulcan.gpsagentbridge.service.GpsStreamingService
import com.madvulcan.gpsagentbridge.ui.StreamingViewModel
import com.madvulcan.gpsagentbridge.ui.theme.StatusError
import com.madvulcan.gpsagentbridge.ui.theme.StatusIdle
import com.madvulcan.gpsagentbridge.ui.theme.StatusStreaming
import com.madvulcan.gpsagentbridge.ui.theme.StatusWaiting
import com.madvulcan.gpsagentbridge.util.BackgroundLocationState
import com.madvulcan.gpsagentbridge.util.BatteryOptimizationHelper
import com.madvulcan.gpsagentbridge.util.backgroundLocationState
import com.madvulcan.gpsagentbridge.util.hasAnyLocationPermission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenOnboarding: () -> Unit,
    vm: StreamingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // ---- permission launchers ----
    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* recompute handled by recomposition */ }

    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* recompute handled by recomposition */ }

    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* recompute handled by recomposition */ }

    // ---- derived state ----
    val hasLocation = hasAnyLocationPermission(context)
    val bgState = backgroundLocationState(context)
    val batteryOpt = BatteryOptimizationHelper.isBatteryOptimizationEnabled(context)

    // Show onboarding prompt the first time we detect missing setup.
    LaunchedEffect(hasLocation, bgState, batteryOpt) {
        if (!hasLocation || bgState != BackgroundLocationState.GRANTED) {
            snackbar.showSnackbar("Setup incomplete — tap to open onboarding.")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_open_settings))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Warnings (if any)
            if (!hasLocation) {
                WarningBanner(
                    text = stringResource(R.string.warning_no_location_permission),
                    onClick = { onOpenOnboarding() },
                )
            } else if (bgState != BackgroundLocationState.GRANTED) {
                WarningBanner(
                    text = stringResource(R.string.warning_background_location),
                    onClick = {
                        // Re-request background location (only works after foreground granted).
                        bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    },
                )
            }
            if (batteryOpt) {
                WarningBanner(
                    text = stringResource(R.string.warning_battery_optimization),
                    onClick = {
                        batteryOptLauncher.launch(
                            BatteryOptimizationHelper.ignoreOptimizationIntent(context)
                        )
                    },
                )
            }
            if (settings.targets.isEmpty()) {
                WarningBanner(
                    text = stringResource(R.string.warning_no_targets),
                    onClick = onOpenSettings,
                )
            }

            // Status card
            StatusCard(state = state)

            // Big STOP/START button
            val isStreaming = state.status != StreamStatus.IDLE
            Button(
                onClick = {
                    if (isStreaming) GpsStreamingService.stop(context)
                    else GpsStreamingService.start(context)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStreaming) StatusError else StatusStreaming,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    if (isStreaming) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isStreaming) stringResource(R.string.action_stop) else stringResource(R.string.action_start),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Test send button
            OutlinedButton(
                onClick = { vm.sendTestPacket() },
                modifier = Modifier.fillMaxWidth(),
                enabled = settings.targets.isNotEmpty(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_test_send))
            }

            // Targets list
            Text(
                stringResource(R.string.label_targets),
                style = MaterialTheme.typography.titleLarge,
            )
            TargetsList(
                targets = settings.targets,
                stats = state.perTargetStats,
            )
        }
    }
}

@Composable
private fun StatusCard(state: TransmissionState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Status row with colored dot
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(colorForStatus(state.status), shape = CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusLabel(state.status),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            val fix = state.currentFix
            if (fix != null) {
                InfoRow(stringResource(R.string.label_coords), "%.5f, %.5f".format(fix.latitude, fix.longitude))
                InfoRow(stringResource(R.string.label_altitude), "%.0f m".format(fix.altitudeMeters))
                InfoRow(stringResource(R.string.label_accuracy), "±%.0f m".format(fix.accuracyMeters))
            } else {
                InfoRow(stringResource(R.string.label_coords), "—")
            }

            InfoRow(stringResource(R.string.label_last_tx), formatLastTx(state.lastTransmissionMillis))
            InfoRow(stringResource(R.string.label_distance_moved), formatDistance(state.distanceSinceLastTx))
            InfoRow(
                stringResource(R.string.label_today),
                "${state.transmissionsToday} events × ${state.perTargetStats.size} servers"
            )
            if (state.fixesRejectedAccuracy > 0) {
                InfoRow(stringResource(R.string.label_fixes_rejected), "${state.fixesRejectedAccuracy}")
            }
        }
    }
}

@Composable
private fun TargetsList(targets: List<ServerTarget>, stats: Map<String, TargetStat>) {
    if (targets.isEmpty()) {
        Text(
            "No targets configured. Open Settings → Destination servers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items = targets, key = { it.stableId }) { t ->
            val stat = stats[t.stableId]
            val okLabel = when {
                stat?.lastError != null -> "fail: ${stat.lastError!!.take(40)}"
                stat == null || stat.totalAttempts == 0 -> "untested"
                else -> "ok"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            when {
                                stat?.lastError != null -> StatusError
                                stat == null || stat.totalAttempts == 0 -> StatusIdle
                                else -> StatusStreaming
                            },
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(t.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("${t.host}:${t.port}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Text(okLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun WarningBanner(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = StatusWaiting.copy(alpha = 0.15f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// -------------------------------------------------------------- helpers

@Composable
private fun statusLabel(s: StreamStatus): String = when (s) {
    StreamStatus.IDLE -> stringResource(R.string.status_idle)
    StreamStatus.CONNECTING -> stringResource(R.string.status_connecting)
    StreamStatus.WAITING_FOR_FIX -> stringResource(R.string.status_waiting_for_fix)
    StreamStatus.TRACKING -> stringResource(R.string.status_tracking)
    StreamStatus.TRANSMITTING -> stringResource(R.string.status_tracking) // brief, same label
    StreamStatus.ERROR -> stringResource(R.string.status_error)
}

private fun colorForStatus(s: StreamStatus): Color = when (s) {
    StreamStatus.IDLE -> StatusIdle
    StreamStatus.CONNECTING -> StatusWaiting
    StreamStatus.WAITING_FOR_FIX -> StatusWaiting
    StreamStatus.TRACKING -> StatusStreaming
    StreamStatus.TRANSMITTING -> StatusStreaming
    StreamStatus.ERROR -> StatusError
}

private fun formatLastTx(lastTxMillis: Long): String {
    if (lastTxMillis == 0L) return "never"
    val deltaSec = (System.currentTimeMillis() - lastTxMillis) / 1000
    return when {
        deltaSec < 5 -> "just now"
        deltaSec < 60 -> "${deltaSec}s ago"
        else -> "${deltaSec / 60} min ago"
    }
}

private fun formatDistance(m: Float?): String = when {
    m == null -> "—"
    m < 1000 -> "%.0f m".format(m)
    else -> "%.2f km".format(m / 1000)
}
