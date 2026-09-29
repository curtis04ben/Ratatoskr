package com.ratatoskr.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ratatoskr.shared.api.EntryIn
import com.ratatoskr.shared.api.EntryOut
import com.ratatoskr.shared.api.ImportResult
import com.ratatoskr.shared.api.RatatoskrApiClient
import com.ratatoskr.shared.api.RatatoskrApiException
import com.ratatoskr.shared.api.RatatoskrConnectionException

/** Which top-level screen is showing. Mirrors the web UI's lock-screen
 * forms (setup/unlock/invite) and the main app screen -- see
 * static/index.html for the equivalent web markup this maps to. */
sealed class Screen {
    data object ServerConnect : Screen()
    data object CheckingServer : Screen()
    data object Setup : Screen()
    data object Unlock : Screen()
    data object AcceptInvite : Screen()
    data object Vault : Screen()
}

/**
 * Single state holder for the whole app, analogous to the web UI's `state`
 * object in app.js plus the screen-switching logic in boot(). Plain
 * Compose `mutableStateOf` properties rather than a platform-specific
 * ViewModel base class, deliberately -- this class lives in commonMain and
 * needs to work unmodified on desktop, Android, and iOS. A platform's own
 * ViewModel wrapper (e.g. androidx.lifecycle.ViewModel on Android) can
 * hold and scope an instance of this later without this class needing to
 * change.
 */
class AppState {
    var screen by mutableStateOf<Screen>(Screen.ServerConnect)
        private set

    var serverUrl by mutableStateOf("")
    var username by mutableStateOf("")
    var role by mutableStateOf("")
    var entries by mutableStateOf<List<EntryOut>>(emptyList())
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    /** Short-lived confirmation message -- the web UI's toast(). Shown
     * and auto-cleared by RatatoskrApp. */
    var notice by mutableStateOf<String?>(null)

    private var client: RatatoskrApiClient? = null

    private fun requireClient(): RatatoskrApiClient =
        client ?: throw IllegalStateException("Not connected to a server yet")

    /** Step 1: point the app at a server and see what it needs next --
     * mirrors the web UI's boot() calling /auth/status before deciding
     * whether to show setup or unlock. */
    suspend fun connectToServer(url: String) {
        errorMessage = null
        isLoading = true
        val trimmed = url.trim()
        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed" // most homelab deployments are plain HTTP over Tailscale, same assumption static/totp.js makes
        }
        try {
            val candidate = RatatoskrApiClient(normalized)
            val status = candidate.status()
            client = candidate
            serverUrl = normalized
            screen = if (status.initialized) Screen.Unlock else Screen.Setup
        } catch (e: RatatoskrConnectionException) {
            errorMessage = e.message
        } catch (e: RatatoskrApiException) {
            errorMessage = e.detail
        } finally {
            isLoading = false
        }
    }

    fun showAcceptInvite() {
        screen = Screen.AcceptInvite
    }

    fun showUnlock() {
        screen = Screen.Unlock
    }

    fun changeServer() {
        client?.close()
        client = null
        serverUrl = ""
        errorMessage = null
        screen = Screen.ServerConnect
    }

    suspend fun setup(username: String, masterPassword: String) {
        runAuthAction { requireClient().setup(username, masterPassword) }
    }

    suspend fun unlock(username: String, masterPassword: String) {
        runAuthAction { requireClient().unlock(username, masterPassword) }
    }

    suspend fun acceptInvite(username: String, inviteToken: String, masterPassword: String) {
        runAuthAction { requireClient().acceptInvite(username, inviteToken, masterPassword) }
    }

    private suspend fun runAuthAction(action: suspend () -> com.ratatoskr.shared.api.AuthResponse) {
        errorMessage = null
        isLoading = true
        try {
            val response = action()
            requireClient().token = response.token
            username = response.username
            role = response.role
            loadEntries()
            screen = Screen.Vault
        } catch (e: RatatoskrConnectionException) {
            errorMessage = e.message
        } catch (e: RatatoskrApiException) {
            errorMessage = e.detail
        } finally {
            isLoading = false
        }
    }

    suspend fun loadEntries() {
        try {
            entries = requireClient().listEntries()
        } catch (e: RatatoskrApiException) {
            errorMessage = e.detail
        } catch (e: RatatoskrConnectionException) {
            errorMessage = e.message
        }
    }

    suspend fun saveEntry(existingId: String?, entry: EntryIn): Boolean {
        errorMessage = null
        return try {
            if (existingId != null) requireClient().updateEntry(existingId, entry)
            else requireClient().createEntry(entry)
            loadEntries()
            true
        } catch (e: RatatoskrApiException) {
            errorMessage = e.detail
            false
        } catch (e: RatatoskrConnectionException) {
            errorMessage = e.message
            false
        }
    }

    suspend fun deleteEntry(id: String): Boolean {
        errorMessage = null
        return try {
            requireClient().deleteEntry(id)
            loadEntries()
            true
        } catch (e: RatatoskrApiException) {
            errorMessage = e.detail
            false
        } catch (e: RatatoskrConnectionException) {
            errorMessage = e.message
            false
        }
    }

    /** Fetches the unencrypted CSV export -- mirrors the web UI's
     * export-confirm handler. Returns null (with errorMessage set) on
     * failure. */
    suspend fun exportCsv(): String? {
        errorMessage = null
        return try {
            requireClient().exportCsv()
        } catch (e: RatatoskrApiException) {
            errorMessage = e.detail
            null
        } catch (e: RatatoskrConnectionException) {
            errorMessage = e.message
            null
        }
    }

    /** Uploads a CSV and refreshes the list, setting the same summary
     * notice the web UI toasts. Returns the server's result so the caller
     * can show per-row errors, or null on failure. */
    suspend fun importCsv(fileName: String, bytes: ByteArray): ImportResult? {
        errorMessage = null
        return try {
            val result = requireClient().importCsv(fileName, bytes)
            var msg = "Imported ${result.imported} entr${if (result.imported == 1) "y" else "ies"}"
            if (result.skipped > 0) msg += ", skipped ${result.skipped} blank row${if (result.skipped == 1) "" else "s"}"
            if (result.errors.isNotEmpty()) msg += ", ${result.errors.size} error(s)"
            notice = msg
            loadEntries()
            result
        } catch (e: RatatoskrApiException) {
            errorMessage = e.detail
            null
        } catch (e: RatatoskrConnectionException) {
            errorMessage = e.message
            null
        }
    }

    suspend fun lock() {
        try {
            client?.lock()
        } catch (_: Exception) {
            // best-effort, same as the web UI's lock button -- still clear
            // local state below even if the network call fails
        }
        requireClient().token = null
        entries = emptyList()
        username = ""
        role = ""
        screen = Screen.Unlock
    }

    fun api(): RatatoskrApiClient = requireClient()
}
