package com.ratatoskr.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ratatoskr.shared.api.ImportResult
import com.ratatoskr.shared.platform.PickedFile
import com.ratatoskr.shared.platform.PlatformFiles
import com.ratatoskr.shared.state.AppState
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Composable
private fun CsvDialogCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .width(440.dp)
            .background(RatatoskrColors.Panel, RoundedCornerShape(8.dp))
            .border(1.dp, RatatoskrColors.Hairline, RoundedCornerShape(8.dp))
            .padding(24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = RatatoskrColors.Text)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** Mirrors #export-modal: the same unencrypted-file warning, and the
 * confirm button stays disabled until it's acknowledged. */
@Composable
fun ExportDialog(appState: AppState, files: PlatformFiles, onDismiss: () -> Unit) {
    var acknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        CsvDialogCard("Export as CSV") {
            Text(
                "This saves every password you can currently see as a plain, unencrypted file. " +
                    "Anyone who can open it — on this device, in a cloud-synced folder, in an email " +
                    "attachment, wherever it ends up — can read every password in it. Nothing about the " +
                    "encryption this app normally uses applies once the data leaves as CSV.",
                style = MaterialTheme.typography.bodyMedium,
                color = RatatoskrColors.DangerBright,
            )
            Column(modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 6.dp)) {
                for (line in listOf(
                    "Delete the file once you're done with whatever you needed it for.",
                    "Don't leave it in a folder that auto-syncs to cloud storage.",
                    "Don't email or message it to yourself as a way to move it between devices — use a direct transfer instead.",
                )) {
                    Text("•  $line", style = MaterialTheme.typography.bodySmall, color = RatatoskrColors.TextMuted,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = acknowledged,
                    onCheckedChange = { acknowledged = it },
                    colors = CheckboxDefaults.colors(checkedColor = RatatoskrColors.Brass),
                )
                Text("I understand this file will not be encrypted", style = MaterialTheme.typography.bodyMedium,
                    color = RatatoskrColors.Text)
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                GhostButton("Cancel", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                DangerButton(
                    "Save unencrypted CSV",
                    enabled = acknowledged && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                val csv = appState.exportCsv()
                                if (csv == null) {
                                    error = appState.errorMessage
                                    appState.errorMessage = null
                                } else {
                                    // UTC date, same as the web UI's toISOString().slice(0, 10)
                                    val date = Clock.System.now().toString().take(10)
                                    if (files.saveCsvExport("ratatoskr-export-$date.csv", csv) != null) {
                                        appState.notice = "Exported — remember to delete the file once you're done with it"
                                        onDismiss()
                                    }
                                }
                            } catch (e: Exception) {
                                error = "Couldn't save the file: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
            }
            ErrorText(error)
        }
    }
}

/** The web UI's confirm() before importing -- import only ever adds. */
@Composable
fun ImportConfirmDialog(
    appState: AppState,
    file: PickedFile,
    onDismiss: () -> Unit,
    onImported: (ImportResult) -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        CsvDialogCard("Import from CSV") {
            Text(
                "Import entries from \"${file.name}\"? This adds new entries, it doesn't overwrite existing ones.",
                style = MaterialTheme.typography.bodyMedium,
                color = RatatoskrColors.Text,
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                GhostButton("Cancel", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                PrimaryButton(
                    if (busy) "Importing…" else "Import",
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            val result = appState.importCsv(file.name, file.bytes)
                            busy = false
                            if (result != null) {
                                onImported(result)
                            } else {
                                error = appState.errorMessage
                                appState.errorMessage = null
                            }
                        }
                    },
                )
            }
            ErrorText(error)
        }
    }
}

/** Per-row problems from an import. The web UI logs these to the browser
 * console; a desktop app has no console its users will see, so they're
 * listed here instead. The server never includes field values in them. */
@Composable
fun ImportErrorsDialog(errors: List<String>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        CsvDialogCard("Some rows had problems") {
            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                items(errors) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = RatatoskrColors.TextMuted,
                        modifier = Modifier.padding(vertical = 3.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                PrimaryButton("OK", onClick = onDismiss)
            }
        }
    }
}

/** The web UI's .toast: panel-alt with a brass border, bottom-center. */
@Composable
fun Toast(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = RatatoskrColors.Text,
        modifier = modifier
            .background(RatatoskrColors.PanelAlt, RoundedCornerShape(6.dp))
            .border(1.dp, RatatoskrColors.Brass, RoundedCornerShape(6.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
