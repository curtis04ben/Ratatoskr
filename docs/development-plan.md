# Ratatoskr development plan

This documents the architecture decisions behind the multi-client Ratatoskr
project and the planned approach for each native client. It's meant as
durable context for whoever (human or AI assistant) picks up client work
next — not a rigid spec, more a record of *why* things are shaped the way
they are, so that reasoning doesn't have to be reconstructed from scratch
each time.

## Picking this back up

**Where things stand** (update this list whenever it changes):

| Component | Version | Tag / release | Notes |
|---|---|---|---|
| Server + web UI | 2.4.0 | none (deployed from source) | `server/app/main.py` holds the version |
| Linux app | 0.1.3 | `linux-v0.1.3` | `ratatoskrDesktopVersion` in `clients/gradle.properties` |
| Android app | 0.1.2 | `android-v0.1.2` | `ratatoskrAndroidVersion`; release key + 4 GitHub secrets in place |
| Windows / macOS | — | — | Phases 4/5 below: packaging on the same `desktop/` module |
| Android Autofill | — | — | Phase 3.x below |

Both native apps cover: connect (server remembered), setup, unlock,
accept-invite, entry list/search/create/edit/delete, live TOTP, password
generator, CSV import/export, change master password, lock. Not in the
apps yet (web UI only): sharing, the admin Users panel, factory reset.

**Development machine setup** (a Fedora KDE box so far):

- **JDK 21 with jmods.** Everyday builds work with Fedora's
  `java-21-openjdk-devel`, but *packaging* (`createDistributable`, jlink)
  also needs `sudo dnf install java-21-openjdk-jmods`, or point
  `JAVA_HOME` at a Temurin 21 JDK (which bundles jmods). Without it:
  "jlink ... this runtime image does not contain jmods directory".
- **Android SDK** lives in `~/Android/Sdk`, with `sdk.dir` in
  `clients/local.properties` (git-ignored). The `sdkmanager` wrapper in
  current command-line tools hands off to a new `android` CLI and can hang
  silently, leaving orphaned `android-cli` processes that hold a lock; use
  `~/Android/Sdk/cmdline-tools/latest/bin/android sdk install <package>`
  directly (package names use `/`, e.g. `platforms/android-37.0`).
- **The Android emulator segfaulted** on this machine's kernel (7.1) with
  every GPU mode tried. Test on a real phone instead: USB debugging on,
  `adb reverse tcp:8765 tcp:8765` to a scratch server (recipe in
  `clients/README.md`). Debug builds install as
  `io.github.curtis04ben.ratatoskr.debug`, alongside the real app.
  `adb shell uiautomator dump` lists on-screen text and bounds, which
  makes scripted tapping reliable (tap by text, not by guessed pixels).
- **Web UI** changes can be checked headlessly with Playwright + Firefox
  against a scratch server (`pip install playwright && playwright install
  firefox` in a throwaway venv).
- The Linux packaging tools (nfpm, appimagetool) are only needed to build
  packages locally; CI downloads pinned, checksum-verified copies itself.

**How work gets shipped:** commit to `main` (fine for this project), bump
the relevant version in `clients/gradle.properties`, then push a
`linux-vX.Y.Z` / `android-vX.Y.Z` tag. The workflow refuses to release if
the tag doesn't match the version. Each client has its own GitHub
Release, so nobody downloads files for a platform they don't use.
Details in `clients/README.md`.

## Repository shape

```
Ratatoskr/
├── server/          Python/FastAPI backend + web UI (the original project)
├── clients/         Kotlin Multiplatform + Compose Multiplatform monorepo
│   ├── shared/       portable API client, models, TOTP, app state, UI screens
│   ├── desktop/      JVM/Compose Desktop entry point — builds Linux, Windows, AND macOS
│   └── android/      Android app module (AGP 9 requires it separate from shared/)
├── shared/assets/    master branding (logo SVG) that every client/platform icon derives from
├── docs/             this file, plus anything else durable
└── .github/workflows/  tag-triggered release builds (linux-v*, android-v*)
```

