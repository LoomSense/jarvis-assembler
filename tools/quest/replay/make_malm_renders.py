#!/usr/bin/env python3
"""Build the `malm-drawer-renders` replay dataset from the MALM drawer job's step renders.

  .venv/bin/python tools/quest/replay/make_malm_renders.py [--src <jobs/malm-drawer/renders>]

step_NN.png (900 px) -> 768 px JPEG (q85) on white, plus a plausible pinhole intrinsics.json
(50 deg field of view, principal point at the centre). No poses.jsonl: these frames test the
identify/locate path (does the server answer step N for frame N?), not the tracker.
Frames are named 000000.jpg.. in step order; dataset.json maps frame -> expected step.
"""
from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
OUT = HERE / "malm-drawer-renders"
EDGE = 768
HFOV_DEG = 50.0


def default_src() -> Path:
    rel = Path("jobs/malm-drawer/renders")
    roots = [HERE.parents[2]]
    try:   # jobs/ is git-ignored: a worktree may only have it in the main checkout
        wt = subprocess.run(["git", "-C", str(HERE), "worktree", "list", "--porcelain"], capture_output=True,
                            text=True).stdout.split("\n")[0].removeprefix("worktree ").strip()
        if wt:
            roots.append(Path(wt))
    except OSError:
        pass
    for r in roots:
        if (r / rel).is_dir():
            return r / rel
    return roots[0] / rel


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", type=Path, default=default_src())
    ap.add_argument("--fps", type=float, default=0.2, help="replay rate: 0.2 = each step frame shown for 5 s")
    a = ap.parse_args()
    steps = sorted(a.src.glob("step_[0-9][0-9].png"))
    if not steps:
        print(f"no step_NN.png in {a.src}", file=sys.stderr)
        return 1
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.jpg"):
        old.unlink()
    frames = []
    for i, p in enumerate(steps):
        im = Image.open(p)
        if im.mode in ("RGBA", "LA", "P"):
            im = im.convert("RGBA")
            bg = Image.new("RGBA", im.size, (255, 255, 255, 255))
            im = Image.alpha_composite(bg, im)
        im = im.convert("RGB")
        s = EDGE / max(im.size)
        im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
        name = f"{i:06d}.jpg"
        im.save(OUT / name, quality=85, optimize=True)
        frames.append({"frame": i, "file": name, "source": p.name, "expected_step": int(p.stem.split("_")[1])})
    W, H = im.size
    f = (W / 2) / math.tan(math.radians(HFOV_DEG) / 2)
    (OUT / "intrinsics.json").write_text(json.dumps(
        {"fx": round(f, 3), "fy": round(f, 3), "cx": (W - 1) / 2, "cy": (H - 1) / 2, "width": W, "height": H,
         "fps": a.fps, "distortion": None}, indent=2))
    (OUT / "dataset.json").write_text(json.dumps({
        "name": "malm-drawer-renders", "job": "malm-drawer", "frames": len(frames), "fps": a.fps,
        "note": "Blender step renders (white background), not camera photos. Intrinsics are plausible, "
                "not the render camera's. Use for identify/locate and step logic, not for tracking accuracy.",
        "frame_list": frames}, indent=2))
    size_kb = sum(p.stat().st_size for p in OUT.iterdir()) / 1024
    print(f"wrote {len(frames)} frames {W}x{H} to {OUT} ({size_kb:.0f} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
