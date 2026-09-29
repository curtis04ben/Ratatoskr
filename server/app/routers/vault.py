import csv
import io
import json
from datetime import datetime, timezone
from urllib.parse import urlparse

from fastapi import APIRouter, Depends, HTTPException, UploadFile, status
from fastapi.responses import StreamingResponse

from app import crypto, database
from app.deps import require_session, require_writer
from app.schemas import EntryIn, EntryOut, ImportResult, ShareEntryOut, ShareRequest, TotpCodeOut
from app.sessions import Session

router = APIRouter(prefix="/api/v1/vault", tags=["vault"])

CSV_FIELDS = ["site", "username", "password", "totp_secret", "url", "notes"]

# Common column-name variants used by other password managers' exports, so
# an import doesn't require the file to already be in Ratatoskr's own
# format. Matched case-insensitively after stripping whitespace.
#
# Firefox's own export (Settings -> Passwords -> "Export Logins") has no
# site/name/title column at all -- just url, username, password, plus a
# few fields we don't use (httpRealm, formActionOrigin, guid,
# timeCreated, timeLastUsed, timePasswordChanged). Rather than special-
# casing "is this a Firefox file?", the importer falls back to deriving
# the site name from the URL's hostname whenever there's no explicit site
# column but there IS a url column -- this handles Firefox's export (and
# any other tool with the same gap) without a brittle format sniff.
_HEADER_SYNONYMS = {
    "site": {"site", "name", "title", "service"},
    "username": {"username", "login_username", "user", "email"},
    "password": {"password", "login_password", "pass"},
    "totp_secret": {"totp_secret", "totp", "2fa_secret", "otp_secret", "otpauth_secret", "secret_key"},
    "url": {"url", "login_uri", "uri", "website", "web site"},
    "notes": {"notes", "note", "extra", "comments"},
}


def _site_from_url(url: str) -> str:
    """Best-effort hostname extraction for rows with no explicit site name.
    Never raises -- falls back to the raw URL string if it can't be parsed,
    so a malformed or scheme-less URL still produces *some* usable site
    name rather than causing the whole row to be skipped."""
    url = url.strip()
    if not url:
        return ""
    parsed = urlparse(url)
    if not parsed.hostname:
        # No scheme (e.g. "example.com" instead of "https://example.com") --
        # urlparse treats that as a bare path with no netloc. Retry with an
        # assumed scheme before giving up.
        parsed = urlparse("https://" + url)
    hostname = parsed.hostname or url
    return hostname[4:] if hostname.startswith("www.") else hostname


def _entry_key_for_admin(entry_id: str, session: Session, direct_grant_sealed: bytes | None) -> bytes:
    """Admins can decrypt anything: use their own direct grant if they have
    one (e.g. they created it), otherwise fall back to the tree-wide
    admin-recovery seal every entry also carries."""
    if direct_grant_sealed is not None:
        return crypto.unseal(direct_grant_sealed, session.private_key)
    admin_seal = database.get_admin_seal(entry_id)
    if admin_seal is None or session.admin_recovery_private_key is None:
        raise HTTPException(
            status.HTTP_500_INTERNAL_SERVER_ERROR,
            f"Entry {entry_id} has no admin recovery seal available",
        )
    return crypto.unseal(admin_seal, session.admin_recovery_private_key)


def _to_entry_out(entry_row, entry_key: bytes, can_write: bool, grant_count: int) -> EntryOut:
    data = json.loads(crypto.decrypt_entry(entry_row["ciphertext"], entry_key))
    return EntryOut(
        id=entry_row["id"],
        owner_username=entry_row["owner_username"],
        can_write=can_write,
        shared=grant_count > 1,
        created_at=entry_row["created_at"],
        updated_at=entry_row["updated_at"],
        **data,
    )