**Monorepo, not polyrepo.** Chosen specifically because development happens
solo with AI assistants: a client change and the API change it depends on
can land in one PR, and an assistant editing a client can read the real
`server/app/schemas.py` directly instead of working from a secondhand
description. Revisit only if a specific client's build tooling genuinely
conflicts with the others (CI minutes, SDK bloat) — not preemptively.

**Independent versioning per component**, not one repo-wide tag. The
server is mature; a first client starts at 0.1.0. Tags in use:
`linux-vX.Y.Z` and `android-vX.Y.Z`, each producing its own GitHub Release
(versions in `clients/gradle.properties`). The server is still deployed
from source; a `server-vX.Y.Z` tag would follow the same pattern if it
ever gets released builds (e.g. a published Docker image).

**`server/` nesting** happened together with adding `clients/`, not before
— moving it earlier would have broken every already-documented deployment
path (TrueNAS, CasaOS) for zero benefit until there was a second thing in
the repo to justify the split.

## Client technology: Compose Multiplatform

Chosen over wrapping the existing web UI (Tauri/Electron) because the
whole point of a *native* client, as the person who owns this project put
it, is defeated by wrapping a web view. Chosen over Flutter/native-per-
platform because Compose Multiplatform is genuinely first-class on both
Android (the very next phase after Linux) and Desktop (Linux/Windows/
macOS) via the JVM — meaning most UI code gets written once and reused,
rather than five platform efforts happening independently.

**Consequence worth being explicit about**: `clients/desktop` is *one*
module that produces installers for Linux, Windows, and macOS — not three
separate client efforts. Phase 2 ("build the Linux client") is really
"build the desktop app"; Phases 4 and 5 are mostly packaging and
platform-glue work on an app that already exists, not new app-building
phases. Treating them as separate would throw away the exact benefit that
justified this framework choice.

## What's genuinely shared vs. what isn't

**Shared (`clients/shared`, commonMain)**: the API client, all data
models, TOTP generation, app/session state, and every screen's UI. This is
the bulk of the app.

**Not shared, by necessity** (`expect`/`actual` platform code, added as
each phase needs it): secure credential storage (Keystore on Android,
Keychain on iOS/macOS, Credential Manager/DPAPI on Windows, libsecret on
Linux), and each platform's own app entry point / window chrome.

**API definitions**: not a hand-maintained shared schema file. The
server's FastAPI already generates a live OpenAPI spec at `/openapi.json`
— that's the real contract. `clients/shared`'s Kotlin models are currently
hand-written against the real `server/app/schemas.py` (accurate as of
this writing, small enough surface that hand-matching is less overhead
than a codegen dependency for one client). Worth switching to generating
Kotlin bindings from the live OpenAPI spec once there are several clients
all needing to stay in sync — not worth it yet for one.

## Client-side crypto: there currently isn't any (beyond TOTP)

Worth stating plainly since it's easy to assume otherwise given how much
cryptographic engineering went into the server: the server derives the
Argon2id key and decrypts entries server-side; the API returns
already-decrypted data to an authenticated session. A native client is
therefore a thin REST client, same as the web UI — it does not need to
reimplement Argon2id/X25519 sealing in Kotlin.

The one piece of real crypto in `clients/shared` is `Totp.kt` — a
hand-written, pure-Kotlin RFC 6238 implementation, deliberately not using
any platform crypto API (`javax.crypto` on JVM, CryptoKit on iOS, etc.),
so it produces identical output on every target without per-platform
wiring. This is the third independent implementation of the same
algorithm in this project (server: Python, web UI: JavaScript, clients:
Kotlin) — all three were checked against the official RFC 6238 Appendix B
test vectors and against each other during development.

