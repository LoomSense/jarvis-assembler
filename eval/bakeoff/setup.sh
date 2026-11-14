#!/bin/sh
# Make a clean workspace for one bake-off run: the pipeline, the guide pages and the brief. No golden files.
#   eval/bakeoff/setup.sh <workspace> <pages_dir> "<extra facts>"
set -eu
REPO=$(cd "$(dirname "$0")/../.." && pwd)
WS=$1; PAGES=$2
mkdir -p "$WS/guide" "$WS/job/parts" "$WS/pipeline"
for d in cad assemble blender; do mkdir -p "$WS/pipeline/$d"; cp "$REPO"/pipeline/$d/*.py "$WS/pipeline/$d/"; done
cp "$REPO/pipeline/__init__.py" "$REPO/pipeline/run.py" "$WS/pipeline/"
cp "$REPO/pipeline/agent/BRIEF.md" "$WS/BRIEF.md"
n=1; for f in $(ls "$PAGES"/hi-*.png | sort -t- -k2 -n); do cp "$f" "$WS/guide/page-$n.png"; n=$((n+1)); done
printf '%s\n' "$3" > "$WS/FACTS.md"
printf '#!/bin/sh\nexec "%s/.venv/bin/python" "$@"\n' "$REPO" > "$WS/py"; chmod +x "$WS/py"
echo "$WS"
