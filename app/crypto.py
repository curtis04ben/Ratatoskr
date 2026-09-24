"""
Encryption model (multi-user)
------------------------------
Every user has an X25519 identity keypair, generated once when their
account is created:

    - `public_key`  is stored in plain in the database (public keys are
      not secret; anyone can encrypt *to* a user with it).
    - `private_key` is encrypted ("wrapped") with a key derived from that
      user's master password via Argon2id, and stored wrapped. The master
      password itself is never stored.

Unlocking derives the Argon2id key, unwraps the private key, and holds it
in server memory for the session -- exactly as the single-user version did
for its one vault key, just per-user now.

Sharing an entry between users doesn't require both people to be online at
once, because "sealing" is asymmetric (ECIES-style, built from X25519 +
HKDF + AES-GCM): anyone who knows a recipient's *public* key can encrypt a
value that only that recipient's *private* key can open. Concretely, each
entry has its own random `entry_key`; a copy of `entry_key` is sealed to
every grantee's public key and stored alongside the entry. Revoking access
just means deleting that grantee's sealed copy -- the entry's own
ciphertext never needs to be touched or re-encrypted.

Changing your master password only re-wraps your private key under a new
Argon2id key. Your X25519 keypair itself doesn't change, so every seal
anyone has ever made to your public key stays valid -- nothing needs to be
re-shared.
"""
import base64
import hashlib
import hmac
import os
import secrets
import time

from argon2.low_level import Type, hash_secret_raw
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat, PublicFormat
from cryptography.hazmat.primitives.hashes import SHA256

from app.config import ARGON2_MEMORY_COST_KIB, ARGON2_PARALLELISM, ARGON2_TIME_COST

KEY_LEN = 32  # AES-256 / X25519 key size
SALT_LEN = 16
NONCE_LEN = 12
PUBKEY_LEN = 32


class WrongPassword(Exception):
    """Raised when a ciphertext fails to authenticate under the given key."""


def new_salt() -> bytes:
    return os.urandom(SALT_LEN)


def derive_kek(master_password: str, salt: bytes) -> bytes:
    """Argon2id-derive a 32-byte key-encryption-key from a master password."""
    return hash_secret_raw(
        secret=master_password.encode("utf-8"),
        salt=salt,
        time_cost=ARGON2_TIME_COST,
        memory_cost=ARGON2_MEMORY_COST_KIB,
        parallelism=ARGON2_PARALLELISM,
        hash_len=KEY_LEN,
        type=Type.ID,
    )


def _aead_encrypt(key: bytes, plaintext: bytes, aad: bytes = b"") -> bytes:
    nonce = os.urandom(NONCE_LEN)
    ct = AESGCM(key).encrypt(nonce, plaintext, aad)
    return nonce + ct


def _aead_decrypt(key: bytes, blob: bytes, aad: bytes = b"") -> bytes:
    nonce, ct = blob[:NONCE_LEN], blob[NONCE_LEN:]
    try:
        return AESGCM(key).decrypt(nonce, ct, aad)
    except InvalidTag as exc:
        raise WrongPassword from exc


# ---------- per-user identity keypair ----------

def generate_identity_keypair() -> tuple[bytes, bytes]:
    """Returns (private_key_bytes, public_key_bytes), both 32 raw bytes."""
    priv = X25519PrivateKey.generate()
    priv_bytes = priv.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
    pub_bytes = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return priv_bytes, pub_bytes


def wrap_private_key(private_key: bytes, kek: bytes) -> bytes:
    return _aead_encrypt(kek, private_key, aad=b"identity-key")


def unwrap_private_key(wrapped: bytes, kek: bytes) -> bytes:
    return _aead_decrypt(kek, wrapped, aad=b"identity-key")


# ---------- ECIES-style sealing (asymmetric, anonymous sender) ----------

def _hkdf_key(shared_secret: bytes) -> bytes:
    return HKDF(algorithm=SHA256(), length=KEY_LEN, salt=None, info=b"ratatoskr-seal-v1").derive(shared_secret)


def seal(plaintext: bytes, recipient_public_key: bytes) -> bytes:
    """Encrypt `plaintext` so only the holder of the matching private key
    can decrypt it. The sender needs no key of their own -- an ephemeral
    keypair is generated per call. Output layout: ephemeral_pubkey(32) ||
    nonce(12) || ciphertext."""
    eph_priv = X25519PrivateKey.generate()
    eph_pub_bytes = eph_priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    recipient_pub = X25519PublicKey.from_public_bytes(recipient_public_key)
    shared = eph_priv.exchange(recipient_pub)
    key = _hkdf_key(shared)
    nonce = os.urandom(NONCE_LEN)
    ct = AESGCM(key).encrypt(nonce, plaintext, b"sealed-key-v1")
    return eph_pub_bytes + nonce + ct


