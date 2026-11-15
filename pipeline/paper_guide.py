"""A printed, wordless assembly guide made from a built job: line drawings, numbered steps, counts.

    .venv/bin/python -m pipeline.paper_guide <job_dir> [out.pdf]

Pages (A4, 200 dpi): the cover with the finished product; what is in the box (hardware with counts and codes,
panels with counts, the tool); one page per step, drawn exploded, with a dashed path and an arrowhead from each
new piece to where it goes and an "Nx" box for the step's hardware; the finished product.
The drawings come from pipeline/blender/guide_passes.py (needs the job's scene.blend and manifest.json).
Writes <job>/paper-guide.pdf and <job>/paper/page-NN.png.
"""
from __future__ import annotations

import json
import math
import re
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

from pipeline.ar import BLENDER

W, H, M = 1654, 2339, 110                       # A4 at 200 dpi, margin
INK = (0, 0, 0)
FONTS = {"bold": ["/System/Library/Fonts/Supplemental/Arial Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"],
         "regular": ["/System/Library/Fonts/Supplemental/Arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]}


def font(size: int, weight: str = "bold") -> ImageFont.FreeTypeFont:
    for f in FONTS[weight]:
        if Path(f).exists():
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()


def line_art(normals: Path, ids: Path, thick: int = 3) -> Image.Image:
    """Black lines on white: a pixel is ink where the surface normal or the piece changes."""
    edge = None
    for path, jump in ((normals, 0.07), (ids, 0.02)):
        a = np.asarray(Image.open(path).convert("RGB"), dtype=np.float32) / 255
        e = np.zeros(a.shape[:2], dtype=bool)
        dy = np.abs(np.diff(a, axis=0)).max(axis=2) > jump
        dx = np.abs(np.diff(a, axis=1)).max(axis=2) > jump
        e[:-1] |= dy; e[:, :-1] |= dx
        edge = e if edge is None else edge | e
    im = Image.fromarray((edge * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(thick))
    return Image.eval(im, lambda v: 255 - v).convert("RGB")


def trim(im: Image.Image, pad: int = 12) -> tuple[Image.Image, tuple[int, int, int, int]]:
    """Cut the white border off a drawing. Returns the cut and its box in the original."""
    a = np.asarray(im.convert("L")) < 250
    ys, xs = np.where(a)
    if not len(xs):
        return im, (0, 0, im.width, im.height)
    box = (max(0, xs.min() - pad), max(0, ys.min() - pad), min(im.width, xs.max() + pad), min(im.height, ys.max() + pad))
    return im.crop(box), box


def dashed(d: ImageDraw.ImageDraw, a, b, width=4, dash=18, gap=12):
    n = math.dist(a, b)
    if n < 1:
        return
    ux, uy = (b[0] - a[0]) / n, (b[1] - a[1]) / n
    t = 0.0
    while t < n:
        e = min(n, t + dash)
        d.line((a[0] + ux * t, a[1] + uy * t, a[0] + ux * e, a[1] + uy * e), fill=INK, width=width)
        t = e + gap


def arrow_head(d: ImageDraw.ImageDraw, tip, direction, size=34):
    n = math.hypot(*direction) or 1
    ux, uy = direction[0] / n, direction[1] / n
    left = (tip[0] - ux * size - uy * size * 0.45, tip[1] - uy * size + ux * size * 0.45)
    right = (tip[0] - ux * size + uy * size * 0.45, tip[1] - uy * size - ux * size * 0.45)
    d.polygon([tip, left, right], fill=INK)


def turn_mark(d: ImageDraw.ImageDraw, cx, cy, r=70):
    """The guide's "turn it over" sign: most of a circle with an arrowhead."""
    d.arc((cx - r, cy - r, cx + r, cy + r), start=200, end=140, fill=INK, width=9)
    a = math.radians(140)
    arrow_head(d, (cx + r * math.cos(a), cy + r * math.sin(a)), (-math.sin(a), math.cos(a)), size=44)


def screwdriver(d: ImageDraw.ImageDraw, x, y, length=420):
    d.rounded_rectangle((x, y - 26, x + length * 0.36, y + 26), radius=22, outline=INK, width=6)
    d.rectangle((x + length * 0.36, y - 7, x + length * 0.94, y + 7), outline=INK, width=5)
    d.polygon([(x + length * 0.94, y - 12), (x + length, y), (x + length * 0.94, y + 12)], outline=INK, fill="white")


def page() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    im = Image.new("RGB", (W, H), "white")
    return im, ImageDraw.Draw(im)


def footer(d: ImageDraw.ImageDraw, n: int, ref: str):
    d.text((W - M, H - 96), str(n), font=font(34, "regular"), fill=INK, anchor="rs")
    d.text((M, H - 96), ref, font=font(26, "regular"), fill=(90, 90, 90), anchor="ls")


def place(im: Image.Image, drawing: Image.Image, box: tuple[int, int, int, int]) -> tuple[float, int, int]:
    """Paste a drawing into a box of the page, as large as fits. Returns its scale and top-left corner."""
    bw, bh = box[2] - box[0], box[3] - box[1]
    k = min(bw / drawing.width, bh / drawing.height)
    t = drawing.resize((max(1, int(drawing.width * k)), max(1, int(drawing.height * k))), Image.LANCZOS)
    x, y = box[0] + (bw - t.width) // 2, box[1] + (bh - t.height) // 2
    im.paste(t, (x, y))
    return k, x, y


def count_box(im: Image.Image, d: ImageDraw.ImageDraw, x, y, qty, code, picture: Path | None, w=330, h=250):
    """The guide's hardware box: the piece, how many, and its number."""
    d.rounded_rectangle((x, y, x + w, y + h), radius=18, outline=INK, width=5)
    d.text((x + 24, y + 18), f"{qty}x", font=font(74), fill=INK)
    if picture and picture.exists():
        art, _ = trim(Image.open(picture).convert("RGB"))
        place(im, art, (x + 150, y + 24, x + w - 18, y + h - 62))
    # a guide prints the piece's number; a piece that has only a name gets the name, without its underscores
    d.text((x + w - 22, y + h - 18), str(code).replace("_", " "), font=font(28, "regular"), fill=INK, anchor="rs")


def build(job: Path, dest: Path | None = None) -> dict:
    job = Path(job)
    m = json.loads((job / "manifest.json").read_text())
    work = job / "paper"
    script = Path(__file__).parent / "blender" / "guide_passes.py"
    r = subprocess.run([BLENDER, "-b", str(job / "scene.blend"), "-P", str(script), "--", str(job), str(work), "1800"],
                       capture_output=True, text=True, timeout=600)
    if not (work / "passes.json").exists():
        raise RuntimeError((r.stdout + r.stderr)[-800:])
    passes = json.loads((work / "passes.json").read_text())
    size = passes["size"]
    title = (m.get("title") or "Assembly").upper()
    ref = m.get("guideId") or m.get("jobId") or ""
    parts = {p["id"]: p for p in m.get("parts", [])}
    hardware = {h["code"]: h for h in m.get("hardware", [])}
    pages: list[Image.Image] = []

    def art(name: str) -> Image.Image:
        return line_art(work / f"{name}.normals.png", work / f"{name}.ids.png")

    # ---- cover
    im, d = page()
    d.text((M, 150), title, font=font(112 if len(title) < 14 else 76), fill=INK)
    cover, _ = trim(art("cover"))
    place(im, cover, (M, 420, W - M, H - 360))
    footer(d, 1, ref)
    pages.append(im)

    # ---- what is in the box
    im, d = page()
    screwdriver(d, M, 190)
    y = 330
    x = M
    for code, h in hardware.items():
        count_box(im, d, x, y, h.get("count", "?"), code, job / "renders" / f"part-{code}.png")
        x += 360
        if x + 330 > W - M:
            x, y = M, y + 280
    y += 330
    d.line((M, y - 40, W - M, y - 40), fill=INK, width=3)
    cell = (W - 2 * M) // 2
    # A left and a right panel that are the same board turned round are one line of the box's contents: "2x".
    listed: dict[str, dict] = {}
    for pid, p in parts.items():
        base = re.sub(r"[_ -]?(left|right|l|r)$", "", pid, flags=re.I)
        if base in listed:
            listed[base]["count"] += p.get("count", 1)
        else:
            listed[base] = {"picture": pid, "count": p.get("count", 1)}
    for i, entry in enumerate(listed.values()):
        cx, cy = M + (i % 2) * cell, y + (i // 2) * 430
        picture = job / "renders" / f"part-{entry['picture']}.png"
        if picture.exists():
            a, _ = trim(Image.open(picture).convert("RGB"))
            place(im, a, (cx + 130, cy, cx + cell - 30, cy + 380))
        d.text((cx, cy + 150), f"{entry['count']}x", font=font(64), fill=INK)
    footer(d, 2, ref)
    pages.append(im)

    # ---- one page per step
    for st in passes["steps"]:
        im, d = page()
        d.text((M, 110), str(st["n"]), font=font(190), fill=INK)
        full = art(f"step_{st['n']:02d}")
        cut, box = trim(full, pad=40)
        area = (M, 420, W - M, H - 300)
        k, ox, oy = place(im, cut, area)
        to_page = lambda p: (ox + (p[0] * size - box[0]) * k, oy + (p[1] * size - box[1]) * k)  # noqa: E731
        for it in st["moving"]:
            a, b = to_page(it["from"]), to_page(it["to"])
            if math.dist(a, b) < 40:
                continue
            dashed(d, a, b, width=4 if it["hardware"] else 6)
            n = math.dist(a, b)
            tip = (a[0] + (b[0] - a[0]) * (0.82 if it["hardware"] else 0.6), a[1] + (b[1] - a[1]) * (0.82 if it["hardware"] else 0.6))
            arrow_head(d, tip, (b[0] - a[0], b[1] - a[1]), size=30 if it["hardware"] else 46)
            if it["hardware"]:
                d.ellipse((b[0] - 9, b[1] - 9, b[0] + 9, b[1] + 9), outline=INK, width=4)
            _ = n
        bx = W - M - 330
        by = 120
        for hw in st.get("hardware", []):
            count_box(im, d, bx, by, hw.get("qty", "?"), hw.get("code", ""), job / "renders" / f"part-{hw.get('code')}.png")
            bx -= 360
        if st.get("turn"):
            turn_mark(d, M + 90, H - 230)
        footer(d, len(pages) + 1, ref)
        pages.append(im)

    # ---- finished
    im, d = page()
    place(im, cover, (M + 120, 520, W - M - 120, H - 520))
    cx, cy = W // 2, H - 330
    d.line((cx - 70, cy, cx - 20, cy + 50, cx + 80, cy - 60), fill=INK, width=18, joint="curve")
    footer(d, len(pages) + 1, ref)
    pages.append(im)

    dest = Path(dest) if dest else job / "paper-guide.pdf"
    pages[0].save(dest, save_all=True, append_images=pages[1:], resolution=200.0)
    for i, p in enumerate(pages, start=1):
        p.save(work / f"page-{i:02d}.png")
    return {"pages": len(pages), "pdf": str(dest), "bytes": dest.stat().st_size}


if __name__ == "__main__":
    print("RESULT " + json.dumps(build(Path(sys.argv[1]), Path(sys.argv[2]) if len(sys.argv) > 2 else None)))