If true end-to-end zero-knowledge (server never sees the master password,
only pre-encrypted blobs) is ever wanted, that's a materially bigger
redesign than anything in this document, deserving its own deliberate
decision — not something to back into via a client architecture choice.

## Per-client plan

### Phase 1 — Server prep (done alongside the `clients/` scaffold)

- `server/` move, done.
- Open item, not yet decided: session lifetime for native clients. The
  current 30-minute idle timeout matches a browser-tab mental model; a
  native app likely wants "stay signed in" longer. Probably a longer
  configurable timeout for native sessions rather than a full redesign.
- CORS (`RATATOSKR_EXTRA_ORIGINS`) is irrelevant to native clients — it's
  a browser-only enforcement mechanism. Nothing to add there for native
  clients specifically.

### Phase 2 — Linux (released; open items below)

MVP scope, deliberately smaller than full web-UI parity:

- ✅ Server connect screen (enter address, test connectivity)
- ✅ Setup (first-run admin creation)
- ✅ Unlock
- ✅ Accept-invite (join with a code from an admin)
- ✅ Entry list with search
- ✅ Entry create/edit/delete
- ✅ TOTP: live code + countdown, toggleable per entry
- ✅ Password generator
- ✅ CSV import/export
- ✅ Change master password (header ⋮ menu)
- ✅ Server remembered between launches (`DesktopSessionStore`: address
  only, in the per-user config dir; the token is never written to disk)
- ⬜ Stay signed in across launches: needs the token in the system
  keyring (libsecret on Linux) — see "Desktop session storage" under
  Phase 4, which covers all three desktop OSes
- ⬜ Clean handling of every disconnect/error edge case (some exists via
  `RatatoskrConnectionException`/`RatatoskrApiException`, not exhaustively
  tested against real network conditions)

**Deliberately deferred as fast-follows, not oversights**: sharing
(entries can be shared between users on the server, but there's no Share
dialog in the clients yet) and the admin users panel (invite creation,
role changes, removal). Both exist in the web UI and the REST API
(`RatatoskrApiClient` already has `listShares`/`shareEntry`/
`unshareEntry` and `listUsers`/`inviteUser`/`updateUserRole`/`deleteUser`),
so they're UI work in `clients/shared`, benefiting every app at once.

Packaging (done): Compose Desktop's `createDistributable` (jpackage)
builds a self-contained app image, and `clients/desktop/packaging/linux/`
wraps it as AppImage (appimagetool) plus `.deb`/`.rpm` (nfpm), with a
Freedesktop `.desktop` entry, icons and AppStream metadata under app ID
`io.github.curtis04ben.Ratatoskr`. jpackage's own `.deb`/`.rpm` output was
skipped because its generated `.desktop` file can't carry the app ID or
`StartupWMClass`. Pushing a `linux-vX.Y.Z` tag publishes a GitHub Release
via `.github/workflows/linux-client-release.yml`. Details: `clients/README.md`.

### Phase 3 — Android (released; Autofill is Phase 3.x)

