"""Make a captioned preview video of a built job: which parts and screws each step uses, and each action as it happens.

    .venv/bin/python -m pipeline.preview <job_dir> [en|el] [out.mp4] [--callout]

--callout draws an arrow to the hole of the screw being driven, labelled with its code and its number in the
step ("108560  3/8"). Parts get no arrow: the tint already shows them, and an arrow to where they go confused.

Needs the job's scene.blend and manifest.json (from pipeline.run), Blender and ffmpeg.
"""
from __future__ import annotations

import json
import math
import shutil
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from pipeline.run import BLENDER

FONTS = ["/System/Library/Fonts/Supplemental/Arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]
INK, MUTED, RULE, PINE = (26, 26, 28), (96, 98, 104), (224, 226, 230), (204, 158, 102)
TOP, BOTTOM, PAD = 196, 96, 22
CALL = (20, 92, 200)
WORDS = {"en": ("Step", "of", "You need", "Finished"), "el": ("Βήμα", "από", "Θα χρειαστείς", "Έτοιμο")}


def font(size):
    for f in FONTS:
        if Path(f).exists():
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()


def wrap(draw, text, fnt, width):
    lines, line = [], ""
    for w in text.split():
        if draw.textlength((line + " " + w).strip(), font=fnt) <= width:
            line = (line + " " + w).strip()
        else:
            lines.append(line); line = w
    return lines + [line]


def callout(d, size, target, frm, label, colour, round_, fnt):
    """An arrow to `target` with a labelled box beside it, placed to the side of the piece's path."""
    tx, ty = target[0], target[1] + TOP
    ax, ay = frm[0] - target[0], frm[1] - target[1]
    n = math.hypot(ax, ay) or 1.0
    ax, ay = ax / n, ay / n
    w, h = d.textlength(label, font=fnt) + 52, 40
    best = None
    for sx, sy in ((-ay, ax), (ay, -ax)):                          # the two sides of the path; keep the box in view
        ux, uy = sx * 0.9 + ax * 0.45, sy * 0.9 + ay * 0.45
        cx = min(max(tx + ux * 150, PAD + w / 2), size - PAD - w / 2)
        cy = min(max(ty + uy * 150, TOP + PAD + h / 2), TOP + size - PAD - h / 2)
        dist = math.hypot(cx - tx, cy - ty)
        if best is None or dist > best[0]:
            best = (dist, cx, cy)
    _, cx, cy = best
    dx, dy = tx - cx, ty - cy
    n = math.hypot(dx, dy) or 1.0
    dx, dy = dx / n, dy / n
    tip = (tx - dx * 12, ty - dy * 12)                             # stop short of the hole so it stays visible
    d.line((cx, cy, tip[0] - dx * 12, tip[1] - dy * 12), fill=CALL, width=4)
    d.polygon([tip, (tip[0] - dx * 18 - dy * 9, tip[1] - dy * 18 + dx * 9), (tip[0] - dx * 18 + dy * 9, tip[1] - dy * 18 - dx * 9)], fill=CALL)
    box = (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    d.rounded_rectangle(box, radius=8, fill="white", outline=CALL, width=3)
    (d.ellipse if round_ else d.rectangle)((box[0] + 12, cy - 9, box[0] + 30, cy + 9), fill=colour, outline=INK)
    d.text((box[0] + 40, cy - fnt.size / 2 - 2), label, font=fnt, fill=INK)


def main(job: Path, lang: str, dest: Path, callouts: bool = False) -> int:
    m = json.loads((job / "manifest.json").read_text())
    tmp = Path(tempfile.mkdtemp(prefix="preview_"))
    script = Path(__file__).parent / "blender" / "preview_frames.py"
    r = subprocess.run([BLENDER, "-b", str(job / "scene.blend"), "-P", str(script), "--", str(job), str(tmp)], capture_output=True, text=True)
    if not (tmp / "frames.json").exists():
        print(r.stdout[-1500:], r.stderr[-1500:]); return 2
    meta = json.loads((tmp / "frames.json").read_text())
    size, steps = meta["size"], m["steps"]
    parts = {p["id"]: p for p in m["parts"]}
    hw = {h["code"]: h for h in m["hardware"]}
    node_part = {n: p["id"] for p in m["parts"] for n in p["nodes"]}
    f_title, f_text, f_small, f_call = font(26), font(24), font(19), font(22)
    step_w, of_w, need_w, done_w = WORDS[lang]
    for i, fr in enumerate(meta["frames"], start=1):
        st = steps[fr["step"]]
        img = Image.new("RGB", (size, TOP + size + BOTTOM), "white")
        img.paste(Image.open(tmp / f"f{i:05d}.jpg"), (0, TOP))
        d = ImageDraw.Draw(img)
        d.text((PAD, 16), f"{step_w} {fr['step'] + 1} {of_w} {len(steps)}", font=f_small, fill=MUTED)
        title = wrap(d, st["title"], f_title, size - 2 * PAD)[:2]
        for k, line in enumerate(title):
            d.text((PAD, 42 + k * 32), line, font=f_title, fill=INK)
        # what this step uses: parts as wood chips, each screw type as a dot in its own colour
        x, y = PAD, 50 + len(title) * 32
        d.text((x, y + 4), need_w + ":", font=f_small, fill=MUTED); x += d.textlength(need_w + ":", font=f_small) + 14
        used = Counter(node_part[a["node"]] for a in st["actions"] if a["type"] == "place")
        items = [(PINE, False, f"{n}× {parts[p]['short']}") for p, n in used.items()]
        items += [(tuple(int(c * 255) for c in hw[h["code"]]["color"]), True, f"{h['qty']}× {hw[h['code']]['short']} {h['code']}") for h in st["hardware"]]
        x0 = x
        for col, round_, label in items:
            if x + 26 + d.textlength(label, font=f_small) > size - PAD and x > x0:   # wrap the list onto a new line
                x, y = x0, y + 28
            (d.ellipse if round_ else d.rectangle)((x, y + 6, x + 18, y + 24), fill=col, outline=INK)
            d.text((x + 26, y + 4), label, font=f_small, fill=INK); x += 26 + d.textlength(label, font=f_small) + 22
        d.line((0, TOP - 1, size, TOP - 1), fill=RULE); d.line((0, TOP + size, size, TOP + size), fill=RULE)
        if fr["after_last"]:
            text = next((s["text"][lang] for s in m["walkthrough"]["steps"] if s["n"] == "finish"), done_w)
        elif fr["action"] is not None:
            text = st["actions"][fr["action"]]["text"][lang]
        else:
            text = ""
        for k, line in enumerate(wrap(d, text, f_text, size - 2 * PAD)[:2]):
            d.text((PAD, TOP + size + 16 + k * 32), line, font=f_text, fill=INK)
        if callouts and fr.get("target_px") and fr["action"] is not None:
            a = st["actions"][fr["action"]]
            if a["type"] == "fasten":
                same = [k for k, b in enumerate(st["actions"]) if b["type"] == "fasten" and b["code"] == a["code"]]
                label = f"{a['code']}   {same.index(fr['action']) + 1}/{len(same)}"
                callout(d, size, fr["target_px"], fr["from_px"], label, tuple(int(c * 255) for c in hw[a["code"]]["color"]), True, f_call)
        img.save(tmp / f"c{i:05d}.jpg", quality=90)
    r = subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", str(meta["fps"]), "-i", str(tmp / "c%05d.jpg"),
                        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "22", str(dest)])
    shutil.rmtree(tmp, ignore_errors=True)
    print(f"RESULT {dest} ({len(meta['frames'])} frames)")
    return r.returncode


if __name__ == "__main__":
    argv = [a for a in sys.argv[1:] if a != "--callout"]
    job = Path(argv[0])
    lang = argv[1] if len(argv) > 1 else "en"
    sys.exit(main(job, lang, Path(argv[2]) if len(argv) > 2 else job / f"preview_{lang}.mp4", "--callout" in sys.argv))
