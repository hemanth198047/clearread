# Disaster recovery drill

**Date:** 2026-10-10  
**Scripts:** `scripts/backup.sh`, `scripts/restore.sh`  
**Runbook:** [operator-runbook.md](./operator-runbook.md)

## Procedure

1. With Compose stack healthy (`./scripts/start.sh`):
   ```bash
   ./scripts/backup.sh .clearread-run/backups/drill
   ```
2. Confirm `postgres.dump` + `MANIFEST.txt` exist.
3. Restore:
   ```bash
   ./scripts/restore.sh .clearread-run/backups/drill
   ```
4. `restore.sh` hard-fails on `pg_restore` exit codes **> 1** (warnings-only exit 1 allowed with `--clean`).
5. Verify:
   ```bash
   curl -sf http://localhost:8080/v1/health
   curl -sf http://localhost:8080/actuator/health
   ```
6. Re-sync object-store `tenants/*` prefixes from the same backup window (manual / operator-owned for S3).

## Result

| Step | Outcome |
| --- | --- |
| Script presence + hard-fail restore | PASS (code) |
| Live drill against running Compose DB | **PASS 2026-10-10** — `./scripts/backup.sh` wrote `postgres.dump` + `MANIFEST.txt`; restore exit ≤1; `/v1/health` UP after |

## Notes

- Object storage is not embedded in the Postgres dump; operators must snapshot the bucket separately.
- Production DR remains operator-owned (IdP, secrets, digests).
