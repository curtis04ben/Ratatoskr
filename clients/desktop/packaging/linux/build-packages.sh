#!/usr/bin/env bash
# Wraps the Compose Desktop app image (from `./gradlew :desktop:createDistributable`)
# into the three Linux release formats:
#
#   Ratatoskr-x86_64.AppImage       portable, runs on any distro, no install
#   ratatoskr_<version>_amd64.deb   Debian/Ubuntu/Mint
#   ratatoskr-<version>-1.x86_64.rpm  Fedora/RHEL/openSUSE
#
# All three carry the same app image, .desktop entry, icons and AppStream
# metadata from this directory. Output goes to desktop/build/linux-packages/
# (override with OUT_DIR). Needs nfpm and appimagetool on PATH, or pointed
# to by $NFPM / $APPIMAGETOOL. See clients/README.md.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CLIENTS="$(cd "$HERE/../../.." && pwd)"
APP_ID="io.github.curtis04ben.Ratatoskr"
NFPM="${NFPM:-nfpm}"
APPIMAGETOOL="${APPIMAGETOOL:-appimagetool}"

VERSION="$(sed -n 's/^ratatoskrDesktopVersion=//p' "$CLIENTS/gradle.properties")"
[ -n "$VERSION" ] || { echo "ratatoskrDesktopVersion missing from clients/gradle.properties" >&2; exit 1; }
# Release date for the AppStream metadata: the commit date, so rebuilding
# the same commit gives the same metadata.
RELEASE_DATE="$(git -C "$CLIENTS" log -1 --format=%cs 2>/dev/null || date -u +%F)"

IMAGE="$CLIENTS/desktop/build/compose/binaries/main/app/Ratatoskr"
[ -x "$IMAGE/bin/Ratatoskr" ] || {
    echo "No app image at $IMAGE. Run ./gradlew :desktop:createDistributable first" >&2
    exit 1
}

WORK="$CLIENTS/desktop/build/linux-packaging"
OUT="${OUT_DIR:-$CLIENTS/desktop/build/linux-packages}"
rm -rf "$WORK"
mkdir -p "$WORK" "$OUT"

# Installs the desktop entry, AppStream metadata and icons under $1/usr/share.
install_desktop_files() {
    local share="$1/usr/share"
    install -Dm644 "$HERE/$APP_ID.desktop" "$share/applications/$APP_ID.desktop"
    install -d "$share/metainfo"
    sed -e "s/@VERSION@/$VERSION/" -e "s/@DATE@/$RELEASE_DATE/" \
        "$HERE/$APP_ID.metainfo.xml" > "$share/metainfo/$APP_ID.metainfo.xml"
    local size
    for size in 48 128 256 512; do
        install -Dm644 "$CLIENTS/desktop/icons/ratatoskr_$size.png" \
            "$share/icons/hicolor/${size}x${size}/apps/$APP_ID.png"
    done
}

# --- .deb and .rpm -------------------------------------------------------
STAGE="$WORK/stage"
mkdir -p "$STAGE/opt"
cp -a "$IMAGE" "$STAGE/opt/ratatoskr"
install_desktop_files "$STAGE"

# nfpm doesn't expand variables in file paths, so fill them in here.
sed -e "s|\${RATATOSKR_VERSION}|$VERSION|g" \
    -e "s|\${RATATOSKR_STAGE}|$STAGE|g" \
    -e "s|\${RATATOSKR_PACKAGING}|$HERE|g" \
    "$HERE/nfpm.yaml" > "$WORK/nfpm.yaml"
"$NFPM" package --config "$WORK/nfpm.yaml" --packager deb --target "$OUT/ratatoskr_${VERSION}_amd64.deb"
"$NFPM" package --config "$WORK/nfpm.yaml" --packager rpm --target "$OUT/ratatoskr-${VERSION}-1.x86_64.rpm"

# --- AppImage ------------------------------------------------------------
APPDIR="$WORK/Ratatoskr.AppDir"
mkdir -p "$APPDIR/usr/lib" "$APPDIR/usr/bin"
cp -a "$IMAGE" "$APPDIR/usr/lib/ratatoskr"
ln -s ../lib/ratatoskr/bin/Ratatoskr "$APPDIR/usr/bin/ratatoskr"
install_desktop_files "$APPDIR"
# appimagetool expects the desktop entry and icon at the AppDir root too.
cp "$HERE/$APP_ID.desktop" "$APPDIR/$APP_ID.desktop"
cp "$CLIENTS/desktop/icons/ratatoskr_256.png" "$APPDIR/$APP_ID.png"
ln -s "$APP_ID.png" "$APPDIR/.DirIcon"
cat > "$APPDIR/AppRun" <<'EOF'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/lib/ratatoskr/bin/Ratatoskr" "$@"
EOF
chmod 755 "$APPDIR/AppRun"

ARCH=x86_64 "$APPIMAGETOOL" --no-appstream "$APPDIR" "$OUT/Ratatoskr-x86_64.AppImage"

(cd "$OUT" && sha256sum Ratatoskr-x86_64.AppImage "ratatoskr_${VERSION}_amd64.deb" \
    "ratatoskr-${VERSION}-1.x86_64.rpm" > SHA256SUMS)
echo "Packages in $OUT:"
ls -lh "$OUT"
