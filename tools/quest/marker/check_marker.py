#!/usr/bin/env python3
"""Check the printable strip: render the PDF, detect both tags, measure them against marker.json.

  .venv/bin/python tools/quest/marker/check_marker.py [--dpi 300] [--png]

36h11 is detected with OpenCV aruco (as the Android app does); tagStandard41h12 with pupil-apriltags
(the same apriltag C library jp.keijiro.apriltag wraps; `.venv/bin/pip install pupil-apriltags`).
For each tag: detected id, detector-edge length in mm (mean of the 4 sides) and the centre's offset from
the strip centre in marker axes, compared with marker.json. Exit 1 on any miss or > 0.3 mm error.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from make_marker import A4L, NAME, SX, SY  # noqa: E402


def render(dpi: int, use_png: bool) -> tuple[np.ndarray, float]:
    if use_png:
        img = cv2.imread(str(HERE / f"{NAME}.png"), cv2.IMREAD_GRAYSCALE)
        return img, img.shape[1] / A4L[0]
    import pymupdf
    pix = pymupdf.open(HERE / f"{NAME}.pdf")[0].get_pixmap(dpi=dpi, colorspace=pymupdf.csGRAY)
    img = np.frombuffer(pix.samples, np.uint8).reshape(pix.height, pix.stride)[:, :pix.width].copy()
    return img, pix.width / A4L[0]


def detect(img: np.ndarray) -> dict:
    found = {}
    params = cv2.aruco.DetectorParameters()
    params.cornerRefinementMethod = cv2.aruco.CORNER_REFINE_SUBPIX
    det = cv2.aruco.ArucoDetector(cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_APRILTAG_36h11), params)
    corners, ids, _ = det.detectMarkers(img)
    for c, i in zip(corners or [], [] if ids is None else ids.flatten()):
        found[("tag36h11", int(i))] = c.reshape(4, 2)
    try:
        from pupil_apriltags import Detector
    except ImportError:
        print("pupil-apriltags missing: .venv/bin/pip install pupil-apriltags", file=sys.stderr)
        return found
    for d in Detector(families="tagStandard41h12", quad_decimate=1.0).detect(img):
        found[("tagStandard41h12", int(d.tag_id))] = np.asarray(d.corners)
    return found


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dpi", type=int, default=300)
    ap.add_argument("--png", action="store_true", help="check the PNG instead of a render of the PDF")
    a = ap.parse_args()
    spec = json.loads((HERE / "marker.json").read_text())
    img, ppmm = render(a.dpi, a.png)
    found = detect(img)
    H = img.shape[0]
    ok = True
    for t in spec["tags"]:
        c = found.get((t["family"], t["id"]))
        if c is None:
            print(f"FAIL {t['key']}: not detected")
            ok = False
            continue
        edge = float(np.mean([np.linalg.norm(c[k] - c[(k + 1) % 4]) for k in range(4)])) / ppmm
        cx, cy = c.mean(0)
        off = ((cx + 0.5) / ppmm - SX, (A4L[1] - (cy + 0.5) / ppmm) - SY)     # page mm -> strip axes (y up)
        want_e, want_o = t["size_m"] * 1000, [v * 1000 for v in t["offset_m"][:2]]
        err = max(abs(edge - want_e), abs(off[0] - want_o[0]), abs(off[1] - want_o[1]))
        good = err <= 0.3
        ok &= good
        print(f"{'OK  ' if good else 'FAIL'} {t['key']}: detected id {t['id']}, edge {edge:.2f} mm (spec {want_e:.2f}), "
              f"centre offset ({off[0]:+.2f}, {off[1]:+.2f}) mm (spec ({want_o[0]:+.1f}, {want_o[1]:+.1f}))")
    src = "PNG" if a.png else f"PDF rendered at {a.dpi} dpi"
    print(f"{src}: {'all tags OK' if ok else 'FAILED'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
