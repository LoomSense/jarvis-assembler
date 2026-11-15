"""Evaluate Method A (edge tracker) on the synthetic dataset.

  python eval_edges.py DATA_DIR OUT.json [--clean] [--no-prior-noise]
         [--single] [--model-scale 1.02] [--seqs 0,1] [--three-dof]

--single  : every frame from scratch (global search, no temporal prior)
default   : per sequence, global init on frame 0 then tracking.
"""
import argparse
import json
import time

import cv2
import numpy as np

from common import camera_pose_noisy, degrade, load_frames, pose_errors, table_prior
from edge_tracker import EdgeTracker
from model import load_state

ap = argparse.ArgumentParser()
ap.add_argument("data"); ap.add_argument("out")
ap.add_argument("--clean", action="store_true")
ap.add_argument("--no-prior-noise", action="store_true")
ap.add_argument("--single", action="store_true")
ap.add_argument("--three-dof", action="store_true")
ap.add_argument("--model-scale", type=float, default=1.0)
ap.add_argument("--seqs", default=None)
ap.add_argument("--stride", type=int, default=1)
a = ap.parse_args()

frames = load_frames(a.data)
seqs = sorted({f["seq"] for f in frames})
if a.seqs:
    seqs = [int(s) for s in a.seqs.split(",")]
noise = not a.no_prior_noise
rows = []
for s in seqs:
    fs = [f for f in frames if f["seq"] == s][:: a.stride]
    st = fs[0]["state"]
    m = load_state(st, (a.model_scale, 1.0, 1.0))
    T_wt = table_prior(s, noise)
    trk = EdgeTracker(m, T_wt, six_dof=not a.three_dof)
    for f in fs:
        img = cv2.imread(f"{a.data}/img/{f['id']}.jpg")
        if not a.clean:
            img = degrade(img, f["id"], dim=f["light"] == "dim")
        K = np.array(f["K"])
        T_wc = camera_pose_noisy(np.array(f["T_world_cam"]), f["id"], noise)
        if a.single:
            trk.th, trk.lost, trk.cands = None, True, None
        T, info = trk.step(img, K, T_wc)
        dt, dr, dy = pose_errors(T, np.array(f["T_world_obj"]))
        rows.append({"id": f["id"], "seq": s, "state": st, "light": f["light"], "hand": f["hand"],
                     "t_mm": dt, "r_deg": dr, "yaw_deg": dy, **info})
        print(f"{f['id']} st{st} {f['light']:6s} hand={int(f['hand'])} {info['mode']:5s} ok={int(info['ok'])} "
              f"t={dt:7.1f}mm r={dr:5.1f} yaw={dy:5.1f} inl={info['inliers']:.2f} "
              f"{info['prep_ms']:.0f}+{info['solve_ms']:.0f}ms", flush=True)
json.dump({"args": vars(a), "rows": rows}, open(a.out, "w"), indent=0)


def summ(rs, label):
    if not rs:
        return
    t = np.array([r["t_mm"] for r in rs]); rr = np.array([r["r_deg"] for r in rs])
    succ = np.mean((t < 10) & (rr < 5)) * 100
    flips = np.mean([abs(r["yaw_deg"] - 180) < 10 for r in rs]) * 100
    tr = [r for r in rs if r["mode"] == "track"]
    ms = np.array([r["prep_ms"] + r["solve_ms"] for r in tr]) if tr else np.array([np.nan])
    print(f"{label:22s} n={len(rs):4d} succ={succ:5.1f}%  t p50={np.median(t):6.1f} p90={np.percentile(t, 90):7.1f} mm  "
          f"r p50={np.median(rr):5.2f} p90={np.percentile(rr, 90):6.2f} deg  flip180={flips:4.1f}%  "
          f"track ms p50={np.nanmedian(ms):.0f}")


summ(rows, "ALL")
for st in sorted({r["state"] for r in rows}):
    summ([r for r in rows if r["state"] == st], f"state {st}")
for li in ["office", "window", "dim"]:
    summ([r for r in rows if r["light"] == li], f"light {li}")
summ([r for r in rows if r["hand"]], "hand occluding")
summ([r for r in rows if not r["hand"]], "no hand")
