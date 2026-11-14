"""Make a captioned preview video of a built job: which parts and screws each step uses, and each action as it happens.

    .venv/bin/python -m pipeline.preview <job_dir> [en|el] [out.mp4]

Needs the job's scene.blend and manifest.json (from pipeline.run), Blender and ffmpeg.
"""
from __future__ import annotations

import json
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


def main(job: Path, lang: str, dest: Path) -> int:
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
    f_title, f_text, f_small = font(26), font(24), font(19)
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
        img.save(tmp / f"c{i:05d}.jpg", quality=90)
    r = subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", str(meta["fps"]), "-i", str(tmp / "c%05d.jpg"),
                        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "22", str(dest)])
    shutil.rmtree(tmp, ignore_errors=True)
    print(f"RESULT {dest} ({len(meta['frames'])} frames)")
    return r.returncode


if __name__ == "__main__":
    job = Path(sys.argv[1])
    lang = sys.argv[2] if len(sys.argv) > 2 else "en"
    sys.exit(main(job, lang, Path(sys.argv[3]) if len(sys.argv) > 3 else job / f"preview_{lang}.mp4"))
