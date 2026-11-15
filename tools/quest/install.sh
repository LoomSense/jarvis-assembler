#!/usr/bin/env bash
# Install the latest APK(s), grant runtime permissions, optionally push a replay dataset.
# Usage: tools/quest/install.sh <unity|android|both> [path/to.apk] [--replay <dataset>] [--no-install]
#   dataset: a folder name under tools/quest/replay/ (e.g. marker-synthetic) or a path.
source "$(dirname "$0")/lib.sh"

usage() { sed -n '2,4p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
[[ $# -ge 1 ]] || usage
target="$1"; shift
apk_arg=""; replay=""; do_install=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --replay) replay="${2:?--replay needs a dataset}"; shift 2 ;;
    --no-install) do_install=0; shift ;;
    -h|--help) usage ;;
    *.apk) apk_arg="$1"; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done
case "$target" in
  unity|android) apps=("$target") ;;
  both) apps=(unity android); [[ -z "$apk_arg" ]] || die "an explicit APK path only works with one app" ;;
  *) usage ;;
esac
require_device

replay_dir=""
if [[ -n "$replay" ]]; then
  if [[ -d "$replay" ]]; then replay_dir="$(cd "$replay" && pwd)"
  elif [[ -d "$QUEST_DIR/replay/$replay" ]]; then replay_dir="$QUEST_DIR/replay/$replay"
  else die "replay dataset '$replay' not found (looked in $QUEST_DIR/replay/). Generate it: see docs/QUEST_TESTING.md, Replay datasets"; fi
  [[ -f "$replay_dir/intrinsics.json" ]] || die "$replay_dir has no intrinsics.json (generate the dataset first)"
fi

rc=0
for app in "${apps[@]}"; do
  pkg="$(pkg_for "$app")"
  echo "== $app ($pkg)"
  if [[ "$do_install" -eq 1 ]]; then
    apk="${apk_arg:-$(latest_apk "$app")}"
    if [[ -z "$apk" ]]; then bad "no APK for $app (build it first, or pass a path)"; rc=1; continue; fi
    if [[ ! -f "$apk" ]]; then bad "APK not found: $apk"; rc=1; continue; fi
    info "installing ${apk#"$REPO_ROOT"/} ($(du -h "$apk" | cut -f1), built $(date -r "$apk" '+%a %H:%M:%S'))"
    # -r replace, -d allow downgrade (versionCode often stays 1), -g grant manifest runtime permissions.
    if ! out="$("$ADB" install -r -d -g "$apk" 2>&1)"; then
      if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE <<<"$out"; then
        warn "signature changed: uninstalling $pkg and retrying (app data is lost)"
        "$ADB" uninstall "$pkg" >/dev/null 2>&1 || true
        out="$("$ADB" install -r -d -g "$apk" 2>&1)" || { bad "$out"; rc=1; continue; }
      else bad "$out"; rc=1; continue; fi
    fi
    ok "installed $pkg $(pkg_version "$pkg")"
  else
    pkg_installed "$pkg" || { bad "$pkg not installed"; rc=1; continue; }
  fi
  for perm in "$HEADSET_CAMERA_PERM" android.permission.CAMERA android.permission.RECORD_AUDIO; do
    if msg="$("$ADB" shell pm grant "$pkg" "$perm" 2>&1 | tr -d '\r')" && [[ -z "$msg" ]]; then
      info "granted $perm"
    else
      warn "could not grant $perm (${msg:-error}); if the app needs it, accept the prompt in the headset"
    fi
  done
  # Spatial data (scene/anchors) is not adb-grantable on every OS version: try, tolerate.
  "$ADB" shell pm grant "$pkg" com.oculus.permission.USE_SCENE >/dev/null 2>&1 && info "granted com.oculus.permission.USE_SCENE" || true

  if [[ -n "$replay_dir" ]]; then
    dest="/sdcard/Android/data/$pkg/files/replay"
    n="$(find "$replay_dir" -maxdepth 1 -name '*.jpg' | wc -l | tr -d ' ')"
    info "pushing replay $(basename "$replay_dir") ($n frames, $(du -sh "$replay_dir" | cut -f1)) -> $dest"
    "$ADB" shell "rm -rf '$dest' && mkdir -p '$dest'" >/dev/null
    # Push the folder contents (not the folder) so files land directly in replay/.
    if "$ADB" push "$replay_dir/." "$dest/" >/dev/null 2>&1; then
      want="$(find "$replay_dir" -maxdepth 1 -type f | wc -l | tr -d ' ')"
      got="$("$ADB" shell ls "$dest" 2>/dev/null | tr -d '\r' | grep -c . || true)"
      if [[ "$got" -eq "$want" ]]; then ok "replay data on device: $got files in $dest"
      else warn "replay push: $got of $want files on device (check: adb shell ls $dest)"; fi
    else
      bad "adb push failed (start the app once so Android creates its data folder, then retry)"; rc=1
    fi
  fi
done
exit "$rc"
