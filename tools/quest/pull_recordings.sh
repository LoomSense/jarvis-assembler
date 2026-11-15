#!/usr/bin/env bash
# Quest recordings for the demo video: list and pull today's recordings from the headset, then cut a span of
# a take into a headset shot of the cut (<cut>/headset/H<n>.mp4), so one take can feed several shots.
# The only adb commands it runs are "adb devices", "adb shell ls" and "adb pull": it never touches the app.
# Usage: tools/quest/pull_recordings.sh [list | pull | map | durations] ...   (-h for the whole session)
source "$(dirname "$0")/lib.sh"

SELF="$0"
REMOTE_DIR="${AGQ_VIDEO_DIR:-/sdcard/Oculus/VideoShots}"
FFMPEG="${FFMPEG:-$(command -v ffmpeg || echo /opt/homebrew/bin/ffmpeg)}"
FFPROBE="${FFPROBE:-$(command -v ffprobe || echo /opt/homebrew/bin/ffprobe)}"
TAB="$(printf '\t')"

# The cut is git-ignored and lives in the main checkout: $AGJ_CUT_DIR, else this checkout's, else the main one's.
cut_dir() {
  if [[ -n "${AGJ_CUT_DIR:-}" ]]; then echo "$AGJ_CUT_DIR"; return 0; fi
  if [[ -d "$REPO_ROOT/jobs/_media/cut" ]]; then echo "$REPO_ROOT/jobs/_media/cut"; return 0; fi
  local main
  main="$(git -C "$QUEST_DIR" worktree list --porcelain 2>/dev/null \
    | awk '/^worktree /{sub(/^worktree /, ""); print; exit}' || true)"
  echo "${main:-$REPO_ROOT}/jobs/_media/cut"
}
CUT_DIR="$(cut_dir)"
HEADSET_DIR="$CUT_DIR/headset"
RAW_DIR="$HEADSET_DIR/raw"
MAP_FILE="$HEADSET_DIR/map.txt"

usage() {
  cat <<EOF
Quest recordings for the demo video.

  $SELF [list] [--date YYYY-MM-DD | --all]
        the videos recorded today on the headset ($REMOTE_DIR), newest last,
        and what is already in headset/raw/. Without a headset it says so and lists only the raw folder.
  $SELF pull [N | <name> ...] [--date YYYY-MM-DD | --all]
        pull the newest recording of today (N: the newest N; names: those files) into headset/raw/,
        print duration, size and frame rate, and the map lines to edit. A file already there with the
        same size is not pulled again.
  $SELF map <H1..H8> <raw file> <in> <out>
        cut that span of a recording into headset/H<n>.mp4, exact to the frame (re-encoded, sound kept),
        and note it in headset/map.txt. Times are seconds in the recording (12.5) or m:ss (0:12.5).
        The raw file may be absolute, relative to here, to headset/ (raw/xxx.mp4) or to headset/raw/.
        Call it once per shot: one take can feed several shots.
  $SELF map            apply every line of headset/map.txt again
  $SELF map --show     print headset/map.txt
  $SELF durations      durations of everything in headset/raw/ and of each headset/H*.mp4

One recording session, in order:
  1. On the headset: Meta button -> Camera -> Record video; stop it the same way.
     Never record with the debug HUD on: it shows the server address.
  2. $SELF pull                           (pull 3: the newest three of today)
  3. $SELF map H2 raw/<file> 12.0 30.0    (one line per shot; open the raw file to find the times)
  4. cd "$CUT_DIR" && ./build.sh

A mapped clip is already trimmed: the shot's in and out in shots.txt count from 0 of the mapped clip,
so leave "in" at 0 there. Cut folder: $CUT_DIR  (override with AGJ_CUT_DIR).
EOF
}

# ---- small helpers ------------------------------------------------------------------------------------------

# flt 3.5 "<" 4   compares two decimal numbers.
flt() { awk -v a="$1" -v b="$3" 'BEGIN { exit !(a '"$2"' b) }'; }

# Seconds from "83.5", "1:23.5" or "0:01:23.5", printed with one to three decimals. Fails on anything else.
to_secs() {
  awk -v t="$1" 'BEGIN {
    if (t !~ /^([0-9]+:)?([0-9]+:)?[0-9]+([.][0-9]+)?$/) exit 1
    n = split(t, p, ":"); s = 0
    for (i = 1; i <= n; i++) s = s * 60 + p[i]
    out = sprintf("%.3f", s); sub(/0+$/, "", out); sub(/[.]$/, ".0", out); print out
  }'
}

