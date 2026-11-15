#!/usr/bin/env bash
# Cuts the two step drawings out of the LACK side table guide (page 1) for the XR demo panel.
# The guide is copyrighted (IKEA): crops are generated locally and git-ignored.
# Needs pdftoppm (poppler) and ImageMagick. Safe to re-run; skips if crops exist.
set -euo pipefail
cd "$(dirname "$0")/.."
PDF=../eval/guides/ikea/lack-side-table__AA-2606170-1.pdf
OUT=public/demo-job/lack
[ -f "$OUT/tip-01.png" ] && exit 0
if ! command -v pdftoppm >/dev/null || ! command -v magick >/dev/null; then
  echo "lack-crops: pdftoppm or magick missing; the panel will show no guide drawing" >&2
  exit 0
fi
TMP=$(mktemp -d)
mkdir -p "$OUT"
pdftoppm -r 150 -png -f 1 -l 1 "$PDF" "$TMP/p"
# Boxes measured on the 150 dpi page (875 x 1241 px): x y w h.
# Step 1: the "4x 115980" header above the left circle (screw into the corner hole).
magick "$TMP/p-1.png" -crop 452x92+158+133 +repage "$TMP/head.png"
magick "$TMP/p-1.png" -crop 178x420+206+238 +repage "$TMP/left.png"
magick "$TMP/head.png" "$TMP/left.png" -background white -gravity center -append \
  -fuzz 8% -trim +repage -bordercolor white -border 12 "$OUT/step-01.png"
magick "$TMP/p-1.png" -crop 225x408+390+242 +repage -fuzz 8% -trim +repage -bordercolor white -border 12 "$OUT/step-02.png"
# Tip: the boxed warning drawing (build on a soft surface, not on a hard floor).
magick "$TMP/p-1.png" -crop 510x215+180+695 +repage -fuzz 8% -trim +repage -bordercolor white -border 12 "$OUT/tip-01.png"
rm -rf "$TMP"
