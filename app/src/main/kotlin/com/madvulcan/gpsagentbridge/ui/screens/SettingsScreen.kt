package com.madvulcan.gpsagentbridge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.madvulcan.gpsagentbridge.R
import com.madvulcan.gpsagentbridge.data.ServerTarget
import com.madvulcan.gpsagentbridge.ui.StreamingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenOnboarding: () -> Unit,
    vm: StreamingViewModel = hiltViewModel(),
) {
    val settings by vm.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenAbout) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.settings_about))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val newTarget = ServerTarget(host = "", port = 2948, label = "")
                vm.setTargets(settings.targets + newTarget)
            }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_add_target))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ----------------------------------------------------------- targets
            Section(stringResource(R.string.settings_section_targets))
            if (settings.targets.isEmpty()) {
                Text(
                    "Tap + to add a destination server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                settings.targets.forEachIndexed { index, t ->
                    TargetEditor(
                        target = t,
                        onChange = { updated ->
                            val list = settings.targets.toMutableList()
                            list[index] = updated
                            vm.setTargets(list)
                        },
                        onDelete = {
                            val list = settings.targets.toMutableList()
                            list.removeAt(index)
                            vm.setTargets(list)
                        },
                    )
                }
            }

            HorizontalDivider()

            // ----------------------------------------------------------- behavior
            Section(stringResource(R.string.settings_section_behavior))
            IntField(
                label = stringResource(R.string.settings_distance_threshold),
                value = settings.distanceThresholdMeters,
                suffix = "m",
                min = 50, max = 5000,
                onValueChange = { vm.setDistanceThreshold(it) },
            )
            IntField(
                label = stringResource(R.string.settings_max_interval),
                value = settings.maxIntervalMinutes,
                suffix = "min",
                min = 5, max = 120,
                onValueChange = { vm.setMaxInterval(it) },
            )
            IntField(
                label = stringResource(R.string.settings_min_accuracy),
                value = settings.minAccuracyMeters,
                suffix = "m",
                min = 1, max = 200,
                onValueChange = { vm.setMinAccuracy(it) },
            )
            ToggleRow(
                label = stringResource(R.string.settings_dry_run),
                checked = settings.dryRun,
                onCheckedChange = { vm.setDryRun(it) },
            )

            HorizontalDivider()

            // ----------------------------------------------------------- notifications
            Section(stringResource(R.string.settings_section_notifications))
            ToggleRow(
                label = stringResource(R.string.settings_detailed_notification),
                checked = settings.detailedNotification,
                onCheckedChange = { vm.setDetailedNotification(it) },
            )

            HorizontalDivider()

            // ----------------------------------------------------------- system
            Section(stringResource(R.string.settings_section_system))
            ToggleRow(
                label = stringResource(R.string.settings_autostart_boot),
                checked = settings.autoStartOnBoot,
                onCheckedChange = { vm.setAutoStartOnBoot(it) },
            )
            Button(onClick = onOpenOnboarding, modifier = Modifier.fillMaxWidth()) {
                Text("Re-run onboarding")
            }

            Spacer(Modifier.height(80.dp)) // FAB clearance
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TargetEditor(
    target: ServerTarget,
    onChange: (ServerTarget) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = target.label,
                onValueChange = { onChange(target.copy(label = it)) },
                label = { Text(stringResource(R.string.settings_target_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = target.host,
                    onValueChange = { onChange(target.copy(host = it.trim())) },
                    label = { Text(stringResource(R.string.settings_target_host)) },
                    modifier = Modifier.weight(2f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = target.port.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { p ->
                            if (p in 1..65535) onChange(target.copy(port = p))
                        }
                    },
                    label = { Text(stringResource(R.string.settings_target_port)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        }
    }
}

@Composable
private fun IntField(
    label: String,
    value: Int,
    suffix: String,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input.filter { it.isDigit() }
                text.toIntOrNull()?.let { n ->
                    if (n in min..max) onValueChange(n)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = { Text(suffix) },
            supportingText = { Text("range $min..$max") },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
