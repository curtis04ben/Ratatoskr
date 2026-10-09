package com.ratatoskr.shared.totp

import kotlin.time.Clock

/**
 * Pure-Kotlin TOTP (RFC 6238), deliberately not using any platform crypto
 * API (javax.crypto on JVM, CryptoKit on iOS, etc.). This file lives in
 * commonMain and needs to produce byte-identical output on every target
 * without expect/actual platform wiring. This is the third independent
 * implementation of the same algorithm in this project: the server has
 * one in Python (app/crypto.py), the web UI has one in JavaScript
 * (static/totp.js), both checked against the official RFC 6238 Appendix B
 * test vectors and against each other. This one mirrors that same
 * structure line-for-line where the languages allow, specifically so it's
 * easy to audit against the other two rather than trusting a fresh
 * translation. See RatatoskrClientsTest.kt in commonTest for the same
 * vectors applied here. Run `./gradlew :shared:test` to check.
 */
object Totp {

    private fun sha1(input: ByteArray): ByteArray {
        val msgLen = input.size
        val withOnePadded = ((msgLen + 9 + 63) / 64) * 64
        val padded = ByteArray(withOnePadded)
        input.copyInto(padded)
        padded[msgLen] = 0x80.toByte()
        val bitLen = msgLen.toLong() * 8
        for (i in 0 until 8) {
            padded[withOnePadded - 1 - i] = ((bitLen ushr (8 * i)) and 0xFF).toByte()
        }

        var h0 = 0x67452301
        var h1 = -0x10325477 // 0xEFCDAB89
        var h2 = -0x67452302 // 0x98BADCFE
        var h3 = 0x10325476
        var h4 = -0x3c2d1e10 // 0xC3D2E1F0

        val w = IntArray(80)
        var chunk = 0
        while (chunk < padded.size) {
            for (i in 0 until 16) {
                val base = chunk + i * 4
                w[i] = ((padded[base].toInt() and 0xFF) shl 24) or
                    ((padded[base + 1].toInt() and 0xFF) shl 16) or
                    ((padded[base + 2].toInt() and 0xFF) shl 8) or
                    (padded[base + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) {
                val v = w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]
                w[i] = (v shl 1) or (v ushr 31)
            }

            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
            for (i in 0 until 80) {
                val f: Int
                val k: Int
                when {
                    i < 20 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                    i < 40 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                    i < 60 -> { f = (b and c) or (b and d) or (c and d); k = -0x70e44324 } // 0x8F1BBCDC
                    else -> { f = b xor c xor d; k = -0x359d3e2a } // 0xCA62C1D6
                }
                val temp = ((a shl 5) or (a ushr 27)) + f + e + k + w[i]
                e = d; d = c; c = (b shl 30) or (b ushr 2); b = a; a = temp
            }

            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
            chunk += 64
        }

        val out = ByteArray(20)
        val hs = intArrayOf(h0, h1, h2, h3, h4)
        for (i in 0 until 5) {
            out[i * 4] = (hs[i] ushr 24).toByte()
            out[i * 4 + 1] = (hs[i] ushr 16).toByte()
            out[i * 4 + 2] = (hs[i] ushr 8).toByte()
            out[i * 4 + 3] = hs[i].toByte()
        }
        return out
    }

    private fun hmacSha1(key: ByteArray, message: ByteArray): ByteArray {
        val blockSize = 64
        val k = if (key.size > blockSize) sha1(key) else key
        val paddedKey = ByteArray(blockSize)
        k.copyInto(paddedKey)

        val ipad = ByteArray(blockSize) { (paddedKey[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(blockSize) { (paddedKey[it].toInt() xor 0x5c).toByte() }

        val inner = sha1(ipad + message)
        return sha1(opad + inner)
    }

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun base32Decode(input: String): ByteArray {
        val clean = input.uppercase().filter { it in BASE32_ALPHABET }
        var bits = 0
        var value = 0
        val out = ArrayList<Byte>()
        for (char in clean) {
            val idx = BASE32_ALPHABET.indexOf(char)
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                out.add(((value ushr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    /**
     * Mirrors the server's `is_valid_totp_secret` (app/crypto.py), which is
     * what actually accepts or rejects the secret on save: spaces are
     * dropped, but any other non-base32 character is an error rather than
     * being silently skipped the way [base32Decode] does. Trailing `=`
     * padding is allowed, and the unpadded length must be one base32 can
     * produce (Python's b32decode rejects lengths of 1, 3 or 6 mod 8).
     */
    fun isValidBase32(input: String): Boolean {
        val clean = input.trim().uppercase().replace(" ", "").trimEnd('=')
        if (clean.isEmpty()) return false
        if (clean.any { it !in BASE32_ALPHABET }) return false
        if (clean.length % 8 in setOf(1, 3, 6)) return false
        return base32Decode(clean).isNotEmpty()
    }

    private fun counterBytes(counter: Long): ByteArray {
        val buf = ByteArray(8)
        for (i in 0 until 8) {
            buf[7 - i] = ((counter ushr (8 * i)) and 0xFF).toByte()
        }
        return buf
    }

    private fun hotp(secret: ByteArray, counter: Long, digits: Int = 6): String {
        val h = hmacSha1(secret, counterBytes(counter))
        val offset = h[h.size - 1].toInt() and 0x0F
        val codeInt = ((h[offset].toInt() and 0x7f) shl 24) or
            ((h[offset + 1].toInt() and 0xff) shl 16) or
            ((h[offset + 2].toInt() and 0xff) shl 8) or
            (h[offset + 3].toInt() and 0xff)
        var pow = 1
        repeat(digits) { pow *= 10 }
        val code = codeInt % pow
        return code.toString().padStart(digits, '0')
    }

    private fun nowSeconds(): Long = Clock.System.now().epochSeconds

    /** Current 6-digit code for a base32 secret. `atSeconds` defaults to
     * the real current time; only ever overridden in tests. */
    fun code(secretB32: String, atSeconds: Long = nowSeconds(), period: Int = 30, digits: Int = 6): String {
        val secretBytes = base32Decode(secretB32)
        val counter = atSeconds / period
        return hotp(secretBytes, counter, digits)
    }

    /** Seconds remaining in the current period, for a countdown display. */
    fun secondsRemaining(atSeconds: Long = nowSeconds(), period: Int = 30): Int {
        return period - (atSeconds % period).toInt()
    }
}
