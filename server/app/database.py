import hashlib
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager

from app.config import DB_PATH

_local = threading.local()


def _connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.execute("PRAGMA foreign_keys = ON")
    conn.row_factory = sqlite3.Row
    return conn


@contextmanager
def get_conn():
    if not hasattr(_local, "conn"):
        _local.conn = _connect()
    conn = _local.conn
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise


def init_db() -> None:
    with get_conn() as conn:
        conn.execute(
            """CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY,
                value BLOB NOT NULL
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS users (
                id TEXT PRIMARY KEY,
                username TEXT UNIQUE NOT NULL,
                role TEXT NOT NULL CHECK(role IN ('admin','user','visitor')),
                salt BLOB NOT NULL,
                wrapped_private_key BLOB NOT NULL,
                public_key BLOB NOT NULL,
                created_at REAL NOT NULL
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS entries (
                id TEXT PRIMARY KEY,
                owner_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                ciphertext BLOB NOT NULL,
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS entry_grants (
                entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                sealed_key BLOB NOT NULL,
                can_write INTEGER NOT NULL DEFAULT 0,
                granted_by TEXT,
                granted_at REAL NOT NULL,
                PRIMARY KEY (entry_id, user_id)
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS entry_admin_seal (
                entry_id TEXT PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
                sealed_key BLOB NOT NULL
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS admin_recovery_grants (
                user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                sealed_key BLOB NOT NULL
            )"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS invites (
                id TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                role TEXT NOT NULL,
                token_hash BLOB NOT NULL,
                created_at REAL NOT NULL,
                expires_at REAL NOT NULL,
                consumed INTEGER NOT NULL DEFAULT 0
            )"""
        )


def _hash_token(token: str) -> bytes:
    return hashlib.sha256(token.encode()).digest()


# ---------- meta ----------

def get_meta(key: str) -> bytes | None:
    with get_conn() as conn:
        row = conn.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
        return row["value"] if row else None


def set_meta(key: str, value: bytes) -> None:
    with get_conn() as conn:
        conn.execute(
            "INSERT INTO meta(key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, value),
        )


def any_users_exist() -> bool:
    with get_conn() as conn:
        return conn.execute("SELECT 1 FROM users LIMIT 1").fetchone() is not None


# ---------- users ----------

def create_user(username: str, role: str, salt: bytes, wrapped_private_key: bytes, public_key: bytes) -> str:
    user_id = str(uuid.uuid4())
    with get_conn() as conn:
        conn.execute(
            "INSERT INTO users(id, username, role, salt, wrapped_private_key, public_key, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (user_id, username, role, salt, wrapped_private_key, public_key, time.time()),
        )
    return user_id


def get_user_by_username(username: str) -> sqlite3.Row | None:
    with get_conn() as conn:
        return conn.execute(
            "SELECT * FROM users WHERE lower(username) = lower(?)", (username,)
        ).fetchone()


def get_user(user_id: str) -> sqlite3.Row | None:
    with get_conn() as conn:
        return conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()


def list_users(role: str | None = None) -> list[sqlite3.Row]:
    with get_conn() as conn:
        if role:
            return conn.execute(
                "SELECT * FROM users WHERE role = ? ORDER BY created_at", (role,)
            ).fetchall()
        return conn.execute("SELECT * FROM users ORDER BY created_at").fetchall()


def count_admins(exclude_user_id: str | None = None) -> int:
    with get_conn() as conn:
        if exclude_user_id:
            row = conn.execute(
                "SELECT COUNT(*) c FROM users WHERE role = 'admin' AND id != ?", (exclude_user_id,)
            ).fetchone()
        else:
            row = conn.execute("SELECT COUNT(*) c FROM users WHERE role = 'admin'").fetchone()
        return row["c"]


def update_user_role(user_id: str, role: str) -> None:
    with get_conn() as conn:
        conn.execute("UPDATE users SET role = ? WHERE id = ?", (role, user_id))


def update_user_password(user_id: str, salt: bytes, wrapped_private_key: bytes) -> None:
    with get_conn() as conn:
        conn.execute(
            "UPDATE users SET salt = ?, wrapped_private_key = ? WHERE id = ?",
            (salt, wrapped_private_key, user_id),
        )


def delete_user(user_id: str) -> bool:
    with get_conn() as conn:
        cur = conn.execute("DELETE FROM users WHERE id = ?", (user_id,))
        return cur.rowcount > 0


# ---------- invites ----------

def create_invite(username: str, role: str, token: str) -> None:
    from app.config import INVITE_EXPIRY_SECONDS

    now = time.time()
    with get_conn() as conn:
        conn.execute(
            "INSERT INTO invites(id, username, role, token_hash, created_at, expires_at, consumed) "
            "VALUES (?, ?, ?, ?, ?, ?, 0)",
            (str(uuid.uuid4()), username, role, _hash_token(token), now, now + INVITE_EXPIRY_SECONDS),
        )


def get_valid_invite(username: str, token: str) -> sqlite3.Row | None:
    with get_conn() as conn:
        return conn.execute(
            "SELECT * FROM invites WHERE lower(username) = lower(?) AND token_hash = ? "
            "AND consumed = 0 AND expires_at > ?",
            (username, _hash_token(token), time.time()),
        ).fetchone()


def consume_invite(invite_id: str) -> None:
    with get_conn() as conn:
        conn.execute("UPDATE invites SET consumed = 1 WHERE id = ?", (invite_id,))


