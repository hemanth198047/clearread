#!/usr/bin/env bash
# ClearRead backup helper (Phase 6). Captures Postgres dump + notes for object-store sync.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/.clearread-run/backups/$(date -u +%Y%m%dT%H%M%SZ)}"
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

cat > "$OUT/MANIFEST.txt" <<EOF
ClearRead backup
created_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
postgres=postgres.dump
object_store=sync tenants/* prefixes from S3/MinIO/LocalStack separately
restore=see docs/phase-6/operator-runbook.md
EOF

echo "Backup complete: $OUT"
ls -la "$OUT"
