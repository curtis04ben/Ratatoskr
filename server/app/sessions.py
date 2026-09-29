"""
Sessions live in server memory only, never on disk. A session maps a random
bearer token to one user's unwrapped private key (plus their admin recovery
key, if they're an admin). Losing the process (container restart)
invalidates every session -- correct behaviour, since none of these keys
should outlive the process that unlocked them.
"""
import secrets
import threading
import time
from dataclasses import dataclass

from app.config import SESSION_IDLE_TIMEOUT


@dataclass
class Session:
    user_id: str
    username: str
    role: str
    private_key: bytes
    public_key: bytes
    admin_recovery_private_key: bytes | None
    expires: float


_lock = threading.Lock()
_sessions: dict[str, Session] = {}


def create_session(
    user_id: str,
    username: str,
    role: str,
    private_key: bytes,
    public_key: bytes,
    admin_recovery_private_key: bytes | None = None,
) -> str:
    token = secrets.token_urlsafe(32)
    with _lock:
        _sessions[token] = Session(
            user_id=user_id,
            username=username,
            role=role,
            private_key=private_key,
            public_key=public_key,
            admin_recovery_private_key=admin_recovery_private_key,
            expires=time.time() + SESSION_IDLE_TIMEOUT,
        )
    return token


def touch_and_get(token: str) -> Session | None:
    with _lock:
        session = _sessions.get(token)
        if not session:
            return None
        if session.expires < time.time():
            del _sessions[token]
            return None
        session.expires = time.time() + SESSION_IDLE_TIMEOUT
        return session


def destroy(token: str) -> None:
    with _lock:
        _sessions.pop(token, None)


def destroy_all_for_user(user_id: str) -> None:
    with _lock:
        for token in [t for t, s in _sessions.items() if s.user_id == user_id]:
            del _sessions[token]


def destroy_all() -> None:
    with _lock:
        _sessions.clear()
