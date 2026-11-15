#!/usr/bin/env python3
"""Write the printable tracking strip (two AprilTags side by side) at true size on A4 landscape.

  .venv/bin/python tools/quest/marker/make_marker.py
  -> agj-marker-strip-36h11-41h12-id0.pdf   vector, exact size (print at 100% / "Actual size")
  -> agj-marker-strip-36h11-41h12-id0.png   300 dpi raster of the same page (dpi tag set)
  -> marker.json                            both tags, their offsets from the strip centre, placement

Why two tags: the Android app detects AprilTag 36h11 with OpenCV; the Unity app's detector
(jp.keijiro.apriltag) only reads tagStandard41h12, which OpenCV does not support. One strip carries both,
and both apps report the pose of the same object: the STRIP CENTRE (marker axes below).

Strip 210 x 105 mm, tag centres 100 mm apart on the strip's horizontal centre line, both tags upright:
  left  (x = -50 mm): AprilTag 36h11 id 0, 8 x 8 modules of 10 mm (incl. black border) = 80 mm black square.
                      Detector edge (the corners OpenCV / apriltag report) = the black square's outer edge = 80 mm.
  right (x = +50 mm): AprilTag tagStandard41h12 id 0, 9 x 9 modules of 80/7 mm = 102.86 mm overall. Its black
                      ring is 7 modules = 80 mm outside; the outer ring of modules is data bits. The detector edge
                      is the INNER edge of the black ring (5 modules = 57.14 mm), so size_m = 0.0571429.
  An 80 mm detector edge on 41h12 would need 144 mm of tag: it does not fit the 130 mm drawer front or the
  100 mm centre spacing, so the 41h12 is sized by its 80 mm black square instead.
The 41h12 bits come from the official AprilRobotics apriltag-imgs repo (vendor/tag41_12_00000.png, BSD-2),
upscaled nearest-neighbour; 36h11 bits come from OpenCV's DICT_APRILTAG_36h11.
"""
from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
NAME = "agj-marker-strip-36h11-41h12-id0"
STRIP_MM = (210.0, 105.0)
A4L = (297.0, 210.0)                               # A4 landscape, mm
SX, SY = 148.5, 126.0                              # strip centre on the page, mm from bottom-left
PT = 72.0 / 25.4

AXES = ("marker axes (OpenCV ArUco convention): origin at the strip centre, x along the strip toward the 41h12 "
        "tag (to the right when TOP is up), y toward the TOP edge, z out of the printed face. Right-handed, metres. "
        "Both tags are upright on the strip, so each tag's own axes are parallel to the strip axes.")

TAGS = [
    {"key": "tag36h11_0", "family": "tag36h11", "opencv_dictionary": "DICT_APRILTAG_36h11", "id": 0,
     "detectors": ["OpenCV aruco (Android app, reference_tracker.py)"],
     "grid": 8, "module_mm": 10.0, "edge_modules": 8, "black_modules": 8, "centre_mm": (-50.0, 0.0),
     "size_note": "detector edge = outer edge of the black square (8 x 8 modules of 10 mm incl. the black border)"},
    {"key": "tag41h12_0", "family": "tagStandard41h12", "opencv_dictionary": None, "id": 0,
     "detectors": ["jp.keijiro.apriltag (Unity app)", "apriltag C library", "pupil-apriltags"],
     "grid": 9, "module_mm": 80.0 / 7.0, "edge_modules": 5, "black_modules": 7, "centre_mm": (50.0, 0.0),
     "size_note": "detector edge = INNER edge of the black ring (5 of the 9 modules, 57.14 mm). The black ring's "
                  "outer edge is 80 mm (7 modules); the outermost ring of modules (102.86 mm) is data bits"},
]

