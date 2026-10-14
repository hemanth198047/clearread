# Changelog

All notable changes to ClearRead are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.3] — 2026-10-14

### Fixed

- **Detailed segmentation blank page:** Structure → Detailed always keeps a non-empty ClearDoc body (mode-key normalization, empty-gate hardening, scroll reset on Structure change). Unit tests fail if Detailed empties a non-empty document.

### Added

- **Keycloak login theme** under `infra/keycloak/theme/clearread/` (Rebeautifier slate/amber, Fraunces/Literata) mounted in Compose; realm `loginTheme: clearread`.
- Dense Rebeautifier-aligned SPA auth chrome for OIDC callback, landing sign-in, and signed-out Library/Reader/Admin/Invite gates.

### Changed

- Docs: verify path for Detailed + sign-in in release notes / store handoff.

## [1.2.2] — 2026-10-14

### Fixed

- **Left local file menu missing after scan:** Root cause — when `GET /v1/capabilities` failed (API down / 429), the scan filter index was empty so every file was dropped, and the vertical menu only mounted on Library (easy to miss after Settings). ClearRead now uses a client-side extension fallback, keeps the local list visible when the API is unreachable, and shows a persistent **Local files** rail on **Library** and **Ingest/Home** via a shared provider.
- **Recent documents “Too many requests”:** Client cache + in-flight dedupe + exponential backoff on 429 for `GET /v1/documents`; rate-limit filter no longer counts bootstrap GETs (`/v1/capabilities`, `/v1/branding/*`).

### Changed

- Preferred-folder scans walk **all subfolders to the leaves** (max depth 20, max 5000 files, skip `node_modules` / `.git` / build dirs). Left menu groups by folder path.
- Docs: [docs/folder-scan-library.md](docs/folder-scan-library.md) and user guide (v1.2.2).

## [1.2.1] — 2026-10-14

### Fixed

- **Scan on folder select:** After **Add folder…** (File System Access or `webkitdirectory` fallback), ClearRead scans immediately with a **Scanning…** state, updates last-scan time, and refreshes the Library left file menu — no wait for the next app start. Capability formats are loaded before filtering so the extension index is not empty. Permission / inaccessible errors surface in Settings. Startup and Library-load re-scan behavior unchanged.

### Changed

- Docs: [docs/folder-scan-library.md](docs/folder-scan-library.md) and user guide note instant scan-on-select (v1.2.1).

## [1.2.0] — 2026-10-14

### Added

- **Local folder library:** Settings for preferred folders; client-side scan via File System Access API (Chrome/Edge) with `webkitdirectory` fallback; IndexedDB persistence for directory handles and file metadata.
- **Library left file menu:** Vertical list of discovered files grouped by folder; open a file → existing upload ingest → reader (soft-dedup preserved). List-only until selection; 10 MiB default size filter; capability-registry extension/MIME matching.
- Re-scan control, last scan time, inaccessible-folder errors, and empty states (no folders / permission denied / no matches).
- Unit tests for file filter / capability matching; docs in [docs/folder-scan-library.md](docs/folder-scan-library.md) and user guide.

### Notes

- Browsers cannot silently scan arbitrary disks; Docker API sees only mounted volumes (documented; no server scan UI in v1.2.0).
- Safari/Firefox: fallback picker — re-add folders after reload to refresh/open files.

## [1.1.4] — 2026-10-14

### Changed

- **Repo rename complete:** GitHub is **`hemanth198047/clearread`** (formerly `mdbeautifier`). Docs, clone URLs, badges, and phase PR permalinks use the new name; [docs/ops/repo-rename.md](docs/ops/repo-rename.md) records remotes and the former name. Java package remains `com.clearread`.

## [1.1.3] — 2026-10-13

### Fixed

- **Flyway checksum mismatch:** Restored `V1__baseline.sql` demo branding seed to the originally applied colors (`#1a5f4a` / `#c4a35a`). v1.1.2 incorrectly edited applied V1 when adding Rebeautifier colors; those belong only in `V7__rebeautifier_branding.sql`. Existing local DBs validate again; new installs still get Rebeautifier colors via V7 after V1.
- Production / all profiles keep `spring.flyway.validate-on-migrate=true` (no silent repair).

