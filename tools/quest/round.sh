#!/usr/bin/env bash
# One full test round: doctor -> install both -> run Unity -> run Android -> compare -> judge packet.
# Usage: tools/quest/round.sh [--secs 120] [--job malm-drawer] [--lang el] [--hud]
#                             [--replay <dataset>] [--skip-install] [--no-pause] [--only unity|android]
#   --replay <dataset>  push tools/quest/replay/<dataset> and start both apps in replay mode;
#                       track events are then scored against the dataset's poses.jsonl
# Output: tools/quest/runs/<ts>-compare/judge.md (comparison + both summaries + scores + capture list)
source "$(dirname "$0")/lib.sh"

secs=120; job="malm-drawer"; lang="el"; hud=""; replay=""; install=1; pause=1; only=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --secs) secs="${2:?}"; shift 2 ;;
    --job) job="${2:?}"; shift 2 ;;
    --lang) lang="${2:?}"; shift 2 ;;
    --hud) hud="--hud"; shift ;;
    --replay) replay="${2:?--replay needs a dataset name}"; shift 2 ;;
    --skip-install) install=0; shift ;;
    --no-pause) pause=0; shift ;;
    --only) only="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
apps=(unity android); [[ -n "$only" ]] && apps=("$only")

echo "=== 1/4 doctor"
set +e; "$QUEST_DIR/doctor.sh"; rc=$?; set -e
[[ "$rc" -eq 0 ]] || die "doctor found blockers (exit $rc): fix them, then re-run"

if [[ "$install" -eq 1 ]]; then
  echo "=== 2/4 install"
  for app in "${apps[@]}"; do
    "$QUEST_DIR/install.sh" "$app" ${replay:+--replay "$replay"} || warn "install $app failed: running what is installed"
  done
fi

runs=()
for app in "${apps[@]}"; do
  echo "=== 3/4 run $app for ${secs}s"
  if [[ "$pause" -eq 1 ]]; then
    read -r -p "Headset on, drawer front in view. Press Enter to start $app... " _ </dev/tty || true
  fi
  "$QUEST_DIR/run.sh" "$app" --job "$job" --lang "$lang" --secs "$secs" $hud ${replay:+--replay}
  r="$(latest_run "$app")"; runs+=("$r")
  if [[ -n "$replay" ]]; then
    hand=""; [[ "$app" == unity ]] && hand="--unity"    # Unity logs track poses in Unity's left-handed axes
    "$PY" "$QUEST_DIR/replay/score_track.py" "$r" --dataset "$replay" $hand --quiet || warn "score_track: no usable track events in $r"
  fi
done

echo "=== 4/4 compare"
out="$RUNS_DIR/$(date +%Y%m%d-%H%M%S)-compare"
if [[ "${#runs[@]}" -ge 2 ]]; then
  "$PY" "$QUEST_DIR/compare.py" "${runs[@]}" --out "$out" --quiet
else
  mkdir -p "$out"; : > "$out/compare.md"
fi
{
  echo "# Quest test round $(date '+%Y-%m-%d %H:%M') (Athens)"
  echo
  echo "job $job, lang $lang, ${secs}s per app, replay ${replay:-off}, repo $(git -C "$REPO_ROOT" rev-parse --short HEAD)"
  echo
  cat "$out/compare.md"
  for r in "${runs[@]}"; do
    echo; cat "$r/summary.md" 2>/dev/null || echo "(no summary for $r)"
    [[ -f "$r/score.md" ]] && { echo; cat "$r/score.md"; }
    if [[ -d "$r/captures" ]]; then echo; echo "Captures: $(ls "$r/captures" | tr '\n' ' ')"; fi
    echo; echo "Run folder: $r"
  done
  echo
  echo "## Human checklist (fill in)"
  echo "- Ghost on the real drawer (unity / android): ? / ?"
  echo "- Jitter walking around: ? / ?"
  echo "- Step auto-advance: ? / ?"
  echo "- Voice: ? / ?"
  echo "- Notes:"
} > "$out/judge.md"
ok "judge packet: $out/judge.md"