PLACEMENT = {
    "part": "drawer_front (MALM drawer, 380 x 130 x 16 mm)",
    "face": "inside face: the face that is up in step 1, with the dowel holes and the groove for the drawer bottom",
    "position": "strip centre on the old single-tag centre: centred along the 380 mm length; centred across the "
                "130 mm height between the groove and the opposite long edge (the 210 x 105 mm strip fits; keep the "
                "groove and dowel holes clear)",
    "orientation": "the printed TOP edge of the strip points toward the groove edge; strip x runs along the 380 mm "
                   "length, marker z points out of the inside face",
    "fixing": "flat, no bubbles: glue stick or low-tack spray; tape only on the white margin, never on the black",
    "visibility": "visible in steps 1-4 (front lying flat, sides going on); hidden once the bottom panel slides in. "
                  "Apps must hold the last good pose (anchor) when the marker is lost.",
}


def tag_bits(tag: dict) -> np.ndarray:
    """grid x grid bool array, True = white module, row 0 = the tag's TOP row."""
    if tag["family"] == "tag36h11":
        d = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_APRILTAG_36h11)
        return cv2.aruco.generateImageMarker(d, tag["id"], 8, borderBits=1) > 0
    g = np.array(Image.open(HERE / "vendor" / "tag41_12_00000.png").convert("L"))
    assert g.shape == (9, 9), g.shape
    return g > 127


def modules(tag: dict):
    """Black modules as (x0, y0, x1, y1) rectangles in strip mm (y up)."""
    b, n, m = tag_bits(tag), tag["grid"], tag["module_mm"]
    cx, cy = tag["centre_mm"]
    left, top = cx - n * m / 2, cy + n * m / 2
    return [(left + c * m, top - (r + 1) * m, left + (c + 1) * m, top - r * m)
            for r in range(n) for c in range(n) if not b[r, c]]


def render_strip(px_per_mm: float) -> np.ndarray:
    """The strip as a uint8 image (255 paper, 0 ink), row 0 = TOP edge; pixel centres sampled exactly."""
    W, H = (round(v * px_per_mm) for v in STRIP_MM)
    x = (np.arange(W) + 0.5) / px_per_mm - STRIP_MM[0] / 2
    y = STRIP_MM[1] / 2 - (np.arange(H) + 0.5) / px_per_mm
    X, Y = np.meshgrid(x, y)
    img = np.full((H, W), 255, np.uint8)
    for tag in TAGS:
        b, n, m = tag_bits(tag), tag["grid"], tag["module_mm"]
        cx, cy = tag["centre_mm"]
        c = np.floor((X - (cx - n * m / 2)) / m).astype(int)
        r = np.floor(((cy + n * m / 2) - Y) / m).astype(int)
        inside = (c >= 0) & (c < n) & (r >= 0) & (r < n)
        black = np.zeros_like(inside)
        black[inside] = ~b[r[inside], c[inside]]
        img[black] = 0
    return img


def spec() -> dict:
    tags = []
    for t in TAGS:
        edge = t["edge_modules"] * t["module_mm"]
        tags.append({
            "key": t["key"], "family": t["family"], "opencv_dictionary": t["opencv_dictionary"], "id": t["id"],
            "size_m": round(edge / 1000, 7), "size_note": t["size_note"],
            "black_square_m": round(t["black_modules"] * t["module_mm"] / 1000, 7),
            "overall_m": round(t["grid"] * t["module_mm"] / 1000, 7),
            "module_m": round(t["module_mm"] / 1000, 7),
            "offset_m": [round(t["centre_mm"][0] / 1000, 4), round(t["centre_mm"][1] / 1000, 4), 0.0],
            "offset_q": [0.0, 0.0, 0.0, 1.0],
            "detectors": t["detectors"],
        })
    return {
        "object": "strip_centre",
        "object_note": "Apps report the pose of the STRIP CENTRE (marker axes), not of a tag: "
                       "T_cam_strip = T_cam_tag * Translation(-offset_m). offset_m is the tag centre in strip axes; "
                       "offset_q (x, y, z, w) is identity because both tags are upright on the strip.",
        "marker_axes": AXES,
        "apriltag_lib_axes_note": "the apriltag C library (and jp.keijiro.apriltag, pupil-apriltags) returns tag "
                                  "poses with x right, y DOWN, z INTO the tag; convert to marker axes with "
                                  "R_marker = R_apriltag * diag(1, -1, -1) (180 deg about x). OpenCV ArUco already "
                                  "uses marker axes.",
        "tags": tags,
        "strip_m": [STRIP_MM[0] / 1000, STRIP_MM[1] / 1000],
        "tag_centre_spacing_m": 0.1,
        "print_check": "print at 100 %: the ruler is 100 mm and BOTH black squares are 80 mm wide",
        "placement": PLACEMENT,
        "files": [f"{NAME}.pdf", f"{NAME}.png"],
    }


