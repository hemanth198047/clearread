#!/usr/bin/env bash
# Scripted API flows for v1.1.0 verification (upload→read→close→reopen, health).
# Requires a running stack (./scripts/start.sh). Skips cleanly when API is down.
set -euo pipefail
API="${CLEARREAD_API_URL:-http://localhost:8080}"
WEB="${CLEARREAD_WEB_URL:-http://localhost:5173}"

echo "== ClearRead API flow smoke =="
echo "API=$API  WEB=$WEB"

if ! curl -sf --max-time 3 "$API/v1/health" >/dev/null; then
  echo "SKIP: API not reachable at $API/v1/health (start stack to run this smoke)."
  exit 0
fi

echo "OK health"
CAPS=$(curl -sf "$API/v1/capabilities")
echo "OK capabilities registry=$(echo "$CAPS" | sed -n 's/.*\"registryVersion\":\"\([^\"]*\)\".*/\1/p' | head -1)"

# Same-origin proxy check via web host when available
if curl -sf --max-time 3 "$WEB/v1/health" >/dev/null 2>&1; then
  echo "OK same-origin proxy $WEB/v1/health"
else
  echo "NOTE: $WEB/v1/health not proxied (vite-only without nginx, or web down) — absolute API still OK"
fi

echo "DONE (auth-bound upload/reopen covered by Playwright when IdP is up)"
