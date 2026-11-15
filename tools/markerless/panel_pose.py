"""Evaluate the markerless rectangle detector (server/perception/panel.py, the only copy) on a dataset.

  python panel_pose.py DATA_DIR [OUT.json] [--raw]     # frames.jsonl + img/, see REPORT.md section 8

Per dataset state (state N = the assembly at the END of guide step N = what stands there when step N+1 starts):
the configuration the app uses for it (the per-step target of footprint.py) and, for the record, the
configurations that were tried and are NOT shipped (the whole outline of the turned-over box, the standing sides).
Success is REPORT.md's: within 10 mm and 5 deg (yaw modulo 180 deg, the rectangle's ambiguity). "gross" = a
trusted pose more than 30 mm or 10 deg off. "reports" replays the app's rule on each 30-frame sequence: the ghost
moves only on 3 trusted results in a row that agree within 10 mm and 3 deg, to their median.
`--raw` skips the image degradation (the renders as they are); the pose and table noise stay.
"""
import json
import os
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
from server.perception.panel import detect_footprint, detect_front_panel  # noqa: E402,F401  (re-exported for other tools)

PANEL = dict(length=0.380, width=0.130, height=0.016)          # the drawer front lying flat: target of steps 1-3
BOX = dict(length=0.418, width=0.380, height=0.130)            # the whole outline from step 4 (turned over)
TALL = dict(length=0.380, width=0.130, height=0.418)           # the whole outline with the sides standing
# (dataset state, label, detector arguments, shipped for which guide steps or None = not shipped)
CONFIGS = [
    (1, "front alone, as in step 1", dict(PANEL), "step 1"),
    (1, "front alone, hull", dict(PANEL, fill_hull=True), "step 2 before the sides are on"),
    (2, "front under the standing sides, hull", dict(PANEL, fill_hull=True), "steps 2 (sides on) and 3"),
    (2, "front under the standing sides, no hull", dict(PANEL), None),
    (2, "whole outline 380 x 130 x 418", dict(TALL), None),
    (2, "whole outline 380 x 130 x 418, hull", dict(TALL, fill_hull=True), None),
    (4, "whole outline 418 x 380 x 130", dict(BOX), None),
    (4, "whole outline 418 x 380 x 130, hull", dict(BOX, fill_hull=True), None),
    (4, "whole outline 418 x 380 at the side rims (115 mm)", dict(BOX, height=0.115), None),
    (4, "front panel target on the turned-over box (must not trust)", dict(PANEL, fill_hull=True), None),
    (5, "whole outline 418 x 380 x 130", dict(BOX), None),
    (5, "whole outline 418 x 380 x 130, hull", dict(BOX, fill_hull=True), None),
    (5, "whole outline 418 x 380 at the side rims (115 mm)", dict(BOX, height=0.115), None),
    (5, "front panel target on the turned-over box (must not trust)", dict(PANEL, fill_hull=True), None),
]
LONG_ALONG_OBJ_Y = {4, 5}     # the dataset's object x is the 380 mm side; from step 4 the long side is the 418 mm depth


def errors(r, f):
    """Position (mm) and yaw (deg, modulo 180) of a result against the ground truth of the rectangle."""
    T, G = np.array(r["T_world_obj"]), np.array(f["T_world_obj"])
    gyaw = f["obj_xy_yaw"][2] + (np.pi / 2 if f["state"] in LONG_ALONG_OBJ_Y else 0.0)
    dy = (r["yaw"] - gyaw) % np.pi
    return float(np.linalg.norm(T[:3, 3] - G[:3, 3]) * 1000), float(np.degrees(min(dy, np.pi - dy)))


def consensus(rows):
    """The app's PanelConsensus on one sequence: [(position error, yaw error)] of every median it would report."""
    out, win = [], []
    for r in rows:
        if not r["trusted"]:
            win = []
            continue
        win = (win + [r])[-3:]
        if len(win) < 3:
            continue
        ok = all(np.hypot(a["x"] - b["x"], a["y"] - b["y"]) <= 0.010
                 and abs((a["yaw"] - b["yaw"] + np.pi / 2) % np.pi - np.pi / 2) <= np.radians(3.0)
                 for i, a in enumerate(win) for b in win[i + 1:])
        if ok:
            ref = win[-1]["yaw"]
            yaw = float(np.median([ref + ((w["yaw"] - ref + np.pi / 2) % np.pi - np.pi / 2) for w in win]))
            x, y = float(np.median([w["x"] for w in win])), float(np.median([w["y"] for w in win]))
            T = np.array(r["T_world_table"]) @ np.array([x, y, 0, 1.0])
            dy = (yaw - r["gt_yaw"]) % np.pi
            out.append((float(np.linalg.norm(T[:3] - np.array(r["gt_t"])) * 1000), float(np.degrees(min(dy, np.pi - dy)))))
    return out