Mostly reuse: `clients/shared`'s screens carry over directly. `:shared`
gained an Android target (AGP's KMP library plugin, OkHttp engine) and
`clients/android` is the app module — AGP 9 requires the app in its own
module rather than an Android target on the KMP module. Real new work:

- ✅ Session storage: `SessionStore` in commonMain (optional, like
  `PlatformFiles`; desktop's stores the server address only), implemented on Android as
  `KeystoreSessionStore` — the token AES-GCM-encrypted under a
  non-exportable Keystore key. Not `EncryptedSharedPreferences`: that
  library was deprecated in 2025. Server address + token survive process
  death; an expired token lands on Unlock for the same server.
- ✅ Manifest: `INTERNET` only; cleartext allowed (homelab HTTP over
  Tailscale) with an in-app warning on non-HTTPS addresses; no backups of
  the session; `FLAG_SECURE`.
- ✅ Shared UI made width-adaptive for phones (max-width columns, scrolling
  forms, compact vault toolbar) — no visual change on desktop.
- ✅ CSV import/export via the Storage Access Framework.
- ✅ Signed APK released via `android-vX.Y.Z` tags
  (`.github/workflows/android-client-release.yml`).

minSdk 26 (Android 8.0) — chosen because that's where the Autofill
Framework starts, so the stretch goal below doesn't force a bump.

### Phase 3.x — Android Autofill

Goal: Ratatoskr as the phone's autofill service, filling logins in other
apps and (via the browser) websites. This is a genuinely different
integration point from the separate browser-extension project. It's a
sizeable piece of work, so the plan below is staged.

**Moving parts** (all in `clients/android`):

- An `AutofillService` subclass, declared in the manifest with
  `android:permission="android.permission.BIND_AUTOFILL_SERVICE"`, an
  intent filter for `android.service.autofill.AutofillService`, and a
  `<meta-data android:name="android.autofill">` XML naming a settings
  activity. The user then picks it in Settings → Passwords/Autofill
  service (wording varies by manufacturer; on Samsung it's under General
  management).
- `onFillRequest`: walk the `AssistStructure` to find the username and
  password fields (`autofillHints` first, then `inputType` password
  flags, then view id/hint text heuristics), and read the target: the
  requesting app's package name, or `webDomain` when the fields belong to
  a web page in a browser.
- Respond with one `Dataset` per matching entry. **If the vault is
  locked** (no valid token), respond with an authentication `IntentSender`
  (`FillResponse.Builder.setAuthentication`) that opens an unlock activity
  and returns the datasets once unlocked, so fill never happens without
  an unlocked session.
- `onSaveRequest` (stage 2): offer to save new credentials as an entry.

**Reuse:** the service can't use `AppState` (it's UI state); it should
use `RatatoskrApiClient` plus `KeystoreSessionStore` directly: load the
saved server and token, call `listEntries()`, and match. Keep decrypted
entries in memory only, never cached on disk. The server does the
decryption, so fill needs the server reachable.

**The hard problem: matching an app to an entry.** Entries have a `url`
but nothing that names an Android app.

1. Websites (stage 1): match `webDomain` against the host of each entry's
   `url`. Straightforward.
2. Native apps: options, from simplest up:
   - Manual: when nothing matches, the response offers "Search Ratatoskr…",
     which opens a picker; remember the chosen package → entry pairing.
     Where to remember it is a decision: on-device only, or a new field on
     the entry (a server schema change to `EntryIn`/`EntryOut`, which the
     server README says is straightforward, and would sync across
     devices).
   - Digital Asset Links: verify an app's package ↔ website association
     (what Google Password Manager does). Most correct, most work; worth
     it only after the manual flow is proven.

**Suggested stages:** (1) service + locked/unlocked flow + website domain
matching; (2) manual package pairing; (3) save requests; (4) inline
suggestions in the keyboard (Android 11+), optional polish.

**Testing:** needs the real phone (the emulator doesn't run on the dev
machine), with Ratatoskr selected as the autofill service. Chrome only
passes web forms to third-party autofill services once "Autofill using
another service" is enabled in its settings. Test against a sample login
app and a few real sites.

**Play Store caveat** (for later): autofill services get extra review
scrutiny on Play. GitHub Releases distribution isn't affected.

Distribution: GitHub Releases APK first. Play Store is a later, deliberate
decision — a password manager requesting autofill/accessibility
permissions gets real scrutiny in Play's review process; not a v1
assumption.

### Before Phase 4/5: decisions that affect both

- **Release tags for the desktop app.** Today `linux-vX.Y.Z` releases
  the Linux packages, versioned by `ratatoskrDesktopVersion`. Windows and
  macOS are the *same app*, so either (a) add `windows-v*`/`macos-v*` tags
  with their own workflows, all reading `ratatoskrDesktopVersion`, or
  (b) switch to one `desktop-vX.Y.Z` tag whose workflow runs a Linux +
  Windows + macOS matrix into one release. (b) keeps the three in step
  and is simpler to maintain, but each release then carries every OS's
  files (still clearly named). Decide before writing the workflows.
- **Nobody has a Windows machine or Mac for testing yet** (as of the
  Linux/Android releases). CI can build everything (`workflow_dispatch`
  produces downloadable artifacts without releasing), but someone needs
  to install and click through each build before its first release: a
  borrowed machine, a VM, or a friend.
- **jpackage builds only for the OS it runs on**, so there's no
  cross-compiling from Linux. Windows needs a `windows-latest` runner,
  macOS a `macos-latest` runner.

### Desktop session storage (all three desktop OSes)

`DesktopSessionStore` (in `clients/desktop`) already has the per-OS
config paths and stores the server address. To also keep people signed in
across launches, the token must go into the OS's secret store (the
Android equivalent, `KeystoreSessionStore`, is the model), and **must not
be written to disk in plain text**:

