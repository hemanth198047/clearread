# Troubleshooting (ClearRead v1.1.4)

## Flyway checksum mismatch (API crash on boot)

Symptom in API logs:

```text
FlywayValidateException: Migration checksum mismatch for migration version 1
Applied to database : 1045756829
Resolved locally    : …
```

**Cause (v1.1.2):** `V1__baseline.sql` was edited after it had already been applied. Applied migrations must stay immutable; Rebeautifier demo branding belongs in `V7__rebeautifier_branding.sql` only. **v1.1.3** restores the original V1 seed so existing local DBs validate again. New installs still get Rebeautifier colors via V7 after V1.

Spring Boot always keeps `spring.flyway.validate-on-migrate=true` (local and production). There is **no** silent repair in the app.

### Prefer: pull v1.1.3+ and restart

Most local DBs that still have the original applied checksum need **no** repair after upgrading — just restart API/Compose.

### If validate still fails: repair (dev only)

With Postgres up (`scripts\start-deps.bat` / `./scripts/start-deps.sh`):

```bat
scripts\repair-flyway.bat
docker compose -p clearread -f infra\compose.yaml up -d api
```

```bash
./scripts/repair-flyway.sh
docker compose -p clearread -f infra/compose.yaml up -d api
```

This runs a one-shot `flyway/flyway` container against Compose Postgres and updates `flyway_schema_history` checksums to match the SQL in the repo. **Do not run against production.**

### Nuclear (dev only): wipe Postgres volume

Destroys local DB data:

```bat
docker compose -p clearread -f infra\compose.yaml stop api
docker volume rm clearread_clearread_pg
scripts\start-deps.bat
start.bat
```

```bash
docker compose -p clearread -f infra/compose.yaml stop api
docker volume rm clearread_clearread_pg
./scripts/start-deps.sh
./scripts/start.sh
```

Volume name is `clearread_clearread_pg` (Compose project `clearread` + volume `clearread_pg`).

## Containers stuck in Created (Docker Desktop / WSL wedge)

If `docker compose ps` shows **Created**, logs are empty, ports are free, and `start-deps` hangs on “Container … Starting”:

1. Quit Docker Desktop → `wsl --shutdown` → start Docker Desktop (wait green).
2. `docker start clearread-db-1` — if **this** hangs, only another Desktop/WSL reset helps; Compose cannot work around a wedged engine.
3. `doctor.bat` then `scripts\start-deps.bat`.

