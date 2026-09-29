package com.ratatoskr.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ratatoskr.shared.api.EntryIn
import com.ratatoskr.shared.api.EntryOut
import com.ratatoskr.shared.state.AppState
import com.ratatoskr.shared.totp.Totp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Create/edit dialog -- mirrors #entry-modal in static/index.html,
 * including the "This login uses 2FA" toggle and the live-updating code
 * with countdown ring (static/totp.js's tickTotpDisplay, reimplemented
 * here in Kotlin against the same Totp.code()/secondsRemaining() shared
 * logic rather than the JS file directly, since this is a different
 * runtime). Sharing is deferred (see VaultScreen's doc comment) so there
 * is no Share button here yet, matching that same scoping call. */
@Composable
fun EntryEditDialog(
    appState: AppState,
    existing: EntryOut?,
    onDismiss: () -> Unit,
    onOpenGenerator: (onGenerated: (String) -> Unit) -> Unit,
) {
    var site by remember { mutableStateOf(existing?.site ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var totpEnabled by remember { mutableStateOf(!existing?.totp_secret.isNullOrBlank()) }
    var totpSecret by remember { mutableStateOf(existing?.totp_secret ?: "") }
    var localError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val readOnly = existing != null && !existing.can_write
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .background(RatatoskrColors.Panel, RoundedCornerShape(8.dp))
                .border(1.dp, RatatoskrColors.Hairline, RoundedCornerShape(8.dp))
                .padding(24.dp),
        ) {
            Text(
                if (existing == null) "New entry" else "Edit entry",
                style = MaterialTheme.typography.titleMedium,
                color = RatatoskrColors.Text,
            )
            Spacer(Modifier.height(12.dp))

            RatatoskrTextField(site, { site = it }, "Site / service", Modifier.fillMaxWidth(), enabled = !readOnly)
            RatatoskrTextField(username, { username = it }, "Username or email", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !readOnly)
            RatatoskrTextField(url, { url = it }, "URL", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !readOnly)

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RatatoskrTextField(password, { password = it }, "Password", Modifier.weight(1f), enabled = !readOnly)
                if (!readOnly) {
                    GhostButton(
                        "Generate",
                        onClick = { onOpenGenerator { generated -> password = generated } },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            if (!readOnly) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = totpEnabled,
                        onCheckedChange = { totpEnabled = it; if (!it) totpSecret = "" },
                        colors = CheckboxDefaults.colors(checkedColor = RatatoskrColors.Brass),
                    )
                    Text(
                        "This login uses 2FA -- not every site needs this",
                        style = MaterialTheme.typography.bodySmall,
                        color = RatatoskrColors.Text,
                    )
                }
            }

            if (totpEnabled) {
                if (!readOnly) {
                    RatatoskrTextField(
                        totpSecret,
                        { totpSecret = it },
                        "2FA secret key",
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        "On the site's \"enable 2FA\" screen, look for \"can't scan the QR code? enter this key manually\" -- " +
                            "paste that key here, not the 6-digit code itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RatatoskrColors.TextMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (Totp.isValidBase32(totpSecret)) {
                    LiveTotpDisplay(secret = totpSecret)
                }
            }

            RatatoskrTextField(
                notes, { notes = it }, "Notes",
                Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = false,
                enabled = !readOnly,
            )

            ErrorText(localError ?: appState.errorMessage, modifier = Modifier.fillMaxWidth())

            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                if (existing != null && existing.can_write) {
                    DangerButton(
                        "Delete",
                        onClick = {
                            scope.launch {
                                isSaving = true
                                if (appState.deleteEntry(existing.id)) onDismiss()
                                isSaving = false
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                GhostButton("Cancel", onClick = onDismiss)
                if (!readOnly) {
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton(
                        "Save",
                        enabled = site.isNotBlank() && !isSaving,
                        onClick = {
                            if (totpEnabled && totpSecret.isNotBlank() && !Totp.isValidBase32(totpSecret)) {
                                localError = "That 2FA secret doesn't look valid -- double check you copied the base32 key, not the 6-digit code."
                                return@PrimaryButton
                            }
                            localError = null
                            scope.launch {
                                isSaving = true
                                val body = EntryIn(
                                    site = site.trim(),
                                    username = username.trim(),
                                    password = password,
                                    url = url.trim(),
                                    notes = notes,
                                    totp_secret = if (totpEnabled) totpSecret.trim() else "",
                                )
                                if (appState.saveEntry(existing?.id, body)) onDismiss()
                                isSaving = false
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Live-updating 6-digit code + countdown ring, ticking every second --
 * the Compose equivalent of static/totp.js's tickTotpDisplay/setInterval,
 * using LaunchedEffect as the idiomatic Compose replacement for a raw
 * timer loop tied to this composable's lifecycle (it's cancelled
 * automatically when this dialog closes, no manual cleanup needed). */
@Composable
private fun LiveTotpDisplay(secret: String) {
    var code by remember(secret) { mutableStateOf(Totp.code(secret)) }
    var remaining by remember(secret) { mutableStateOf(Totp.secondsRemaining()) }

    LaunchedEffect(secret) {
        while (true) {
            code = Totp.code(secret)
            remaining = Totp.secondsRemaining()
            delay(1000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(RatatoskrColors.PanelAlt, RoundedCornerShape(4.dp))
            .border(1.dp, RatatoskrColors.Hairline, RoundedCornerShape(4.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val spacedCode = if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code
        Text(
            spacedCode,
            style = MaterialTheme.typography.titleLarge,
            color = RatatoskrColors.Verdigris,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        val fraction = remaining / 30f
        val ringColor = if (remaining <= 5) RatatoskrColors.DangerBright else RatatoskrColors.Brass
        Canvas(modifier = Modifier.size(22.dp)) {
            val strokeWidth = 3.dp.toPx()
            drawArc(
                color = RatatoskrColors.Hairline,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
    }
}
