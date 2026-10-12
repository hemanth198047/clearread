# Changelog

All notable changes to ClearRead are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.3] — 2026-10-12

### Fixed

- API Docker build no longer mounts BuildKit cache over the entire Gradle user home (avoids Windows Docker Desktop `Failed to load native library 'libnative-platform.so'` / `Could not initialize native services`). Build stage is **`eclipse-temurin:21-jdk`** with Gradle copied from **`gradle:8.14.3-jdk21`** (`/opt/gradle`); caches only `caches/` + `wrapper/`.

### Changed

- `start.sh` / `start.bat` distinguish `libnative-platform.so` failures (suggest `docker builder prune` + `--no-cache`) from Gradle zip download timeouts.
- Docs: `docs/ops/compose-build-fix.md` covers the native-platform cache issue.

## [1.0.2] — 2026-10-12

### Fixed

- API Docker build no longer downloads the Gradle distribution zip during `docker build` (avoids Windows / flaky-network `SocketTimeoutException` / `timeout (10000ms)` on `gradle-8.14.3-bin.zip`). Build stage used official **`gradle:8.14.3-jdk21`** (refined in **v1.0.3** to Temurin + `/opt/gradle` copy and narrower cache mounts).

### Changed

- Wrapper `networkTimeout` raised to **120000** ms; `backend/gradle.properties` sets Gradle HTTP connection/socket timeouts for host builds.
- Docs + `start.sh` / `start.bat` error hints cover Gradle download timeout (not only exit 127).

## [1.0.1] — 2026-10-12

### Fixed

- Start scripts **fail fast** when published Compose ports are already allocated (no infinite “Waiting for API health…” hang).
- Health wait aborts early if the `api` container exits; timeout prints a clear error and points at `./scripts/stop.sh`.

### Added

- Configurable host ports via repo-root `.env` / `.env.example`: `CLEARREAD_API_PORT`, `CLEARREAD_WEB_PORT`, `CLEARREAD_KEYCLOAK_PORT`, `CLEARREAD_DB_PORT`, `CLEARREAD_S3_PORT`.
- Start scripts derive `VITE_API_BASE_URL`, CORS origins, and OIDC issuer URLs from those ports; web image CSP `connect-src` follows API/OIDC origins at build time.
- Troubleshooting + deployment docs for port conflicts (Linux `ss`/`lsof`, Windows `netstat` / `Get-NetTCPConnection`).

## [1.0.0] — 2026-10-12

### Added

- Formal **v1.0.0** product release (backend, frontend, OpenAPI `info.version`).
- User, admin, deployment, backup/restore, troubleshooting, format-support, and security guides under `docs/guides/` and `docs/`.
- Operator scripts matrix (`docs/ops/scripts.md`) for Windows + Unix green paths.
- Optional object-store backup via `./scripts/backup.sh --with-objects` (AWS CLI → LocalStack/MinIO/S3).
- Playwright browser smoke suite (`frontend/e2e/`) for paste→read, upload→read, share create/revoke, export download, and theme switch.
- Release checklist / go-live updates for FINAL v1.0.

### Changed

- Package versions: backend `1.0.0`, frontend `1.0.0` (was `0.1.0` / `0.1.0-SNAPSHOT`).
- Testcontainers integration tests use `disabledWithoutDocker = true` so unit suites remain green without Docker.

### Security

- Mermaid remains a **non-executing** safe path (source + size-limited text outline only).
- AsciiDoc and reStructuredText stay **unsupported honesty stubs** (detect → 415); no parser engines in v1.0.

### Out of scope (final for v1.0)

- Office ingest, OCR, PDF ingest, collaborative realtime editing, Math/KaTeX, live Mermaid diagram engines, real AsciiDoc/RST adapters.

## [0.1.0] — 2026-10-10

### Added

- Phases 1–6 vertical slice through production hardening (auth, ingest, reader, org workflows, optional AI, ops).
- Post-acceptance hardening (PRs #5–#6): Mermaid text fallback, registry honesty stubs, SSRF denials, Windows DX scripts.
