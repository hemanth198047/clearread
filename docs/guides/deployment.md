# Deployment guide (ClearRead v1.0)

**Source repo (target):** https://github.com/hemanth198047/clearread — owner rename from `mdbeautifier` + remote update: [ops/repo-rename.md](../ops/repo-rename.md).

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
| Keycloak mgmt | 9002 | `CLEARREAD_KEYCLOAK_MGMT_PORT` | `/health/ready` |
| Postgres | **5433** | `CLEARREAD_DB_PORT` | Flyway on boot (host; container 5432) |
| Object store | 9000 | `CLEARREAD_S3_PORT` | LocalStack S3 (Compose service `minio`) |

Copy [`.env.example`](../../.env.example) → `.env` to remap published ports when the defaults are busy. Start scripts export matching `VITE_API_BASE_URL` / `CLEARREAD_CORS_ORIGINS` so the SPA and CORS stay aligned.

Scripts matrix: [ops/scripts.md](../ops/scripts.md). Deps not starting: [ops/compose-deps-fix.md](../ops/compose-deps-fix.md). Compose build (exit 127 / Gradle download timeout): [ops/compose-build-fix.md](../ops/compose-build-fix.md). Port conflicts: [troubleshooting](./troubleshooting.md).

## Hybrid (deps in Compose, API/web on host)

```bash
./scripts/start-deps.sh          # or scripts\start-deps.bat
cd backend && ./gradlew bootRun  # DB host default :5433
cd frontend && npm run dev
```

Local `bootRun` uses the same `application.yml` defaults as Compose (Postgres `:5433`, Keycloak, LocalStack). Flyway **always validates** on migrate (`validate-on-migrate=true`) — there is no local profile that skips checksum checks. If you hit a checksum mismatch on an old volume, use [repair-flyway](../../scripts/repair-flyway.bat) (dev only) or wipe `clearread_clearread_pg` — see [troubleshooting](./troubleshooting.md#flyway-checksum-mismatch-api-crash-on-boot).

## Access model

- **Local:** Keycloak realm import seeds `admin`/`admin`, `user`/`user`, `demo`/`demo`; self-registration enabled; SPA **Create account** + demo credential panel (`CLEARREAD_SHOW_DEMO_ACCOUNTS`).
- **Production / enterprise:** org IdP OIDC SSO (or IdP invite). Disable Keycloak/demo self-registration, set `CLEARREAD_SHOW_DEMO_ACCOUNTS=false`, and use Admin → Invite user (DB pending invite + copyable link) or IdP provisioning.

## Production checklist (summary)

1. Replace demo IdP; disable seeded users and IdP self-registration; `CLEARREAD_SHOW_DEMO_ACCOUNTS=false`.
2. Terminate TLS + HSTS at the edge; set `CLEARREAD_CORS_ORIGINS` to real origins.
3. Pin image **digests** (Compose ships version tags; see operator runbook).
4. Keep AI disabled unless policy + provider reviewed.
5. Schedule Postgres **and** object-store backups ([backup-restore](./backup-restore.md)).
6. Verify `./scripts/check.sh` equivalents against production health endpoints.

## Configuration

Primary knobs live in `backend/src/main/resources/application.yml` and Compose `environment:` for the `api` service (`CLEARREAD_*`). Host ports + derived URLs: repo-root [`.env.example`](../../.env.example). Frontend: `frontend/.env.example` (`VITE_API_BASE_URL`, OIDC authority/client).

## OpenAPI

- Spec: `/v3/api-docs` (version **1.0.8**)
- Swagger UI: `/swagger-ui.html`
