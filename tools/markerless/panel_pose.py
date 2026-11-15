"""Evaluate the front panel detector (server/perception/panel.py, the only copy) on a dataset.

  python panel_pose.py DATA_DIR [OUT.json]     # frames.jsonl + img/, see REPORT.md section 8
"""
import json
import os
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
from server.perception.panel import detect_front_panel  # noqa: E402,F401  (re-exported for other tools)


if __name__ == "__main__":
    from common import camera_pose_noisy, degrade, load_frames, table_prior
    root = sys.argv[1]
    rows = []
    for f in load_frames(root):
        img = degrade(cv2.imread(f"{root}/img/{f['id']}.jpg"), f["id"], dim=f["light"] == "dim")
        K = np.array(f["K"])
        T_wc = camera_pose_noisy(np.array(f["T_world_cam"]), f["id"])
        T_wt = table_prior(f["seq"])
        t0 = time.perf_counter()
        r = detect_front_panel(img, K, T_wc, T_wt)
        ms = (time.perf_counter() - t0) * 1e3
        row = {"id": f["id"], "state": f["state"], "light": f["light"], "hand": f["hand"],
               "trusted": r["trusted"], "reason": r["reason"], "ms": ms}
        if "x" in r and f["state"] == 1:
            T = np.array(r["T_world_obj"]); G = np.array(f["T_world_obj"])
            row["t_mm"] = float(np.linalg.norm(T[:3, 3] - G[:3, 3]) * 1000)
            dy = (r["yaw"] - f["obj_xy_yaw"][2]) % np.pi
            row["yaw_deg"] = float(np.degrees(min(dy, np.pi - dy)))
        rows.append(row)
    if len(sys.argv) > 2:
        json.dump({"rows": rows}, open(sys.argv[2], "w"), indent=0)
    s1 = [r for r in rows if r["state"] == 1]

    def rep(rs, label):
        tr = [r for r in rs if r["trusted"]]
        good = [r for r in tr if r["t_mm"] < 10 and r["yaw_deg"] < 5]
        t = [r["t_mm"] for r in tr]; y = [r["yaw_deg"] for r in tr]
        print(f"{label:16s} n={len(rs):3d} trusted={len(tr):3d} ({100 * len(tr) / max(1, len(rs)):.0f}%) "
              f"trusted&correct={len(good):3d} wrong-but-trusted={len(tr) - len(good)} "
              + (f"pos p50={np.median(t):.1f} p90={np.percentile(t, 90):.1f} max={max(t):.1f} mm  "
                 f"yaw p50={np.median(y):.2f} p90={np.percentile(y, 90):.2f} deg" if tr else ""))

    rep(s1, "front panel ALL")
    for li in ("office", "window", "dim"):
        rep([r for r in s1 if r["light"] == li], "  " + li)
    rep([r for r in s1 if r["hand"]], "  hand frames")
    rep([r for r in s1 if not r["hand"]], "  no hand")
    other = [r for r in rows if r["state"] != 1]
    print(f"other assembly states (panel not alone): n={len(other)} falsely trusted={sum(r['trusted'] for r in other)}")
    reasons = {}
    for r in s1:
        reasons[r["reason"]] = reasons.get(r["reason"], 0) + 1
    print("reasons (front panel frames):", reasons)
    print(f"ms/frame p50={np.median([r['ms'] for r in rows]):.1f} p90={np.percentile([r['ms'] for r in rows], 90):.1f}")
