#!/bin/bash
# Usage: bench_render.sh BLENDER_BIN BLENDFILE OUTDIR  -> prints RENDER json + wall time per run
BL="$1"; BLEND="$2"; OUT="$3"; HERE=$(cd "$(dirname "$0")" && pwd); mkdir -p "$OUT"
for cfg in "BLENDER_WORKBENCH 1 0" "BLENDER_WORKBENCH 1 1" "BLENDER_EEVEE 16 0" "BLENDER_EEVEE 16 1" "CYCLES 4 0" "CYCLES 16 0" "CYCLES 16 1"; do
  set -- $cfg
  for i in 1 2 3; do
    s=$(python3 -c 'import time;print(time.time())')
    line=$("$BL" -b "$BLEND" --python "$HERE/blender_render.py" -- $1 $2 $3 "$OUT/${1}_s${2}_fs${3}.png" 2>&1 | grep -E '^RENDER|Error' | head -3)
    e=$(python3 -c 'import time;print(time.time())')
    echo "$line wall=$(python3 -c "print(round($e-$s,2))")"
  done
done
