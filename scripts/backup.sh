#!/usr/bin/env bash
# ClearRead backup helper (v1.0). Captures Postgres dump; optionally syncs object-store tenants/*.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

WITH_OBJECTS=0
OUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-objects) WITH_OBJECTS=1; shift ;;
    -h|--help)
      cat <<'USAGE'
Usage: backup.sh [--with-objects] [/path/out]

  Default: Postgres custom-format dump + MANIFEST.txt
  --with-objects: also aws s3 sync bucket/tenants → OUT/objects/tenants
                  (requires AWS CLI; uses CLEARREAD_S3_* env vars)
USAGE
      exit 0
      ;;
    *)
      if [[ -n "$OUT" ]]; then
        echo "Unexpected argument: $1" >&2
        exit 2
      fi
      OUT="$1"
      shift
      ;;
  esac
done

OUT="${OUT:-$ROOT/.clearread-run/backups/$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$OUT"

DB_URL="${CLEARREAD_DB_URL:-postgresql://clearread:clearread@localhost:5432/clearread}"
# Convert JDBC URL if provided
if [[ "$DB_URL" == jdbc:postgresql:* ]]; then
  DB_URL="${DB_URL#jdbc:}"
fi

echo "Backing up Postgres to $OUT/postgres.dump"
if command -v pg_dump >/dev/null 2>&1; then
  pg_dump --format=custom --file="$OUT/postgres.dump" "$DB_URL"
else
  docker compose -p clearread -f "$ROOT/infra/compose.yaml" exec -T db \
    pg_dump -U clearread -d clearread -Fc > "$OUT/postgres.dump"
fi

OBJECT_NOTE="not included — re-sync tenants/* separately, or re-run with --with-objects"
if [[ "$WITH_OBJECTS" -eq 1 ]]; then
  S3_ENDPOINT="${CLEARREAD_S3_ENDPOINT:-http://localhost:9000}"
  S3_BUCKET="${CLEARREAD_S3_BUCKET:-clearread}"
  S3_REGION="${CLEARREAD_S3_REGION:-us-east-1}"
  export AWS_ACCESS_KEY_ID="${CLEARREAD_S3_ACCESS_KEY:-test}"
  export AWS_SECRET_ACCESS_KEY="${CLEARREAD_S3_SECRET_KEY:-test}"
  export AWS_DEFAULT_REGION="$S3_REGION"
  if ! command -v aws >/dev/null 2>&1; then
    echo "ERROR: --with-objects requires AWS CLI (aws). Install awscli or sync manually." >&2
    echo "  Example: aws --endpoint-url $S3_ENDPOINT s3 sync s3://$S3_BUCKET/tenants/ \"$OUT/objects/tenants/\"" >&2
    exit 1
  fi
  mkdir -p "$OUT/objects/tenants"
  echo "Syncing s3://$S3_BUCKET/tenants/ → $OUT/objects/tenants/"
  aws --endpoint-url "$S3_ENDPOINT" s3 sync "s3://$S3_BUCKET/tenants/" "$OUT/objects/tenants/"
  OBJECT_NOTE="objects/tenants/ (from s3://$S3_BUCKET/tenants via $S3_ENDPOINT)"
fi

cat > "$OUT/MANIFEST.txt" <<EOF
ClearRead backup
created_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
product_version=1.0.0
postgres=postgres.dump
object_store=$OBJECT_NOTE
restore=see docs/guides/backup-restore.md
manual_object_sync=aws --endpoint-url http://localhost:9000 s3 sync s3://clearread/tenants/ ./tenants-backup/
EOF

echo "Backup complete: $OUT"
ls -la "$OUT"
