"""Cut each step's drawing out of the guide PDF at 300 dpi (PLAN §4.1 `crops/`, §5.1 step 3).

    .venv/bin/python -m pipeline.crops eval/golden/knagglig/plan.json jobs/knagglig

Writes <job>/crops/step-NN.png and sets `crop` and `cropSource` on every step in manifest.json.

Where the box comes from, in this order:
  box    the step's `cite.box_2d` in the plan: [ymin, xmin, ymax, xmax] on a 0-1000 scale, as the model returns it
  auto   no box in the plan. The page is trimmed to its drawing (page number and footer removed). If several
         steps cite the same page, the page is cut at its widest empty horizontal bands, top step first.
`auto` is a guess that works for guides that stack their steps down the page. Check the crops by eye.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pymupdf
from PIL import Image

DPI = 300
FOOTER = 0.92                # ink below this fraction of the page height is the page number and document id
PAD = 0.012                  # white border kept around a crop, as a fraction of the page width
ROOT = Path(__file__).resolve().parent.parent


def page_image(doc, page_no: int) -> Image.Image:
    pix = doc[page_no - 1].get_pixmap(dpi=DPI, alpha=False)
    return Image.frombytes("RGB", (pix.width, pix.height), pix.samples)


def ink_box(ink: np.ndarray, y0: int, y1: int):
    rows = np.flatnonzero(ink[y0:y1].any(axis=1))
    if not len(rows):
        return None
    cols = np.flatnonzero(ink[y0 + rows[0]:y0 + rows[-1] + 1].any(axis=0))
    return y0 + rows[0], cols[0], y0 + rows[-1] + 1, cols[-1] + 1


def auto_boxes(im: Image.Image, k: int) -> list[tuple[int, int, int, int]]:
    """k boxes (ymin, xmin, ymax, xmax in pixels), top to bottom."""
    ink = np.asarray(im.convert("L")) < 200
    h = int(im.height * FOOTER)
    whole = ink_box(ink, 0, h) or (0, 0, h, im.width)
    cuts = []
    if k > 1:
        empty = ~ink[whole[0]:whole[2]].any(axis=1)
        gaps, start = [], None                                    # runs of empty rows inside the drawing
        for y, e in enumerate(np.append(empty, False)):
            if e and start is None:
                start = y
            elif not e and start is not None:
                gaps.append((y - start, whole[0] + (start + y) // 2)); start = None
        cuts = sorted(mid for _, mid in sorted(gaps, reverse=True)[:k - 1])
    edges = [whole[0], *cuts, whole[2]]
    boxes = [ink_box(ink, a, b) or (a, whole[1], b, whole[3]) for a, b in zip(edges, edges[1:])]
    while len(boxes) < k:                                         # fewer empty bands than steps: repeat the page
        boxes.append(whole)
    return boxes


def build(plan_path: Path, job: Path) -> dict:
    plan = json.loads(plan_path.read_text())
    pdf = Path(plan["guide"])
    pdf = pdf if pdf.is_absolute() else ROOT / pdf
    if not pdf.exists():
        return {"crops": 0, "reason": f"guide not found: {plan['guide']}"}
    doc = pymupdf.open(pdf)
    (job / "crops").mkdir(exist_ok=True)
    by_page: dict[int, list[dict]] = {}
    for s in plan["steps"]:
        by_page.setdefault(s["cite"]["page"], []).append(s)
    made = {}
    for page_no, steps in sorted(by_page.items()):
        if not 1 <= page_no <= len(doc):
            continue
        im = page_image(doc, page_no)
        pad = int(im.width * PAD)
        need = [s for s in steps if not s["cite"].get("box_2d")]
        auto = dict(zip([s["n"] for s in sorted(need, key=lambda s: s["n"])], auto_boxes(im, len(need)))) if need else {}
        for s in steps:
            b = s["cite"].get("box_2d")
            if b:
                box, source = (b[0] * im.height // 1000, b[1] * im.width // 1000, b[2] * im.height // 1000, b[3] * im.width // 1000), "box"
            else:
                box, source = auto[s["n"]], "auto"
            y0, x0, y1, x1 = (int(v) for v in box)
            crop = im.crop((max(0, x0 - pad), max(0, y0 - pad), min(im.width, x1 + pad), min(im.height, y1 + pad)))
            name = f"step-{s['n']:02d}.png"
            crop.save(job / "crops" / name, optimize=True)
            made[s["n"]] = {"crop": f"crops/{name}", "cropSource": source, "size_px": list(crop.size), "page": page_no}
    mpath = job / "manifest.json"
    if mpath.exists():
        manifest = json.loads(mpath.read_text())
        for s in manifest["steps"]:
            if s["n"] in made:
                s["crop"], s["cropSource"] = made[s["n"]]["crop"], made[s["n"]]["cropSource"]
        mpath.write_text(json.dumps(manifest, indent=1, ensure_ascii=False))
    return {"crops": len(made), "dpi": DPI, "auto": sum(1 for m in made.values() if m["cropSource"] == "auto"), "steps": made}


if __name__ == "__main__":
    out = build(Path(sys.argv[1]), Path(sys.argv[2]))
    print("RESULT " + json.dumps(out))
    sys.exit(0 if out["crops"] else 1)