def unseal(sealed: bytes, recipient_private_key: bytes) -> bytes:
    eph_pub_bytes = sealed[:PUBKEY_LEN]
    nonce = sealed[PUBKEY_LEN:PUBKEY_LEN + NONCE_LEN]
    ct = sealed[PUBKEY_LEN + NONCE_LEN:]
    recipient_priv = X25519PrivateKey.from_private_bytes(recipient_private_key)
    eph_pub = X25519PublicKey.from_public_bytes(eph_pub_bytes)
    shared = recipient_priv.exchange(eph_pub)
    key = _hkdf_key(shared)
    try:
        return AESGCM(key).decrypt(nonce, ct, b"sealed-key-v1")
    except InvalidTag as exc:
        raise WrongPassword from exc


# ---------- entry content encryption ----------

def new_entry_key() -> bytes:
    return os.urandom(KEY_LEN)


def encrypt_entry(plaintext_json: bytes, entry_key: bytes) -> bytes:
    return _aead_encrypt(entry_key, plaintext_json, aad=b"entry")


def decrypt_entry(blob: bytes, entry_key: bytes) -> bytes:
    return _aead_decrypt(entry_key, blob, aad=b"entry")


# ---------- TOTP (RFC 6238), for optional per-entry 2FA codes ----------
#
# Verified against the official RFC 6238 Appendix B test vectors, and
# against an independently hand-written JavaScript implementation used by
# the web UI (both produce identical output for the same inputs -- see the
# project's test notes). Storing a totp_secret is entirely optional per
# entry, since not every login needs 2FA.

def _hotp(secret_bytes: bytes, counter: int, digits: int = 6) -> str:
    counter_bytes = counter.to_bytes(8, "big")
    h = hmac.new(secret_bytes, counter_bytes, hashlib.sha1).digest()
    offset = h[-1] & 0x0F
    code_int = int.from_bytes(h[offset:offset + 4], "big") & 0x7FFFFFFF
    return str(code_int % (10 ** digits)).zfill(digits)


def _normalize_base32(secret_b32: str) -> str:
    clean = secret_b32.strip().upper().replace(" ", "")
    return clean + "=" * ((8 - len(clean) % 8) % 8)


def is_valid_totp_secret(secret_b32: str) -> bool:
    if not secret_b32.strip():
        return False
    try:
        decoded = base64.b32decode(_normalize_base32(secret_b32))
        return len(decoded) > 0
    except Exception:
        return False


def totp_now(secret_b32: str, period: int = 30, digits: int = 6, at: float | None = None) -> tuple[str, int]:
    """Returns (current_code, seconds_remaining_in_this_period)."""
    secret_bytes = base64.b32decode(_normalize_base32(secret_b32))
    t = at if at is not None else time.time()
    counter = int(t // period)
    code = _hotp(secret_bytes, counter, digits)
    remaining = period - int(t % period)
    return code, remaining


# ---------- misc ----------

def new_invite_token() -> str:
    return secrets.token_urlsafe(24)


def generate_password(
    length: int = 20,
    use_upper: bool = True,
    use_lower: bool = True,
    use_digits: bool = True,
    use_symbols: bool = True,
    avoid_ambiguous: bool = True,
) -> str:
    upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    lower = "abcdefghijklmnopqrstuvwxyz"
    digits = "0123456789"
    symbols = "!@#$%^&*()-_=+[]{};:,.<>?"
    if avoid_ambiguous:
        for amb in "Il1O0":
            upper = upper.replace(amb, "")
            lower = lower.replace(amb, "")
            digits = digits.replace(amb, "")

    classes = []
    if use_upper:
        classes.append(upper)
    if use_lower:
        classes.append(lower)
    if use_digits:
        classes.append(digits)
    if use_symbols:
        classes.append(symbols)
    if not classes:
        raise ValueError("At least one character class must be selected")

    pool = "".join(classes)
    length = max(length, len(classes))

    required = [secrets.choice(c) for c in classes]
    rest = [secrets.choice(pool) for _ in range(length - len(required))]
    chars = required + rest
    for i in range(len(chars) - 1, 0, -1):
        j = secrets.randbelow(i + 1)
        chars[i], chars[j] = chars[j], chars[i]
    return "".join(chars)
