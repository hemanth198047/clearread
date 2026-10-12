# ClearRead ![version](https://img.shields.io/badge/version-v1.0.3-1a5f4a)

**ClearRead v1.0.3** (temporary product name; branding is configurable).  
Turns Markdown and plain text into structured, accessible reading experiences — **preserves originals**, **no AI required** for the core path, and **honest format support** via a capability registry.

## Prerequisites

- Docker + Docker Compose V2
- (Optional for local hybrid) JDK 21, Node.js 22+
- (Optional object-store backup) AWS CLI

## Quick start (Compose)

```bash
# Linux / macOS
./scripts/start.sh

# Windows (Git Bash / WSL preferred for gradlew LF + execute bit)
scripts\start.bat
```

Build images only (useful when debugging Dockerfile / Gradle download issues):

```bash
docker compose -p clearread -f infra/compose.yaml build api web
docker compose -p clearread -f infra/compose.yaml up -d
curl -sf http://localhost:8080/v1/health
```

If `api` build times out downloading `gradle-*-bin.zip`, pull `gradle:8.14.3-jdk21` and retry, or build on the host with `./gradlew bootJar` — see [docs/ops/compose-build-fix.md](docs/ops/compose-build-fix.md). Exit **127** on `./gradlew` is usually a missing execute bit or CRLF; `.gitattributes` forces LF on `gradlew`.

Then open **http://localhost:5173**

| Service | URL | Notes |
| --- | --- | --- |
| Web | http://localhost:5173 | SPA (`CLEARREAD_WEB_PORT`) |
| API | http://localhost:8080 | REST `/v1`, health `/v1/health`, OpenAPI `/v3/api-docs` (`CLEARREAD_API_PORT`) |
| Keycloak | http://localhost:8081 | Admin `admin` / `admin` (`CLEARREAD_KEYCLOAK_PORT`) |
| S3 (LocalStack) | http://localhost:9000 | Access key `test` / `test` (`CLEARREAD_S3_PORT`) |

### Port conflicts

If Compose reports `port is already allocated` (often **8080**), start scripts **fail fast** with the busy port and how to free or remap it. Copy [`.env.example`](.env.example) to `.env` and set e.g. `CLEARREAD_API_PORT=18080`. Scripts derive CORS + `VITE_API_BASE_URL` from those ports so the SPA still reaches the API. See [troubleshooting](docs/guides/troubleshooting.md).

`scripts/start.sh` starts the Compose stack and, if the API container cannot become healthy, automatically falls back to a host-run API + Vite against the published dependency ports. Health waits **time out** with a clear error (no infinite hang). `scripts/stop.sh` still only stops the ClearRead Compose project and ClearRead-tracked host PIDs.

Windows: `scripts\start.bat` / `stop.bat` / `check.bat` / `backup.bat` / `restore.bat`. The `.bat` start path does **not** include host fallback — if Compose API never becomes healthy (Docker bridge issues), use Git Bash or WSL with `bash scripts/start.sh`. Full matrix: [docs/ops/scripts.md](docs/ops/scripts.md).

**Demo login (Keycloak):** username `demo`, password `demo`

### Checks

```bash
./scripts/check.sh
# or
scripts\check.bat
```

### Stop (this app only)

```bash
./scripts/stop.sh
# or
scripts\stop.bat
```

Stop scripts tear down only the Compose project named `clearread` (and optional ClearRead pid-tracked local processes). They do not stop unrelated containers or system services.

## Documentation

| Guide | Path |
| --- | --- |
| User | [docs/guides/user.md](docs/guides/user.md) |
| Admin | [docs/guides/admin.md](docs/guides/admin.md) |
| Deployment | [docs/guides/deployment.md](docs/guides/deployment.md) |
| Backup / restore | [docs/guides/backup-restore.md](docs/guides/backup-restore.md) |
| Troubleshooting | [docs/guides/troubleshooting.md](docs/guides/troubleshooting.md) |
| Format support | [docs/format-support.md](docs/format-support.md) |
| Security | [docs/guides/security.md](docs/guides/security.md) |
| Operator runbook | [docs/phase-6/operator-runbook.md](docs/phase-6/operator-runbook.md) |
| Changelog | [CHANGELOG.md](CHANGELOG.md) |

## Repository layout

| Path | Purpose |
| --- | --- |
| `frontend/` | React + TypeScript + Vite SPA |
| `backend/` | Java 21 + Spring Boot 4.1.1 modular monolith |
| `infra/` | Docker Compose, Keycloak realm |
| `scripts/` | start / stop / check / backup / restore (`.sh` + `.bat`) |
| `docs/` | Guides, phase exit packages, ops |
| `examples/` | Sample Markdown / plain text / structured fixtures |
| `schemas/` | Reserved for ClearDoc JSON Schema |

## Local hybrid development

1. Start dependencies only:

```bash
./scripts/start-deps.sh
```

2. API: `cd backend && ./gradlew bootRun`
3. Web: `cd frontend && npm install && npm run dev`

Environment defaults are in `backend/src/main/resources/application.yml` and Vite `VITE_*` vars (see `frontend/.env.example`).

## Essential tests

```bash
# Backend unit + Testcontainers integration (ITs skip automatically if Docker is missing)
cd backend && ./gradlew test

# Frontend unit
cd frontend && npm test

# Playwright browser smokes (requires running stack — see frontend/e2e/README.md)
cd frontend && npm run test:e2e:install && npm run test:e2e
```

## Backup

```bash
./scripts/backup.sh                  # Postgres + MANIFEST
./scripts/backup.sh --with-objects   # also sync s3://$BUCKET/tenants (AWS CLI)
```

Windows Postgres: `scripts\backup.bat`. Object sync: Git Bash `bash scripts/backup.sh --with-objects` or see [backup-restore](docs/guides/backup-restore.md).

## Release status

**v1.0.3** — patch on **v1.0.0 FINAL** (API Docker Gradle native-platform / BuildKit cache fix; v1.0.2 Gradle base image; v1.0.1 port-conflict fail-fast). Phases 1–6 + acceptance + post-acceptance hardening. See [CHANGELOG.md](CHANGELOG.md).

| Phase | Docs |
| --- | --- |
| 1 Vertical slice | [docs/phase-1/](docs/phase-1/) |
| 2 Reader experience | [docs/phase-2/](docs/phase-2/) |
| 3 URL + formats | [docs/phase-3/](docs/phase-3/) |
| 4 Org workflows | [docs/phase-4/](docs/phase-4/) |
| 5 Optional AI | [docs/phase-5/](docs/phase-5/) |
| 6 Production hardening | [docs/phase-6/](docs/phase-6/) |

### Optional AI (Phase 5)

AI is **off by default**. Enable in Admin → Optional AI enrichment (mock-local for demos). See [docs/phase-5/exit-package.md](docs/phase-5/exit-package.md).

## Principles

1. Preserve original bytes (checksummed, downloadable).
2. Derived ClearDoc structure carries provenance (`source` / `inferred`).
3. Capability registry is the only honest claim of format support.
4. Conversion/export to other readable formats (MD + HTML + constrained PDF); originals remain separate.
5. AI is optional and never required for the core reader path.