@router.get("", response_model=list[EntryOut])
def list_entries(session: Session = Depends(require_session)):
    out = []
    if session.role == "admin":
        for row in database.list_all_entries_with_owner():
            grant = database.get_grant(row["id"], session.user_id)
            entry_key = _entry_key_for_admin(row["id"], session, grant["sealed_key"] if grant else None)
            grant_count = len(database.list_grants_for_entry(row["id"]))
            out.append(_to_entry_out(row, entry_key, can_write=True, grant_count=grant_count))
    else:
        for row in database.list_entries_for_user(session.user_id):
            entry_key = crypto.unseal(row["sealed_key"], session.private_key)
            can_write = bool(row["can_write"]) and session.role != "visitor"
            grant_count = len(database.list_grants_for_entry(row["id"]))
            out.append(_to_entry_out(row, entry_key, can_write=can_write, grant_count=grant_count))
    return out


def _persist_new_entry(body: EntryIn, session: Session) -> str:
    """Shared by the JSON create endpoint and CSV import: encrypts, stores,
    grants the owner, and seals a copy for tree-wide admin recovery."""
    entry_key = crypto.new_entry_key()
    ciphertext = crypto.encrypt_entry(json.dumps(body.model_dump()).encode(), entry_key)
    entry_id = database.create_entry(session.user_id, ciphertext)

    sealed_owner = crypto.seal(entry_key, session.public_key)
    database.create_grant(entry_id, session.user_id, sealed_owner, can_write=True, granted_by=session.user_id)

    admin_recovery_public = database.get_meta("admin_recovery_public")
    if admin_recovery_public:
        database.set_admin_seal(entry_id, crypto.seal(entry_key, admin_recovery_public))

    return entry_id


def _validate_entry_body(body: EntryIn) -> None:
    if body.totp_secret and not crypto.is_valid_totp_secret(body.totp_secret):
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            "That doesn't look like a valid 2FA secret -- it should be the base32 code "
            "(the \"can't scan the QR code? enter this key instead\" text) from the site's "
            "2FA setup screen, not the 6-digit code itself.",
        )


@router.post("", response_model=EntryOut, status_code=status.HTTP_201_CREATED)
def create_entry(body: EntryIn, session: Session = Depends(require_writer)):
    _validate_entry_body(body)
    entry_id = _persist_new_entry(body, session)
    row = database.get_entry(entry_id)
    entry_key = crypto.unseal(database.get_grant(entry_id, session.user_id)["sealed_key"], session.private_key)
    row_with_owner = dict(row)
    row_with_owner["owner_username"] = session.username
    return _to_entry_out(row_with_owner, entry_key, can_write=True, grant_count=1)


def _load_writable_entry(entry_id: str, session: Session):
    entry = database.get_entry(entry_id)
    if entry is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Entry not found")
    if session.role == "admin":
        grant = database.get_grant(entry_id, session.user_id)
        entry_key = _entry_key_for_admin(entry_id, session, grant["sealed_key"] if grant else None)
        return entry, entry_key
    grant = database.get_grant(entry_id, session.user_id)
    if grant is None or not grant["can_write"]:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "You don't have write access to this entry")
    entry_key = crypto.unseal(grant["sealed_key"], session.private_key)
    return entry, entry_key


def _load_readable_entry(entry_id: str, session: Session):
    """Like _load_writable_entry, but for anyone who can merely see the
    entry (any grant, or an admin via the recovery path) -- used for
    endpoints that don't modify anything, like reading the current TOTP
    code."""
    entry = database.get_entry(entry_id)
    if entry is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Entry not found")
    if session.role == "admin":
        grant = database.get_grant(entry_id, session.user_id)
        entry_key = _entry_key_for_admin(entry_id, session, grant["sealed_key"] if grant else None)
        return entry, entry_key
    grant = database.get_grant(entry_id, session.user_id)
    if grant is None:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "You don't have access to this entry")
    entry_key = crypto.unseal(grant["sealed_key"], session.private_key)
    return entry, entry_key


