#!/usr/bin/env bash
# ClearRead restore helper (v1.0). Restores a pg_dump custom-format file; optionally object prefixes.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

WITH_OBJECTS=0
SRC=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-objects) WITH_OBJECTS=1; shift ;;
    -h|--help)
      cat <<'USAGE'
Usage: restore.sh [--with-objects] /path/to/backup-dir-or-dump

  Restores postgres.dump (or a bare .dump file).
  --with-objects: if OUT/objects/tenants exists, aws s3 sync back to the bucket.
USAGE
      exit 0
      ;;
    *)
      if [[ -n "$SRC" ]]; then
        echo "Unexpected argument: $1" >&2
        exit 2
      fi
      SRC="$1"
      shift
      ;;
  esac
done

SRC="${SRC:?Usage: restore.sh [--with-objects] /path/to/backup-dir-or-dump}"

DUMP="$SRC"
BACKUP_DIR=""
if [[ -d "$SRC" ]]; then
  BACKUP_DIR="$SRC"
  DUMP="$SRC/postgres.dump"
fi
[[ -f "$DUMP" ]] || { echo "Missing dump: $DUMP" >&2; exit 1; }

DB_URL="${CLEARREAD_DB_URL:-postgresql://clearread:clearread@localhost:5432/clearread}"
# Convert JDBC URL if provided (same as backup.sh)
if [[ "$DB_URL" == jdbc:postgresql:* ]]; then
  DB_URL="${DB_URL#jdbc:}"
fi

echo "Restoring Postgres from $DUMP"
# pg_restore may exit 1 on ignorable object warnings when --clean is used; treat exit >1 as hard fail.
set +e
if command -v pg_restore >/dev/null 2>&1; then
  pg_restore --clean --if-exists --no-owner --dbname="$DB_URL" "$DUMP"
  rc=$?
else
  echo "pg_restore not on PATH — using Compose db container…"
  docker compose -p clearread -f "$ROOT/infra/compose.yaml" exec -T db \
    pg_restore -U clearread -d clearread --clean --if-exists --no-owner < "$DUMP"
  rc=$?
fi
set -e
if [[ "$rc" -gt 1 ]]; then
  echo "pg_restore failed with exit $rc" >&2
  echo "  Tip: ensure Postgres is up (./scripts/start.sh or compose up db) and CLEARREAD_DB_URL is reachable." >&2
  exit "$rc"
fi
echo "DB restore finished (exit $rc)."

if [[ "$WITH_OBJECTS" -eq 1 ]]; then
  OBJ_DIR="${BACKUP_DIR:-}/objects/tenants"
  if [[ -z "$BACKUP_DIR" || ! -d "$OBJ_DIR" ]]; then
    echo "ERROR: --with-objects needs a backup directory containing objects/tenants/" >&2
    exit 1
  fi
  S3_ENDPOINT="${CLEARREAD_S3_ENDPOINT:-http://localhost:9000}"
  S3_BUCKET="${CLEARREAD_S3_BUCKET:-clearread}"
  S3_REGION="${CLEARREAD_S3_REGION:-us-east-1}"
  export AWS_ACCESS_KEY_ID="${CLEARREAD_S3_ACCESS_KEY:-test}"
  export AWS_SECRET_ACCESS_KEY="${CLEARREAD_S3_SECRET_KEY:-test}"
  export AWS_DEFAULT_REGION="$S3_REGION"
  if ! command -v aws >/dev/null 2>&1; then
    echo "ERROR: --with-objects requires AWS CLI (aws)." >&2
    exit 1
  fi
  echo "Syncing $OBJ_DIR → s3://$S3_BUCKET/tenants/"
  aws --endpoint-url "$S3_ENDPOINT" s3 sync "$OBJ_DIR/" "s3://$S3_BUCKET/tenants/"
else
  echo "Next: re-sync object-store tenants/* from the same backup window (or restore.sh --with-objects), then:"
fi
echo "  curl -sf http://localhost:8080/v1/health && ./scripts/check.sh"
