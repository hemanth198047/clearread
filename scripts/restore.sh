#!/usr/bin/env bash
# ClearRead restore helper (Phase 6). Restores a pg_dump custom-format file.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:?Usage: restore.sh /path/to/backup-dir-or-dump}"

DUMP="$SRC"
if [[ -d "$SRC" ]]; then
  DUMP="$SRC/postgres.dump"
fi
[[ -f "$DUMP" ]] || { echo "Missing dump: $DUMP" >&2; exit 1; }

echo "Restoring Postgres from $DUMP"
if command -v pg_restore >/dev/null 2>&1; then
  pg_restore --clean --if-exists --no-owner --dbname="${CLEARREAD_DB_URL:-postgresql://clearread:clearread@localhost:5432/clearread}" "$DUMP" || true
else
  docker compose -p clearread -f "$ROOT/infra/compose.yaml" exec -T db \
    pg_restore -U clearread -d clearread --clean --if-exists --no-owner < "$DUMP" || true
fi
echo "DB restore finished. Re-sync object store prefixes from the same backup window, then verify /actuator/health."
