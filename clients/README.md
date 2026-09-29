# Ratatoskr clients

Kotlin Multiplatform + Compose Multiplatform. `shared/` holds the API
client, models and every screen; `desktop/` is the desktop entry point and
packaging. Architecture and phase plan: [`docs/development-plan.md`](../docs/development-plan.md).

Needs JDK 21. On Fedora, packaging also needs `java-21-openjdk-jmods`
(jlink can't build the bundled runtime without it); Temurin JDKs include it.

## Three different things

| | What it is | How you get it |
|---|---|---|
| **Development build** | Runs straight from the source tree, using your JDK | `./gradlew :desktop:run` |
| **Packaged app** | AppImage / `.deb` / `.rpm` with its own bundled Java runtime | GitHub Releases, or build locally (below) |
| **Installed app** | The `.rpm`/`.deb` installed: in the app launcher, `ratatoskr` on `PATH` | `sudo dnf install ./ratatoskr-*.rpm` |

Users only ever deal with the last two.

## Tests

```bash
./gradlew :shared:jvmTest
```

`LiveServerTest` only runs when `RATATOSKR_TEST_SERVER` points at a real server.

## Linux packaging

Compose Desktop's `createDistributable` task (jpackage) builds a
self-contained app image: launcher + app jars + a jlinked Java runtime.
`desktop/packaging/linux/build-packages.sh` wraps that one image three ways:

| File | For | Tool |
|---|---|---|
| `Ratatoskr-x86_64.AppImage` | any distro, no install | [appimagetool](https://github.com/AppImage/appimagetool) |
| `ratatoskr_<ver>_amd64.deb` | Debian/Ubuntu | [nfpm](https://nfpm.goreleaser.com) |
| `ratatoskr-<ver>-1.x86_64.rpm` | Fedora/RHEL | nfpm |

jpackage can build `.deb`/`.rpm` itself, but its generated `.desktop` file
can't carry the app ID, `StartupWMClass` or AppStream metadata, so we build
the packages from the app image instead.

```bash
./gradlew :desktop:createDistributable
desktop/packaging/linux/build-packages.sh     # needs nfpm + appimagetool on PATH
ls desktop/build/linux-packages/
```

### Desktop integration

All in `desktop/packaging/linux/`, installed by the `.deb`/`.rpm` (and
embedded in the AppImage):

- **App ID** `io.github.curtis04ben.Ratatoskr` -- the reverse-DNS of the
  GitHub Pages domain the project controls. It names the `.desktop` file,
  the icon and the AppStream metadata. Don't change it once released:
  launchers, pinned favourites and software centres key on it.
- **`.desktop` entry** -> `/usr/share/applications/`. `StartupWMClass`
  must equal the window's X11 `WM_CLASS`, which AWT derives from the main
  class (`com-ratatoskr-desktop-MainKt`). If `mainClass` is renamed, update
  it, or KDE/GNOME will show the running window with a generic icon.
- **Icons** -> `/usr/share/icons/hicolor/{48,128,256,512}/apps/`, from `desktop/icons/`.
- **AppStream** metainfo -> `/usr/share/metainfo/` (Discover/GNOME Software).

Install layout: the app in `/opt/ratatoskr/`, `/usr/bin/ratatoskr` a
symlink to its launcher.

## Versioning and releases

The desktop version lives in one place: `ratatoskrDesktopVersion` in
`gradle.properties`. Gradle's `packageVersion` and the package filenames
all read it.

Releases use their own tags (`linux-vX.Y.Z`), separate from the server, so
each component gets its own GitHub Release with only its own files:

```bash
# 1. bump ratatoskrDesktopVersion in clients/gradle.properties, commit, push
# 2. tag that commit
git tag linux-v0.1.1
git push origin linux-v0.1.1
```

The tag triggers `.github/workflows/linux-client-release.yml`, which runs
the tests, builds all three packages, and publishes them with a
`SHA256SUMS` file as the release "Ratatoskr Linux client v0.1.1". It
refuses to release if the tag doesn't match `ratatoskrDesktopVersion`.
Running the workflow manually from the Actions tab builds the packages as
a workflow artifact without releasing anything.