- **Windows:** DPAPI. JNA's `jna-platform` has `Crypt32Util.cryptProtectData`
  / `cryptUnprotectData`; encrypt the token for the current user and keep
  the ciphertext next to `settings.properties` in `%APPDATA%\Ratatoskr`.
  Small, and JNA is well maintained.
- **macOS:** the login Keychain, simplest via the built-in
  `/usr/bin/security add-generic-password` / `find-generic-password`
  (or JNA to the Security framework).
- **Linux:** libsecret over D-Bus (the Secret Service API that KDE Wallet
  and GNOME Keyring both provide), e.g. via `secret-tool` or a D-Bus
  library. Handle "no keyring available" by falling back to today's
  behaviour (address only).
- The all-in-one `java-keyring` library covers all three, but its last
  release was 1.0.4 in 2023; check its state before depending on it.

The shared code needs no change: `AppState` already restores a token
when the `SessionStore` returns one.

### Phase 4 — Windows

Mostly packaging on the desktop app that already exists; the Compose
Desktop DSL in `clients/desktop/build.gradle.kts` already lists
`TargetFormat.Msi`/`Exe` and a `windows {}` block.

**Must be settled before the first Windows release (permanent after):**

- **`upgradeUuid`** in `windows {}`: a fixed, randomly generated UUID.
  Without it, each MSI installs *alongside* the previous version instead
  of upgrading it. Generate once (`uuidgen`), commit it, never change it.
- Keep `perUserInstall = true` (no admin rights needed to install), and
  set `shortcut = true` / `menu = true` so it appears in the Start menu
  under `menuGroup`.

**To do:**

- **Icon:** replace the placeholder PNG with a real `.ico` holding 16–256
  px sizes, e.g. `magick clients/desktop/icons/ratatoskr_512.png -define
  icon:auto-resize=256,128,64,48,32,16 clients/desktop/icons/ratatoskr.ico`.
- **Installer toolchain:** jpackage's `.msi`/`.exe` need the WiX Toolset.
  JDK 21's jpackage expects WiX 3.x (support for WiX 4+ only arrived in
  later JDKs). Check what the `windows-latest` runner image provides, and
  install WiX 3 in the workflow if needed.
