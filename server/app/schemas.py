from pydantic import BaseModel, Field

Role = str  # "admin" | "user" | "visitor"


class SetupRequest(BaseModel):
    """Bootstraps the very first account, which always becomes admin."""
    username: str = Field(min_length=2, max_length=64)
    master_password: str = Field(min_length=8)


class UnlockRequest(BaseModel):
    username: str
    master_password: str


class AcceptInviteRequest(BaseModel):
    username: str
    invite_token: str
    master_password: str = Field(min_length=8)


class AuthResponse(BaseModel):
    token: str
    expires_in: int
    username: str
    role: Role


class StatusResponse(BaseModel):
    initialized: bool


class MeResponse(BaseModel):
    username: str
    role: Role


class ChangePasswordRequest(BaseModel):
    current_password: str
    new_password: str = Field(min_length=8)


class FactoryResetRequest(BaseModel):
    """Destructive last-resort recovery: wipes every user and entry. Only
    path available when no admin account can be logged into."""
    confirm_wipe: bool = False


class InviteRequest(BaseModel):
    username: str = Field(min_length=2, max_length=64)
    role: Role = "user"


class InviteResponse(BaseModel):
    username: str
    role: Role
    token: str
    expires_in: int


class UserOut(BaseModel):
    id: str
    username: str
    role: Role
    created_at: float


class RoleUpdateRequest(BaseModel):
    role: Role


class EntryIn(BaseModel):
    site: str = Field(min_length=1, max_length=200)
    username: str = ""
    password: str = ""
    url: str = ""
    notes: str = ""
    totp_secret: str = ""  # base32 2FA secret; empty means no 2FA for this entry


class EntryOut(EntryIn):
    id: str
    owner_username: str
    can_write: bool
    shared: bool
    created_at: float
    updated_at: float


class TotpCodeOut(BaseModel):
    code: str
    period: int
    seconds_remaining: int


class ShareRequest(BaseModel):
    username: str
    can_write: bool = False


class ShareEntryOut(BaseModel):
    username: str
    role: Role
    can_write: bool


class ImportResult(BaseModel):
    imported: int
    skipped: int
    errors: list[str]


class GenerateRequest(BaseModel):
    length: int = Field(default=20, ge=8, le=128)
    use_upper: bool = True
    use_lower: bool = True
    use_digits: bool = True
    use_symbols: bool = True
    avoid_ambiguous: bool = True


class GenerateResponse(BaseModel):
    password: str
