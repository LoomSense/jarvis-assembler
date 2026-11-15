# shellcheck shell=bash
# Shared helpers for tools/quest/*.sh. Source it; do not run it.
set -euo pipefail

QUEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$QUEST_DIR" rev-parse --show-toplevel 2>/dev/null || (cd "$QUEST_DIR/../.." && pwd))"
# jobs/ and .env are git-ignored, so a worktree may not have them: fall back to the main checkout.
MAIN_ROOT="$(git -C "$QUEST_DIR" worktree list --porcelain 2>/dev/null | awk '/^worktree /{print $2; exit}' || true)"
MAIN_ROOT="${MAIN_ROOT:-$REPO_ROOT}"
RUNS_DIR="${AGQ_RUNS_DIR:-$QUEST_DIR/runs}"
PY="${AGQ_PYTHON:-}"
if [[ -z "$PY" ]]; then
  for c in "$REPO_ROOT/.venv/bin/python" "$MAIN_ROOT/.venv/bin/python" python3; do
    if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
  done
fi

ADB="${ADB:-$(command -v adb || echo /opt/homebrew/bin/adb)}"
UNITY_PKG="com.agj.unity"
ANDROID_PKG="com.agj.android"
ANDROID_ACTIVITY="com.agj.android/.MainActivity"
UNITY_ACTIVITY_DEFAULT="com.agj.unity/com.unity3d.player.UnityPlayerGameActivity"
# shellcheck disable=SC2034  # used by the scripts that source this file
HEADSET_CAMERA_PERM="horizonos.permission.HEADSET_CAMERA"
# shellcheck disable=SC2034
MIN_HORIZON_OS=74   # Passthrough Camera API needs Horizon OS v74 or later

if [[ -t 1 ]]; then C_OK=$'\e[32m'; C_BAD=$'\e[31m'; C_WARN=$'\e[33m'; C_DIM=$'\e[2m'; C_0=$'\e[0m'
else C_OK=""; C_BAD=""; C_WARN=""; C_DIM=""; C_0=""; fi
ok()   { printf '%s[ ok ]%s %s\n' "$C_OK" "$C_0" "$*"; }
bad()  { printf '%s[FAIL]%s %s\n' "$C_BAD" "$C_0" "$*"; }
warn() { printf '%s[warn]%s %s\n' "$C_WARN" "$C_0" "$*"; }
info() { printf '%s[info]%s %s\n' "$C_DIM" "$C_0" "$*"; }
die()  { bad "$*"; exit 1; }

# First existing file among the worktree and the main checkout.
repo_file() {
  local rel="$1" root
  for root in "$REPO_ROOT" "$MAIN_ROOT"; do
    if [[ -f "$root/$rel" ]]; then echo "$root/$rel"; return 0; fi
  done
  return 1
}

pkg_for() {
  case "$1" in
    unity) echo "$UNITY_PKG" ;;
    android) echo "$ANDROID_PKG" ;;
    *) die "app must be 'unity' or 'android' (got '$1')" ;;
  esac
}

# Device state: "device", "unauthorized", "offline", "none", "many" or "noadb".
device_state() {
  if ! command -v "$ADB" >/dev/null 2>&1; then echo noadb; return; fi
  local lines n
  lines="$("$ADB" devices 2>/dev/null | awk 'NR>1 && NF>=2 {print $1" "$2}')"
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    printf '%s\n' "$lines" | awk -v s="$ANDROID_SERIAL" '$1==s{print $2; f=1} END{if(!f) print "none"}'
    return
  fi
  n="$(printf '%s\n' "$lines" | grep -c . || true)"
  if [[ "$n" -eq 0 ]]; then echo none
  elif [[ "$n" -gt 1 ]]; then echo many
  else printf '%s\n' "$lines" | awk '{print $2}'
  fi
}

require_device() {
  local st; st="$(device_state)"
  case "$st" in
    device) return 0 ;;
    noadb) die "adb not found. brew install --cask android-platform-tools" ;;
    none) die "No Quest attached. Plug in USB-C, put the headset on and accept 'Allow USB debugging'. Then run tools/quest/doctor.sh." ;;
    unauthorized) die "Quest attached but unauthorized: put the headset on and accept 'Allow USB debugging' (tick 'Always allow from this computer')." ;;
    many) die "Several adb devices: set ANDROID_SERIAL=<serial> (see 'adb devices')." ;;
    *) die "adb device state is '$st'. Try: adb kill-server && adb start-server" ;;
  esac
}

adb_sh() { "$ADB" shell "$@" 2>/dev/null | tr -d '\r'; }

# Launchable activity of a package, e.g. com.agj.unity/com.unity3d.player.UnityPlayerGameActivity
resolve_activity() {
  local pkg="$1" out
  out="$(adb_sh cmd package resolve-activity --brief "$pkg" | tail -1)"
  if [[ "$out" == */* ]]; then echo "$out"; return 0; fi
  case "$pkg" in
    "$UNITY_PKG") echo "$UNITY_ACTIVITY_DEFAULT" ;;
    "$ANDROID_PKG") echo "$ANDROID_ACTIVITY" ;;
  esac
}

pkg_installed() { adb_sh pm list packages "$1" | grep -qx "package:$1"; }
pkg_version() { adb_sh dumpsys package "$1" | awk -F= '/versionName=/{print $2; exit}'; }

latest_apk() {
  local app="$1" dir
  case "$app" in
    unity) dir="$REPO_ROOT/quest-unity/Builds" ;;
    android) dir="$REPO_ROOT/quest-android/app/build/outputs/apk/debug" ;;
  esac
  [[ -d "$dir" ]] || return 0
  find "$dir" -maxdepth 2 -name '*.apk' -print0 2>/dev/null | xargs -0 ls -t 2>/dev/null | head -1 || true
}

# Server URL: $AGQ_SERVER, else jobs/_host/url.txt.
server_url() {
  if [[ -n "${AGQ_SERVER:-}" ]]; then echo "$AGQ_SERVER"; return 0; fi
  local f; f="$(repo_file jobs/_host/url.txt)" || return 1
  head -1 "$f" | tr -d '[:space:]'
}

# DEMO_KEY: $AGQ_KEY, else .env. Never echo it to the terminal.
demo_key() {
  if [[ -n "${AGQ_KEY:-}" ]]; then echo "$AGQ_KEY"; return 0; fi
  local f; f="$(repo_file .env)" || return 1
  sed -n 's/^DEMO_KEY=//p' "$f" | head -1 | sed -e 's/^["'\'']//' -e 's/["'\'']$//'
}

# Latest run dir for an app ("unity", "android", or empty for any).
latest_run() {
  local app="${1:-}" d best="" best_m=0 m
  for d in "$RUNS_DIR"/*/; do
    [[ -d "$d" ]] || continue
    d="${d%/}"
    [[ "$d" == *-compare ]] && continue
    [[ -z "$app" || "$d" == *-"$app" ]] || continue
    m="$(stat -f %m "$d" 2>/dev/null || stat -c %Y "$d")"
    if [[ "$m" -ge "$best_m" ]]; then best="$d"; best_m="$m"; fi
  done
  [[ -n "$best" ]] && echo "$best"
  return 0
}
