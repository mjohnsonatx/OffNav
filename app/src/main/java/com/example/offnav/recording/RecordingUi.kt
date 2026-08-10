package com.example.offnav.recording

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.offnav.data.ActivityType
import com.example.offnav.data.ElevationSource
import com.example.offnav.data.RecordingStatus
import com.example.offnav.data.UnitFormat

@Composable
fun RecordingPanel(viewModel: RecordViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val stats by viewModel.stats.collectAsState()
    val pendingSave by viewModel.pendingSave.collectAsState()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            when (stats.status) {
                RecordingStatus.IDLE -> StartRow { type -> viewModel.start(context, type) }
                else -> ActiveRecording(stats, viewModel)
            }
        }
    }

    if (pendingSave) {
        SaveActivitySheet(
            stats = stats,
            onSave = { title, note -> viewModel.save(context, title, note) },
            onDiscard = { viewModel.discard(context) },
            onDismiss = viewModel::cancelStop,
        )
    }
}

@Composable
private fun StartRow(onStart: (ActivityType) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(ActivityType.WALK) }
    Column {
        Text("Record an activity", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityType.entries.filter { it != ActivityType.OTHER }.forEach { type ->
                FilterChip(
                    selected = selected == type,
                    onClick = { selected = type },
                    label = { Text("${type.emoji} ${type.displayName}") },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onStart(selected) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FiberManualRecord, null, Modifier.size(18.dp), tint = Color(0xFFE53935))
            Spacer(Modifier.width(8.dp))
            Text("Start ${selected.displayName}")
        }
    }
}

@Composable
private fun ActiveRecording(stats: LiveStats, viewModel: RecordViewModel) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.FiberManualRecord, null, Modifier.size(12.dp),
                tint = if (stats.status == RecordingStatus.RECORDING) Color(0xFFE53935)
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (stats.status == RecordingStatus.PAUSED) "Paused"
                else "${stats.type.emoji} ${stats.type.displayName}",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            stats.gpsAccuracyMeters?.let {
                Text(
                    "GPS ±${it.toInt()} m",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (it > 25) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            UnitFormat.clock(stats.activeMillis),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("Distance", UnitFormat.miles(stats.distanceMeters))
            Stat(
                if (stats.type.usesPace) "Avg pace" else "Avg speed",
                UnitFormat.speedOrPace(stats.avgMovingSpeedMps, stats.type),
            )
            if (stats.elevationSource != ElevationSource.NONE) {
                Stat("Gain", UnitFormat.feet(stats.elevationGainMeters))
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (stats.status == RecordingStatus.RECORDING) {
                FilledTonalButton(onClick = viewModel::pause, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Pause, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Pause")
                }
            } else {
                FilledTonalButton(onClick = viewModel::resume, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Resume")
                }
            }
            Button(
                onClick = viewModel::requestStop,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Finish")
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveActivitySheet(
    stats: LiveStats,
    onSave: (String, String) -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("Save activity", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "${UnitFormat.miles(stats.distanceMeters)} · ${UnitFormat.clock(stats.activeMillis)} · " +
                        UnitFormat.speedOrPace(stats.avgMovingSpeedMps, stats.type),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Title") },
                placeholder = { Text("Morning ${stats.type.displayName}") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text("Notes (optional)") },
                minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = { onSave(title, note) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Discard", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard activity?") },
            text = { Text("This permanently deletes the recorded track. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscard() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
    }
}