fsize() { wc -c < "$1" 2>/dev/null | tr -d '[:space:]' || echo 0; }
mb() { awk -v s="$1" 'BEGIN { printf "%.1f MB", s / 1048576 }'; }
is_video() { case "$1" in *.[mM][pP]4|*.[mM][oO][vV]|*.[mM]4[vV]) return 0 ;; *) return 1 ;; esac; }
have_ffprobe() { command -v "$FFPROBE" >/dev/null 2>&1; }

# Duration in seconds, or nothing when ffprobe cannot read the file (an unfinished recording has no index).
dur_of() {
  have_ffprobe || return 0
  "$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$1" </dev/null 2>/dev/null | head -1 || true
}

# "  40.00 s  1024x1024  30.00 fps  sound" for a video file.
describe() {
  local f="$1" d v a
  d="$(dur_of "$f")"
  if [[ -z "$d" ]]; then printf 'not readable by ffprobe'; return 0; fi
  v="$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=width,height,avg_frame_rate \
        -of csv=p=0 "$f" </dev/null 2>/dev/null | head -1 || true)"
  a="$("$FFPROBE" -v error -select_streams a:0 -show_entries stream=codec_name \
        -of csv=p=0 "$f" </dev/null 2>/dev/null | head -1 || true)"
  awk -v d="$d" -v v="$v" -v a="$a" 'BEGIN {
    n = split(v, p, ","); fps = "?"
    if (n >= 3 && split(p[3], q, "/") == 2 && q[2] > 0) fps = sprintf("%.2f", q[1] / q[2])
    printf "%7.2f s  %sx%s  %s fps  %s", d, (n >= 2 ? p[1] : "?"), (n >= 2 ? p[2] : "?"), fps, (a != "" ? "sound" : "no sound")
  }'
}

no_device_line() {
  case "$1" in
    noadb) echo "adb not found: nothing read from the headset" ;;
    none) echo "no headset answers on adb: nothing read from it (recordings stay on the headset until pulled)" ;;
    unauthorized) echo "headset attached but unauthorized (accept 'Allow USB debugging' in it): nothing read from it" ;;
    many) echo "several adb devices: set ANDROID_SERIAL=<serial> (see 'adb devices'); nothing read from the headset" ;;
    *) echo "adb device state is '$1': nothing read from the headset" ;;
  esac
}

# The headset's recordings as "date<TAB>time<TAB>size<TAB>name", oldest first (toybox "ls -l": the date column
# is YYYY-MM-DD HH:MM; recordings of the same minute are ordered by the time in their names).
remote_videos() {
  local out rows
  out="$("$ADB" shell ls -l "$REMOTE_DIR/" </dev/null 2>/dev/null | tr -d '\r')" || return 1
  rows="$(printf '%s\n' "$out" | awk '
    /^-/ && NF >= 8 && $6 ~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]$/ {
      name = $0
      for (i = 1; i <= 7; i++) sub(/^[ \t]*[^ \t]+[ \t]+/, "", name)
      if (tolower(name) !~ /[.](mp4|mov|m4v)$/) next
      key = name
      if (match(name, /[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][-_][0-9][0-9][0-9][0-9][0-9][0-9]/))
        key = substr(name, RSTART, RLENGTH)
      printf "%s\t%s\t%s\t%s\t%s\n", $6, $7, key, $5, name
    }' | sort -t "$TAB" -k1,1 -k2,2 -k3,3 | cut -f1,2,4,5)"
  if [[ -z "$rows" ]] && grep -qiE '[.](mp4|mov|m4v)$' <<<"$out"; then
    warn "the headset lists videos, but not in the 'ls -l' layout this script reads. First line: $(grep -iE '[.](mp4|mov|m4v)$' <<<"$out" | head -1)" >&2
  fi
  printf '%s\n' "$rows"
}

