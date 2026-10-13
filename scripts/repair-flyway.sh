#!/usr/bin/env bash
# Dev-only: repair Flyway checksums against local Compose Postgres.
# Does NOT run in production. Prefer fixing migrations (never edit applied V*) —
# this script only realigns flyway_schema_history after a local mismatch.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROJECT="${CLEARREAD_COMPOSE_PROJECT:-clearread}"
COMPOSE_FILE="${ROOT}/infra/compose.yaml"
MIGRATIONS="${ROOT}/backend/src/main/resources/db/migration"
FLYWAY_IMAGE="${CLEARREAD_FLYWAY_IMAGE:-flyway/flyway:10.22.0}"
DB_CONTAINER="${CLEARREAD_DB_CONTAINER:-clearread-db-1}"

if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

CLEARREAD_DB_USER="${CLEARREAD_DB_USER:-clearread}"
CLEARREAD_DB_PASSWORD="${CLEARREAD_DB_PASSWORD:-clearread}"
CLEARREAD_DB_NAME="${CLEARREAD_DB_NAME:-clearread}"

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: Docker CLI not found."
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker daemon not reachable. Start Docker Desktop / daemon first."
  exit 1
fi
if [[ ! -d "$MIGRATIONS" ]]; then
  echo "ERROR: Migrations directory missing: $MIGRATIONS"
  exit 1
fi

if ! docker inspect "$DB_CONTAINER" >/dev/null 2>&1; then
  echo "ERROR: DB container '$DB_CONTAINER' not found."
  echo "  Start deps first: ./scripts/start-deps.sh"
  echo "  Or wipe local DB volume (dev only):"
  echo "    docker compose -p $PROJECT -f infra/compose.yaml stop api"
  echo "    docker volume rm clearread_clearread_pg"
  exit 1
fi

STATUS="$(docker inspect -f '{{.State.Status}}' "$DB_CONTAINER" 2>/dev/null || true)"
if [[ "$STATUS" != "running" ]]; then
  echo "ERROR: DB container '$DB_CONTAINER' is not running (status=$STATUS)."
  echo "  Try: docker start $DB_CONTAINER"
  exit 1
fi

NETWORK="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$DB_CONTAINER")"
if [[ -z "$NETWORK" ]]; then
  echo "ERROR: Could not resolve Compose network for $DB_CONTAINER"
  exit 1
fi

echo "=== ClearRead Flyway repair (dev only) ==="
echo "Project:   $PROJECT"
echo "DB:        $DB_CONTAINER"
echo "Network:   $NETWORK"
echo "SQL:       $MIGRATIONS"
echo "Image:     $FLYWAY_IMAGE"
echo
echo "WARNING: This updates flyway_schema_history checksums to match local SQL."
echo "         Do not use against production databases."
echo

docker run --rm \
  --network "$NETWORK" \
  -v "$MIGRATIONS:/flyway/sql:ro" \
  "$FLYWAY_IMAGE" \
  -url="jdbc:postgresql://db:5432/${CLEARREAD_DB_NAME}" \
  -user="$CLEARREAD_DB_USER" \
  -password="$CLEARREAD_DB_PASSWORD" \
  -locations=filesystem:/flyway/sql \
  repair

echo
echo "OK  Flyway repair finished."
echo "Next: restart API (./scripts/start.sh or docker compose -p $PROJECT -f $COMPOSE_FILE up -d api)"
echo
echo "Nuclear alternative (dev wipe Postgres volume — destroys local data):"
echo "  docker compose -p $PROJECT -f infra/compose.yaml stop api"
echo "  docker volume rm clearread_clearread_pg"
echo "  ./scripts/start-deps.sh && ./scripts/start.sh"
