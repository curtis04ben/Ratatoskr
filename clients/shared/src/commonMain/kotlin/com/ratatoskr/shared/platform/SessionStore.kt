package com.ratatoskr.shared.platform

/** What a platform remembers between launches: the server last connected
 * to and, while unlocked, the session token for it. */
class SavedSession(val serverUrl: String, val token: String?)

/**
 * Persists the current server and session so the app picks up where it
 * left off. Android needs this most: the OS routinely kills backgrounded
 * apps, and without it every return to the app would mean re-entering the
 * server address and master password. The server's idle timeout still
 * applies. A restored token that has expired just lands on Unlock.
 *
 * Implementations must store the token encrypted (Android: a Keystore
 * key) or not at all. The desktop store keeps only the server address
 * until it has the system keyring to put the token in. The master
 * password is never passed here. Supplied by each platform's entry point,
 * like PlatformFiles; without one, nothing persists.
 */
interface SessionStore {
    fun load(): SavedSession?
    fun save(session: SavedSession)
    fun clear()
}
