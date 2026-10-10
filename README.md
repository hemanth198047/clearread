# ClearRead

Temporary product name: **ClearRead** (branding is configurable).  
Turns Markdown and plain text into structured, accessible reading experiences — **preserves originals**, **no AI required** for the core path, and **honest format support** via a capability registry.

Phase 1 delivers a working vertical slice: authenticate → paste/upload → immutable store → parse → basic reader.

## Prerequisites

- Docker + Docker Compose V2
- (Optional for local hybrid) JDK 21, Node.js 22+

## Quick start (Compose)

```bash
# Linux / macOS
./scripts/start.sh

# Windows
scripts\start.bat
```

Then open **http://localhost:5173**

| Service | URL | Notes |
| --- | --- | --- |
| Web | http://localhost:5173 | SPA |
| API | http://localhost:8080 | REST `/v1`, health `/v1/health` |
| Keycloak | http://localhost:8081 | Admin `admin` / `admin` |
| S3 (LocalStack) | http://localhost:9000 | Access key `test` / `test` (S3 API; substitutes for MinIO when Hub pulls are blocked) |

`scripts/start.sh` starts the Compose stack and, if the API container cannot become healthy, automatically falls back to a host-run API + Vite against the published dependency ports. `scripts/stop.sh` still only stops the ClearRead Compose project and ClearRead-tracked host PIDs.

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

## Repository layout

| Path | Purpose |
| --- | --- |
| `frontend/` | React + TypeScript + Vite SPA |
| `backend/` | Java 21 + Spring Boot 4.1.1 modular monolith |
| `infra/` | Docker Compose, Keycloak realm |
| `scripts/` | start / stop / check (`.sh` + `.bat`) |
| `docs/` | Phase status and references |
| `examples/` | Sample Markdown / plain text |
| `schemas/` | Reserved for ClearDoc JSON Schema |

## Local hybrid development

1. Start dependencies only:

```bash
docker compose -p clearread -f infra/compose.yaml up -d db minio minio-init keycloak
```

2. API:

```bash
cd backend
./gradlew bootRun
```

3. Web:

```bash
cd frontend
npm install
npm run dev
```

Environment defaults are in `backend/src/main/resources/application.yml` and Vite `VITE_*` vars (see `frontend/.env.example`).

## Essential tests

```bash
# Backend unit + Testcontainers integration
cd backend && ./gradlew test

# Frontend unit
cd frontend && npm test
```

## Phase status

Phases **1–6** are implemented on this repository:

| Phase | Docs |
| --- | --- |
| 1 Vertical slice | [docs/phase-1/](docs/phase-1/) |
| 2 Reader experience | [docs/phase-2/](docs/phase-2/) |
| 3 URL + formats | [docs/phase-3/](docs/phase-3/) |
| 4 Org workflows | [docs/phase-4/](docs/phase-4/) |
| 5 Optional AI | [docs/phase-5/](docs/phase-5/) |
| 6 Production hardening | [docs/phase-6/](docs/phase-6/) |

Phase 0 architecture package lives in the project agent store.

### Optional AI (Phase 5)

AI is **off by default**. Enable in Admin → Optional AI enrichment (mock-local for demos). See [docs/phase-5/exit-package.md](docs/phase-5/exit-package.md).

### Operators (Phase 6)

See [docs/phase-6/operator-runbook.md](docs/phase-6/operator-runbook.md). Backup: `./scripts/backup.sh`.

## Principles

1. Preserve original bytes (checksummed, downloadable).
2. Derived ClearDoc structure carries provenance (`source` / `inferred`).
3. Capability registry is the only honest claim of format support.
4. Conversion/export to other readable formats (MD + HTML); PDF later.
5. AI is optional and never required for the core reader path.
