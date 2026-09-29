package com.ratatoskr.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.ratatoskr.shared.state.AppState
import kotlinx.coroutines.launch

/** Shared shell for every auth-related screen: centered narrow column,
 * headline, optional lede text, then the form-specific content. Mirrors
 * .lock-card / .lock-form in style.css. */
@Composable
private fun AuthScreenShell(
    title: String,
    lede: String? = null,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(360.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = RatatoskrColors.Text)
            if (lede != null) {
                Text(
                    lede,
                    style = MaterialTheme.typography.bodySmall,
                    color = RatatoskrColors.TextMuted,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
            }
            content()
        }
    }
}

@Composable
fun SetupScreen(appState: AppState) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AuthScreenShell(
        title = "Create the admin account",
        lede = "No vault found on this server yet. The first account you create becomes the Admin. " +
            "Choose a master password -- it is never stored, and there is no reset link, so keep it somewhere safe.",
    ) {
        RatatoskrTextField(username, { username = it }, "Username", Modifier.fillMaxWidth())
        RatatoskrTextField(password, { password = it }, "Master password", Modifier.fillMaxWidth().padding(top = 10.dp), isPassword = true)
        RatatoskrTextField(confirm, { confirm = it }, "Confirm master password", Modifier.fillMaxWidth().padding(top = 10.dp), isPassword = true)

        if (appState.isLoading) {
            CircularProgressIndicator(color = RatatoskrColors.Brass, modifier = Modifier.padding(16.dp))
        } else {
            PrimaryButton(
                "Create admin account",
                onClick = {
                    if (password != confirm) {
                        localError = "Passwords don't match."
                    } else {
                        localError = null
                        scope.launch { appState.setup(username, password) }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                enabled = username.isNotBlank() && password.isNotBlank(),
            )
        }
        ErrorText(localError ?: appState.errorMessage, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun UnlockScreen(appState: AppState) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AuthScreenShell(title = "Ratatoskr") {
        RatatoskrTextField(username, { username = it }, "Username", Modifier.fillMaxWidth())
        RatatoskrTextField(password, { password = it }, "Master password", Modifier.fillMaxWidth().padding(top = 10.dp), isPassword = true)

        if (appState.isLoading) {
            CircularProgressIndicator(color = RatatoskrColors.Brass, modifier = Modifier.padding(16.dp))
        } else {
            PrimaryButton(
                "Unlock",
                onClick = { scope.launch { appState.unlock(username, password) } },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                enabled = username.isNotBlank() && password.isNotBlank(),
            )
        }
        ErrorText(appState.errorMessage, modifier = Modifier.fillMaxWidth())

        LinkButton("Have an invite code?", onClick = { appState.showAcceptInvite() }, modifier = Modifier.padding(top = 8.dp))
        LinkButton("Change server", onClick = { appState.changeServer() })
    }
}

@Composable
fun AcceptInviteScreen(appState: AppState) {
    var username by remember { mutableStateOf("") }
    var inviteToken by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AuthScreenShell(
        title = "Join with an invite",
        lede = "An admin gave you an invite code and a username. Choose your own master password now -- the admin never sees it.",
    ) {
        RatatoskrTextField(username, { username = it }, "Username", Modifier.fillMaxWidth())
        RatatoskrTextField(inviteToken, { inviteToken = it }, "Invite code", Modifier.fillMaxWidth().padding(top = 10.dp))
        RatatoskrTextField(password, { password = it }, "Choose a master password", Modifier.fillMaxWidth().padding(top = 10.dp), isPassword = true)

        if (appState.isLoading) {
            CircularProgressIndicator(color = RatatoskrColors.Brass, modifier = Modifier.padding(16.dp))
        } else {
            PrimaryButton(
                "Create my account",
                onClick = { scope.launch { appState.acceptInvite(username, inviteToken, password) } },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                enabled = username.isNotBlank() && inviteToken.isNotBlank() && password.isNotBlank(),
            )
        }
        ErrorText(appState.errorMessage, modifier = Modifier.fillMaxWidth())

        LinkButton("Back to unlock", onClick = { appState.showUnlock() }, modifier = Modifier.padding(top = 8.dp))
    }
}