@router.put("/{entry_id}", response_model=EntryOut)
def update_entry(entry_id: str, body: EntryIn, session: Session = Depends(require_writer)):
    _validate_entry_body(body)
    _entry, entry_key = _load_writable_entry(entry_id, session)
    ciphertext = crypto.encrypt_entry(json.dumps(body.model_dump()).encode(), entry_key)
    database.update_entry_ciphertext(entry_id, ciphertext)

    row = database.get_entry(entry_id)
    owner = database.get_user(row["owner_id"])
    row_with_owner = dict(row)
    row_with_owner["owner_username"] = owner["username"]
    grant_count = len(database.list_grants_for_entry(entry_id))
    return _to_entry_out(row_with_owner, entry_key, can_write=True, grant_count=grant_count)


@router.get("/{entry_id}/totp", response_model=TotpCodeOut)
def get_totp_code(entry_id: str, session: Session = Depends(require_session)):
    """Server-computed current 2FA code, for thin clients (a future
    browser extension or native app) that would rather call this than
    reimplement HMAC-SHA1/TOTP themselves. The web UI computes this
    client-side instead (see totp.js) purely so the code can live-update
    every second without a network round trip -- both use the identical
    RFC 6238 algorithm, just in two languages."""
    _entry, entry_key = _load_readable_entry(entry_id, session)
    row = database.get_entry(entry_id)
    data = json.loads(crypto.decrypt_entry(row["ciphertext"], entry_key))
    secret = data.get("totp_secret", "")
    if not secret:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "This entry doesn't have 2FA configured")
    code, remaining = crypto.totp_now(secret)
    return TotpCodeOut(code=code, period=30, seconds_remaining=remaining)


