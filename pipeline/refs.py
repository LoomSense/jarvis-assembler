"""Part renders, step reference renders and the composite reference sheets for phase 2 (CAD-03, CAD-07).

    .venv/bin/python -m pipeline.refs eval/golden/knagglig/plan.json jobs/knagglig

pipeline.run calls it as its last stage; run it alone to redo only the pictures. It writes, in the job folder:
    renders/part-<id>.png, part-<id>-shaded.png    every part and hardware item on its own
    refs/step-NN-ref.png                            the correct result of the step, phone-on-a-stand view (plain colours)
    refs/step-NN-added.png                          the same, with the pieces this step adds drawn in orange
    refs/step-NN-before.png                         the pieces of the step before, already in this step's pose (none for step 1)
    refs/step-NN-sheet.png                          LEFT half: the "added" picture. RIGHT half: labelled candidates
    refs/step-NN-sheet.json                         the labels on the sheet, for whoever writes the questions
and sets `thumb` on parts and hardware and `refRender`, `refSheet` on steps in manifest.json.

Candidates on a sheet: the parts and hardware of this step, parts that look like them, and the parts and
hardware of the next step (to catch work done out of order). A label is the part id or the hardware code,
so it can be used unchanged as an answer key.
"""
from __future__ import annotations

import json
import math
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

import trimesh
from PIL import Image, ImageChops, ImageDraw, ImageFont

BLENDER = os.environ.get("BLENDER_BIN") or shutil.which("blender") or "/Applications/Blender.app/Contents/MacOS/Blender"
HALF = 768                   # the sheet is 2 * HALF wide and HALF high
LOOKALIKE = 0.15             # two parts look alike when every sorted box edge differs by less than this fraction
INK, RULE = (20, 20, 20), (200, 200, 200)


def blender(script: str, *args) -> dict:
    r = subprocess.run([BLENDER, "-b", "--factory-startup", "-P", str(Path(__file__).parent / "blender" / script), "--",
                        *map(str, args)], capture_output=True, text=True, timeout=300)
    res = [l for l in r.stdout.splitlines() if l.startswith("RESULT ")]
    if r.returncode or not res:
        raise RuntimeError(f"{script} failed:\n{r.stdout[-2000:]}\n{r.stderr[-2000:]}")
    return json.loads(res[-1][7:])


def look_alikes(plan: dict, parts_dir: Path) -> dict[str, list[str]]:
    """Part ids that could be mistaken for each other. A plan can also name them with `look_alikes` on a part."""
    ids = [p["id"] for p in plan["parts"]]
    box = {i: sorted(trimesh.load(parts_dir / f"{i}.stl").extents) for i in ids}
    out = {i: [] for i in ids}
    for i in ids:
        for j in ids:
            if i != j and all(abs(a - b) <= LOOKALIKE * max(a, b) for a, b in zip(box[i], box[j])):
                out[i].append(j)
    for p in plan["parts"]:
        for j in p.get("look_alikes", []):
            if j in out and j not in out[p["id"]]:
                out[p["id"]].append(j)
    return out


def candidates(manifest: dict, alike: dict, k: int) -> list[dict]:
    steps = manifest["steps"]
    s, nxt = steps[k], steps[k + 1] if k + 1 < len(steps) else None
    out, seen = [], set()

    def add(key, kind, why):
        if key not in seen:
            seen.add(key)
            out.append({"label": key, "kind": kind, "why": why})

    for pid in s["pickParts"]:
        add(pid, "part", "this_step")
    for h in s["hardware"]:
        add(h["code"], "hardware", "this_step")
    for pid in s["pickParts"]:
        for j in alike.get(pid, []):
            add(j, "part", "look_alike")
    if nxt:
        for pid in nxt["pickParts"]:
            add(pid, "part", "next_step")
        for h in nxt["hardware"]:
            add(h["code"], "hardware", "next_step")
    if len([c for c in out if c["kind"] == "hardware"]) == 1:      # one screw alone gives nothing to compare it with
        for h in manifest["hardware"]:
            add(h["code"], "hardware", "other_hardware")
    return out


def trimmed(path: Path) -> Image.Image:
    im = Image.open(path).convert("RGB")
    box = ImageChops.difference(im, Image.new("RGB", im.size, "white")).getbbox()
    return im.crop(box) if box else im


def font(size: int):
    try:
        return ImageFont.load_default(size=size)
    except TypeError:                                              # Pillow before 10.1 has only the tiny bitmap font
        return ImageFont.load_default()


