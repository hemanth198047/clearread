# Phase 1 Exit Package

## What works

1. Compose brings up Postgres, MinIO, Keycloak, API, and web.
2. User signs in via Keycloak (demo/demo), lands in Demo Workspace.
3. Paste or upload Markdown / plain text → original stored immutably → transform job → ClearDoc → basic reader with TOC.
4. Download original matches uploaded/pasted bytes (SHA-256 header + body).
5. Cross-tenant document access returns 404 in automated integration tests.
6. Public health, capabilities, and default branding endpoints.

## Known limitations

- HTML ingest not in Phase 1 (capability registry lists Markdown + plain text only).
- No full conversion/export suite (MD/HTML export is Phase 4; PDF later); original download only.
- Reader typography/a11y polish is basic (Phase 2).
- No URL ingestion, sharing, annotations, search, or admin UI.
- First login auto-joins the seeded Demo workspace (local/dev convenience).
- Keycloak realm is for local/dev; production IdP is any OIDC provider.

## Checks run

Recorded at exit time in the PR / agent summary (must be executed, not claimed).

## Local run

See root [README.md](../../README.md): `./scripts/start.sh` then http://localhost:5173

## Demo media

Screenshots / recordings under the project store `media/phase-1/` (absolute paths in the Phase 1 handoff).