# ---------- admin recovery ----------

def set_admin_recovery_grant(user_id: str, sealed_key: bytes) -> None:
    with get_conn() as conn:
        conn.execute(
            "INSERT INTO admin_recovery_grants(user_id, sealed_key) VALUES (?, ?) "
            "ON CONFLICT(user_id) DO UPDATE SET sealed_key = excluded.sealed_key",
            (user_id, sealed_key),
        )


def get_admin_recovery_grant(user_id: str) -> bytes | None:
    with get_conn() as conn:
        row = conn.execute(
            "SELECT sealed_key FROM admin_recovery_grants WHERE user_id = ?", (user_id,)
        ).fetchone()
        return row["sealed_key"] if row else None


# ---------- entries ----------

def create_entry(owner_id: str, ciphertext: bytes) -> str:
    entry_id = str(uuid.uuid4())
    now = time.time()
    with get_conn() as conn:
        conn.execute(
            "INSERT INTO entries(id, owner_id, ciphertext, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            (entry_id, owner_id, ciphertext, now, now),
        )
    return entry_id


def get_entry(entry_id: str) -> sqlite3.Row | None:
    with get_conn() as conn:
        return conn.execute("SELECT * FROM entries WHERE id = ?", (entry_id,)).fetchone()


def update_entry_ciphertext(entry_id: str, ciphertext: bytes) -> None:
    with get_conn() as conn:
        conn.execute(
            "UPDATE entries SET ciphertext = ?, updated_at = ? WHERE id = ?",
            (ciphertext, time.time(), entry_id),
        )


def delete_entry(entry_id: str) -> bool:
    with get_conn() as conn:
        cur = conn.execute("DELETE FROM entries WHERE id = ?", (entry_id,))
        return cur.rowcount > 0


def list_all_entries_with_owner() -> list[sqlite3.Row]:
    """Admin view: every entry, plus the owning username."""
    with get_conn() as conn:
        return conn.execute(
            "SELECT e.*, u.username AS owner_username FROM entries e "
            "JOIN users u ON u.id = e.owner_id ORDER BY e.updated_at DESC"
        ).fetchall()


def list_entries_for_user(user_id: str) -> list[sqlite3.Row]:
    """Entries this user has an explicit grant on (their own + shared-to-them),
    plus the owning username and their grant's write permission."""
    with get_conn() as conn:
        return conn.execute(
            "SELECT e.*, u.username AS owner_username, g.sealed_key, g.can_write "
            "FROM entry_grants g "
            "JOIN entries e ON e.id = g.entry_id "
            "JOIN users u ON u.id = e.owner_id "
            "WHERE g.user_id = ? ORDER BY e.updated_at DESC",
            (user_id,),
        ).fetchall()


# ---------- grants ----------

def create_grant(entry_id: str, user_id: str, sealed_key: bytes, can_write: bool, granted_by: str) -> None:
    with get_conn() as conn:
        conn.execute(
            "INSERT INTO entry_grants(entry_id, user_id, sealed_key, can_write, granted_by, granted_at) "
            "VALUES (?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(entry_id, user_id) DO UPDATE SET sealed_key = excluded.sealed_key, "
            "can_write = excluded.can_write, granted_by = excluded.granted_by, granted_at = excluded.granted_at",
            (entry_id, user_id, sealed_key, int(can_write), granted_by, time.time()),
        )


def get_grant(entry_id: str, user_id: str) -> sqlite3.Row | None:
    with get_conn() as conn:
        return conn.execute(
            "SELECT * FROM entry_grants WHERE entry_id = ? AND user_id = ?", (entry_id, user_id)
        ).fetchone()


def delete_grant(entry_id: str, user_id: str) -> bool:
    with get_conn() as conn:
        cur = conn.execute(
            "DELETE FROM entry_grants WHERE entry_id = ? AND user_id = ?", (entry_id, user_id)
        )
        return cur.rowcount > 0


def list_grants_for_entry(entry_id: str) -> list[sqlite3.Row]:
    with get_conn() as conn:
        return conn.execute(
            "SELECT g.user_id, g.can_write, u.username, u.role FROM entry_grants g "
            "JOIN users u ON u.id = g.user_id WHERE g.entry_id = ? ORDER BY u.username",
            (entry_id,),
        ).fetchall()


def set_admin_seal(entry_id: str, sealed_key: bytes) -> None:
    with get_conn() as conn:
        conn.execute(
            "INSERT INTO entry_admin_seal(entry_id, sealed_key) VALUES (?, ?) "
            "ON CONFLICT(entry_id) DO UPDATE SET sealed_key = excluded.sealed_key",
            (entry_id, sealed_key),
        )


def get_admin_seal(entry_id: str) -> bytes | None:
    with get_conn() as conn:
        row = conn.execute(
            "SELECT sealed_key FROM entry_admin_seal WHERE entry_id = ?", (entry_id,)
        ).fetchone()
        return row["sealed_key"] if row else None


def wipe_everything() -> None:
    """Full factory reset -- the only 'forgot password' path when no admin
    can log in to help. Deletes every user, entry, grant, and invite."""
    with get_conn() as conn:
        conn.execute("DELETE FROM entry_grants")
        conn.execute("DELETE FROM entry_admin_seal")
        conn.execute("DELETE FROM admin_recovery_grants")
        conn.execute("DELETE FROM entries")
        conn.execute("DELETE FROM invites")
        conn.execute("DELETE FROM users")
        conn.execute("DELETE FROM meta")
