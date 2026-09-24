#!/bin/sh
set -e

# This image is built to run the application as the unprivileged
# "ratatoskr" user, but /data is normally a bind-mounted HOST directory --
# a TrueNAS dataset, a CasaOS AppData folder, a plain ./data folder on any
# other Docker host. Docker only auto-populates ownership for genuine
# named volumes on first use; a host bind mount always keeps whatever
# ownership the host directory already has, which is usually root (or
# whoever ran `docker compose up` first) with no write access for the
# in-container "ratatoskr" user. That mismatch is exactly what used to
# require a manual `sudo chmod -R 777 ./data` after the first deploy.
#
# The fix: start the container as root just long enough to create /data
# if it's missing and fix its ownership/permissions, then permanently
# drop to the unprivileged user via gosu for the actual application
# process. The app itself never runs as root -- only this brief startup
# step does.

if [ "$(id -u)" = "0" ]; then
    mkdir -p /data
    chown ratatoskr:ratatoskr /data
    # Owner-only access: this directory holds the vault database, so
    # nothing beyond rwx for the app's own user is appropriate here --
    # deliberately not the 777-open-to-everyone workaround this replaces.
    chmod 700 /data
    exec gosu ratatoskr "$@"
fi

# Already non-root (e.g. someone overrides USER at build time) -- just run
# the command directly, nothing to fix.
exec "$@"
