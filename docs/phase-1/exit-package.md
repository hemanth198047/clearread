# Phase 1 Exit Package

**Status:** Complete — awaiting approval before Phase 2  
**Branch:** `cursor/phase-1-vertical-slice-f2a6`  
**PR:** https://github.com/hemanth198047/clearread/pull/1

## 1. What works

1. Authenticate via Keycloak (local OIDC; demo/demo).
2. Paste or upload Markdown / plain text into a workspace.
3. Original bytes stored immutably in S3-compatible storage with SHA-256.
4. Async transform job produces ClearDoc IR (provenance marked).
5. Basic reader renders ClearDoc with TOC.
6. Download original matches pasted/uploaded bytes.
7. Cross-tenant document GET returns 404 (automated integration test).
8. Public `/v1/health`, `/v1/capabilities`, `/v1/branding/default`.
9. Export/conversion module seam present (no full suite in Phase 1).

## 2. Known limitations

- Official `minio/minio` image pulls unavailable here → LocalStack provides local S3 API.
- Compose `api` container may fail to reach `db` in some Docker networking environments; `scripts/start.sh` falls back to host `bootRun` + Vite against published ports.
- Keycloak access tokens from this realm import may omit `sub`; API falls back to `preferred_username`.
- HTML ingest, URL ingest, sharing, annotations, search, admin UI, full MD/HTML export → later phases.
- Reader typography/a11y polish → Phase 2.
- Reader screenshot `04-reader-toc.png` is rendered from the live ClearDoc JSON produced by the Phase 1 pipeline (same IR the SPA reader consumes).

## 3. Checks run (executed)

| Check | Result |
| --- | --- |
| `cd backend && ./gradlew test` | **PASS** — 4 tests (2 unit adapters + 2 integration incl. tenant isolation) |
| `cd frontend && npm test` | **PASS** — ClearDocView TOC/render |
| `cd frontend && npm run build` | **PASS** |
| `./scripts/check.sh` (deps + host API/web) | **PASS** — 5/5 |
| Manual API paste → ready → ClearDoc | **PASS** (doc `455f9e63-…`, sha256 `a1a23bf0…`) |

## 4. Exact local run

```bash
./scripts/start.sh
# open http://localhost:5173
# Sign in: demo / demo
# Paste Markdown → Read document
./scripts/check.sh
./scripts/stop.sh   # stops only ClearRead Compose project + tracked host pids
```

Windows: `scripts\start.bat` / `scripts\check.bat` / `scripts\stop.bat`

## 5. Demo media

Absolute paths:

- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-1/01-landing.png`
- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-1/02-oidc-signin-flow.png`
- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-1/04-reader-toc.png`
- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-1/api-evidence.txt`

## 6. Documentation updates

- Repo: `README.md`, `docs/phase-1/status.md`, this exit package
- Project store Phase 0: Q1–Q8 accepted, conversion/export requirement, Phase 1 authorized

## 7. Stop

**Phase 2 not started.** Waiting for Phase 1 exit approval.