# What a shot list uses of each headset shot: "H2 18.0 6 fit" (shot, seconds of the clip, shot ids, fit or -).
shot_needs() {
  local f="$1"
  [[ -f "$f" ]] || return 0
  awk -F'|' '
    /^[ \t]*#/ || NF < 4 { next }
    {
      id = $1; src = $2; out = $4
      gsub(/[ \t]/, "", id); gsub(/[ \t]/, "", src); gsub(/[ \t]/, "", out)
      if (src !~ /^H[0-9]+$/) next
      if (!(src in need) || out + 0 > need[src]) need[src] = out + 0
      ids[src] = (src in ids) ? ids[src] "," id : id
      if ($0 ~ /speed=fit/) fit[src] = 1
    }
    END { for (s in need) printf "%s %.1f %s %s\n", s, need[s], ids[s], (s in fit) ? "fit" : "-" }' "$f" | sort
}

# Warn when a shot's clip is shorter than what the shot lists use of it.
check_need() {
  local shot="$1" dur="$2" f s need ids fit
  [[ -n "$dur" ]] || return 0
  for f in shots.txt shots-60s.txt; do
    while read -r s need ids fit; do
      if [[ "$s" != "$shot" || "$fit" == fit ]]; then continue; fi
      if flt "$dur" "<" "$(awk -v n="$need" 'BEGIN { print n - 0.05 }')"; then
        warn "$f uses $shot up to $need s (shot $ids) but the clip is $(printf '%.2f' "$dur") s: map a longer span or lower that shot's out time"
      fi
    done <<<"$(shot_needs "$CUT_DIR/$f")"
  done
  return 0
}

# The map lines to edit for a pulled recording: one per headset shot of shots.txt, with the length it uses.
print_map_lines() {
  local rel="$1" dur="$2" needs shot need ids fit out note
  needs="$(shot_needs "$CUT_DIR/shots.txt")"
  if [[ -z "$needs" ]]; then
    printf '    %s map H1 %q 0.0 %s\n' "$SELF" "$rel" "$(to_secs "$dur")"
    return 0
  fi
  while read -r shot need ids fit; do
    if [[ -z "$shot" ]]; then continue; fi
    out="$need"
    if flt "$out" ">" "$dur"; then out="$(to_secs "$dur")"; fi
    note="shot $ids uses $need s"
    if [[ "$fit" == fit ]]; then note="shot $ids: any length, sped up or slowed to fill its slot"; fi
    printf '    %s map %s %q 0.0 %s    # %s\n' "$SELF" "$shot" "$rel" "$out" "$note"
  done <<<"$needs"
}

