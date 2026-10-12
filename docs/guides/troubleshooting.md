# Troubleshooting (ClearRead v1.0.2)

## Port already allocated / start hangs on API health

Compose publishes host ports (defaults: API **8080**, web **5173**, Keycloak **8081**, Postgres **5432**, S3 **9000**). If another process holds one of them, `docker compose up` fails with `port is already allocated` and older start scripts waited forever on health.

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
| `CLEARREAD_DB_PORT` | 5432 | Postgres |
| `CLEARREAD_S3_PORT` | 9000 | LocalStack S3 |

## Compose API never healthy (Docker bridge)

Published ports work from the host, but `api` cannot reach `db` / Keycloak / object store on the Compose network.

1. `docker compose -p clearread -f infra/compose.yaml logs --tail=80 api`
2. Prefer `./scripts/start.sh` — waits for health (with timeout), then **falls back** to host `bootRun` + Vite.
3. Windows `scripts\start.bat` has **no** host fallback — use `bash scripts/start.sh`.
4. Smoke: `./scripts/check.sh` (reads the same `.env` ports).

## Gradle distribution download timeout (`SocketTimeoutException`)

`docker compose build api` times out downloading `gradle-8.14.3-bin.zip` (web image may still succeed). **v1.0.2+** builds the API image from `gradle:8.14.3-jdk21` so the distribution is not fetched during build. See [ops/compose-build-fix.md](../ops/compose-build-fix.md).

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

[Operator runbook](../phase-6/operator-runbook.md) · [scripts matrix](../ops/scripts.md)
