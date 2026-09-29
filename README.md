# Ratatoskr

A self-hosted, multi-user password manager with a growing family of native
clients. Named for the squirrel who runs up and down Yggdrasil carrying
messages between the eagle at its crown and the serpent at its roots.

## Downloads

The Linux desktop client is published on the
[Releases page](https://github.com/curtis04ben/Ratatoskr/releases) as
releases named "Ratatoskr Linux client" (tags `linux-v*`):

- **Fedora / RHEL:** `ratatoskr-<version>-1.x86_64.rpm` → `sudo dnf install ./ratatoskr-*.rpm`
- **Debian / Ubuntu:** `ratatoskr_<version>_amd64.deb` → `sudo apt install ./ratatoskr_*.deb`
- **Any distro, no install:** `Ratatoskr-x86_64.AppImage`

Once installed, Ratatoskr appears in your application launcher. No Java
install is needed. The client connects to a Ratatoskr server you host
yourself; see [`server/README.md`](server/README.md).

## Repository structure

```
server/      FastAPI backend + web UI — the core, self-hosted component.
             See server/README.md for deployment (TrueNAS, CasaOS, plain Docker).

clients/     Native clients (Kotlin Multiplatform + Compose Multiplatform).
             clients/shared/  — portable API client, models, and UI screens
             clients/desktop/ — Compose Desktop app (builds Linux/Windows/macOS installers)

shared/assets/   Master branding (logo) that every platform's icon derives from.

docs/        Project documentation, including the full multi-client
             architecture and development plan.
```

**Full details on the client architecture, technology choices, and the
per-platform build plan (Linux → Android → Windows → macOS → iOS) are in
[`docs/development-plan.md`](docs/development-plan.md).** Start there if
you're picking up client work.

For running the server itself, see [`server/README.md`](server/README.md).