if __name__ == "__main__":
    from common import camera_pose_noisy, degrade, load_frames, table_prior
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    raw = "--raw" in sys.argv
    root = args[0]
    frames = load_frames(root)
    states = sorted({c[0] for c in CONFIGS})
    imgs = {}
    for f in frames:
        if f["state"] in states:
            img = cv2.imread(f"{root}/img/{f['id']}.jpg")
            imgs[f["id"]] = img if raw else degrade(img, f["id"], dim=f["light"] == "dim")
    print(f"{len(imgs)} frames, {'raw renders' if raw else 'degraded images'}, noisy camera pose and table plane")
    summary = []
    for state, label, kw, shipped in CONFIGS:
        rows = []
        for f in frames:
            if f["state"] != state:
                continue
            T_wc = camera_pose_noisy(np.array(f["T_world_cam"]), f["id"])
            T_wt = table_prior(f["seq"])
            t0 = time.perf_counter()
            r = detect_footprint(imgs[f["id"]], np.array(f["K"]), T_wc, T_wt, **kw)
            row = {"id": f["id"], "seq": f["seq"], "state": state, "light": f["light"], "hand": f["hand"], "config": label,
                   "trusted": r["trusted"], "reason": r["reason"], "ms": (time.perf_counter() - t0) * 1e3}
            if "x" in r:
                row["t_mm"], row["yaw_deg"] = errors(r, f)
                row.update(x=r["x"], y=r["y"], yaw=r["yaw"], T_world_table=T_wt.tolist(), gt_t=np.array(f["T_world_obj"])[:3, 3].tolist(),
                           gt_yaw=f["obj_xy_yaw"][2] + (np.pi / 2 if state in LONG_ALONG_OBJ_Y else 0.0))
            rows.append(row)
        tr = [r for r in rows if r["trusted"]]
        good = [r for r in tr if r["t_mm"] < 10 and r["yaw_deg"] < 5]
        gross = [r for r in tr if r["t_mm"] > 30 or r["yaw_deg"] > 10]
        rep = [e for s in sorted({r["seq"] for r in rows}) for e in consensus([r for r in rows if r["seq"] == s])]
        rep_gross = [e for e in rep if e[0] > 30 or e[1] > 10]
        t = [r["t_mm"] for r in tr]
        y = [r["yaw_deg"] for r in tr]
        reasons = {}
        for r in rows:
            reasons[r["reason"]] = reasons.get(r["reason"], 0) + 1
        hand = [r for r in rows if r["hand"]]
        print(f"\nstate {state}: {label}  [{'SHIPPED: ' + shipped if shipped else 'not shipped'}]")
        print(f"  n={len(rows)} trusted={len(tr)} ({100 * len(tr) / len(rows):.0f}%) within 10 mm & 5 deg={len(good)} "
              f"trusted but outside={len(tr) - len(good)} GROSS={len(gross)}"
              + (f"  pos p50={np.median(t):.1f} p90={np.percentile(t, 90):.1f} max={max(t):.1f} mm"
                 f"  yaw p50={np.median(y):.2f} p90={np.percentile(y, 90):.2f} max={max(y):.2f} deg" if tr else ""))
        print(f"  per light: " + ", ".join(f"{li} {sum(r['trusted'] for r in rows if r['light'] == li)}/{sum(r['light'] == li for r in rows)}"
                                           for li in ("office", "window", "dim"))
              + f"; hand frames trusted {sum(r['trusted'] for r in hand)}/{len(hand)}"
              + (f" (max {max(r['t_mm'] for r in hand if r['trusted']):.1f} mm)" if any(r["trusted"] for r in hand) else ""))
        print(f"  3-in-a-row reports={len(rep)}" + (f" max {max(e[0] for e in rep):.1f} mm / {max(e[1] for e in rep):.2f} deg" if rep else "")
              + f" gross reports={len(rep_gross)}")
        print(f"  reasons: {reasons}   {np.median([r['ms'] for r in rows]):.0f} ms p50")
        summary.append({"state": state, "config": label, "shipped": shipped, "n": len(rows), "trusted": len(tr), "within": len(good),
                        "gross": len(gross), "reports": len(rep), "gross_reports": len(rep_gross),
                        "pos_p50_p90_max": [float(np.median(t)), float(np.percentile(t, 90)), float(max(t))] if tr else None,
                        "yaw_p50_p90_max": [float(np.median(y)), float(np.percentile(y, 90)), float(max(y))] if tr else None,
                        "rows": [{k: v for k, v in r.items() if k not in ("T_world_table", "gt_t", "gt_yaw", "x", "y", "yaw")} for r in rows]})
    if len(args) > 1:
        json.dump({"degraded": not raw, "configs": summary}, open(args[1], "w"), indent=0)
