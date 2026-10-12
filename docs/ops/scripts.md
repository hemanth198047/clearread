# ClearRead scripts matrix (v1.0)

Green-path operators for local Compose. Prefer **Git Bash / WSL** on Windows when the Docker bridge is broken (host API fallback lives only in `start.sh`).

| Script | Unix | Windows | What it does | Host fallback | Object store |
| --- | --- | --- | --- | --- | --- |
| `start.sh` / `start.bat` | ✅ | ✅ | Build + up Compose; wait for API health | **sh only** → host `bootRun` + Vite | n/a |
| `start-deps.sh` | ✅ | use Git Bash | Deps only (db, LocalStack, Keycloak) | n/a | n/a |
| `stop.sh` / `stop.bat` | ✅ | ✅ | Tear down Compose project `clearread` (+ tracked host PIDs) | stops host PIDs if tracked | n/a |
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
scripts\start.bat
scripts\check.bat
scripts\backup.bat
REM Object store: use Git Bash  bash scripts/backup.sh --with-objects
scripts\stop.bat
```

If `start.bat` never reaches a healthy API (Docker bridge), switch to:

```bat
bash scripts/start.sh
```

See [troubleshooting](../guides/troubleshooting.md) and [operator-runbook](../phase-6/operator-runbook.md).