- **Version format:** MSI versions are `MAJOR.MINOR.BUILD`, each part with
  a limited range. Check jpackage accepts the current `0.x.y` on Windows
  (macOS doesn't — see Phase 5) before tagging.
- **Workflow:** `windows-latest` runner, same shape as the Linux one:
  Temurin 21 (has jmods), `./gradlew :shared:jvmTest`, then
  `:desktop:packageMsi` (and/or `packageExe`), `SHA256SUMS`, release.
  Run the Gradle steps under `shell: bash` so the existing scripts'
  idioms still work.
- **Check on a real Windows machine:** installs without admin, Start menu
  entry and icon, the taskbar icon while running, upgrade over the
  previous version (the `upgradeUuid` test), uninstall, and that
  `%APPDATA%\Ratatoskr\settings.properties` remembers the server.
- **SmartScreen:** unsigned installers get "Windows protected your PC"
  (More info → Run anyway). Fine for personal use; getting rid of it
  needs a code-signing certificate (a paid certificate, or a hosted
  signing service), which is a cost decision for later.

### Phase 5 — macOS

Same pattern: packaging on the existing desktop app. `TargetFormat.Dmg`
and a `macOS {}` block are already in `clients/desktop/build.gradle.kts`.

**Must be settled before the first macOS release (permanent after):**

- **`bundleID`** is still the placeholder `com.ratatoskr.desktop`. Change
  it to the project's app ID, `io.github.curtis04ben.Ratatoskr`, *before*
  the first release; macOS keys preferences, Keychain items and
  permissions on it.
- **Bundle version:** macOS requires the version's major part to be > 0,
  so `ratatoskrDesktopVersion`'s `0.x.y` can't be used as-is. The build
  file currently hard-codes `packageVersion = "1.0.0"` for macOS as a
  placeholder, which would never change between releases. Pick a rule:
  e.g. move the whole desktop app to 1.0.0 when macOS ships (simplest), or
  derive the macOS version from the desktop one. It must increase with
  every release.

**To do:**

- **Icon:** a real `.icns`. Easiest on the macOS runner: build an
  `.iconset` folder of PNGs (16–1024 px, @1x/@2x) from
  `ratatoskr_512.png`/the SVG and run `iconutil -c icns`.
- **Architecture:** `macos-latest` runners are Apple silicon (arm64), and
  jpackage builds for the runner's architecture. Intel Macs would need
  a separate Intel build (and Intel runner availability is shrinking);
  arm64-only is a reasonable start.
- **Workflow:** `macos-latest`, Temurin 21, tests, `:desktop:packageDmg`,
  `SHA256SUMS`, release.
- **Gatekeeper:** an unsigned, un-notarized app downloaded from the web
  is blocked on first open ("cannot be opened because the developer
  cannot be verified" / "is damaged"). Users can get past it (right-click →
  Open, or `xattr -dr com.apple.quarantine /Applications/Ratatoskr.app`),
  and the release notes must say so. Proper signing + notarization needs
  an Apple Developer account ($99/year); Compose supports it via
  `macOS { signing { } notarization { } }` with the certificate and an
  App Store Connect API key as CI secrets. Decide when this phase starts.
- **Mac conventions worth checking:** the app menu (app name, Quit,
  ⌘Q), ⌘ instead of Ctrl for shortcuts, the Dock icon, and that the
  settings file lands in `~/Library/Application Support/Ratatoskr`.

### Phase 6 — iOS

The newest, least battle-tested part of Compose Multiplatform — expect
more friction here than the other four phases.

Distribution is materially different from everywhere else: no
"download and run" the way desktop/Android allow. Realistically means
Xcode-local builds or TestFlight for personal use, which also means an
Apple Developer account. Worth setting that expectation now so "v1 for
iOS" isn't measured against the same yardstick as the other four.

## Testing note for whoever picks this up next

The client code was first written without a Kotlin compiler available and
checked by static analysis only, including verifying `Totp.kt` against the
RFC 6238 test vectors via a Python transliteration. It has since been
built for real and is exercised by CI on every release:

- `./gradlew :shared:jvmTest` runs the unit tests, including the TOTP test
  vectors; both release workflows run it before building anything.
- The Linux packages were installed and run on Fedora KDE (launcher entry,
  icon, window matching).
- The Android app was tested on a real phone (Galaxy A55, Android 16)
  against a scratch server: setup, creating an entry, repeated
  kill-and-relaunch session restores, and Lock. The Android emulator
  crashed on the development machine's kernel at the time, so real-device
  testing over `adb` is the proven route; see `clients/README.md` for the
  local-server setup.

Not yet exercised on a device: the entry editor's live 2FA code, and CSV
import/export through Android's file picker.