### Added

- Dev-only `scripts/repair-flyway.sh` / `scripts/repair-flyway.bat` — one-shot `flyway/flyway` container against Compose Postgres (updates `flyway_schema_history` checksums). Use when a local DB was repaired against the broken V1 or otherwise diverged.
- Troubleshooting + scripts matrix docs for checksum recovery (repair vs wipe volume `clearread_clearread_pg`).

### Notes

- Target GitHub repo name: **`hemanth198047/clearread`** (completed in 1.1.4; old `mdbeautifier` URL redirects).

## [1.1.2] — 2026-10-13

### Changed

- **Rebeautifier theme:** App chrome aligned to [Rebeautifier](https://github.com/hemanth198047/Rebeautifier) — slate primary `#1e293b`, amber accent `#f59e0b`, Fraunces / Literata / Newsreader, light sticky topbar (replacing dark Projectly sidebar), dense panels / meta strips / pill controls, atmospheric page background.
- **Branding defaults:** API / SPA defaults use Rebeautifier slate + amber; DB Demo Workspace update via `V7__rebeautifier_branding.sql` (do not edit applied V1 — corrected in 1.1.3).
- **Reader accents:** Day / Sepia / Night modes preserved; Sepia accent mapped to amber-deep `#D97706`; Day accent `#2563EB`; Night sky accent unchanged.

## [1.1.1] — 2026-10-13

### Fixed

- **Recently opened sort:** Reader now calls `POST /v1/documents/{id}/opened` after a successful open so Library sort by “Recently opened” updates (gap found in UI revamp exploration).

## [1.1.0] — 2026-10-13

### Fixed

- **Failed to fetch / library reopen:** SPA defaults to same-origin `/v1` (Vite proxy + nginx→`api`). Compose web nginx proxies `/v1` and `/actuator` so remapped API host ports no longer break browser fetches. Network errors show actionable guidance (ports, start stack) instead of raw `TypeError: Failed to fetch`. Recent links use React Router `Link` (no hard-nav stale state).
- **Stuck on Completing sign-in:** OIDC callback surfaces errors with Retry; successful callback uses full navigation so React Router leaves `/auth/callback`. Return-to path preserved across login/register.
- **Detailed segmentation empty:** Detailed always returns the full body; Balanced/Conservative fall back to full body when a filter would incorrectly empty a non-empty document; empty state only when body is truly empty.
- **Overlapping layout:** Reader uses a dedicated stage (export bar + isolated reader grid); app chrome hidden in reader/preview; sticky stacking/z-index cleaned up for mobile and desktop.

### Changed

- **UI revamp (dense chrome):** Projectly-inspired sidebar, sticky topbar, status bars, denser ingest/library/admin/invite/share surfaces. ClearRead forest/teal branding retained (not Projectly indigo). Reader Day/Sepia/Night tokens unchanged.

### Added

- Auth callback page with error + retry; API client `formatApiError` / same-origin base resolution; unit coverage for segmentation Detailed and API error formatting.

## [1.0.8] — 2026-10-12

### Fixed

- **Original view empty:** Reader scroll position is stored per view mode (Read / Original / Compare). Switching to Original no longer re-applies Read scroll, which scrolled short `.md` sources out of view. Original/Compare also show explicit loading and error empty states instead of a blank pane.
- **Re-upload same file:** Uploading identical bytes returns the existing document (`duplicateOfExisting`) and opens it — no hard failure. Optional “upload as new document” (`forceNew`) still creates a second copy. File input resets after upload so the same path can be chosen again on Windows.

### Added

- Integration coverage for duplicate upload soft-dedup + force-new; frontend tests for Original pane content, loading/error states, and scroll isolation.

## [1.0.7] — 2026-10-12

### Fixed

- **P0 local-run:** Detect containers stuck in Docker **`Created`** (never started / empty logs) — usually a wedged Docker Desktop / WSL engine, not ports or Keycloak.
- `start-deps` no longer hangs unbounded on `compose up`: Windows PowerShell timeout + Unix `timeout`; fail if Status stays `Created` past `CLEARREAD_CREATED_STUCK_SECONDS` (default 45s) with `docker inspect` State + recovery steps.
- Loud doctor diagnosis when Status=`Created` with empty logs; documents that if `docker start <id>` itself hangs, only Docker Desktop quit + `wsl --shutdown` fixes it.

### Added

- Root convenience wrappers: `stop.bat`, `start.bat`, `doctor.bat` → `scripts\*.bat` (so `.\stop.bat` from repo root works).
- Env knobs: `CLEARREAD_COMPOSE_UP_TIMEOUT` (default 90), `CLEARREAD_CREATED_STUCK_SECONDS` (default 45).
- Docs section: [Containers stuck in Created](docs/ops/compose-deps-fix.md#containers-stuck-in-created).

## [1.0.6] — 2026-10-12

### Fixed

- **P0 local-run:** Compose deps (`db`, LocalStack/`minio`, Keycloak) start reliably on Docker Desktop (Windows).
- Keycloak healthcheck uses explicit `/bin/bash` + `/dev/tcp` on management `:9000` (UBI image has no curl; plain `sh` `/dev/tcp` never went healthy).
- Removed Keycloak hard `mem_limit: 768m` that OOM-killed first-boot realm import.
- Default host Postgres port is **5433** (`CLEARREAD_DB_PORT`) so Windows local Postgres on **5432** no longer blocks all deps from starting.
- LocalStack healthcheck uses Python urllib (curl fallback); start scripts bring deps up **one service at a time** with `ps` + `logs --tail 80` on failure.
- API `depends_on` Keycloak is `service_started` (not healthy) so slow IdP does not block container create; scripts still wait for OIDC discovery.

### Added

- `scripts/doctor.sh` + `scripts/doctor.bat` — Docker running?, Compose V2, port conflicts, `compose ps`, last logs for db/minio/keycloak, clear next action.
- `scripts/start-deps.bat` (Windows deps-only path matching `start-deps.sh`).
- Keycloak management port published as `CLEARREAD_KEYCLOAK_MGMT_PORT` (default **9002**).
- Docs: [docs/ops/compose-deps-fix.md](docs/ops/compose-deps-fix.md).

### Changed

- Object storage remains Compose service name `minio` but is documented as **LocalStack S3** (image `localstack/localstack:4.3`).

## [1.0.5] — 2026-10-12

### Fixed

- Local Keycloak startup is faster and more predictable on Docker Desktop: persistent data volume (realm import only on first boot), real `/health/ready` healthcheck, heap cap to reduce thrashing.
- Start scripts (`start.sh` / `start.bat` / `start-deps.sh`) wait for Keycloak with visible progress and fail after **240s** with clear tips instead of hanging forever.

### Added

- Docs: [docs/ops/keycloak-startup.md](docs/ops/keycloak-startup.md) — expected first-boot vs warm-start times, volume wipe, Windows retry steps.
- Optional `CLEARREAD_KEYCLOAK_WAIT_SECONDS` (default 240).

### Changed

- Compose `api` waits for Keycloak **healthy** before starting (relaxed to `service_started` in **v1.0.6**).

## [1.0.4] — 2026-10-12

### Added

- Seeded Keycloak demo accounts: **admin** / **admin** (workspace admin), **user** / **user** (author), plus legacy **demo** / **demo**.
- Keycloak realm **self-registration** enabled for local Compose; SPA **Create account** opens Keycloak registration (OIDC PKCE).
- Landing / sign-in copy for Sign in, Create account, and Demo accounts (gated by `CLEARREAD_SHOW_DEMO_ACCOUNTS`).
- First-login provisioning maps seeded usernames to roles; new signups join Demo Workspace as **author** (or accept a pending invite role).
- Workspace admin **invite user** (email + role) stores a pending invite and copies an invite deep link (no outbound email).

### Changed

- Docs / start scripts (`.sh` + `.bat`) document admin/user credentials and signup steps.

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
