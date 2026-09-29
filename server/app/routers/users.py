from fastapi import APIRouter, Depends, HTTPException, status

from app import crypto, database, sessions
from app.config import INVITE_EXPIRY_SECONDS
from app.deps import require_admin
from app.schemas import InviteRequest, InviteResponse, RoleUpdateRequest, UserOut
from app.sessions import Session

router = APIRouter(prefix="/api/v1/users", tags=["users"])


@router.get("", response_model=list[UserOut])
def list_users(_admin: Session = Depends(require_admin)):
    return [
        UserOut(id=u["id"], username=u["username"], role=u["role"], created_at=u["created_at"])
        for u in database.list_users()
    ]


@router.post("/invite", response_model=InviteResponse, status_code=status.HTTP_201_CREATED)
def invite_user(body: InviteRequest, _admin: Session = Depends(require_admin)):
    if body.role not in ("admin", "user", "visitor"):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "role must be admin, user, or visitor")
    if database.get_user_by_username(body.username) is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "That username is already taken")

    token = crypto.new_invite_token()
    database.create_invite(body.username, body.role, token)
    # The token is only ever returned here, once -- hand it to the invitee
    # out of band (in person, chat, whatever you'd trust with a temporary
    # code). Nothing about it is stored in recoverable form.
    return InviteResponse(username=body.username, role=body.role, token=token, expires_in=INVITE_EXPIRY_SECONDS)


@router.patch("/{user_id}/role", response_model=UserOut)
def update_role(user_id: str, body: RoleUpdateRequest, admin: Session = Depends(require_admin)):
    if body.role not in ("admin", "user", "visitor"):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "role must be admin, user, or visitor")
    target = database.get_user(user_id)
    if target is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "User not found")

    if target["role"] == "admin" and body.role != "admin" and database.count_admins(exclude_user_id=user_id) == 0:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Can't demote the last remaining admin")

    if body.role == "admin" and target["role"] != "admin":
        # Promoting to admin: reseal the shared admin-recovery private key
        # to the new admin's public key, using the promoting admin's own
        # already-unwrapped copy. Without this step the new admin would
        # only see entries they personally own or have been shared.
        if admin.admin_recovery_private_key is None:
            raise HTTPException(
                status.HTTP_400_BAD_REQUEST,
                "Your own admin session doesn't hold the recovery key (corrupt grant) -- "
                "ask another admin to perform this promotion instead.",
            )
        sealed = crypto.seal(admin.admin_recovery_private_key, target["public_key"])
        database.set_admin_recovery_grant(user_id, sealed)

    database.update_user_role(user_id, body.role)
    sessions.destroy_all_for_user(user_id)  # force re-login so their session role/keys refresh
    updated = database.get_user(user_id)
    return UserOut(id=updated["id"], username=updated["username"], role=updated["role"], created_at=updated["created_at"])


@router.delete("/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_user(user_id: str, admin: Session = Depends(require_admin)):
    if user_id == admin.user_id:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "You can't delete your own account while logged in")
    target = database.get_user(user_id)
    if target is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "User not found")
    if target["role"] == "admin" and database.count_admins(exclude_user_id=user_id) == 0:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Can't delete the last remaining admin")

    # Cascades to their entries (and everyone else's grants on those
    # entries), their own grants on other people's entries, and their
    # admin-recovery grant if they had one. Entries only they could write
    # are genuinely gone -- there is no way around that for a system with
    # no plaintext-recoverable backups by design.
    database.delete_user(user_id)
    sessions.destroy_all_for_user(user_id)
