# Compose deps fix (db / LocalStack / Keycloak) — v1.0.6

On Windows Docker Desktop, **v1.0.5 and earlier** often failed to bring up `db`, object storage, and Keycloak at all. This document explains what broke and how **v1.0.6** recovers.

## What was going wrong

| Failure mode | Symptom | Fix in v1.0.6 |
| --- | --- | --- |
| Host **Postgres already on 5432** | `start.bat` fail-fast: “Port 5432 is in use” — **no deps start** | Default `CLEARREAD_DB_PORT=**5433**` |
| Keycloak healthcheck used `/dev/tcp` via plain `sh` | Container never becomes `healthy`; API blocked on `depends_on` | Explicit `/bin/bash` + `/dev/tcp` on management `:9000` |
| Keycloak `mem_limit: 768m` | OOM kill during first-boot realm import → crash loop | Removed hard mem limit; soft heap `-Xmx768m` |
| All three deps started in one `up` with silent failure | Hard to see which service died | Per-service `up` + `ps` + `logs --tail 80` on failure |
| API waited for Keycloak **healthy** | Slow IdP blocked the whole stack | API `depends_on` Keycloak = `service_started`; scripts wait for OIDC |
| LocalStack named `minio` + curl-only health | Confusion + fragile probe | Keep service name `minio` for DNS; Python health probe; docs clarify LocalStack |
| No doctor path | Guessing from vague errors | `scripts/doctor.sh` + `scripts/doctor.bat` |

## Object storage: LocalStack (not MinIO binary)

Compose service name remains **`minio`** so `CLEARREAD_S3_ENDPOINT=http://minio:4566` and existing scripts keep working. The **image** is `localstack/localstack:4.3` (S3 only). Host port default **9000** maps to container **4566**.

Health: `http://localhost:9000/_localstack/health` (credentials `test` / `test`).

## Keycloak health

- Management interface on container port **9000**, published as host **`CLEARREAD_KEYCLOAK_MGMT_PORT` (default 9002)**.
- Docker healthcheck: bash `/dev/tcp` → `GET /health/ready` (UBI image has no curl).
- Realm import mount: `infra/keycloak/realm-clearread.json` (path relative to `infra/compose.yaml`).

## Windows recovery (do this now)

```bat
git pull
scripts\stop.bat

REM Optional: wipe corrupt / half-created volumes after many failed boots
docker volume rm clearread_clearread_pg clearread_clearread_minio clearread_clearread_keycloak

REM Diagnose Docker / ports / last logs
scripts\doctor.bat

REM Bring up deps only (db → LocalStack → Keycloak) with per-service status
scripts\start-deps.bat

REM Or full stack
scripts\start.bat
```

If port warnings remain, copy `.env.example` → `.env` and change only the busy ports (Postgres default is already **5433**).

Git Bash / WSL:

```bash
git pull
./scripts/stop.sh
docker volume rm clearread_clearread_pg clearread_clearread_minio clearread_clearread_keycloak
./scripts/doctor.sh
./scripts/start-deps.sh
./scripts/start.sh
```

## Expected healthy state

```bat
docker compose -p clearread -f infra\compose.yaml ps
```

- `db` — `healthy` (host **:5433** → container 5432)
- `minio` — `healthy` (LocalStack; host **:9000** → 4566)
- `keycloak` — running / healthy (host **:8081**, mgmt **:9002**)

OIDC check:

```bat
curl -sf http://localhost:8081/realms/clearread/.well-known/openid-configuration
```

## Related

- [keycloak-startup.md](./keycloak-startup.md)
- [troubleshooting](../guides/troubleshooting.md)
- [scripts matrix](./scripts.md)
