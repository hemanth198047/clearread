#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="$ROOT/.clearread-run"
COMPOSE_FILE="${CLEARREAD_COMPOSE_FILE:-$ROOT/infra/compose.yaml}"
PROJECT="${CLEARREAD_COMPOSE_PROJECT:-clearread}"

if [[ -f "$PID_DIR/compose.file" ]]; then
  COMPOSE_FILE="$(cat "$PID_DIR/compose.file")"
fi
if [[ -f "$PID_DIR/compose.project" ]]; then
  PROJECT="$(cat "$PID_DIR/compose.project")"
fi

echo "Stopping ClearRead Compose project '$PROJECT' only..."
docker compose -p "$PROJECT" -f "$COMPOSE_FILE" down

# Stop locally-started API/web if pid files exist (dev hybrid mode)
for name in api.pid web.pid; do
  if [[ -f "$PID_DIR/$name" ]]; then
    pid="$(cat "$PID_DIR/$name" || true)"
    if [[ -n "${pid:-}" ]] && kill -0 "$pid" 2>/dev/null; then
      # verify command line belongs to ClearRead
      cmd="$(ps -p "$pid" -o args= 2>/dev/null || true)"
      if echo "$cmd" | grep -Eqi 'clearread|vite|spring-boot|clearread-api'; then
        echo "Stopping local process $pid ($name)"
        kill "$pid" 2>/dev/null || true
      else
        echo "Skipping pid $pid — does not look like a ClearRead process"
      fi
    fi
    rm -f "$PID_DIR/$name"
  fi
done

echo "ClearRead stopped."