def compose(job: Path, n: int, cands: list[dict], px_per_mm: dict) -> Path:
    sheet = Image.new("RGB", (2 * HALF, HALF), "white")
    # the added pieces in orange show the model where to look for step_complete (stand-in bench, Sun 15 Nov)
    sheet.paste(Image.open(job / "refs" / f"step-{n:02d}-added.png").convert("RGB").resize((HALF, HALF), Image.LANCZOS), (0, 0))
    draw = ImageDraw.Draw(sheet)
    draw.line([(HALF, 0), (HALF, HALF)], fill=INK, width=3)
    cols = 1 if len(cands) <= 2 else 2 if len(cands) <= 6 else 3
    rows = max(1, math.ceil(len(cands) / cols))
    cw, ch = HALF // cols, HALF // rows
    f = font(max(18, min(40, ch // 6, cw // 9)))
    label_h = f.size + 14 if hasattr(f, "size") else 30
    room = (cw - 24, ch - label_h - 20)
    # parts share one scale and hardware shares another, so a long screw is drawn longer than a short one
    ims = {c["label"]: trimmed(job / "renders" / f"part-{c['label']}-shaded.png") for c in cands}
    scale = {}
    for kind in ("part", "hardware"):
        keys = [c["label"] for c in cands if c["kind"] == kind]
        if keys:
            mm = {k: (ims[k].width / px_per_mm[k], ims[k].height / px_per_mm[k]) for k in keys}
            fit = min(room[0] / max(w for w, _ in mm.values()), room[1] / max(h for _, h in mm.values()))
            scale.update({k: fit / px_per_mm[k] for k in keys})
    for i, c in enumerate(cands):
        x0, y0 = HALF + (i % cols) * cw, (i // cols) * ch
        im = ims[c["label"]]
        im = im.resize((max(1, round(im.width * scale[c["label"]])), max(1, round(im.height * scale[c["label"]]))), Image.LANCZOS)
        sheet.paste(im, (x0 + (cw - im.width) // 2, y0 + 10 + (room[1] - im.height) // 2))
        w = draw.textlength(c["label"], font=f)
        draw.text((x0 + (cw - w) / 2, y0 + ch - label_h), c["label"], fill=INK, font=f)
        draw.rectangle([x0, y0, x0 + cw - 1, y0 + ch - 1], outline=RULE)
    path = job / "refs" / f"step-{n:02d}-sheet.png"
    sheet.save(path, optimize=True)
    return path


def questions(job: Path) -> dict:
    """Phase-2 questions for every step (pipeline.perception.questions). AGJ_QUESTIONS=model lets gemini-4-hydrogen
    write the visual criteria (one call per step, needs GEMINI_API_KEY); the default is template wording, with no
    network; `off` skips it. A failure here never fails the build: phase 1 does not need the questions."""
    mode = os.environ.get("AGJ_QUESTIONS", "template")
    if mode == "off":
        return {"skipped": "AGJ_QUESTIONS=off"}
    try:
        from pipeline.perception import questions as writer
        return writer.build(job, use_model=mode == "model")
    except Exception as e:
        return {"error": f"{type(e).__name__}: {e}"[:300]}


def build(plan_path: Path, job: Path) -> dict:
    t0 = time.perf_counter()
    plan = json.loads(plan_path.read_text())
    parts = blender("render_parts.py", plan_path, job / "parts", job)
    t1 = time.perf_counter()
    refs = blender("render_refs.py", job)
    for mode in ("added", "before"):
        blender("render_refs.py", job, 768, "-", job / "refs", "1,1,1", mode)
    t2 = time.perf_counter()
    manifest = json.loads((job / "manifest.json").read_text())
    alike = look_alikes(plan, job / "parts")
    px_per_mm = {k: v["px_per_mm"] for k, v in json.loads((job / "renders" / "parts.json").read_text()).items()}
    sizes = []
    for k, s in enumerate(manifest["steps"]):
        n = s["n"]
        cands = candidates(manifest, alike, k)
        path = compose(job, n, cands, px_per_mm)
        sizes.append(path.stat().st_size)
        (job / "refs" / f"step-{n:02d}-sheet.json").write_text(json.dumps({
            "step": n, "sheet": f"refs/{path.name}", "size_px": [2 * HALF, HALF],
            "left": "the correct result of this step, seen from the front, about 35 degrees above the table, "
                    "with the pieces this step adds drawn in orange",
            "right": "labelled candidate parts and hardware", "candidates": cands,
            "added": f"refs/step-{n:02d}-added.png", "before": f"refs/step-{n:02d}-before.png" if k else None}, indent=1))
        s["refRender"], s["refSheet"] = f"refs/step-{n:02d}-ref.png", f"refs/{path.name}"
    for p in manifest["parts"]:
        p["thumb"] = f"renders/part-{p['id']}-shaded.png"
    for h in manifest["hardware"]:
        h["thumb"] = f"renders/part-{h['code']}-shaded.png"
    (job / "manifest.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False))
    t3 = time.perf_counter()
    asked = questions(job)
    return {"questions": asked, "questions_s": round(time.perf_counter() - t3, 2), "part_renders": parts["files"], "refs": len(refs["refs"]), "sheets": len(sizes), "sheet_bytes_max": max(sizes),
            "look_alikes": {k: v for k, v in alike.items() if v},
            "timings_s": {"parts": round(t1 - t0, 2), "refs": round(t2 - t1, 2), "sheets": round(time.perf_counter() - t2, 2)}}


def main(plan_path: Path, job: Path) -> int:
    print("RESULT " + json.dumps(build(plan_path, job)))
    return 0


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1]), Path(sys.argv[2])))
