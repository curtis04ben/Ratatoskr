package com.ratatoskr.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ratatoskr.shared.platform.SavedSession
import com.ratatoskr.shared.platform.SessionStore
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SessionStore backed by the Android Keystore. The session token is
 * encrypted with AES-256-GCM under a key generated inside the Keystore
 * (hardware-backed where the device supports it) that can't be exported,
 * then kept in private SharedPreferences. The server address is stored as
 * plain text -- it isn't secret.
 *
 * Deliberately not androidx.security's EncryptedSharedPreferences: that
 * library is deprecated, and this is the same scheme without the extra
 * dependency.
 *
 * If the key is gone or the ciphertext won't decrypt (app data restored
 * from elsewhere, Keystore reset), load() just returns no token and the
 * user unlocks again.
 */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    override fun load(): SavedSession? {
        val serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null)?.let(::decryptOrNull)
        return SavedSession(serverUrl, token)
    }

    override fun save(session: SavedSession) {
        prefs.edit().apply {
            putString(KEY_SERVER_URL, session.serverUrl)
            val token = session.token
            if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, encrypt(token))
        }.apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Base64 of IV (12 bytes, chosen by the Keystore) + ciphertext/tag. */
    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decryptOrNull(stored: String): String? = try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
        cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH).decodeToString()
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ratatoskr-session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TOKEN = "token"
    }
}
