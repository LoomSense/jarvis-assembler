#!/usr/bin/env bash
# Launch an app with the shared intent extras and record its AGQ log into a run folder.
# Usage: tools/quest/run.sh <unity|android> [--job J] [--lang el|en] [--replay] [--hud] [--reduced-motion]
#                           [--secs N] [--no-launch] [--keep-running] [--note "text"]
#   extras (= the apps' Params): server, key, job, lang, replay, hud, reduced_motion
#   --secs N        stop after N seconds (default 120; 0 = until Ctrl-C)
#   --no-launch     do not start the app, only record (e.g. you launched it in the headset)
#   --keep-running  do not force-stop the app at the end
# Server URL and DEMO_KEY come from jobs/_host/url.txt and .env (override: AGQ_SERVER, AGQ_KEY).
# Output: tools/quest/runs/<YYYYmmdd-HHMMSS>-<app>/{agq.jsonl,logcat.txt,meta.json,summary.json,summary.md}
source "$(dirname "$0")/lib.sh"

usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
[[ $# -ge 1 ]] || usage
app="$1"; shift
pkg="$(pkg_for "$app")"
job="malm-drawer"; lang="el"; replay=false; hud=false; reduced=false; secs=120; launch=1; keep=0; note=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --job) job="${2:?}"; shift 2 ;;
    --lang) lang="${2:?}"; [[ "$lang" == el || "$lang" == en ]] || die "--lang must be el or en"; shift 2 ;;
    --replay) replay=true; shift ;;
    --hud) hud=true; shift ;;
    --reduced-motion) reduced=true; shift ;;
    --secs) secs="${2:?}"; shift 2 ;;
    --no-launch) launch=0; shift ;;
    --keep-running) keep=1; shift ;;
    --note) note="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) die "unknown argument: $1" ;;
  esac
done
require_device
pkg_installed "$pkg" || die "$pkg is not installed: tools/quest/install.sh $app"

server="$(server_url || true)"; key="$(demo_key || true)"
[[ -n "$server" ]] || warn "no server URL (jobs/_host/url.txt or AGQ_SERVER): launching without 'server'"
[[ -n "$key" ]] || warn "no DEMO_KEY (.env or AGQ_KEY): launching without 'key'"

stamp="$(date +%Y%m%d-%H%M%S)"
run="$RUNS_DIR/$stamp-$app"
mkdir -p "$run"
activity="$(resolve_activity "$pkg")"
version="$(pkg_version "$pkg")"

# Single-quote a value for the device shell.
q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

"$ADB" logcat -c 2>/dev/null || true
"$ADB" logcat -v raw -s AGQ:V 2>/dev/null | grep --line-buffered '^{' > "$run/agq.jsonl" &   # no `tr` here: it block-buffers and loses a short run's events when killed
pid_agq=$!
"$ADB" logcat -v threadtime 'AGQ:V' 'AndroidRuntime:E' 'Unity:W' 'DEBUG:E' 'libc:F' "*:E" 2>/dev/null > "$run/logcat.txt" &
pid_full=$!

cleanup_done=0
finish() {
  [[ "$cleanup_done" -eq 1 ]] && return; cleanup_done=1
  trap - INT TERM
  kill "$pid_tail" 2>/dev/null || true
  sleep 1                                     # let the last lines arrive
  kill "$pid_agq" "$pid_full" 2>/dev/null || true
  # $! is the last command of each pipeline: also stop the adb logcat processes feeding them.
  pkill -f "logcat -v raw -s AGQ:V" 2>/dev/null || true
  pkill -f "logcat -v threadtime AGQ:V" 2>/dev/null || true
  local ended; ended="$(date +%s)"
  if [[ "$keep" -eq 0 && "$launch" -eq 1 ]]; then "$ADB" shell am force-stop "$pkg" >/dev/null 2>&1 || true; fi
  "$PY" - "$run/meta.json" "$ended" <<'PYEOF'
import json, sys
p, ended = sys.argv[1], int(sys.argv[2])
m = json.load(open(p)); m["ended_epoch"] = ended; m["duration_s"] = ended - m["started_epoch"]
json.dump(m, open(p, "w"), indent=2)
PYEOF
  echo
  info "$(wc -l < "$run/agq.jsonl" | tr -d ' ') AGQ lines in $run/agq.jsonl"
  "$PY" "$QUEST_DIR/summarize.py" "$run" --quiet || warn "summarize failed"
  [[ -f "$run/summary.md" ]] && cat "$run/summary.md"
  echo
  ok "run folder: $run"
}
pid_tail=""
trap 'finish; exit 0' INT TERM

