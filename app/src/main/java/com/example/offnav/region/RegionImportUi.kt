package com.example.offnav.region

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.offnav.MainActivity

object AppRestart {
    /** The region can't be swapped while MapLibre / SQLite / GraphHopper hold it: restart the process. */
    fun restart(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            context, 0x0FF4A7, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.RTC, System.currentTimeMillis() + 150L, pending)
        Runtime.getRuntime().exit(0)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionsSheet(
    manager: RegionImportManager,
    catalog: RegionCatalog,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val importState by manager.state.collectAsStateWithLifecycle()
    val regions by catalog.regions.collectAsStateWithLifecycle()
    val pending by catalog.pendingActivation.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(manager::import)
    }

    // Any completed import changes what is on disk.
    LaunchedEffect(importState) {
        if (importState is ImportState.RestartRequired) catalog.refresh()
    }

    ModalBottomSheet(onDismissRequest = { if (!manager.isBusy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text("Offline regions", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))

            when (val s = importState) {
                ImportState.Reading -> Busy("Reading bundle manifest…")
                ImportState.Validating -> Busy("Verifying checksums and databases…")
                is ImportState.Copying -> {
                    LinearProgressIndicator({ s.fraction }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${s.copiedBytes / (1024 * 1024)} MB of ${s.totalBytes / (1024 * 1024)} MB")
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = manager::cancel) { Text("Cancel") }
                }
                is ImportState.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Text(
                        "No installed region was modified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = manager::acknowledge) { Text("Dismiss") }
                }
                is ImportState.RestartRequired, ImportState.Idle -> {
                    Button(
                        onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Import .offnav bundle…") }
                }
            }

            pending?.let { p ->
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${p.displayName} loads on next launch", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Maps, routing and search can't be swapped while they're open.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { AppRestart.restart(context) }) { Text("Restart") }
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()

            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(regions, key = { it.installId }) { region ->
                    RegionRow(
                        region = region,
                        onActivate = {
                            error = null
                            catalog.activate(region.installId) { r ->
                                error = r.exceptionOrNull()?.message
                            }
                        },
                        onDelete = {
                            error = null
                            catalog.delete(region.installId) { r ->
                                error = r.exceptionOrNull()?.message
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RegionRow(region: RegionInfo, onActivate: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(region.displayName, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                when {
                    region.isActive -> AssistChip({}, { Text("Active") }, enabled = false)
                    region.isPendingActivation -> AssistChip({}, { Text("Next launch") }, enabled = false)
                }
            }
            Text(
                buildString {
                    append(region.regionId).append(" · ").append(region.version)
                    region.installedBytes?.let { append(" · ").append(it / (1024 * 1024)).append(" MB") }
                        ?: append(" · size unknown")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            region.bounds?.let { b ->
                Text(
                    "%.2f,%.2f → %.2f,%.2f".format(b.minLatitude, b.minLongitude, b.maxLatitude, b.maxLongitude),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (region.canActivate) TextButton(onClick = onActivate) { Text("Use") }
        if (region.canDelete) {
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete ${region.displayName}") }
        }
    }
}

@Composable
private fun Busy(text: String) = Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    Spacer(Modifier.width(12.dp))
    Text(text)
}