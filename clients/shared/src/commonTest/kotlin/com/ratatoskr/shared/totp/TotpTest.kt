package com.ratatoskr.shared.totp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Official RFC 6238 Appendix B test vectors (the SHA-1 row), plus the same
 * cross-checks already run against the server's Python implementation and
 * the web UI's JavaScript implementation during development. This file
 * was NOT executed by the assistant that wrote it, which had no network access to
 * fetch Gradle/Kotlin/Compose dependencies in that environment. The logic
 * itself WAS hand-verified (every magic constant checked arithmetically,
 * and the full algorithm transliterated into Python and run against these
 * same vectors), but that's not a substitute for actually running this
 * file. Run it with: ./gradlew :shared:test
 */
class TotpTest {

    // Base32 of the ASCII seed "12345678901234567890" used throughout
    // RFC 6238's Appendix B.
    private val rfcSeed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun rfc6238_vector_t59() {
        assertEquals("287082", Totp.code(rfcSeed, atSeconds = 59))
    }

    @Test
    fun rfc6238_vector_t1111111109() {
        assertEquals("081804", Totp.code(rfcSeed, atSeconds = 1111111109))
    }

    @Test
    fun rfc6238_vector_t1234567890() {
        assertEquals("005924", Totp.code(rfcSeed, atSeconds = 1234567890))
    }

    @Test
    fun rfc6238_vector_t2000000000() {
        assertEquals("279037", Totp.code(rfcSeed, atSeconds = 2000000000))
    }

    @Test
    fun code_is_stable_within_the_same_30_second_window() {
        val a = Totp.code(rfcSeed, atSeconds = 1000000000)
        val b = Totp.code(rfcSeed, atSeconds = 1000000005)
        assertEquals(a, b)
    }

    @Test
    fun code_changes_after_a_full_period() {
        val a = Totp.code(rfcSeed, atSeconds = 1000000000)
        val b = Totp.code(rfcSeed, atSeconds = 1000000030)
        assertTrue(a != b)
    }

    @Test
    fun seconds_remaining_is_within_the_period() {
        val remaining = Totp.secondsRemaining(atSeconds = 1000000005, period = 30)
        assertTrue(remaining in 1..30)
    }

    @Test
    fun valid_base32_is_accepted() {
        assertTrue(Totp.isValidBase32(rfcSeed))
        assertTrue(Totp.isValidBase32("jbswy3dpehpk3pxp")) // lowercase, unpadded, as users will paste it
    }

    @Test
    fun invalid_base32_is_rejected() {
        assertFalse(Totp.isValidBase32(""))
        assertFalse(Totp.isValidBase32("   "))
        assertFalse(Totp.isValidBase32("not valid base32!!!"))
    }

    // Verdicts taken from running the server's is_valid_totp_secret
    // (app/crypto.py) on the same inputs. The server is what accepts or
    // rejects a secret on save, so the client must agree with it.
    @Test
    fun base32_validation_matches_server() {
        val serverVerdicts = mapOf(
            "JBSW Y3DP EHPK 3PXP" to true,
            " JBSWY3DPEHPK3PXP\t" to true,
            "MY======" to true,
            "MY" to true,
            "M" to false,
            "MYA" to false,
        )
        for ((input, expected) in serverVerdicts) {
            assertEquals(expected, Totp.isValidBase32(input), "isValidBase32(\"$input\")")
        }
    }
}
