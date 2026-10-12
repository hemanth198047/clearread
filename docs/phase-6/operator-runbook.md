# ClearRead Operator Runbook (v1.0)

Companion guides: [user](../guides/user.md) · [admin](../guides/admin.md) · [deployment](../guides/deployment.md) · [backup-restore](../guides/backup-restore.md) · [troubleshooting](../guides/troubleshooting.md) · [format-support](../format-support.md) · [security](../guides/security.md) · [scripts matrix](../ops/scripts.md)

## Services

| Service | Default | Notes |
| --- | --- | --- |
| API | `:8080` | Health `/actuator/health`, Prometheus `/actuator/prometheus` (auth) |
| Web | `:5173` / `:80` in compose | SPA; OIDC PKCE |
| Postgres | `:5432` | Flyway on boot |
| Object store | `:9000` → LocalStack S3 | Prefix `tenants/{tenantId}/…` |
| Keycloak | `:8081` | Realm `clearread`, client `clearread-spa`; accounts admin/admin, user/user, demo/demo; local volume `clearread_keycloak` (see docs/ops/keycloak-startup.md) |

## Capability honesty

UI and `/v1/capabilities` must only claim formats registered in `capabilities/registry.json`. Unsupported uploads are rejected. PDF export is a **constrained text PDF** converter (`cleardoc-to-pdf-constrained`) — generated artifact only, not a pixel-perfect print engine.

## IdP

- Production: replace demo Keycloak with enterprise IdP; rotate clients/secrets; disable seeded users + self-registration; `CLEARREAD_SHOW_DEMO_ACCOUNTS=false`.
- Issuer + JWK set URIs via `CLEARREAD_OIDC_*`.

## AI kill switch

- Workspace `ai_enabled=false` (default) or set provider unavailable.
- Global: leave `CLEARREAD_AI_OLLAMA_ENABLED` / `CLEARREAD_AI_EXTERNAL_ENABLED` false.
- Core reader never calls AI.

## Backup / restore

```bash
./scripts/backup.sh [/path/out]
./scripts/backup.sh --with-objects          # Postgres + aws s3 sync tenants/*
./scripts/restore.sh /path/to/backup-dir
./scripts/restore.sh --with-objects /path/to/backup-dir
```

On Windows (cmd), beginner-friendly wrappers call Compose `pg_dump` / `pg_restore` (Postgres only):

```bat
scripts\backup.bat
scripts\restore.bat path\to\backup-dir
```

Prefer Git Bash / WSL for the full bash helpers (`CLEARREAD_DB_URL` JDBC normalization, `--with-objects`, MANIFEST notes). Details: [guides/backup-restore.md](../guides/backup-restore.md). After restore: `./scripts/check.sh` (or `scripts\check.bat`) and confirm `/v1/health`.

## Image pinning (production)

Compose pins **version tags**. For production, resolve digests and pin:

```bash
docker buildx imagetools inspect postgres:16-alpine --format '{{json .Manifest.Digest}}'
# then: image: postgres:16-alpine@sha256:…
```

Recommended pins for this release train: `postgres:16-alpine`, `localstack/localstack:4.3`, `quay.io/keycloak/keycloak:26.8.0`.

## Observability

- Liveness/readiness: Spring Boot probes
- Metrics: Micrometer → Prometheus scrape (authenticated)
- Logs: never enable `clearread.ai.log-document-content` in production

## Incident basics

1. Disable AI + sharing if content exfil suspected.
2. Rotate preview/share tokens (revoke APIs) and OIDC clients.
3. Requeue dead jobs from Admin after root-cause.
4. Restore from last known-good backup if integrity failure.

## Troubleshooting (local / Compose)

### Compose API never healthy (Docker bridge)

Some environments break container-to-container networking after a manual `dockerd` start: published ports work from the host, but `api` cannot reach `db` / Keycloak / object store on the Compose network.

1. Check: `docker compose -p clearread -f infra/compose.yaml logs --tail=80 api`
2. Prefer `./scripts/start.sh` — it waits for `/v1/health`, then **falls back** to host `bootRun` + Vite against published dependency ports.
3. Windows `scripts\start.bat` does **not** include host fallback; use Git Bash/WSL `bash scripts/start.sh` when the bridge is broken.
4. Smoke: `./scripts/check.sh` (five named checks).

### Gradle distribution download timeout

`SocketTimeoutException` / `timeout (10000ms)` while downloading `gradle-*-bin.zip` during `docker compose build api`. Prefer **v1.0.2+** (Gradle from `gradle:8.14.3-jdk21`). Workarounds: retry, `docker pull gradle:8.14.3-jdk21`, or host `./gradlew bootJar` / `start.sh` fallback.

`Failed to load native library 'libnative-platform.so'` / `Could not initialize native services`: prefer **v1.0.3+** (Temurin + `/opt/gradle` copy; cache only `caches/` + `wrapper/`). Workarounds: `docker builder prune -f` then `docker compose … build --no-cache api`. See [compose-build-fix.md](../ops/compose-build-fix.md).

### Image build exit 127 (`./gradlew`)

Usually `gradlew` not executable or CRLF line endings. See [compose-build-fix.md](../ops/compose-build-fix.md). `start.sh` / `start.bat` print the same hints on build failure.

### After restore

1. Re-sync object-store `tenants/*` for the same backup window.
2. `curl -sf http://localhost:8080/v1/health`
3. `./scripts/check.sh` (or `scripts\check.bat`)

### Capability honesty

`GET /v1/capabilities` is the source of truth. AsciiDoc/RST appear as **unsupported** stubs (no parser). PDF export is **constrained text PDF** (`cleardoc-to-pdf-constrained`), registered as a generated artifact — not a layout/print engine claim.
