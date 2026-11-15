#!/usr/bin/env bash
# Pull the newest frame recording from the headset (quest-android vision/FrameDump.kt, `--ez dump_frames true`).
# Usage: tools/markerless/pull.sh [--session <yyyyMMdd-HHmmss>] [--run <run_dir>] [--list] [--delete]
#   --session  a named session instead of the newest one       --list    only list the sessions on the headset
#   --run      the run folder to put it in (default: the latest tools/quest/runs/*-android, else a new one)
#   --delete   remove the session from the headset after a complete pull
# Writes <run>/dump/ (JPEGs, poses.jsonl, intrinsics.json, session.json). tools/quest/runs/ is git-ignored:
# the pictures show your room, keep them out of git. Then: python tools/markerless/eval_real.py <run>/dump
source "$(dirname "$0")/../quest/lib.sh"

session=""; run=""; list=0; delete=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --session) session="${2:?}"; shift 2 ;;
    --run) run="${2:?}"; shift 2 ;;
    --list) list=1; shift ;;
    --delete) delete=1; shift ;;
    -h|--help) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
require_device
base="/sdcard/Android/data/$ANDROID_PKG/files/dump"
sessions="$(adb_sh ls -1 "$base" | grep -E '^[0-9]{8}-[0-9]{6}$' | sort || true)"
[[ -n "$sessions" ]] || die "no recording in $base. Launch with: tools/quest/run.sh android --dump (or --ez dump_frames true) and look for the AGQ 'dump' event."
if [[ "$list" -eq 1 ]]; then
  while read -r s; do echo "$s  $(adb_sh ls -1 "$base/$s" | grep -c '\.jpg$' || true) frames  $(adb_sh du -sh "$base/$s" | cut -f1)"; done <<<"$sessions"
  exit 0
fi
[[ -n "$session" ]] || session="$(tail -1 <<<"$sessions")"
grep -qx "$session" <<<"$sessions" || die "session '$session' is not on the headset (tools/markerless/pull.sh --list)"
if [[ -z "$run" ]]; then
  run="$(latest_run android)"
  # the latest run already has a recording (or there is no run yet): a folder of its own, named after the session
  if [[ -z "$run" || -e "$run/dump/poses.jsonl" ]]; then run="$RUNS_DIR/$session-android"; fi
fi
dest="$run/dump"
[[ ! -e "$dest/poses.jsonl" ]] || die "$dest already holds a recording: pass --run <other folder> or move it away"
mkdir -p "$dest"
dest="$(cd "$dest" && pwd)"
if [[ "$dest" == "$REPO_ROOT"/* ]] && ! git -C "$REPO_ROOT" check-ignore -q "$dest/x.jpg"; then
  warn "$dest is inside the repository and NOT git-ignored: do not commit the pictures (they show your room)"
fi
want="$(adb_sh ls -1 "$base/$session" | grep -c . || true)"
info "pulling $session ($want files, $(adb_sh du -sh "$base/$session" | cut -f1)) -> $dest"
"$ADB" pull "$base/$session/." "$dest/" >/dev/null || die "adb pull failed"
rm -f "$dest"/*.part
got="$(find "$dest" -maxdepth 1 -type f | wc -l | tr -d ' ')"
frames="$(find "$dest" -maxdepth 1 -name '*.jpg' | wc -l | tr -d ' ')"
lines="$(grep -c . "$dest/poses.jsonl" 2>/dev/null || echo 0)"
[[ -f "$dest/poses.jsonl" && -f "$dest/intrinsics.json" ]] || die "incomplete: poses.jsonl or intrinsics.json missing in $dest"
ok "$frames pictures, $lines lines in poses.jsonl, $got files, $(du -sh "$dest" | cut -f1) in $dest"
[[ "$frames" -eq "$lines" ]] || warn "$frames pictures but $lines lines (the app was stopped mid-frame: the tool reads the lines)"
if [[ "$delete" -eq 1 ]]; then "$ADB" shell rm -rf "$base/$session" && ok "removed $session from the headset"; fi
echo
echo "next: $PY tools/markerless/eval_real.py $dest"
