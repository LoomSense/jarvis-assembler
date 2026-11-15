#!/usr/bin/env bash
# Checks everything a Quest test round needs and prints what is missing.
# Usage: tools/quest/doctor.sh        Exit code: 0 ready, 1 something blocks a round, 2 no device.
source "$(dirname "$0")/lib.sh"
set +e
problems=0
blockers=0
fail() { bad "$*"; blockers=$((blockers + 1)); }
soft() { warn "$*"; problems=$((problems + 1)); }

echo "== Mac side"
if command -v "$ADB" >/dev/null 2>&1; then ok "adb: $("$ADB" version | head -1) ($ADB)"; else fail "adb missing: brew install --cask android-platform-tools"; fi
if command -v scrcpy >/dev/null 2>&1; then ok "scrcpy: $(scrcpy --version 2>/dev/null | head -1 | awk '{print $2}') (live mirror: tools/quest/mirror.sh)"; else soft "scrcpy missing (live mirror): brew install scrcpy"; fi
if [[ -n "$PY" ]] && "$PY" -c 'import cv2, numpy' 2>/dev/null; then ok "python with cv2: $PY"; else soft "no python with opencv: $REPO_ROOT/.venv/bin/pip install opencv-contrib-python (needed by replay generator + score_track)"; fi
if url="$(server_url)" && [[ -n "$url" ]]; then
  code="$(curl -s -o /dev/null -m 6 -w '%{http_code}' "$url/healthz")"
  if [[ "$code" == "200" ]]; then ok "server $url/healthz -> 200"; else soft "server $url/healthz -> ${code:-no answer} (is the tunnel up? make host)"; fi
else soft "no server URL: jobs/_host/url.txt missing and AGQ_SERVER unset"; fi
if key="$(demo_key)" && [[ -n "$key" ]]; then ok "DEMO_KEY found in .env (${#key} chars, not shown)"; else soft "DEMO_KEY missing: make env, or export AGQ_KEY"; fi
for app in unity android; do
  apk="$(latest_apk "$app")"
  if [[ -n "$apk" ]]; then ok "$app APK: ${apk#"$REPO_ROOT"/} ($(date -r "$apk" '+%a %H:%M'))"; else soft "$app APK not built yet (expected under $([[ $app == unity ]] && echo quest-unity/Builds/ || echo quest-android/app/build/outputs/apk/debug/))"; fi
done

echo "== Headset"
st="$(device_state)"
case "$st" in
  device) ok "adb device connected (${ANDROID_SERIAL:-$("$ADB" get-serialno 2>/dev/null)})" ;;
  none)
    bad "NO DEVICE: no Quest is attached over adb."
    cat <<'TXT'
       1. Developer mode: Meta Horizon phone app > Menu > Devices > Headset settings > Developer mode ON
          (needs a verified developer account in the same Meta account).
       2. Plug the headset into the Mac with a USB-C data cable (not charge-only).
       3. Put the headset on and accept "Allow USB debugging?" (tick "Always allow from this computer").
       4. Re-run tools/quest/doctor.sh.   Wireless: adb tcpip 5555 && adb connect <quest-ip>:5555
TXT
    echo "Summary: Mac side has $blockers blocker(s), $problems warning(s); headset checks skipped."
    exit 2 ;;
  unauthorized) fail "device unauthorized: put the headset on and accept 'Allow USB debugging'"; exit 1 ;;
  many) fail "several adb devices: export ANDROID_SERIAL=<serial>"; "$ADB" devices -l; exit 1 ;;
  *) fail "adb device state '$st': adb kill-server && adb start-server"; exit 1 ;;
esac

props="$(adb_sh getprop)"
prop() { printf '%s\n' "$props" | sed -n "s/^\[$1\]: \[\(.*\)\]$/\1/p" | head -1; }
model="$(prop ro.product.model)"; manuf="$(prop ro.product.manufacturer)"
sdk="$(prop ro.build.version.sdk)"; rel="$(prop ro.build.version.release)"
fp="$(prop ro.build.fingerprint)"; disp="$(prop ro.build.display.id)"
info "model: $manuf $model   android: $rel (sdk $sdk)"
info "fingerprint: $fp"
info "display id: $disp"
case "$model" in
  *"Quest 3"*|*eureka*|*panther*) ok "headset model: $model" ;;
  *) soft "headset model '$model' is not a Quest 3/3S: the passthrough camera API is Quest 3/3S only" ;;
