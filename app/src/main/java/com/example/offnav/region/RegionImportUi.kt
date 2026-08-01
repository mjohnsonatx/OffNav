package com.example.offnav.region

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
fun RegionImportSheet(
    manager: RegionImportManager,
    activeRegion: RegionSnapshot,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state by manager.state.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(manager::import) }

    ModalBottomSheet(onDismissRequest = { if (!manager.isBusy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding()) {
            Text("Offline regions", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Active: ${activeRegion.regionId} · ${activeRegion.version}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            when (val s = state) {
                ImportState.Idle -> {
                    Button(
                        onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Import .offnav bundle…") }
                }

                ImportState.Reading -> Busy("Reading bundle manifest…")
                ImportState.Validating -> Busy("Verifying checksums and databases…")

                is ImportState.Copying -> {
                    LinearProgressIndicator(
                        progress = { s.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${s.copiedBytes / (1024 * 1024)} MB of ${s.totalBytes / (1024 * 1024)} MB")
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = manager::cancel) { Text("Cancel") }
                }

                is ImportState.RestartRequired -> {
                    Text("${s.regionId} ${s.version} is ready.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "OffNav must restart to switch regions. The current region stays active until then.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { AppRestart.restart(context) }) { Text("Restart now") }
                        OutlinedButton(onClick = { manager.acknowledge(); onDismiss() }) { Text("Later") }
                    }
                }

                is ImportState.Failed -> {
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "The current region was not modified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = manager::acknowledge, modifier = Modifier.fillMaxWidth()) { Text("OK") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Busy(text: String) = Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    Spacer(Modifier.width(12.dp))
    Text(text)
}