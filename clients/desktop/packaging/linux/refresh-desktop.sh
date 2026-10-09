#!/bin/sh
# Post-install/post-remove hook for the .deb and .rpm: refresh the icon
# cache and desktop-entry database so Ratatoskr appears in (or disappears
# from) the application launcher straight away. Every step is optional.
# A missing tool just means the desktop picks the change up on next login.
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
fi
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database -q /usr/share/applications || true
fi
exit 0
