#!/bin/bash
# Update the demo on this Mac to the latest `main`, while deploy/mac_host.sh keeps running in another terminal.
#   deploy/redeploy_mac.sh
# Fetches main into this checkout (the .demo-host worktree), rebuilds the client, restarts the server (the host
# script brings it back within seconds; the public URL does not change) and checks /healthz.
set -eu
cd "$(dirname "$0")/.."
git fetch -q origin
git checkout -q --detach origin/main
echo "== code: $(git log --oneline -1)"
(cd client && npm ci --no-audit --no-fund >/dev/null && npm run build >/dev/null) && echo "== client rebuilt"
pkill -f "uvicorn server.app:create_app" || true
for _ in $(seq 1 60); do curl -fsS -m 2 http://127.0.0.1:8000/healthz >/dev/null 2>&1 && break; sleep 1; done
KEY=$(sed -n 's/^DEMO_KEY=//p' .env | head -1)
echo "== health: $(curl -fsS -m 5 -H "X-Demo-Key: $KEY" http://127.0.0.1:8000/healthz)"
echo "== URL: $(cat jobs/_host/url.txt 2>/dev/null)"
