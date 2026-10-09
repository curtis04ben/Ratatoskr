package com.ratatoskr.desktop

import com.ratatoskr.shared.platform.SavedSession
import com.ratatoskr.shared.platform.SessionStore
import java.io.File
import java.util.Properties

/**
 * Remembers the last server between launches, so the app opens on Unlock
 * for that server instead of asking for its address every time.
 *
 * Only the server address is written, never the session token. Keeping
 * the token across launches needs an encrypted store (libsecret, the
 * system keyring, on Linux; Credential Manager on Windows; Keychain on
 * macOS), and a plain-text token on disk would be a real downgrade. So the
 * master password is still asked for on each launch, which is also what
 * the web UI does.
 *
 * Stored as a one-line properties file in the platform's per-user config
 * directory; see [configDir].
 */
class DesktopSessionStore(private val file: File = File(configDir(), "settings.properties")) : SessionStore {

    override fun load(): SavedSession? = try {
        val props = Properties().apply { file.reader().use { load(it) } }
        props.getProperty(KEY_SERVER_URL)?.takeIf { it.isNotBlank() }?.let { SavedSession(it, token = null) }
    } catch (_: Exception) {
        null // missing or unreadable: just start at the server screen
    }

    override fun save(session: SavedSession) {
        try {
            file.parentFile.mkdirs()
            val props = Properties().apply { setProperty(KEY_SERVER_URL, session.serverUrl) }
            file.writer().use { props.store(it, "Ratatoskr desktop settings") }
        } catch (_: Exception) {
            // Not remembering the server is an inconvenience, not an error
            // worth interrupting the user for.
        }
    }

    override fun clear() {
        file.delete()
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
    }
}

/** Per-user config directory: $XDG_CONFIG_HOME (or ~/.config) on Linux,
 * %APPDATA% on Windows, ~/Library/Application Support on macOS. */
private fun configDir(): File {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.startsWith("windows") ->
            File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "Ratatoskr")
        os.startsWith("mac") -> File(home, "Library/Application Support/Ratatoskr")
        else -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "ratatoskr")
    }
}
