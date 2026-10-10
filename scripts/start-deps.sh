#!/usr/bin/env bash
# Start only infrastructure dependencies (Postgres, LocalStack S3, Keycloak).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/infra/compose.yaml"
PROJECT="clearread"
mkdir -p "$ROOT/.clearread-run"
echo "$PROJECT" > "$ROOT/.clearread-run/compose.project"
echo "$COMPOSE_FILE" > "$ROOT/.clearread-run/compose.file"
docker compose -p "$PROJECT" -f "$COMPOSE_FILE" up -d db minio keycloak
echo "Deps started. API/web: see README hybrid section or ./scripts/start.sh"
