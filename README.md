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
| S3 (LocalStack) | http://localhost:9000 | Access key `test` / `test` (S3 API on mapped port) |

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

See [docs/phase-1/status.md](docs/phase-1/status.md). Phase 0 package (approvals, architecture) lives in the project agent store; this repo implements Phase 1.

**Do not start Phase 2 until Phase 1 exit is approved.**

## Principles

1. Preserve original bytes (checksummed, downloadable).
2. Derived ClearDoc structure carries provenance (`source` / `inferred`).
3. Capability registry is the only honest claim of format support.
4. Conversion/export to other readable formats is a product requirement (MD + HTML in Phase 4; PDF later) — not implemented as a full suite in Phase 1.
