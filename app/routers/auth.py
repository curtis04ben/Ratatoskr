from fastapi import APIRouter, Depends, HTTPException, status

from app import crypto, database, sessions
from app.config import SESSION_IDLE_TIMEOUT
from app.deps import get_token, require_session
from app.schemas import (
    AcceptInviteRequest,
    AuthResponse,
    ChangePasswordRequest,
    FactoryResetRequest,
    MeResponse,
    SetupRequest,
    StatusResponse,
    UnlockRequest,
)
from app.sessions import Session

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])


def _start_session(user_id: str, username: str, role: str, private_key: bytes, public_key: bytes) -> str:
    admin_recovery_private = None
    if role == "admin":
        sealed = database.get_admin_recovery_grant(user_id)
        if sealed is not None:
            try:
                admin_recovery_private = crypto.unseal(sealed, private_key)
            except crypto.WrongPassword:
                admin_recovery_private = None  # corrupt grant; admin keeps normal access only
    return sessions.create_session(user_id, username, role, private_key, public_key, admin_recovery_private)


@router.get("/status", response_model=StatusResponse)
def status_check():
    return StatusResponse(initialized=database.any_users_exist())


@router.get("/me", response_model=MeResponse)
def me(session: Session = Depends(require_session)):
    return MeResponse(username=session.username, role=session.role)


@router.post("/setup", response_model=AuthResponse, status_code=status.HTTP_201_CREATED)
def setup(body: SetupRequest):
    """Creates the very first account. Always becomes admin, and also
    generates the tree-wide admin-recovery keypair described in crypto.py,
    sealing its private half to this founding admin."""
    if database.any_users_exist():
        raise HTTPException(status.HTTP_409_CONFLICT, "Already set up -- use /unlock or an invite")

    salt = crypto.new_salt()
    kek = crypto.derive_kek(body.master_password, salt)
    private_key, public_key = crypto.generate_identity_keypair()
    wrapped = crypto.wrap_private_key(private_key, kek)

    user_id = database.create_user(body.username, "admin", salt, wrapped, public_key)

    recovery_private, recovery_public = crypto.generate_identity_keypair()
    database.set_meta("admin_recovery_public", recovery_public)
    sealed_recovery = crypto.seal(recovery_private, public_key)
    database.set_admin_recovery_grant(user_id, sealed_recovery)

    token = _start_session(user_id, body.username, "admin", private_key, public_key)
    return AuthResponse(token=token, expires_in=SESSION_IDLE_TIMEOUT, username=body.username, role="admin")


@router.post("/unlock", response_model=AuthResponse)
def unlock(body: UnlockRequest):
    if not database.any_users_exist():
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Not set up yet")
    user = database.get_user_by_username(body.username)
    if user is None:
        # Same error as a wrong password -- don't reveal whether the
        # username exists.
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Incorrect username or master password")

    kek = crypto.derive_kek(body.master_password, user["salt"])
    try:
        private_key = crypto.unwrap_private_key(user["wrapped_private_key"], kek)
    except crypto.WrongPassword:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Incorrect username or master password")

    token = _start_session(user["id"], user["username"], user["role"], private_key, user["public_key"])
    return AuthResponse(token=token, expires_in=SESSION_IDLE_TIMEOUT, username=user["username"], role=user["role"])


@router.post("/accept-invite", response_model=AuthResponse, status_code=status.HTTP_201_CREATED)
def accept_invite(body: AcceptInviteRequest):
    invite = database.get_valid_invite(body.username, body.invite_token)
    if invite is None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Invite is invalid, expired, or already used")
    if database.get_user_by_username(body.username) is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "That username is already taken")

    salt = crypto.new_salt()
    kek = crypto.derive_kek(body.master_password, salt)
    private_key, public_key = crypto.generate_identity_keypair()
    wrapped = crypto.wrap_private_key(private_key, kek)

    user_id = database.create_user(body.username, invite["role"], salt, wrapped, public_key)
    database.consume_invite(invite["id"])

    # A brand-new admin has no admin-recovery grant yet; they'll only see
    # entries they own or have been explicitly shared with until an
    # existing admin promotes/re-shares recovery access with them (the
    # promote-to-admin endpoint in the users router does this).

    token = _start_session(user_id, body.username, invite["role"], private_key, public_key)
    return AuthResponse(token=token, expires_in=SESSION_IDLE_TIMEOUT, username=body.username, role=invite["role"])


@router.post("/lock", status_code=status.HTTP_204_NO_CONTENT)
def lock(token: str = Depends(get_token)):
    sessions.destroy(token)


@router.post("/change-password", response_model=AuthResponse)
def change_password(body: ChangePasswordRequest, session: Session = Depends(require_session)):
    user = database.get_user(session.user_id)
    kek = crypto.derive_kek(body.current_password, user["salt"])
    try:
        private_key = crypto.unwrap_private_key(user["wrapped_private_key"], kek)
    except crypto.WrongPassword:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Incorrect current password")

    # Only the wrapping changes -- the X25519 keypair itself is untouched,
    # so every seal anyone has made to this user's public key (their own
    # entries, shares from others, admin-recovery grant) stays valid.
    new_salt = crypto.new_salt()
    new_kek = crypto.derive_kek(body.new_password, new_salt)
    new_wrapped = crypto.wrap_private_key(private_key, new_kek)
    database.update_user_password(session.user_id, new_salt, new_wrapped)

    sessions.destroy_all_for_user(session.user_id)
    token = _start_session(session.user_id, session.username, session.role, private_key, user["public_key"])
    return AuthResponse(token=token, expires_in=SESSION_IDLE_TIMEOUT, username=session.username, role=session.role)


@router.post("/factory-reset", status_code=status.HTTP_204_NO_CONTENT)
def factory_reset(body: FactoryResetRequest):
    """Last-resort recovery when no admin can log in. There is no
    cryptographic way to recover anyone's data without their master
    password, so this deliberately deletes every account and every entry,
    then leaves the vault ready for /setup to bootstrap a fresh admin."""
    if not body.confirm_wipe:
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            "This deletes every account and every saved entry, for everyone. "
            "Set confirm_wipe=true to proceed.",
        )
    database.wipe_everything()
    sessions.destroy_all()