# ---- page layout (shared by the PDF and the PNG) -------------------------------------------------------

def page_items():
    """(kind, data) items in page mm, y up. kinds: ink (filled rect), line, dash, text."""
    items = []
    for t in TAGS:
        for x0, y0, x1, y1 in modules(t):
            items.append(("ink", (SX + x0, SY + y0, SX + x1, SY + y1)))
    w, h = STRIP_MM
    l, r, b, tp = SX - w / 2, SX + w / 2, SY - h / 2, SY + h / 2
    for a, c in (((l, b), (r, b)), ((r, b), (r, tp)), ((r, tp), (l, tp)), ((l, tp), (l, b))):
        items.append(("dash", (a, c)))                     # cut line
    for x, y, dx, dy in ((l, b, -1, -1), (r, b, 1, -1), (r, tp, 1, 1), (l, tp, -1, 1)):
        items.append(("line", ((x + 2 * dx, y), (x + 7 * dx, y))))   # crop marks outside each corner
        items.append(("line", ((x, y + 2 * dy), (x, y + 7 * dy))))
    # TOP label in the 36h11 quiet zone (12.5 mm above its black square), grey.
    items.append(("text", (SX - 50 - 8.0, SY + 44.5, 8, "F2", "TOP  ^", 0.55)))
    items.append(("text", (SX - 50 - 12.5, SY - 49.0, 6, "F1", "36h11 id 0  |  80 mm", 0.55)))
    rx, ry = 20.0, 50.0
    items.append(("line", ((rx, ry), (rx + 100, ry))))
    for k in range(11):
        hk = 4.0 if k % 5 == 0 else 2.5
        items.append(("line", ((rx + 10 * k, ry), (rx + 10 * k, ry + hk))))
    texts = [
        (20, 200, 15, "F2", "Assembly Guide Agent: tracking strip (two tags, one object)"),
        (20, 193.5, 9, "F1", "Left: AprilTag 36h11 id 0 (Android app, OpenCV). Right: AprilTag tagStandard41h12 id 0 "
                              "(Unity app). Centres 100 mm apart."),
        (20, 189, 9, "F1", "Print at 100% (\"Actual size\"), never \"Fit to page\". Check: ruler 100 mm, and BOTH "
                            "black squares 80 mm wide."),
        (SX - 105, 62, 8, "F1", "36h11: black square 80 mm = detector edge."),
        (SX + 2, 62, 8, "F1", "41h12: black square 80 mm (outer edge of the black ring); detector edge 57.14 mm."),
        (rx, ry - 6, 9, "F1", "This line must measure exactly 100 mm. If not, reprint."),
        (140, 54, 11, "F2", "Where it goes (MALM drawer)"),
        (140, 47.5, 8.5, "F1", "1. Cut along the dashed line (210 x 105 mm). Keep the white margin."),
        (140, 42.5, 8.5, "F1", "2. Drawer front, inside face: up in step 1, with the dowel holes and the groove."),
        (140, 37.5, 8.5, "F1", "3. Strip centre on the face centre: centred along the 380 mm length and"),
        (144, 33, 8.5, "F1", "between the groove and the other long edge."),
        (140, 28, 8.5, "F1", "4. TOP edge toward the groove. Glue flat: no bubbles, no tape on black."),
        (140, 23, 8.5, "F1", "5. Visible in steps 1-4; the apps keep the last pose once the bottom covers it."),
        (20, 14, 7, "F1", "Generated by tools/quest/marker/make_marker.py. Spec and tag offsets: "
                          "tools/quest/marker/marker.json. 41h12 image: AprilRobotics apriltag-imgs (BSD-2)."),
    ]
    for x, y, size, font, s in texts:
        items.append(("text", (x, y, size, font, s, 0.0)))
    return items


