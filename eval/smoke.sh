#!/bin/sh
# End-to-end smoke test (INF-07, PLAN §9). Exit 0 only when a guide goes from upload to a valid GLB.
#   eval/smoke.sh [BASE] [KEY] [guide.pdf]
# BASE defaults to http://localhost:8000, KEY to DEMO_KEY in .env, the guide to the golden KNAGGLIG box.
set -eu
REPO=$(cd "$(dirname "$0")/.." && pwd)
PY="$REPO/.venv/bin/python"
[ -x "$PY" ] || PY=python3
exec "$PY" "$REPO/eval/smoke.py" "$@"