list_raw() {
  local f any=0
  echo "In $RAW_DIR:"
  if [[ -d "$RAW_DIR" ]]; then
    for f in "$RAW_DIR"/*; do
      if [[ -f "$f" ]] && is_video "$f"; then
        any=1
        printf '  %-44s %s  %s\n' "$(basename "$f")" "$(describe "$f")" "$(mb "$(fsize "$f")")"
      fi
    done
  fi
  if [[ "$any" -eq 0 ]]; then echo "  (nothing pulled yet)"; fi
}

# ---- list ---------------------------------------------------------------------------------------------------

DAY="$(date +%F)"
POSITIONAL=()
parse_day_opts() {
  local re='^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
  POSITIONAL=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --date) DAY="${2:-}"; [[ "$DAY" =~ $re ]] || die "--date needs YYYY-MM-DD (got '$DAY')"; shift 2 ;;
      --all) DAY=""; shift ;;
      -h|--help) usage; exit 0 ;;
      --*) die "unknown option: $1" ;;
      *) POSITIONAL+=("$1"); shift ;;
    esac
  done
}

cmd_list() {
  parse_day_opts "$@"
  if [[ ${#POSITIONAL[@]} -gt 0 ]]; then die "list takes no file names (got '${POSITIONAL[0]}')"; fi
  local st rows sel d t size name mark what="recorded on $DAY"
  if [[ -z "$DAY" ]]; then what="all recordings"; fi
  st="$(device_state)"
  if [[ "$st" != device ]]; then
    info "$(no_device_line "$st")"
  elif ! rows="$(remote_videos)"; then
    warn "cannot list $REMOTE_DIR on the headset (no recording made yet?)"
  else
    sel="$(printf '%s\n' "$rows" | awk -F'\t' -v d="$DAY" 'NF >= 4 && (d == "" || $1 == d)')"
    echo "On the headset, $what ($REMOTE_DIR), newest last:"
    if [[ -z "$sel" ]]; then
      echo "  (none; --all lists every recording)"
    else
      while IFS="$TAB" read -r d t size name; do
        if [[ -z "$name" ]]; then continue; fi
        mark=""
        if [[ -f "$RAW_DIR/$name" && "$(fsize "$RAW_DIR/$name")" == "$size" ]]; then mark="  (already in raw/)"; fi
        printf '  %s %s  %10s  %s%s\n' "$d" "$t" "$(mb "$size")" "$name" "$mark"
      done <<<"$sel"
    fi
  fi
  list_raw
}

# ---- pull ---------------------------------------------------------------------------------------------------

cmd_pull() {
  parse_day_opts "$@"
  local st rows sel="" n="" d t size name dest part got dur row fails=0 count=0 digits='^[0-9]+$'
  if [[ ${#POSITIONAL[@]} -eq 1 && "${POSITIONAL[0]}" =~ $digits ]]; then
    n="${POSITIONAL[0]}"
    if [[ "$n" -lt 1 ]]; then die "pull N: N must be 1 or more"; fi
  elif [[ ${#POSITIONAL[@]} -eq 0 ]]; then
    n=1
  fi
  st="$(device_state)"
  if [[ "$st" != device ]]; then info "$(no_device_line "$st")"; return 0; fi
  if ! rows="$(remote_videos)"; then
    warn "cannot list $REMOTE_DIR on the headset (no recording made yet?)"; return 0
  fi
  if [[ -n "$n" ]]; then
    sel="$(printf '%s\n' "$rows" | awk -F'\t' -v d="$DAY" 'NF >= 4 && (d == "" || $1 == d)' | tail -n "$n")"
    if [[ -z "$sel" ]]; then
      info "no video ${DAY:+recorded on $DAY }on the headset ($SELF list --all shows every recording)"; return 0
    fi
  else
    for name in "${POSITIONAL[@]}"; do
      row="$(printf '%s\n' "$rows" | NAME="${name##*/}" awk -F'\t' '$4 == ENVIRON["NAME"]' | tail -n 1)"
      if [[ -z "$row" ]]; then bad "not on the headset: ${name##*/}"; fails=1; continue; fi
      sel="${sel:+$sel
}$row"
    done
  fi
  mkdir -p "$RAW_DIR"
  while IFS="$TAB" read -r d t size name; do
    if [[ -z "$name" ]]; then continue; fi
    dest="$RAW_DIR/$name"; part="$RAW_DIR/.part.$name"
    if [[ -f "$dest" && "$(fsize "$dest")" == "$size" ]]; then
      info "already in raw/ with the same size, not pulled again: $name"
    else
      rm -f "$part"
      if ! "$ADB" pull -a "$REMOTE_DIR/$name" "$part" </dev/null; then
        bad "adb pull failed: $name"; rm -f "$part"; fails=1; continue
      fi
      got="$(fsize "$part")"
      if [[ "$got" != "$size" ]]; then
        bad "$name: $got bytes pulled, the headset listed $size. Still recording? Stop the recording and pull again."
        rm -f "$part"; fails=1; continue
      fi
      mv -f "$part" "$dest"; chmod u+rw "$dest"
      ok "pulled $name ($(mb "$size"), recorded $d $t)"
    fi
    dur="$(dur_of "$dest")"
    printf '  %s  %s\n' "raw/$name" "$(describe "$dest")"
    if [[ -z "$dur" ]]; then
      warn "ffprobe cannot read raw/$name: if it is still being recorded, stop the recording and pull again"
      fails=1; continue
    fi
    count=$((count + 1))
    echo "  Map a span of it to a shot (set the in and out seconds; open the file to find them):"
    print_map_lines "raw/$name" "$dur"
  done <<<"$sel"
  if [[ "$count" -gt 0 ]]; then echo "Then: cd \"$CUT_DIR\" && ./build.sh"; fi
  return "$fails"
}

# ---- map ----------------------------------------------------------------------------------------------------

# A recording given as an absolute path, or relative to here, to headset/ or to headset/raw/.
resolve_raw() {
  local a="$1" c
  for c in "$a" "$HEADSET_DIR/$a" "$RAW_DIR/$a"; do
    if [[ -f "$c" ]]; then
      (cd "$(dirname "$c")" && printf '%s/%s\n' "$(pwd -P)" "$(basename "$c")")
      return 0
    fi
  done
  return 1
}