esac
# Horizon OS version: look at vendor props for a major version number.
hz=""
while IFS= read -r line; do
  v="$(printf '%s' "$line" | sed -n 's/^\[[^]]*\]: \[v\{0,1\}\([0-9][0-9]*\).*\]$/\1/p')"
  if [[ -n "$v" && "$v" -ge 40 && "$v" -le 400 ]]; then hz="$v"; info "OS prop: $line"; break; fi
done < <(printf '%s\n' "$props" | grep -Ei '^\[(ro\.(vros|ovr|oculus)[^]]*(version|release)[^]]*)\]')
printf '%s\n' "$props" | grep -Ei '^\[ro\.(vros|ovr|oculus)' | head -8 | sed 's/^/       /'
if [[ -n "$hz" ]]; then
  if [[ "$hz" -ge "$MIN_HORIZON_OS" ]]; then ok "Horizon OS v$hz (>= v$MIN_HORIZON_OS needed for the passthrough camera API)"
  else fail "Horizon OS v$hz is too old for the passthrough camera API (needs v$MIN_HORIZON_OS+): Settings > General > Software update"; fi
elif [[ -n "$sdk" && "$sdk" -lt 32 ]]; then
  fail "Android sdk $sdk: Horizon OS far too old for the passthrough camera API (needs v$MIN_HORIZON_OS+). Update the headset."
else
  soft "Horizon OS version not readable from props: check Settings > General > About (needs v$MIN_HORIZON_OS+ for the passthrough camera API)"
fi
dev="$(adb_sh settings get global development_settings_enabled)"
[[ "$dev" == "1" ]] && ok "developer settings enabled" || info "development_settings_enabled=$dev (adb works, so developer mode is on)"
bv="$(adb_sh dumpsys package com.oculus.browser | awk -F= '/versionName=/{print $2; exit}')"
[[ -n "$bv" ]] && ok "Quest Browser $bv (web client fallback)" || soft "Quest Browser not found"
batt="$(adb_sh dumpsys battery | awk -F': ' '/ level:/{print $2; exit}')"
if [[ -n "$batt" ]]; then
  if [[ "$batt" -lt 30 ]]; then soft "battery $batt%: charge before a round (a 5 min round uses ~5-8%)"; else ok "battery $batt%"; fi
fi
free_kb="$(adb_sh df -k /sdcard | awk 'NR==2{print $4}')"
if [[ -n "$free_kb" ]]; then
  free_mb=$((free_kb / 1024))
  if [[ "$free_mb" -lt 2048 ]]; then soft "free space on /sdcard: ${free_mb} MB (< 2 GB)"; else ok "free space on /sdcard: $((free_mb / 1024)) GB"; fi
fi
info "tip: keep the headset awake on the desk: adb shell am broadcast -a com.oculus.vrpowermanager.prox_close (undo: .automation_disable)"

echo "== Apps"
for app in unity android; do
  pkg="$(pkg_for "$app")"
  if pkg_installed "$pkg"; then
    dump="$(adb_sh dumpsys package "$pkg")"
    ver="$(printf '%s\n' "$dump" | awk -F= '/versionName=/{print $2; exit}')"
    code="$(printf '%s\n' "$dump" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)"
    upd="$(printf '%s\n' "$dump" | awk -F= '/lastUpdateTime=/{print $2; exit}')"
    act="$(resolve_activity "$pkg")"
    ok "$pkg $ver ($code), updated $upd, launch: $act"
    for perm in "$HEADSET_CAMERA_PERM" android.permission.CAMERA android.permission.RECORD_AUDIO; do
      g="$(printf '%s\n' "$dump" | grep -m1 "$perm: granted=" | sed 's/.*granted=\([a-z]*\).*/\1/')"
      case "$g" in
        true) info "  $perm granted" ;;
        false) soft "  $pkg: $perm NOT granted (tools/quest/install.sh $app grants it, or accept the prompt in the headset)" ;;
        *) info "  $perm not requested by the app" ;;
      esac
    done
    rp="$(adb_sh ls "/sdcard/Android/data/$pkg/files/replay/intrinsics.json")"
    [[ -n "$rp" ]] && info "  replay data present on device" || info "  no replay data on device (install.sh $app --replay <dataset>)"
  else
    soft "$pkg not installed: tools/quest/install.sh $app"
  fi
done

echo
if [[ "$blockers" -gt 0 ]]; then bad "$blockers blocker(s), $problems warning(s)"; exit 1; fi
if [[ "$problems" -gt 0 ]]; then warn "ready with $problems warning(s)"; else ok "ready for a test round"; fi
exit 0
