#!/bin/bash
# Host the demo on this Mac with a public HTTPS URL (PLAN §8.3 fallback, used because boxd sign-ups are closed).
#
#   deploy/mac_host.sh            # Ctrl-C stops everything
#
# - keeps the Mac awake while it runs (caffeinate)
# - builds client/ once if client/dist is missing
# - runs the server on 127.0.0.1 only (nothing is open on the network) and restarts it if it exits
# - runs a cloudflared quick tunnel and restarts it too; the URL changes when the tunnel restarts
# - writes the current URL to $HOST_DIR/url.txt and checks /healthz through it every 60 s
#
# Set AGJ_BUILD_AGENT=claude before running to read new guides with the build agent (uses this Mac's Claude login).
set -u
REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"
PORT=${PORT:-8000}
export JOBS_DIR=${JOBS_DIR:-$REPO/jobs}
HOST_DIR=${HOST_DIR:-$JOBS_DIR/_host}
mkdir -p "$HOST_DIR"
command -v cloudflared >/dev/null || { echo "cloudflared is missing: brew install cloudflared"; exit 1; }
grep -q '^DEMO_KEY=.\+' .env 2>/dev/null || { echo "DEMO_KEY is missing in .env: run 'make env'"; exit 1; }
KEY=$(sed -n 's/^DEMO_KEY=//p' .env | head -1)

if [ ! -f client/dist/index.html ]; then
  echo "== building the client"
  (cd client && npm ci --no-audit --no-fund && npm run build) || echo "client build failed: the API still works, / shows a notice"
fi

pids=()
cleanup() { echo; echo "== stopping"; kill "${pids[@]}" 2>/dev/null; pkill -P $$ 2>/dev/null; exit 0; }
trap cleanup INT TERM

caffeinate -dimsu -w $$ & pids+=($!)

( while true; do
    echo "$(date '+%F %T') server start" >> "$HOST_DIR/server.log"
    .venv/bin/python -W ignore -m uvicorn server.app:create_app --factory --host 127.0.0.1 --port "$PORT" \
      --timeout-graceful-shutdown 5 >> "$HOST_DIR/server.log" 2>&1
    echo "$(date '+%F %T') server exited ($?), restarting" >> "$HOST_DIR/server.log"; sleep 2
  done ) & pids+=($!)

( while true; do
    echo "$(date '+%F %T') tunnel start" >> "$HOST_DIR/tunnel.log"
    cloudflared tunnel --no-autoupdate --url "http://localhost:$PORT" >> "$HOST_DIR/tunnel.log" 2>&1
    echo "$(date '+%F %T') tunnel exited, restarting" >> "$HOST_DIR/tunnel.log"; sleep 2
  done ) & pids+=($!)

url() { grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$HOST_DIR/tunnel.log" | tail -1; }
for _ in $(seq 1 60); do [ -n "$(url)" ] && break; sleep 1; done
last=""
while true; do
  u=$(url)
  if [ -n "$u" ] && [ "$u" != "$last" ]; then
    echo "$u" > "$HOST_DIR/url.txt"
    echo "== public URL: $u"
    echo "   open on a phone:  $u/?key=$KEY"
    echo "   smoke test:       eval/smoke.sh $u"
    last=$u
  fi
  if [ -n "$u" ]; then
    if curl -fsS -m 15 -H "X-Demo-Key: $KEY" "$u/healthz" > "$HOST_DIR/health.json" 2>/dev/null; then
      echo "$(date '+%F %T') ok" > "$HOST_DIR/health.txt"
    else
      echo "$(date '+%F %T') healthz FAILED through $u" | tee -a "$HOST_DIR/health.txt"
    fi
  fi
  sleep 60
done