started="$(date +%s)"
launch_out=""
if [[ "$launch" -eq 1 ]]; then
  cmd="am start -S -W -n $(q "$activity") --es lang $(q "$lang") --es job $(q "$job") --ez replay $replay --ez hud $hud --ez reduced_motion $reduced"
  [[ -n "$server" ]] && cmd+=" --es server $(q "$server")"
  [[ -n "$key" ]] && cmd+=" --es key $(q "$key")"
  info "launching $activity  job=$job lang=$lang replay=$replay hud=$hud reduced_motion=$reduced server=${server:-none} key=$([[ -n $key ]] && echo '***' || echo none)"
  launch_out="$("$ADB" shell "$cmd" 2>&1 | tr -d '\r')"
  if grep -qi 'error' <<<"$launch_out"; then bad "am start: $(grep -i error <<<"$launch_out" | head -2)"; fi
fi
launch_ms="$(sed -n 's/^TotalTime: *//p' <<<"$launch_out" | head -1)"

M_APP="$app" M_PKG="$pkg" M_ACT="$activity" M_VER="$version" M_JOB="$job" M_LANG="$lang" \
M_REPLAY="$replay" M_HUD="$hud" M_REDUCED="$reduced" M_LAUNCH="$launch" M_LAUNCH_MS="$launch_ms" M_SERVER="$server" \
M_STARTED="$started" M_SECS="$secs" M_NOTE="$note" M_ADB="$ADB" M_ROOT="$REPO_ROOT" M_APK="$(latest_apk "$app")" \
"$PY" - "$run/meta.json" <<'PYEOF'
import json, os, subprocess, sys
e = os.environ
def sh(*a):
    try: return subprocess.run(a, capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception: return ""
adb = e["M_ADB"]
meta = {
  "app": e["M_APP"], "pkg": e["M_PKG"], "activity": e["M_ACT"], "version": e["M_VER"] or None,
  "job": e["M_JOB"], "lang": e["M_LANG"], "replay": e["M_REPLAY"] == "true", "hud": e["M_HUD"] == "true",
  "reduced_motion": e["M_REDUCED"] == "true",
  "launched": e["M_LAUNCH"] == "1",
  "launch_total_ms": int(e["M_LAUNCH_MS"]) if e["M_LAUNCH_MS"].isdigit() else None,
  "server": e["M_SERVER"] or None, "started_epoch": int(e["M_STARTED"]), "secs_requested": int(e["M_SECS"]),
  "note": e["M_NOTE"] or None,
  "device": {"model": sh(adb, "shell", "getprop", "ro.product.model"),
             "fingerprint": sh(adb, "shell", "getprop", "ro.build.fingerprint"),
             "serial": sh(adb, "get-serialno")},
  "repo_commit": sh("git", "-C", e["M_ROOT"], "rev-parse", "--short", "HEAD"),
  "apk": e["M_APK"] or None,
}
json.dump(meta, open(sys.argv[1], "w"), indent=2)
PYEOF

# Live view: every non-track event, and one track line every 5 s.
tail -n +1 -f "$run/agq.jsonl" 2>/dev/null | awk '
  /"ev" *: *"track"/ { if (++n % 5 != 1) next }
  { print substr($0, 1, 180); fflush() }' &
pid_tail=$!

if [[ "$secs" -gt 0 ]]; then
  info "recording for ${secs}s (Ctrl-C to stop early)"
  end=$((started + secs))
  while [[ "$(date +%s)" -lt "$end" ]]; do
    if ! kill -0 "$pid_agq" 2>/dev/null; then warn "logcat stopped (device unplugged?)"; break; fi
    sleep 1
  done
else
  info "recording until Ctrl-C"
  while kill -0 "$pid_agq" 2>/dev/null; do sleep 1; done
fi
finish
