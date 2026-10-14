# ClearRead scripts matrix (v1.1.4)

Green-path operators for local Compose. Prefer **Git Bash / WSL** on Windows when the Docker bridge is broken (host API fallback lives only in `start.sh`).

**Repo remote:** prefer `https://github.com/hemanth198047/clearread.git` (formerly `mdbeautifier`):

```bash
git remote set-url origin https://github.com/hemanth198047/clearread.git
```

Rename / remote notes: [repo-rename.md](./repo-rename.md).

**Windows root wrappers (v1.0.7+):** from the repo root you can run `stop.bat`, `start.bat`, and `doctor.bat` — they forward to `scripts\*.bat`.

| Script | Unix | Windows | What it does | Host fallback | Object store |
| --- | --- | --- | --- | --- | --- |
| `start.sh` / `start.bat` | ✅ | ✅ (+ root `start.bat`) | Build + up Compose; **per-service deps**; fail fast on busy ports; wait for Keycloak (240s) then API health | **sh only** → host `bootRun` + Vite | n/a |
| `start-deps.sh` / `start-deps.bat` | ✅ | ✅ | Deps only: **db → LocalStack(`minio`) → Keycloak**; timed `compose up`; fail if stuck in **Created**; per-service status + logs on failure | n/a | n/a |
| `doctor.sh` / `doctor.bat` | ✅ | ✅ (+ root `doctor.bat`) | Docker running?, Compose V2, ports, **Created-stuck**, `compose ps`, last logs, next action | n/a | n/a |
| `repair-flyway.sh` / `repair-flyway.bat` | ✅ | ✅ | **Dev only:** one-shot Flyway `repair` against Compose Postgres (checksum realign). Never use in production | n/a | n/a |
| `stop.sh` / `stop.bat` | ✅ | ✅ (+ root `stop.bat`) | Tear down Compose project `clearread` (+ tracked host PIDs) | stops host PIDs if tracked | n/a |
| `check.sh` / `check.bat` | ✅ | ✅ | Health, capabilities, branding, web, OIDC | n/a | n/a |
| `backup.sh` / `backup.bat` | ✅ | ✅ (Compose pg_dump) | Postgres dump + MANIFEST | n/a | **`--with-objects`** on `.sh` (AWS CLI); `.bat` documents manual sync |
| `restore.sh` / `restore.bat` | ✅ | ✅ (Compose pg_restore) | Restore dump | n/a | **`--with-objects`** on `.sh` when `objects/tenants/` present |

## End-to-end green path (Unix / macOS)

```bash
./scripts/start.sh
./scripts/check.sh
# …use the app…
./scripts/backup.sh --with-objects
./scripts/stop.sh
# later:
./scripts/start.sh
./scripts/restore.sh --with-objects /path/to/backup-dir
./scripts/check.sh
```

## End-to-end green path (Windows)

```bat
doctor.bat
scripts\start-deps.bat
start.bat
scripts\check.bat
scripts\backup.bat
REM Object store: use Git Bash  bash scripts/backup.sh --with-objects
stop.bat
```

If deps never start:

```bat
doctor.bat
stop.bat
docker volume rm clearread_clearread_pg clearread_clearread_minio clearread_clearread_keycloak
scripts\start-deps.bat
```

If doctor reports **STATUS=Created** with empty logs (Docker Desktop / WSL wedge):

```bat
REM Quit Docker Desktop → wsl --shutdown → start Docker Desktop (wait green)
docker start clearread-db-1
doctor.bat
scripts\start-deps.bat
```

See [compose-deps-fix](./compose-deps-fix.md#containers-stuck-in-created).

### Flyway checksum mismatch (API crash)

After **v1.1.3**, most local DBs recover by pulling and restarting. If validate still fails:

```bat
scripts\repair-flyway.bat
```

```bash
./scripts/repair-flyway.sh
```

Or wipe Postgres only: `docker volume rm clearread_clearread_pg` (dev). Details: [troubleshooting — Flyway checksum mismatch](../guides/troubleshooting.md#flyway-checksum-mismatch-api-crash-on-boot).

If `start.bat` never reaches a healthy API (Docker bridge), switch to:

```bat
bash scripts/start.sh
```

See [compose-deps-fix](./compose-deps-fix.md), [troubleshooting](../guides/troubleshooting.md) and [operator-runbook](../phase-6/operator-runbook.md).
