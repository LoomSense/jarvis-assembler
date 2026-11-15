#!/usr/bin/env bash
# Cuts one drawing per step out of the KNAGGLIG guide for the XR demo panel.
# The guide is copyrighted (IKEA): crops are generated locally and git-ignored.
# Needs pdftoppm (poppler) and ImageMagick. Safe to re-run; skips if crops exist.
set -euo pipefail
cd "$(dirname "$0")/.."
PDF=../eval/guides/ikea/knagglig-box__AA-1159337-7.pdf
OUT=public/demo-job/crops
[ -f "$OUT/step-08.png" ] && exit 0
if ! command -v pdftoppm >/dev/null || ! command -v magick >/dev/null; then
  echo "demo-crops: pdftoppm or magick missing; the panel will show no guide drawing" >&2
  exit 0
fi
TMP=$(mktemp -d)
mkdir -p "$OUT"
pdftoppm -r 110 -png "$PDF" "$TMP/p"
crop() { # step page top% bottom%
  local f="$TMP/p-$2.png" h
  h=$(magick identify -format %h "$f")
  local y0=$((h * $3 / 100)) y1=$((h * $4 / 100))
  magick "$f" -crop "x$((y1 - y0))+0+$y0" +repage -fuzz 8% -trim +repage \
    -bordercolor white -border 12 "$OUT/step-0$1.png"
}
crop 1 3 3 46
crop 2 3 46 93
crop 3 4 3 35
crop 4 4 35 93
crop 5 5 34 95
crop 6 6 3 78
crop 7 7 34 95
crop 8 8 3 56
rm -rf "$TMP"
echo "demo-crops: wrote $OUT/step-01..08.png"
