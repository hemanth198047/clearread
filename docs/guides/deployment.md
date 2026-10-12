# Deployment guide (ClearRead v1.0)

## Local Compose (recommended starting point)

```bash
./scripts/start.sh          # Linux / macOS / Git Bash / WSL
# Windows cmd: scripts\start.bat
./scripts/check.sh
```

Open http://localhost:5173 — API http://localhost:8080.

| Service | Port | Notes |
| --- | --- | --- |
| Web | 5173 | SPA (nginx in Compose image) |
| API | 8080 | Spring Boot `/v1` |
| Keycloak | 8081 | Realm `clearread` |
| Postgres | 5432 | Flyway on boot |
| Object store | 9000 | LocalStack S3 API |

Scripts matrix: [ops/scripts.md](../ops/scripts.md). Compose build exit 127: [ops/compose-build-fix.md](../ops/compose-build-fix.md).

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

Primary knobs live in `backend/src/main/resources/application.yml` and Compose `environment:` for the `api` service (`CLEARREAD_*`). Frontend: `frontend/.env.example` (`VITE_API_BASE_URL`, OIDC authority/client).

## OpenAPI

- Spec: `/v3/api-docs` (version **1.0.0**)
- Swagger UI: `/swagger-ui.html`
