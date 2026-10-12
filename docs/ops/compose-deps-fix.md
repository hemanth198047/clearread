# Compose deps fix (db / LocalStack / Keycloak) — v1.0.7

On Windows Docker Desktop, **v1.0.5 and earlier** often failed to bring up `db`, object storage, and Keycloak at all. **v1.0.6** fixed ports/healthchecks/doctor. **v1.0.7** detects the next failure mode: containers stuck forever in **`Created`** (Docker engine wedge).

## What was going wrong

| Failure mode | Symptom | Fix |
| --- | --- | --- |
| Host **Postgres already on 5432** | `start.bat` fail-fast: “Port 5432 is in use” — **no deps start** | Default `CLEARREAD_DB_PORT=**5433**` (v1.0.6) |
| Keycloak healthcheck used `/dev/tcp` via plain `sh` | Container never becomes `healthy`; API blocked on `depends_on` | Explicit `/bin/bash` + `/dev/tcp` on management `:9000` (v1.0.6) |
| Keycloak `mem_limit: 768m` | OOM kill during first-boot realm import → crash loop | Removed hard mem limit; soft heap `-Xmx768m` (v1.0.6) |
| All three deps started in one `up` with silent failure | Hard to see which service died | Per-service `up` + `ps` + `logs --tail 80` on failure (v1.0.6) |
| API waited for Keycloak **healthy** | Slow IdP blocked the whole stack | API `depends_on` Keycloak = `service_started`; scripts wait for OIDC (v1.0.6) |
| LocalStack named `minio` + curl-only health | Confusion + fragile probe | Keep service name `minio` for DNS; Python health probe; docs clarify LocalStack (v1.0.6) |
| No doctor path | Guessing from vague errors | `scripts/doctor.sh` + `scripts/doctor.bat` (v1.0.6) |
| **Containers stuck in `Created`** | `compose up` / `start-deps` hangs on “Container … Starting” for minutes; Status=`Created`; **empty logs**; ports free; daemon “reachable” | Doctor loud diagnosis + start-deps timeouts + Created fail-fast (v1.0.7) |

## Containers stuck in Created

### What it looks like

```text
STATUS: Created   (NOT running)
logs: empty
start-deps hangs: "Container clearread-db-1 Starting" for minutes
Ports all FREE. Daemon reachable.
```

`docker compose ps -a` shows `Created`. `docker logs` is empty. Ports are free. `docker info` works. This is **not** Keycloak slowness, **not** a port conflict, and **not** a ClearRead healthcheck bug.

### What it means

The Docker engine accepted `create` but **`docker start` never completes**. On Windows Docker Desktop this is almost always a **wedged Docker Desktop / WSL backend**. Application Compose scripts cannot finish starting containers while the engine is in this state.

**Important:** If `docker start clearread-db-1` itself hangs (even with a long wait), only a Docker Desktop restart / WSL reset fixes it. ClearRead cannot work around a wedged engine.

### Recovery (do this)

```bat
REM 1. Quit Docker Desktop completely (system tray → Quit Docker Desktop)

REM 2. Reset WSL
wsl --shutdown

REM 3. Start Docker Desktop and wait until the whale icon is green / "Running"

REM 4. Probe whether start works at all (short patience — if THIS hangs, repeat 1–3)
docker start clearread-db-1

REM 5. Diagnose + bring deps up with timeouts
doctor.bat
scripts\start-deps.bat
```

Repo root wrappers (v1.0.7+): `doctor.bat`, `start.bat`, `stop.bat` → `scripts\*.bat`. Deps-only remains `scripts\start-deps.bat`.

Git Bash / WSL:

```bash
./scripts/doctor.sh
./scripts/start-deps.sh
```

### What v1.0.7 scripts do

- **`doctor`**: Detects `STATUS=Created` with empty/near-empty logs, dumps `docker inspect` `.State`, prints the Docker Desktop / WSL recovery steps, and links here.
- **`start-deps`**: Never hangs unbounded on `compose up`.
  - Windows: PowerShell `WaitForExit` timeout around `docker compose up -d <svc>` (`CLEARREAD_COMPOSE_UP_TIMEOUT`, default **90s**).
  - Unix: GNU `timeout` (or background fallback) with the same limit.
  - After create/up, if Status stays **Created** longer than `CLEARREAD_CREATED_STUCK_SECONDS` (default **45s**), fails with recovery steps + `docker inspect` State.
  - Still starts **one container at a time** with clear progress.

Optional env overrides (`.env`):

| Variable | Default | Meaning |
| --- | --- | --- |
| `CLEARREAD_COMPOSE_UP_TIMEOUT` | `90` | Max seconds for each `compose up -d <svc>` |
| `CLEARREAD_CREATED_STUCK_SECONDS` | `45` | Fail if Status remains `Created` this long |
| `CLEARREAD_DEP_WAIT_SECONDS` | `90` | Healthy wait for db / LocalStack |
| `CLEARREAD_KEYCLOAK_WAIT_SECONDS` | `240` | OIDC readiness wait |

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

REM Root wrappers work from repo root (v1.0.7+):
stop.bat

REM Optional: wipe corrupt / half-created volumes after many failed boots
docker volume rm clearread_clearread_pg clearread_clearread_minio clearread_clearread_keycloak

REM Diagnose Docker / ports / Created-stuck / last logs
doctor.bat

REM Bring up deps only (db → LocalStack → Keycloak) with per-service status + timeouts
scripts\start-deps.bat
scripts\start.bat
```

If port warnings remain, copy `.env.example` → `.env` and change only the busy ports (Postgres default is already **5433**).

**If doctor reports Created-stuck:** do the [Containers stuck in Created](#containers-stuck-in-created) recovery first — do not keep retrying `compose up` against a wedged engine.

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