@router.delete("/{entry_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_entry(entry_id: str, session: Session = Depends(require_writer)):
    _entry, _key = _load_writable_entry(entry_id, session)
    database.delete_entry(entry_id)


@router.get("/{entry_id}/shares", response_model=list[ShareEntryOut])
def list_shares(entry_id: str, session: Session = Depends(require_session)):
    entry = database.get_entry(entry_id)
    if entry is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Entry not found")
    if session.role != "admin" and entry["owner_id"] != session.user_id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only the owner or an admin can view sharing")
    return [
        ShareEntryOut(username=g["username"], role=g["role"], can_write=bool(g["can_write"]))
        for g in database.list_grants_for_entry(entry_id)
    ]


@router.post("/{entry_id}/shares", response_model=ShareEntryOut, status_code=status.HTTP_201_CREATED)
def share_entry(entry_id: str, body: ShareRequest, session: Session = Depends(require_writer)):
    entry, entry_key = _load_writable_entry(entry_id, session)
    if session.role != "admin" and entry["owner_id"] != session.user_id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only the owner or an admin can share this entry")

    target = database.get_user_by_username(body.username)
    if target is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No user with that username")

    can_write = body.can_write and target["role"] != "visitor"  # visitors are always read-only
    sealed = crypto.seal(entry_key, target["public_key"])
    database.create_grant(entry_id, target["id"], sealed, can_write=can_write, granted_by=session.user_id)
    return ShareEntryOut(username=target["username"], role=target["role"], can_write=can_write)


@router.delete("/{entry_id}/shares/{username}", status_code=status.HTTP_204_NO_CONTENT)
def unshare_entry(entry_id: str, username: str, session: Session = Depends(require_writer)):
    entry = database.get_entry(entry_id)
    if entry is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Entry not found")
    if session.role != "admin" and entry["owner_id"] != session.user_id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only the owner or an admin can change sharing")

    target = database.get_user_by_username(username)
    if target is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No user with that username")
    if target["id"] == entry["owner_id"]:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Can't revoke the owner's own access")
    database.delete_grant(entry_id, target["id"])


# ---------- CSV export / import ----------
#
# Both directions handle plaintext: an exported file is every visible
# password sitting unencrypted on whatever device downloads it, and an
# import reads plaintext passwords from a file the user supplies. Neither
# side of this touches the encryption model for anything already stored --
# it only exists at the boundary where data enters or leaves the vault.

@router.get("/export")
def export_csv(session: Session = Depends(require_session)):
    """Exports every entry visible to the current session (their own,
    anything shared with them, or -- for admins -- everything) as CSV.
    Read-only for visitors is not relevant here since export never writes."""
    entries = list_entries(session)  # reuses the same visibility logic as GET /vault

    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=CSV_FIELDS + ["owner"])
    writer.writeheader()
    for e in entries:
        writer.writerow({
            "site": e.site, "username": e.username, "password": e.password,
            "totp_secret": e.totp_secret, "url": e.url, "notes": e.notes, "owner": e.owner_username,
        })
    buffer.seek(0)

    filename = f"ratatoskr-export-{datetime.now(timezone.utc).strftime('%Y%m%d')}.csv"
    return StreamingResponse(
        iter([buffer.getvalue()]),
        media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


def _normalize_header(raw: str) -> str | None:
    key = raw.strip().lower()
    for field, synonyms in _HEADER_SYNONYMS.items():
        if key in synonyms:
            return field
    return None


@router.post("/import", response_model=ImportResult)
async def import_csv(file: UploadFile, session: Session = Depends(require_writer)):
    raw_bytes = await file.read()
    try:
        raw = raw_bytes.decode("utf-8-sig")
    except UnicodeDecodeError:
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            "That file isn't readable as text (not valid UTF-8). Make sure you're uploading the "
            "CSV file itself, not something else.",
        )

    try:
        rows_iter = csv.reader(io.StringIO(raw))
        all_rows = list(rows_iter)  # force full parse now, so a malformed row surfaces as one clear error
    except csv.Error as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, f"This doesn't look like valid CSV: {exc}")

    if not all_rows:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "That file is empty.")

    header_row, data_rows = all_rows[0], all_rows[1:]
    column_map = {i: _normalize_header(h) for i, h in enumerate(header_row)}
    recognized = set(column_map.values())

    has_site_column = "site" in recognized
    has_url_column = "url" in recognized
    if not has_site_column and not has_url_column:
        # A genuinely valid CSV, just not one Ratatoskr (or any format it
        # knows how to read, like Chrome/Bitwarden/Firefox exports) can
        # make sense of -- distinct from a malformed-file error above.
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            "This CSV has a header row, but none of its columns look like a site name or URL "
            "(recognized names include site/name/title/service, or url/login_uri/uri/website). "
            "Ratatoskr needs at least one of those to know what each entry is for.",
        )

    imported, skipped, errors = 0, 0, []
    for row_num, row in enumerate(data_rows, start=2):
        values = {column_map[i]: v for i, v in enumerate(row) if column_map.get(i) and i < len(row)}

        site = values.get("site", "").strip()
        if not site and has_url_column:
            # Firefox-shaped export (and anything else with a url column
            # but no site/name/title): derive a display name from the URL.
            site = _site_from_url(values.get("url", ""))
        if not site:
            skipped += 1
            continue

        try:
            body = EntryIn(
                site=site,
                username=values.get("username", "").strip(),
                password=values.get("password", ""),
                totp_secret=values.get("totp_secret", "").strip(),
                url=values.get("url", "").strip(),
                notes=values.get("notes", ""),
            )
            if body.totp_secret and not crypto.is_valid_totp_secret(body.totp_secret):
                # Don't hard-fail the whole row over an unusable 2FA secret --
                # import everything else and flag it so the person can fix it
                # by hand afterward. (Never logs the secret itself.)
                errors.append(f"Row {row_num}: 2FA secret for \"{site}\" wasn't valid base32, imported without it")
                body.totp_secret = ""
            _persist_new_entry(body, session)
            imported += 1
        except Exception as exc:  # noqa: BLE001 -- surfaced to the caller, not swallowed
            # Deliberately not including field values in this message --
            # only the row number and the validation error itself, so a
            # bad row can be diagnosed without echoing back a password.
            errors.append(f"Row {row_num}: {exc}")

    return ImportResult(imported=imported, skipped=skipped, errors=errors)
