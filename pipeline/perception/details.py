"""Close-up reference pictures for the steps whose additions do not show on the reference sheet.

    .venv/bin/python -m pipeline.perception.details <job_dir>

The reference sheet shows the assembly from the front. Small hardware that goes into a side or a far face (a cam
lock, the screws of a drawer runner) is then hidden: `refs/step-NN-added.png` has no orange in it at all, and
`step_complete` is asked about a picture that looks the same before and after the step. Measured on the MALM
drawer (Sun 15 Nov): step 3 (cam locks) scored 0.44 when done and never fired; step 7 (runner screws) scored 0.69
on its "before" frame, because that frame simply looked like the sheet.

For every such step (`questions.hidden(questions.added_px(...))`; step 1 has no "before" and is left out) this
renders the assembly from a few other sides with the same Blender script as the sheets, keeps the side from which
most of the added hardware shows, and writes

    refs/step-NN-detail.png      LEFT: one place where the hardware goes, before the step. RIGHT: the same place
                                 after it, with the fitted hardware in orange. A close-up (DETAIL_ZOOM x).

and `detail`, `detail_view_az_el_deg`, `detail_px` in refs/step-NN-sheet.json. `questions.step_complete` then asks
about that close-up, and the request carries it as Image 3 (`request.build`, `server.perception.questions`).
A step whose hardware shows from none of the sides (inside a closed box) gets no close-up and keeps the wording
that answers "cannot tell" as no. Nothing else in the job folder is touched; no model is called.
"""
from __future__ import annotations

import json
import sys
import tempfile
import time
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

from pipeline import refs
from pipeline.perception import questions

VIEWS = ((-45, 30), (-135, 30), (45, 30), (135, 30), (-90, 75))   # azimuth, elevation: the four corners, and from above
SIZE = 1536                  # render edge: twice the sheet's, so that a crop is still sharp
DETAIL_ZOOM = 4              # the crop is SIZE / DETAIL_ZOOM wide
PANEL = 512                  # each half of the close-up
CELLS = 24                   # the densest of CELLS x CELLS cells of the picture is where the close-up is centred
INK = (20, 20, 20)


def _mask(a: Path, b: Path) -> Image.Image:
    diff = ImageChops.difference(Image.open(a).convert("RGB"), Image.open(b).convert("RGB")).convert("L")
    return diff.point(lambda v: 255 if v > 24 else 0)


def _centre(mask: Image.Image) -> tuple[int, int]:
    """The middle of the cell with the most changed pixels: one place where the hardware sits, not the middle of all."""
    small = mask.resize((CELLS, CELLS), Image.BOX)
    best = max(range(CELLS * CELLS), key=lambda i: small.getpixel((i % CELLS, i // CELLS)))
    cell = mask.width / CELLS
    box = mask.crop((round((best % CELLS - 1) * cell), round((best // CELLS - 1) * cell),
                     round((best % CELLS + 2) * cell), round((best // CELLS + 2) * cell))).getbbox()
    x0, y0 = (best % CELLS - 1) * cell, (best // CELLS - 1) * cell
    if not box:
        return round(x0 + 1.5 * cell), round(y0 + 1.5 * cell)
    return round(x0 + (box[0] + box[2]) / 2), round(y0 + (box[1] + box[3]) / 2)


def compose(before: Path, added: Path, centre: tuple[int, int], out: Path) -> None:
    side = SIZE // DETAIL_ZOOM
    x = min(max(0, centre[0] - side // 2), SIZE - side)
    y = min(max(0, centre[1] - side // 2), SIZE - side)
    sheet = Image.new("RGB", (2 * PANEL, PANEL), "white")
    f = refs.font(26)
    draw = ImageDraw.Draw(sheet)
    for i, (path, text) in enumerate(((before, "BEFORE"), (added, "AFTER"))):
        im = Image.open(path).convert("RGB").crop((x, y, x + side, y + side)).resize((PANEL, PANEL), Image.LANCZOS)
        sheet.paste(im, (i * PANEL, 0))
        draw.rectangle([i * PANEL + 8, 8, i * PANEL + 16 + draw.textlength(text, font=f), 44], fill="white")
        draw.text((i * PANEL + 12, 10), text, fill=INK, font=f)
    draw.line([(PANEL, 0), (PANEL, PANEL)], fill=INK, width=3)
    sheet.save(out, optimize=True)


def build(job: Path) -> dict:
    t0 = time.perf_counter()
    manifest = json.loads((job / "manifest.json").read_text())
    sheets = {s["n"]: json.loads((job / "refs" / f"step-{s['n']:02d}-sheet.json").read_text()) for s in manifest["steps"]}
    todo = [s["n"] for k, s in enumerate(manifest["steps"])
            if k and questions.hidden(questions.added_px(job, sheets[s["n"]]))]
    made, none = {}, []
    if not todo:
        return {"hidden_steps": [], "details": {}, "seconds": 0.0}
    with tempfile.TemporaryDirectory() as tmp:
        best: dict[int, tuple] = {}
        for i, (az, el) in enumerate(VIEWS):
            out = Path(tmp) / f"view{i}"
            for mode in ("ref", "added", "before"):
                refs.blender("render_refs.py", job, SIZE, f"{az},{el}", out, "1,1,1", mode)
            for n in todo:
                mask = _mask(out / f"step-{n:02d}-added.png", out / f"step-{n:02d}-ref.png")   # the same camera: only the orange differs
                px = mask.histogram()[255]
                if px > best.get(n, (0,))[0]:
                    best[n] = (px, i, _centre(mask))
        for n in todo:
            px, i, centre = best.get(n, (0, None, None))
            sheet = sheets[n]
            for key in ("detail", "detail_view_az_el_deg", "detail_px"):
                sheet.pop(key, None)
            if px >= questions.MIN_ADDED_PX * (SIZE / 768) ** 2:           # the same share of the picture as on the sheet
                rel = f"refs/step-{n:02d}-detail.png"
                compose(Path(tmp) / f"view{i}" / f"step-{n:02d}-before.png", Path(tmp) / f"view{i}" / f"step-{n:02d}-added.png",
                        centre, job / rel)
                sheet.update({"detail": rel, "detail_view_az_el_deg": list(VIEWS[i]), "detail_px": px})
                made[n] = {"view_az_el_deg": list(VIEWS[i]), "px": px}
            else:
                none.append(n)
            (job / "refs" / f"step-{n:02d}-sheet.json").write_text(json.dumps(sheet, indent=1))
    return {"hidden_steps": todo, "details": made, "still_hidden": none, "seconds": round(time.perf_counter() - t0, 1)}


if __name__ == "__main__":
    print("RESULT " + json.dumps(build(Path(sys.argv[1]))))
