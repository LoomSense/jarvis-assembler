#!/usr/bin/env bash
# Live view of the headset on the Mac (scrcpy), optionally recorded into a run folder.
# Usage: tools/quest/mirror.sh [--eye left|right|both] [--record [unity|android]] [--fps 30] [--audio]
#   --eye     crop to one eye (default left; both = the full side-by-side buffer)
#   --record  also save an .mp4 into that app's latest run folder (captures/mirror-HHMMSS.mp4)
#   --audio   forward the headset audio too (to hear the voice coach on the Mac)
source "$(dirname "$0")/lib.sh"
command -v scrcpy >/dev/null 2>&1 || die "scrcpy missing: brew install scrcpy"
eye="left"; record=""; fps=30; audio="--no-audio"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --eye) eye="${2:?}"; shift 2 ;;
    --record) if [[ "${2:-}" == unity || "${2:-}" == android ]]; then record="$2"; shift 2; else record="any"; shift; fi ;;
    --fps) fps="${2:?}"; shift 2 ;;
    --audio) audio=""; shift ;;
    -h|--help) sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
require_device

# Physical display size, e.g. "Physical size: 4128x2208" (both eyes side by side on Quest 3).
size="$(adb_sh wm size | sed -n 's/.*Physical size: *//p' | tail -1)"
w="${size%x*}"; h="${size#*x}"
args=(--window-title "Quest mirror ($eye eye)" --max-fps "$fps" --video-bit-rate 16M --stay-awake)
[[ -n "$audio" ]] && args+=("$audio")
if [[ "$w" =~ ^[0-9]+$ && "$h" =~ ^[0-9]+$ && "$eye" != both ]]; then
  half=$((w / 2))
  # Trim the lens-distorted edges a little (10% each side) so the view looks like one eye.
  cw=$((half * 8 / 10)); ch=$((h * 8 / 10)); cy=$((h / 10))
  if [[ "$eye" == left ]]; then cx=$((half / 10)); else cx=$((half + half / 10)); fi
  args+=(--crop "$cw:$ch:$cx:$cy")
  info "display ${w}x${h}: cropping $eye eye to ${cw}x${ch}+${cx}+${cy}"
else
  info "display size '${size:-unknown}': no crop"
fi
if [[ -n "$record" ]]; then
  app="$record"; [[ "$app" == any ]] && app=""
  run="$(latest_run "$app")"
  [[ -n "$run" ]] || run="$RUNS_DIR/$(date +%Y%m%d-%H%M%S)-${app:-mirror}"
  mkdir -p "$run/captures"
  out="$run/captures/mirror-$(date +%H%M%S).mp4"
  args+=(--record "$out")
  info "recording to $out"
fi
exec scrcpy "${args[@]}"
