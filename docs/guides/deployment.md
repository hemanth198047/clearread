# Deployment guide (ClearRead v1.0)

## Local Compose (recommended starting point)

```bash
./scripts/start.sh          # Linux / macOS / Git Bash / WSL
# Windows cmd: scripts\start.bat
./scripts/check.sh
```

Open http://localhost:5173 — API http://localhost:8080.

| Service | Default host port | Env override | Notes |
| --- | --- | --- | --- |
| Web | 5173 | `CLEARREAD_WEB_PORT` | SPA (nginx in Compose image) |
| API | 8080 | `CLEARREAD_API_PORT` | Spring Boot `/v1` |
| Keycloak | 8081 | `CLEARREAD_KEYCLOAK_PORT` | Realm `clearread` |
| Postgres | 5432 | `CLEARREAD_DB_PORT` | Flyway on boot |
| Object store | 9000 | `CLEARREAD_S3_PORT` | LocalStack S3 API |

Copy [`.env.example`](../../.env.example) → `.env` to remap published ports when the defaults are busy. Start scripts export matching `VITE_API_BASE_URL` / `CLEARREAD_CORS_ORIGINS` so the SPA and CORS stay aligned.

Scripts matrix: [ops/scripts.md](../ops/scripts.md). Compose build (exit 127 / Gradle download timeout): [ops/compose-build-fix.md](../ops/compose-build-fix.md). Port conflicts: [troubleshooting](./troubleshooting.md).

## Hybrid (deps in Compose, API/web on host)

```bash
./scripts/start-deps.sh
cd backend && ./gradlew bootRun
cd frontend && npm run dev
```

## Production checklist (summary)

1. Replace demo IdP; disable demo users.
2. Terminate TLS + HSTS at the edge; set `CLEARREAD_CORS_ORIGINS` to real origins.
3. Pin image **digests** (Compose ships version tags; see operator runbook).
4. Keep AI disabled unless policy + provider reviewed.
5. Schedule Postgres **and** object-store backups ([backup-restore](./backup-restore.md)).
6. Verify `./scripts/check.sh` equivalents against production health endpoints.

## Configuration

Primary knobs live in `backend/src/main/resources/application.yml` and Compose `environment:` for the `api` service (`CLEARREAD_*`). Host ports + derived URLs: repo-root [`.env.example`](../../.env.example). Frontend: `frontend/.env.example` (`VITE_API_BASE_URL`, OIDC authority/client).

## OpenAPI

- Spec: `/v3/api-docs` (version **1.0.2**)
- Swagger UI: `/swagger-ui.html`