Full section: [ops/compose-deps-fix.md — Containers stuck in Created](../ops/compose-deps-fix.md#containers-stuck-in-created).

## Deps never start (db / LocalStack / Keycloak) — Windows P0

If containers do not come up at all (or start scripts die before API):

1. Run the doctor (Docker running? Compose V2? port conflicts? **Created-stuck**? last logs?):

```bat
doctor.bat
```

2. Stop + optional volume wipe + deps-only start:

```bat
stop.bat
docker volume rm clearread_clearread_pg clearread_clearread_minio clearread_clearread_keycloak
scripts\start-deps.bat
```

**v1.0.6+ defaults:** Postgres host port **5433** (not 5432 — local Windows Postgres often owns 5432 and used to block the entire start). Object store is **LocalStack** under Compose service name `minio`. Keycloak health uses bash `/dev/tcp` on management port **9002**. **v1.0.7** adds Created-stuck detection + compose-up timeouts. Full write-up: [ops/compose-deps-fix.md](../ops/compose-deps-fix.md).

## Keycloak takes forever / start hangs on IdP

First Keycloak boot (empty volume + realm import) often takes **1–3 minutes** on Windows Docker Desktop. Warm starts are usually under 30s. Start scripts print progress and fail after **240s** with tips instead of hanging forever. See [ops/keycloak-startup.md](../ops/keycloak-startup.md).

```bat
docker logs clearread-keycloak-1
REM Give Docker Desktop 4GB+ RAM, then:
docker compose -p clearread -f infra\compose.yaml up -d --force-recreate keycloak
```

## Port already allocated / start hangs on API health

Compose publishes host ports (defaults: API **8080**, web **5173**, Keycloak **8081**, Keycloak mgmt **9002**, Postgres **5433**, S3 **9000**). If another process holds one of them, `docker compose up` fails with `port is already allocated` and older start scripts waited forever on health.

**v1.0.1+ behavior:** `scripts/start.sh` and `scripts/start.bat` **fail fast** before/at compose up, print which port is busy, and do **not** infinite-wait for API health. Health waits also abort early if the `api` container exits.

### Unblock now (keep default ports)

```bash
# See what holds 8080
ss -ltnp | grep :8080
# or: lsof -nP -iTCP:8080 -sTCP:LISTEN

./scripts/stop.sh   # free ClearRead leftovers only
./scripts/start.sh
```

Windows:

```bat
netstat -ano | findstr :8080
REM PowerShell: Get-NetTCPConnection -LocalPort 8080 | Select OwningProcess
scripts\stop.bat
scripts\start.bat
```

### Unblock by remapping host ports

Copy [`.env.example`](../../.env.example) → `.env` at the repo root and change only the conflicting ports:

```bash
cp .env.example .env
# example: API 8080 busy
echo 'CLEARREAD_API_PORT=18080' >> .env
./scripts/stop.sh
./scripts/start.sh
```

Start scripts derive `VITE_API_BASE_URL`, CORS origins, and OIDC issuer URLs from these ports and rebuild the web image so the SPA still talks to the API. Defaults remain 8080/5173 when unset.

| Variable | Default | Service |
| --- | --- | --- |
| `CLEARREAD_API_PORT` | 8080 | API |
| `CLEARREAD_WEB_PORT` | 5173 | Web SPA |
| `CLEARREAD_KEYCLOAK_PORT` | 8081 | Keycloak |
| `CLEARREAD_KEYCLOAK_MGMT_PORT` | 9002 | Keycloak management `/health` |
| `CLEARREAD_DB_PORT` | **5433** | Postgres (host; container stays 5432) |
| `CLEARREAD_S3_PORT` | 9000 | LocalStack S3 (Compose service `minio`) |

## Compose API never healthy (Docker bridge)

Published ports work from the host, but `api` cannot reach `db` / Keycloak / object store on the Compose network.

1. `docker compose -p clearread -f infra/compose.yaml logs --tail=80 api`
2. Prefer `./scripts/start.sh` — waits for health (with timeout), then **falls back** to host `bootRun` + Vite.
3. Windows `scripts\start.bat` has **no** host fallback — use `bash scripts/start.sh`.
4. Smoke: `./scripts/check.sh` (reads the same `.env` ports).

## Failed to load `libnative-platform.so`

`docker compose build api` fails with `Could not initialize native services` / `Failed to load native library 'libnative-platform.so'`. Usually a **corrupt BuildKit Gradle cache** on Windows Docker Desktop (whole-home cache mount), not a zip timeout. **v1.0.3+** copies Gradle from `gradle:8.14.3-jdk21` into Temurin and caches only `caches/` + `wrapper/`. See [ops/compose-build-fix.md](../ops/compose-build-fix.md).

**Unblock now:**

```bash
git pull
docker builder prune -f
docker compose -p clearread -f infra/compose.yaml build --no-cache api
```

## Gradle distribution download timeout (`SocketTimeoutException`)

`docker compose build api` times out downloading `gradle-8.14.3-bin.zip` (web image may still succeed). **v1.0.2+** obtains Gradle from `gradle:8.14.3-jdk21` so the distribution is not fetched during build. See [ops/compose-build-fix.md](../ops/compose-build-fix.md).

**Unblock now:** retry; or `docker pull gradle:8.14.3-jdk21` then rebuild; or host `cd backend && ./gradlew bootJar` / `./scripts/start.sh` hybrid fallback. Host timeouts: `GRADLE_OPTS="-Dorg.gradle.internal.http.connectionTimeout=120000 -Dorg.gradle.internal.http.socketTimeout=120000"`.

## Image build exit 127 (`./gradlew`)

Wrapper not executable or CRLF endings. See [ops/compose-build-fix.md](../ops/compose-build-fix.md). `.gitattributes` forces LF on `gradlew`.

## Unsupported format (415)

AsciiDoc/RST and other unsupported registry entries are **detected then rejected**. Convert to Markdown or use an ingestible format. See [format-support.md](../format-support.md) and `GET /v1/capabilities`.

## Document stuck / failed

1. Open reader diagnostics / processing notes.
2. **Reprocess** from the recovery panel.
3. Download original (checksummed) if needed.
4. Check API logs and Admin job state.

## Share / preview failures

- Expired, revoked, or permission flags are enforced server-side.
- Auth-required shares need a signed-in user.
- Create a fresh link after revoke.

## Object store after DB restore

Metadata without objects yields broken originals. Re-sync `tenants/*` for the same backup window ([backup-restore](./backup-restore.md)).

## Playwright E2E skipped

Suite needs a running stack (`./scripts/start.sh`). Without Docker/API, tests skip. See `frontend/e2e/README.md`.

## More

[Compose deps fix](../ops/compose-deps-fix.md) · [Operator runbook](../phase-6/operator-runbook.md) · [scripts matrix](../ops/scripts.md)
