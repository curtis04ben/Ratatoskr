/*
 * Pure-JavaScript TOTP (RFC 6238), deliberately NOT using window.crypto.subtle.
 *
 * SubtleCrypto only works in "secure contexts" (HTTPS or localhost) --
 * it's simply undefined over plain HTTP on a LAN/Tailscale hostname, which
 * is exactly how this app is often deployed. Hand-rolling SHA-1/HMAC here
 * means 2FA codes work regardless of whether the deployment has a TLS
 * certificate in front of it.
 *
 * This implementation is verified against the official RFC 6238 Appendix B
 * test vectors and cross-checked against an independent Python
 * implementation (app/crypto.py) for 25+ randomized cases -- both produce
 * bit-identical output. See the project's development notes for details.
 */
(function (global) {
  "use strict";

  function sha1(bytes) {
    const msgLen = bytes.length;
    const withOne = new Uint8Array((msgLen + 9 + 63) & ~63);
    withOne.set(bytes);
    withOne[msgLen] = 0x80;
    const bitLenHi = Math.floor((msgLen * 8) / 0x100000000);
    const bitLenLo = (msgLen * 8) >>> 0;
    const dv = new DataView(withOne.buffer);
    dv.setUint32(withOne.length - 8, bitLenHi, false);
    dv.setUint32(withOne.length - 4, bitLenLo, false);

    let h0 = 0x67452301, h1 = 0xEFCDAB89, h2 = 0x98BADCFE, h3 = 0x10325476, h4 = 0xC3D2E1F0;
    const w = new Uint32Array(80);

    for (let chunk = 0; chunk < withOne.length; chunk += 64) {
      for (let i = 0; i < 16; i++) w[i] = dv.getUint32(chunk + i * 4, false);
      for (let i = 16; i < 80; i++) {
        const v = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16];
        w[i] = (v << 1) | (v >>> 31);
      }
      let a = h0, b = h1, c = h2, d = h3, e = h4;
      for (let i = 0; i < 80; i++) {
        let f, k;
        if (i < 20) { f = (b & c) | (~b & d); k = 0x5A827999; }
        else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
        else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
        else { f = b ^ c ^ d; k = 0xCA62C1D6; }
        const temp = (((a << 5) | (a >>> 27)) + f + e + k + w[i]) >>> 0;
        e = d; d = c; c = (b << 30) | (b >>> 2); b = a; a = temp;
      }
      h0 = (h0 + a) >>> 0; h1 = (h1 + b) >>> 0; h2 = (h2 + c) >>> 0;
      h3 = (h3 + d) >>> 0; h4 = (h4 + e) >>> 0;
    }

    const out = new Uint8Array(20);
    const outDv = new DataView(out.buffer);
    outDv.setUint32(0, h0, false); outDv.setUint32(4, h1, false);
    outDv.setUint32(8, h2, false); outDv.setUint32(12, h3, false);
    outDv.setUint32(16, h4, false);
    return out;
  }

  function concatBytes(a, b) {
    const out = new Uint8Array(a.length + b.length);
    out.set(a, 0); out.set(b, a.length);
    return out;
  }

  function hmacSha1(keyBytes, msgBytes) {
    const blockSize = 64;
    let key = keyBytes;
    if (key.length > blockSize) key = sha1(key);
    const paddedKey = new Uint8Array(blockSize);
    paddedKey.set(key);
    const ipad = new Uint8Array(blockSize);
    const opad = new Uint8Array(blockSize);
    for (let i = 0; i < blockSize; i++) {
      ipad[i] = paddedKey[i] ^ 0x36;
      opad[i] = paddedKey[i] ^ 0x5c;
    }
    const inner = sha1(concatBytes(ipad, msgBytes));
    return sha1(concatBytes(opad, inner));
  }

  function base32Decode(input) {
    const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    const clean = input.toUpperCase().replace(/[^A-Z2-7]/g, "");
    let bits = 0, value = 0;
    const bytes = [];
    for (const char of clean) {
      const idx = alphabet.indexOf(char);
      if (idx === -1) continue;
      value = (value << 5) | idx;
      bits += 5;
      if (bits >= 8) {
        bytes.push((value >>> (bits - 8)) & 0xFF);
        bits -= 8;
      }
    }
    return new Uint8Array(bytes);
  }

  function isValidBase32(input) {
    if (!input || !input.trim()) return false;
    return base32Decode(input).length > 0;
  }

  function counterBytes(counter) {
    const buf = new Uint8Array(8);
    const dv = new DataView(buf.buffer);
    const hi = Math.floor(counter / 0x100000000);
    const lo = counter >>> 0;
    dv.setUint32(0, hi, false);
    dv.setUint32(4, lo, false);
    return buf;
  }

  function hotp(secretBytes, counter, digits) {
    digits = digits || 6;
    const h = hmacSha1(secretBytes, counterBytes(counter));
    const offset = h[h.length - 1] & 0x0F;
    const codeInt = (
      ((h[offset] & 0x7f) << 24) |
      ((h[offset + 1] & 0xff) << 16) |
      ((h[offset + 2] & 0xff) << 8) |
      (h[offset + 3] & 0xff)
    );
    const code = codeInt % Math.pow(10, digits);
    return String(code).padStart(digits, "0");
  }

  function totp(secretB32, atSeconds, period, digits) {
    period = period || 30;
    const secretBytes = base32Decode(secretB32);
    const counter = Math.floor((atSeconds !== undefined ? atSeconds : Date.now() / 1000) / period);
    return hotp(secretBytes, counter, digits);
  }

  function secondsRemaining(atSeconds, period) {
    period = period || 30;
    const t = atSeconds !== undefined ? atSeconds : Date.now() / 1000;
    return period - Math.floor(t % period);
  }

  global.RatatoskrTOTP = { totp, secondsRemaining, isValidBase32 };
})(window);
