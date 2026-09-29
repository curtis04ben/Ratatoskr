from fastapi import Depends, Header, HTTPException, status

from app import sessions
from app.sessions import Session


def get_token(authorization: str | None = Header(default=None)) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Missing bearer token")
    return authorization.removeprefix("Bearer ").strip()


def require_session(authorization: str | None = Header(default=None)) -> Session:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Missing bearer token")
    token = authorization.removeprefix("Bearer ").strip()
    session = sessions.touch_and_get(token)
    if session is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Session expired or invalid, unlock again")
    return session


def require_admin(session: Session = Depends(require_session)) -> Session:
    if session.role != "admin":
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Admin role required")
    return session


def require_writer(session: Session = Depends(require_session)) -> Session:
    """Visitors are read-only everywhere; everyone else may attempt writes
    (per-entry ownership/grant checks still apply on top of this)."""
    if session.role == "visitor":
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Visitors have read-only access")
    return session
