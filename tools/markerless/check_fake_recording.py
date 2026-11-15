#!/usr/bin/env python3
"""Prove recorder format -> eval_real.py end to end without a headset.

  python check_fake_recording.py [WORK_DIR]        # exit 0 = the pipeline reproduces the detector's numbers

1. make_fake_recording.py writes the sample renders as a recording in the app recorder's format.
2. eval_real.py runs on it (through `T_world_cam`, and again through `cam_p`/`cam_q`).
3. Each frame is compared with
   (a) the detector called directly the way panel_pose.py calls it, on this machine: must be the same pose
       within 0.05 mm and 0.01 deg, and the same trusted / reason. This is the proof that the format and the pose
       conversion lose nothing (the recording stores 32-bit poses, hence the tolerance);
   (b) the committed out/panel_pose.json: reported. A difference that (a) shows too is the committed file being
       older than the OpenCV build here, not the recording.
"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent.parent))
from common import camera_pose_noisy, degrade, load_frames, table_prior  # noqa: E402
from eval_real import evaluate  # noqa: E402
from make_fake_recording import make  # noqa: E402
from server.perception.panel import detect_front_panel  # noqa: E402

TOL_MM, TOL_DEG = 0.05, 0.01


def direct(sample: Path):
    """panel_pose.py's loop on the sample frames that have a picture."""
    rows = {}
    for f in load_frames(str(sample)):
        src = sample / "img" / f"{f['id']}.jpg"
        if not src.exists():
            continue
        img = degrade(cv2.imread(str(src)), f["id"], dim=f["light"] == "dim")
        r = detect_front_panel(img, np.array(f["K"]), camera_pose_noisy(np.array(f["T_world_cam"]), f["id"]),
                               table_prior(f["seq"]))
        row = {"state": f["state"], "trusted": r["trusted"], "reason": r["reason"]}
        if "x" in r:
            T, G = np.array(r["T_world_obj"]), np.array(f["T_world_obj"])
            dy = (r["yaw"] - f["obj_xy_yaw"][2]) % np.pi
            row.update(t_mm=float(np.linalg.norm(T[:3, 3] - G[:3, 3]) * 1000), yaw_deg=float(np.degrees(min(dy, np.pi - dy))))
        rows[f["id"]] = row
    return rows


def main() -> int:
    work = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(tempfile.mkdtemp(prefix="agj-fake-"))
    sample = HERE / "sample"
    ids = make(work / "dump", sample)
    ref_file = {r["id"]: r for r in json.loads((HERE / "out" / "panel_pose.json").read_text())["rows"]}
    ref_here = direct(sample)
    bad = 0
    for pose in ("matrix", "quat"):
        s, res = evaluate(work / "dump", work / f"eval-{pose}", pose=pose, quiet=True, overlays=pose == "matrix")
        print(f"\ncamera pose from {'T_world_cam' if pose == 'matrix' else 'cam_p / cam_q'}:")
        print(f"  {'frame':9s} {'recording -> eval_real':42s} {'detector called directly (here)':34s} committed panel_pose.json")
        for r in res:
            a, b, c = r, ref_here[r["id"]], ref_file[r["id"]]
            same = a["trusted"] == b["trusted"] and a["reason"] == b["reason"]
            if "t_mm" in a and "t_mm" in b:
                same = same and abs(a["t_mm"] - b["t_mm"]) <= TOL_MM and abs(a["yaw_deg"] - b["yaw_deg"]) <= TOL_DEG
            else:
                same = same and ("t_mm" in a) == ("t_mm" in b)
            bad += not same
            num = lambda d: f"{d['t_mm']:7.3f} mm {d['yaw_deg']:6.3f} deg" if d.get("t_mm") is not None and d.get("state", 1) == 1 else " " * 20  # noqa: E731
            a1 = dict(a, state=b["state"])
            file_same = (c["trusted"] == a["trusted"] and c["reason"] == a["reason"]
                         and (c.get("t_mm") is None or b["state"] != 1 or (abs(c["t_mm"] - a["t_mm"]) <= TOL_MM and abs(c["yaw_deg"] - a["yaw_deg"]) <= TOL_DEG)))
            print(f"  {r['id']:9s} {('trusted' if a['trusted'] else a['reason'][:18]):18s} {num(a1)}   "
                  f"{'SAME' if same else 'DIFFERENT':9s} {num(b)}   {'same' if file_same else 'differs: ' + num(c).strip()}")
    g = s["ghost_vs_detection_trusted"]
    print(f"\nghost pushed 22.4 mm and 4 deg aside on purpose: the tool reads {g['mm_p10_p50_p90'][1]} mm, {g['yaw_deg_p10_p50_p90'][1]} deg (p50)")
    print(f"overlays: {work / 'eval-matrix' / 'overlay'}")
    print(f"\n{'OK' if bad == 0 else 'FAILED'}: {2 * len(ids) - bad} of {2 * len(ids)} comparisons with the direct detector call agree")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