# The path as map.txt keeps it: relative to headset/ when the file is inside it, absolute otherwise.
rel_to_headset() {
  local abs="$1" root
  root="$(cd "$HEADSET_DIR" && pwd -P)"
  case "$abs" in "$root"/*) echo "${abs#"$root"/}" ;; *) echo "$abs" ;; esac
}

# Cut [in, out] of a recording into headset/<shot>.mp4. Re-encoded, so the cut is exact to the frame.
cut_shot() {
  local shot="$1" raw="$2" tin="$3" tout="$4" dur len dst tmp e
  command -v "$FFMPEG" >/dev/null 2>&1 || { bad "ffmpeg not found (brew install ffmpeg)"; return 1; }
  dur="$(dur_of "$raw")"
  if [[ -z "$dur" ]]; then bad "ffprobe cannot read $raw"; return 1; fi
  if flt "$tout" ">" "$dur"; then
    if flt "$(awk -v a="$tout" -v b="$dur" 'BEGIN { print a - b }')" ">=" 0.5; then
      bad "$shot: out $tout s is beyond the end of the recording ($(printf '%.2f' "$dur") s)"; return 1
    fi
    warn "$shot: out $tout s is just beyond the end of the recording: using its end, $(printf '%.2f' "$dur") s"
    tout="$(to_secs "$dur")"
  fi
  if ! flt "$tin" "<" "$tout"; then bad "$shot: in ($tin s) must be before out ($tout s)"; return 1; fi
  len="$(awk -v a="$tout" -v b="$tin" 'BEGIN { printf "%.3f", a - b }')"
  dst="$HEADSET_DIR/$shot.mp4"; tmp="$HEADSET_DIR/.$shot.part.mp4"
  mkdir -p "$HEADSET_DIR"
  if ! "$FFMPEG" -nostdin -hide_banner -loglevel error -y -ss "$tin" -i "$raw" -t "$len" \
      -map 0:v:0 -map '0:a:0?' -vf 'scale=trunc(iw/2)*2:trunc(ih/2)*2' \
      -c:v libx264 -preset medium -crf 17 -pix_fmt yuv420p -c:a aac -b:a 192k \
      -movflags +faststart "$tmp" </dev/null; then
    rm -f "$tmp"; bad "$shot: ffmpeg could not cut $raw"; return 1
  fi
  if ! mv -f "$tmp" "$dst"; then rm -f "$tmp"; bad "$shot: cannot write $dst"; return 1; fi
  ok "$shot.mp4 = $(rel_to_headset "$raw") from $tin to $tout s: $(describe "$dst")"
  for e in mov m4v; do
    if [[ -f "$HEADSET_DIR/$shot.$e" ]]; then
      warn "$shot.$e is also in headset/: build.sh takes $shot.mp4 first; delete the other one to avoid a mix-up"
    fi
  done
  check_need "$shot" "$(dur_of "$dst")"
}

# Keep one line per shot in map.txt: "H2 raw/xxx.mp4 12.0 30.0".
record_map() {
  local shot="$1" line="$1 $2 $3 $4" tmp="$MAP_FILE.tmp.$$"
  if [[ -f "$MAP_FILE" ]]; then
    LINE="$line" awk -v s="$shot" '
      $1 == s { if (!done) { print ENVIRON["LINE"]; done = 1 } next }
      { print }
      END { if (!done) print ENVIRON["LINE"] }' "$MAP_FILE" > "$tmp"
  else
    {
      echo "# shot, recording (relative to this folder, or absolute), in s, out s. One line per shot."
      echo "# Written by tools/quest/pull_recordings.sh map; \"map\" without arguments applies every line again."
      echo "$line"
    } > "$tmp"
  fi
  mv -f "$tmp" "$MAP_FILE"
}

map_reapply() {
  [[ -f "$MAP_FILE" ]] || die "no $MAP_FILE yet. First: $SELF map H2 raw/<file> 12.0 30.0"
  local line shot rest path tin tout raw fails=0 n=0 re='^H[1-8]$'
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line//$TAB/ }"
    read -r shot rest <<<"$line" || true
    case "$shot" in ''|'#'*) continue ;; esac
    tout="${rest##* }"; rest="${rest% *}"; rest="${rest%"${rest##*[! ]}"}"
    tin="${rest##* }"; path="${rest% *}"; path="${path%"${path##*[! ]}"}"
    n=$((n + 1))
    if ! [[ "$shot" =~ $re ]] || ! tin="$(to_secs "$tin")" || ! tout="$(to_secs "$tout")" || [[ -z "$path" ]]; then
      bad "map.txt: cannot read the line: $line"; fails=1; continue
    fi
    if ! raw="$(resolve_raw "$path")"; then bad "$shot: recording not found: $path"; fails=1; continue; fi
    if ! cut_shot "$shot" "$raw" "$tin" "$tout"; then fails=1; fi
  done < "$MAP_FILE"
  if [[ "$n" -eq 0 ]]; then info "map.txt has no shot lines"; fi
  return "$fails"
}

cmd_map() {
  if [[ $# -eq 0 ]]; then map_reapply; return $?; fi
  case "$1" in
    --show)
      if [[ -f "$MAP_FILE" ]]; then cat "$MAP_FILE"; else info "no $MAP_FILE yet"; fi
      return 0 ;;
    -h|--help) usage; return 0 ;;
  esac
  [[ $# -eq 4 ]] || die "usage: $SELF map <H1..H8> <raw file> <in s> <out s>   (got $# arguments)"
  local shot raw tin tout re='^H[1-8]$'
  shot="$(printf '%s' "$1" | tr 'h' 'H')"
  [[ "$shot" =~ $re ]] || die "the shot must be H1 to H8 (got '$1')"
  tin="$(to_secs "$3")" || die "in must be seconds (12.5) or m:ss (0:12.5), got '$3'"
  tout="$(to_secs "$4")" || die "out must be seconds (30) or m:ss (0:30), got '$4'"
  flt "$tin" "<" "$tout" || die "in ($tin s) must be before out ($tout s)"
  raw="$(resolve_raw "$2")" || die "recording not found: $2 (looked here, in $HEADSET_DIR and in $RAW_DIR)"
  cut_shot "$shot" "$raw" "$tin" "$tout" || exit 1
  # cut_shot may have moved "out" back to the end of the recording: note the span that was really cut.
  local dur; dur="$(dur_of "$raw")"
  if [[ -n "$dur" ]] && flt "$tout" ">" "$dur"; then tout="$(to_secs "$dur")"; fi
  record_map "$shot" "$(rel_to_headset "$raw")" "$tin" "$tout"
  info "noted in $MAP_FILE. When every shot is mapped: cd \"$CUT_DIR\" && ./build.sh"
}

# ---- durations ----------------------------------------------------------------------------------------------

cmd_durations() {
  local f shot any=0 src
  list_raw
  echo "Shots in $HEADSET_DIR:"
  if [[ -d "$HEADSET_DIR" ]]; then
    for f in "$HEADSET_DIR"/H[0-9]*; do
      if [[ -f "$f" ]] && is_video "$f"; then
        any=1
        shot="$(basename "$f")"; shot="${shot%.*}"
        src=""
        if [[ -f "$MAP_FILE" ]]; then
          src="$(awk -v s="$shot" '$1 == s { $1 = ""; sub(/^ /, ""); print "  = " $0 }' "$MAP_FILE" | tail -n 1)"
        fi
        printf '  %-10s %s%s\n' "$(basename "$f")" "$(describe "$f")" "$src"
        check_need "$shot" "$(dur_of "$f")"
      fi
    done
  fi
  if [[ "$any" -eq 0 ]]; then echo "  (no shot mapped yet)"; fi
}

# ---- main ---------------------------------------------------------------------------------------------------

cmd="list"
if [[ $# -gt 0 ]]; then
  case "$1" in
    list|pull|map|durations) cmd="$1"; shift ;;
    -h|--help|help) usage; exit 0 ;;
    --date|--all) ;;
    *) bad "unknown command: $1"; usage >&2; exit 2 ;;
  esac
fi
case "$cmd" in
  list) cmd_list "$@" ;;
  pull) cmd_pull "$@" ;;
  map) cmd_map "$@" ;;
  durations) cmd_durations "$@" ;;
esac
