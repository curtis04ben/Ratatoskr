# Ratatoskr

A self-hosted, multi-user password manager with a growing family of native
clients. Named for the squirrel who runs up and down Yggdrasil carrying
messages between the eagle at its crown and the serpent at its roots.

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
