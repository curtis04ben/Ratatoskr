import os
from pathlib import Path

DATA_DIR = Path(os.environ.get("RATATOSKR_DATA_DIR", "/data"))
DATA_DIR.mkdir(parents=True, exist_ok=True)

DB_PATH = DATA_DIR / "ratatoskr.db"

# How long an unlocked session stays valid without activity (seconds).
SESSION_IDLE_TIMEOUT = int(os.environ.get("RATATOSKR_SESSION_TIMEOUT", "1800"))

# How long an admin-issued invite stays redeemable (seconds). Default 7 days.
INVITE_EXPIRY_SECONDS = int(os.environ.get("RATATOSKR_INVITE_EXPIRY", str(7 * 24 * 3600)))

# Comma-separated list of extra origins allowed to call the API -- for a
# future browser extension, which runs from its own chrome-extension:// /
# moz-extension:// origin rather than the page you're viewing.
EXTRA_CORS_ORIGINS = [
    o.strip() for o in os.environ.get("RATATOSKR_EXTRA_ORIGINS", "").split(",") if o.strip()
]

# Argon2id KDF parameters used to turn each user's master password into the
# key that wraps their private key. Deliberately expensive; tune down only
# if the host hardware genuinely can't keep up (check unlock latency).
ARGON2_TIME_COST = int(os.environ.get("RATATOSKR_ARGON2_TIME_COST", "3"))
ARGON2_MEMORY_COST_KIB = int(os.environ.get("RATATOSKR_ARGON2_MEMORY_KIB", "262144"))  # 256 MiB
ARGON2_PARALLELISM = int(os.environ.get("RATATOSKR_ARGON2_PARALLELISM", "2"))