def pdf_text(s: str) -> str:
    return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def make_pdf(path: Path) -> None:
    ops, ink = ["q"], []
    for kind, d in page_items():
        if kind == "ink":
            x0, y0, x1, y1 = d
            ink.append(f"{x0 * PT:.4f} {y0 * PT:.4f} {(x1 - x0) * PT:.4f} {(y1 - y0) * PT:.4f} re")
    ops += ["0 g"] + ink + ["f"]           # one path, one fill (nonzero): no seams between adjacent modules
    for kind, d in page_items():
        if kind in ("line", "dash"):
            (x0, y0), (x1, y1) = d
            style = "0.6 G 0.4 w [3 3] 0 d" if kind == "dash" else "0 G 0.5 w [] 0 d"
            ops.append(f"{style} {x0 * PT:.3f} {y0 * PT:.3f} m {x1 * PT:.3f} {y1 * PT:.3f} l S")
        elif kind == "text":
            x, y, size, font, s, grey = d
            ops.append(f"{grey} g BT /{font} {size} Tf {x * PT:.2f} {y * PT:.2f} Td ({pdf_text(s)}) Tj ET")
    ops.append("Q")
    stream = "\n".join(ops).encode("latin-1")
    W, H = A4L[0] * PT, A4L[1] * PT
    objs = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {W:.3f} {H:.3f}] /Contents 4 0 R "
        f"/Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> >>".encode(),
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>",
    ]
    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for i, o in enumerate(objs, 1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode() + o + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode()
    out += b"".join(f"{o:010d} 00000 n \n".encode() for o in offsets)
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    path.write_bytes(bytes(out))


def make_png(path: Path, dpi: int = 300) -> None:
    px = dpi / 25.4
    W, H = round(A4L[0] * px), round(A4L[1] * px)
    im = Image.new("L", (W, H), 255)
    strip = render_strip(px)
    im.paste(Image.fromarray(strip), (round((SX - STRIP_MM[0] / 2) * px), round((A4L[1] - SY - STRIP_MM[1] / 2) * px)))
    d = ImageDraw.Draw(im)

    def xy(x_mm, y_mm):                        # bottom-left mm -> top-left px
        return x_mm * px, (A4L[1] - y_mm) * px
    fonts = {}

    def font(size_pt):
        if size_pt not in fonts:
            try:
                fonts[size_pt] = ImageFont.load_default(size=round(size_pt / 72 * dpi))
            except TypeError:
                fonts[size_pt] = ImageFont.load_default()
        return fonts[size_pt]
    for kind, dd in page_items():
        if kind == "line":
            d.line([xy(*dd[0]), xy(*dd[1])], fill=0, width=2)
        elif kind == "dash":
            (x0, y0), (x1, y1) = (xy(*p) for p in dd)
            n = int(max(abs(x1 - x0), abs(y1 - y0)) // 24)
            for k in range(n + 1):
                a, b = k / max(n, 1), min((k + 0.5) / max(n, 1), 1.0)
                d.line([(x0 + (x1 - x0) * a, y0 + (y1 - y0) * a), (x0 + (x1 - x0) * b, y0 + (y1 - y0) * b)],
                       fill=150, width=2)
        elif kind == "text":
            x, y, size, _, s, grey = dd
            f = font(size)
            d.text(xy(x, y), s, fill=round(255 * grey), font=f, anchor="ls")
    im.save(path, dpi=(dpi, dpi))


def main() -> None:
    make_pdf(HERE / f"{NAME}.pdf")
    make_png(HERE / f"{NAME}.png")
    (HERE / "marker.json").write_text(json.dumps(spec(), indent=2) + "\n")
    print(f"wrote {NAME}.pdf, {NAME}.png, marker.json in {HERE}")


if __name__ == "__main__":
    main()
