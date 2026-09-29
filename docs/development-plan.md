# Ratatoskr development plan

This documents the architecture decisions behind the multi-client Ratatoskr
project and the planned approach for each native client. It's meant as
durable context for whoever (human or AI assistant) picks up client work
next — not a rigid spec, more a record of *why* things are shaped the way
they are, so that reasoning doesn't have to be reconstructed from scratch
each time.

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
- ⬜ Settings/logout beyond the basic Lock button
- ⬜ Clean handling of every disconnect/error edge case (some exists via
  `RatatoskrConnectionException`/`RatatoskrApiException`, not exhaustively
  tested against real network conditions)

**Deliberately deferred as fast-follows, not oversights**: sharing
(entries can be shared between users on the server, but there's no Share
dialog in the client yet), the admin users panel (invite creation, role
changes — admin can currently only do these via the web UI), and CSV
import/export. These are real gaps, not forgotten — they're sequenced
after the core loop is solid rather than blocking a first usable version.

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
  `PlatformFiles`; desktop still passes none), implemented on Android as
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

Worth a stretch-goal look once the core app is solid: Android's system
Autofill Framework — filling native app logins, not just browser forms,
a genuinely different integration point from the (separate) browser
extension project.

Distribution: GitHub Releases APK first. Play Store is a later, deliberate
decision — a password manager requesting autofill/accessibility
permissions gets real scrutiny in Play's review process; not a v1
assumption.

### Phase 4 — Windows

Mostly packaging + platform glue on the desktop app that already exists
from Phase 2: `.msi`/`.exe` via `jpackage`, Credential Manager/DPAPI for
secure storage.

**CI-relevant fact to remember**: `jpackage` installers must be built *on*
the target OS. No cross-compiling a Windows installer from Linux — a real
`windows-latest` GitHub Actions runner will be needed in the eventual CI
matrix, not a shortcut.

The desktop module's icon config currently points at the PNG for all
three OS targets as a placeholder; Windows packaging needs a real `.ico`
conversion before this phase ships.

### Phase 5 — macOS

Same pattern: packaging + glue on the existing desktop app. `.dmg` via
`jpackage`, Keychain for storage. Needs a `macos-latest` CI runner for the
same cross-compilation reason as Windows, and a real `.icns` conversion
(same placeholder-PNG caveat as Windows' `.ico`).

Worth flagging now rather than at the surprise stage: an unsigned build
triggers Gatekeeper warnings on other people's Macs. Fine for personal
use; a smoother "stranger downloads it and it just works" experience
needs an Apple Developer account ($99/year) — a real decision point when
this phase starts.

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
