#!/usr/bin/env bash
# Screenshot + short clip of what the headset shows, saved into the app's latest run folder.
# Usage: tools/quest/capture.sh <unity|android> [--secs 10] [--run <run_dir>] [--label name]
# Quest captures come from the compositor's eye buffers: you may get both eyes side by side
# (lens-distorted), and the passthrough camera layer is often black. For a faithful view use
# tools/quest/mirror.sh (scrcpy) or casting from the Meta Horizon app.
source "$(dirname "$0")/lib.sh"

usage() { sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
[[ $# -ge 1 ]] || usage
app="$1"; shift
pkg_for "$app" >/dev/null
secs=10; run=""; label="cap"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --secs) secs="${2:?}"; shift 2 ;;
    --run) run="${2:?}"; shift 2 ;;
    --label) label="${2:?}"; shift 2 ;;
    -h|--help) usage ;;
    *) die "unknown argument: $1" ;;
  esac
done
require_device
if [[ -z "$run" ]]; then run="$(latest_run "$app")"; fi
if [[ -z "$run" ]]; then run="$RUNS_DIR/$(date +%Y%m%d-%H%M%S)-$app"; info "no run folder yet for $app: creating $run"; fi
mkdir -p "$run/captures"
t="$(date +%H%M%S)"
shot="$run/captures/$label-$t.png"
clip="$run/captures/$label-$t.mp4"

"$ADB" exec-out screencap -p > "$shot" 2>/dev/null || true
if [[ -s "$shot" ]]; then
  lum="$("$PY" - "$shot" <<'PYEOF' 2>/dev/null || echo "?"
import sys
from PIL import Image, ImageStat
im = Image.open(sys.argv[1]).convert("L"); im.thumbnail((256, 256))
print(round(ImageStat.Stat(im).mean[0], 1))
PYEOF
)"
  ok "screenshot $shot (mean luminance $lum/255)"
  if [[ "$lum" != "?" ]] && awk -v l="$lum" 'BEGIN{exit !(l < 4)}'; then
    warn "screenshot is (almost) black: the compositor layers/passthrough are not in screencap. Use tools/quest/mirror.sh --record"
  fi
else
  rm -f "$shot"; warn "screencap returned nothing"
fi

info "recording ${secs}s clip"
dev_clip="/sdcard/agq-clip-$t.mp4"
if "$ADB" shell screenrecord --time-limit "$secs" --bit-rate 8000000 "$dev_clip" >/dev/null 2>&1 \
   && "$ADB" pull "$dev_clip" "$clip" >/dev/null 2>&1 && [[ -s "$clip" ]]; then
  ok "clip $clip ($(du -h "$clip" | cut -f1))"
else
  rm -f "$clip"
  if command -v scrcpy >/dev/null 2>&1; then
    warn "screenrecord failed on the headset: recording with scrcpy instead"
    scrcpy --no-window --no-audio --max-fps 30 --time-limit "$secs" --record "$clip" >/dev/null 2>&1 || true
    [[ -s "$clip" ]] && ok "clip $clip ($(du -h "$clip" | cut -f1))" || bad "no clip (try tools/quest/mirror.sh --record)"
  else
    bad "screenrecord failed and scrcpy is missing: brew install scrcpy"
  fi
fi
"$ADB" shell rm -f "$dev_clip" >/dev/null 2>&1 || true
