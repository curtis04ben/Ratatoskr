package com.ratatoskr.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ratatoskr.shared.state.AppState
import kotlinx.coroutines.launch

/** Matches the server's ChangePasswordRequest.new_password minimum. */
private const val MIN_MASTER_PASSWORD_LENGTH = 8

/** Change the signed-in account's master password -- mirrors the web UI's
 * #password-modal. The server re-wraps the account's private key under the
 * new password (entries and shares are untouched), signs out every other
 * session for the account, and hands this one a fresh token, which
 * AppState.changePassword() adopts. */
@Composable
fun ChangePasswordDialog(appState: AppState, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        error = when {
            newPassword.length < MIN_MASTER_PASSWORD_LENGTH ->
                "The new password must be at least $MIN_MASTER_PASSWORD_LENGTH characters."
            newPassword != confirm -> "The new passwords don't match."
            newPassword == current -> "The new password is the same as the current one."
            else -> null
        }
        if (error != null) return
        isSaving = true
        scope.launch {
            error = appState.changePassword(current, newPassword)
            isSaving = false
            if (error == null) onDismiss()
        }
    }

    Dialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .background(RatatoskrColors.Panel, RoundedCornerShape(8.dp))
                .border(1.dp, RatatoskrColors.Hairline, RoundedCornerShape(8.dp))
                .padding(24.dp),
        ) {
            Text("Change master password", style = MaterialTheme.typography.titleMedium, color = RatatoskrColors.Text)
            Text(
                "Your entries and shares stay as they are. Changing it signs you out on your other " +
                    "devices, and there's still no reset link, so keep the new one somewhere safe.",
                style = MaterialTheme.typography.bodySmall,
                color = RatatoskrColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(12.dp))

            RatatoskrTextField(current, { current = it }, "Current master password", Modifier.fillMaxWidth(), isPassword = true, enabled = !isSaving)
            RatatoskrTextField(newPassword, { newPassword = it }, "New master password", Modifier.fillMaxWidth().padding(top = 8.dp), isPassword = true, enabled = !isSaving)
            RatatoskrTextField(confirm, { confirm = it }, "Confirm new master password", Modifier.fillMaxWidth().padding(top = 8.dp), isPassword = true, enabled = !isSaving)

            ErrorText(error, modifier = Modifier.fillMaxWidth())

            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                if (isSaving) {
                    // Two Argon2id derivations on the server: a few seconds is normal.
                    CircularProgressIndicator(color = RatatoskrColors.Brass, modifier = Modifier.padding(8.dp))
                } else {
                    GhostButton("Cancel", onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton(
                        "Change password",
                        onClick = ::submit,
                        enabled = current.isNotEmpty() && newPassword.isNotEmpty() && confirm.isNotEmpty(),
                    )
                }
            }
        }
    }
}
