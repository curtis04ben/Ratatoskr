package com.ratatoskr.shared.api

import com.ratatoskr.shared.totp.Totp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * End-to-end check of RatatoskrApiClient against a real running server,
 * covering every call the MVP UI makes. Skipped unless
 * RATATOSKR_TEST_SERVER is set, since it needs a live, throwaway instance:
 *
 *   RATATOSKR_TEST_SERVER=http://127.0.0.1:8765 ./gradlew :shared:jvmTest
 *
 * On an uninitialized server it runs setup as `e2e-admin`; on an
 * initialized one it unlocks with RATATOSKR_TEST_USER/RATATOSKR_TEST_PASSWORD.
 * Never point this at a real vault. It creates and deletes entries.
 */
class LiveServerTest {
    private val serverUrl = System.getenv("RATATOSKR_TEST_SERVER")

    @Test
    fun full_mvp_flow() = runBlocking {
        if (serverUrl.isNullOrBlank()) return@runBlocking
        val api = RatatoskrApiClient(serverUrl)
        try {
            val auth = if (!api.status().initialized) {
                api.setup("e2e-admin", "correct horse battery")
            } else {
                api.unlock(System.getenv("RATATOSKR_TEST_USER") ?: "e2e-admin",
                    System.getenv("RATATOSKR_TEST_PASSWORD") ?: "correct horse battery")
            }
            api.token = auth.token
            assertEquals(auth.username, api.me().username)

            val generated = api.generatePassword(GenerateRequest(length = 32))
            assertEquals(32, generated.password.length)

            val rfcSeed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
            val created = api.createEntry(
                EntryIn(site = "e2e-site", username = "bob", password = generated.password, totp_secret = rfcSeed)
            )
            assertTrue(api.listEntries().any { it.id == created.id && it.password == generated.password })

            // Server (Python) and client (Kotlin) TOTP must agree. Accept the
            // previous window too, in case the request straddled a boundary.
            val serverCode = api.getTotpCode(created.id).code
            val now = Clock.System.now().epochSeconds
            assertTrue(serverCode in setOf(Totp.code(rfcSeed, now), Totp.code(rfcSeed, now - 30)),
                "server TOTP $serverCode disagrees with client Totp.code")

            val updated = api.updateEntry(created.id, EntryIn(site = "e2e-site-renamed", notes = "edited"))
            assertEquals("e2e-site-renamed", updated.site)
            assertEquals("", updated.totp_secret)

            api.deleteEntry(created.id)
            assertTrue(api.listEntries().none { it.id == created.id })

            // CSV import (multipart upload) and export round-trip.
            val csv = "site,username,password,totp_secret\n" +
                "e2e-import-a,alice,pw-a,\n" +
                ",,,\n" +
                "e2e-import-b,bob,pw-b,not base32!\n"
            val imported = api.importCsv("e2e.csv", csv.encodeToByteArray())
            assertEquals(2, imported.imported)
            assertEquals(1, imported.skipped)
            assertEquals(1, imported.errors.size, imported.errors.toString())
            val exported = api.exportCsv()
            assertTrue("e2e-import-a,alice,pw-a" in exported, exported)
            assertTrue("e2e-import-b,bob,pw-b" in exported, exported)
            api.listEntries().filter { it.site.startsWith("e2e-import-") }.forEach { api.deleteEntry(it.id) }

            // FastAPI request-validation errors arrive as a list, not a string.
            val tooShort = assertFailsWith<RatatoskrApiException> {
                api.acceptInvite("someone", "bogus-token", "short")
            }
            assertEquals(422, tooShort.statusCode)
            assertTrue("master_password" in tooShort.detail, tooShort.detail)

            api.lock()
            val expired = assertFailsWith<RatatoskrApiException> { api.listEntries() }
            assertEquals(401, expired.statusCode)
            assertEquals("Session expired or invalid, unlock again", expired.detail)
        } finally {
            api.close()
        }

        assertFailsWith<RatatoskrConnectionException> {
            RatatoskrApiClient("http://127.0.0.1:1").status()
        }
        Unit
    }
}
