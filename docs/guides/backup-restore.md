# Backup and restore (ClearRead v1.0)

ClearRead state = **Postgres metadata** + **object-store originals** under `tenants/{tenantId}/…`. Back up both for the same window.

## Postgres (default)

```bash
./scripts/backup.sh [/path/out]
./scripts/restore.sh /path/to/backup-dir
```

Windows (Compose `pg_dump` / `pg_restore`):

```bat
scripts\backup.bat
scripts\restore.bat path\to\backup-dir
```

Prefer Git Bash/WSL for JDBC URL normalization and object helpers.

## Object store (LocalStack / MinIO / S3)

### Automated (Unix)

Requires [AWS CLI](https://aws.amazon.com/cli/) and reachable endpoint credentials:

```bash
export CLEARREAD_S3_ENDPOINT=http://localhost:9000   # LocalStack mapped port
export CLEARREAD_S3_BUCKET=clearread
export CLEARREAD_S3_ACCESS_KEY=test
export CLEARREAD_S3_SECRET_KEY=test

./scripts/backup.sh --with-objects
./scripts/restore.sh --with-objects /path/to/backup-dir
```

This writes `objects/tenants/` beside `postgres.dump` and records it in `MANIFEST.txt`.

### Manual sync examples

```bash
# Backup
aws --endpoint-url http://localhost:9000 s3 sync s3://clearread/tenants/ ./tenants-backup/

# Restore
aws --endpoint-url http://localhost:9000 s3 sync ./tenants-backup/ s3://clearread/tenants/
```

MinIO users can substitute `mc mirror` with equivalent alias/bucket paths. Production S3: omit `--endpoint-url` and use IAM roles / keys as usual.

### Windows note

`backup.bat` / `restore.bat` cover Postgres only. For objects, run the bash helpers from Git Bash/WSL or the `aws s3 sync` examples above.

## After restore

1. Confirm object prefixes match the dump window.
2. `curl -sf http://localhost:8080/v1/health`
3. `./scripts/check.sh` (or `scripts\check.bat`)

Evidence of a prior drill: [phase-6/dr-drill.md](../phase-6/dr-drill.md).
