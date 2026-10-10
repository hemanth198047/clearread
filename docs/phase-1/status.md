# Phase 1 — Status

**Status:** In progress / exit package pending  
**Authorized:** 2026-10-10 (Phase 0 approved; Q1–Q8 defaults accepted)  
**Gate:** Do **not** start Phase 2 until this exit package is approved.

## Delivered in this slice

- Repository structure (`frontend/`, `backend/`, `infra/`, `scripts/`, `docs/`, `examples/`)
- Docker Compose: Postgres 16, MinIO, Keycloak 26.8, API, web
- Flyway baseline for tenants/users/memberships/documents/sources/revisions/jobs/audit
- OIDC resource-server auth + SPA PKCE; workspace context via membership + `X-Tenant-Id`
- Health/readiness (`/v1/health`, actuator probes)
- Configurable branding (`/v1/branding/default`)
- Upload + paste ingest (Markdown, plain text); 10 MiB default limit
- Immutable originals in S3/MinIO with SHA-256
- ClearDoc IR via CommonMark/GFM + plain-text heuristic adapters
- Basic reader with TOC
- Export module **seam** only (full MD/HTML conversion in Phase 4)
- Essential automated tests (unit + integration with tenant-isolation negative case)
- Windows `.bat` + Unix shell start/stop/check scripts

## Explicitly deferred

- Reader polish (Phase 2)
- URL ingest + broader formats (Phase 3)
- Library/search/annotations/sharing/**export converters**/admin (Phase 4)
- Optional AI (Phase 5)
- Production hardening (Phase 6)
