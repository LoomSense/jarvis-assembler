"""Markdown tables for REPORT.md from out/*.json."""
import json
import os
import sys

import numpy as np

out = sys.argv[1] if len(sys.argv) > 1 else "out"
CONF = [
    ("a_track", "A edges, tracking, 6-DoF, degraded images + prior noise"),
    ("a_track_clean", "A edges, tracking, clean images, exact priors"),
    ("a_track_3dof", "A edges, tracking, 3-DoF only (x, y, yaw)"),
    ("a_track_scale2", "A edges, tracking, model 2% too long (dims estimated)"),
    ("a_single", "A edges, every frame from scratch (no temporal prior)"),
    ("b_orb", "B ORB templates -> PnP + table"),
    ("b_sift", "B SIFT templates -> PnP + table"),
]


def stats(rows):
    t = np.array([r["t_mm"] for r in rows]); rr = np.array([r["r_deg"] for r in rows])
    s = (t < 10) & (rr < 5)
    return t, rr, s


def pct(x):
    return f"{100 * np.mean(x):.0f}%" if len(x) else "-"


def fmt(v):
    return "inf" if not np.isfinite(v) else (f"{v:.1f}" if v < 1000 else f"{v:.0f}")


print("| config | n | success (<10 mm & <5 deg) | trans p50 / p90 (mm) | rot p50 / p90 (deg) | "
      "success, hand frames | ms/frame p50 (tracking) |")
print("|---|---|---|---|---|---|---|")
per_state = {}
for key, label in CONF:
    p = os.path.join(out, key + ".json")
    if not os.path.exists(p):
        continue
    rows = json.load(open(p))["rows"]
    t, rr, s = stats(rows)
    hand = [r for r in rows if r["hand"]]
    _, _, sh = stats(hand) if hand else (None, None, [])
    if "mode" in rows[0]:
        tr = [r["prep_ms"] + r["solve_ms"] for r in rows if r["mode"] in ("track", "hold")]
        if key == "a_single":
            tr = [r["prep_ms"] + r["solve_ms"] for r in rows]
    else:
        tr = [r["ms"] for r in rows]
    print(f"| {label} | {len(rows)} | {pct(s)} | {fmt(np.median(t))} / {fmt(np.percentile(t, 90))} | "
          f"{np.median(rr):.2f} / {np.percentile(rr, 90):.2f} | {pct(sh)} | {np.median(tr):.0f} |")
    per_state[key] = rows

print()
if "a_track" in per_state:
    rows = per_state["a_track"]
    print("Main config (A, tracking, degraded + noisy priors) by slice:\n")
    print("| slice | n | success | trans p50 (mm) | rot p50 (deg) | yaw p50 (deg) | 180 deg flips |")
    print("|---|---|---|---|---|---|---|")
    sl = [("state %d" % st, [r for r in rows if r["state"] == st]) for st in sorted({r["state"] for r in rows})]
    sl += [("light " + li, [r for r in rows if r["light"] == li]) for li in ("office", "window", "dim")]
    sl += [("hand occluding", [r for r in rows if r["hand"]]), ("no hand", [r for r in rows if not r["hand"]])]
    sl += [("after init (frame >= 3)", [r for r in rows if int(r["id"].split("_f")[1]) >= 3])]
    for name, rs in sl:
        t, rr, s = stats(rs)
        flips = np.mean([abs(r["yaw_deg"] - 180) < 10 for r in rs])
        print(f"| {name} | {len(rs)} | {pct(s)} | {np.median(t):.1f} | {np.median(rr):.2f} | "
              f"{np.median([r['yaw_deg'] for r in rs]):.2f} | {pct([flips])} |")
    modes = {}
    for r in rows:
        modes[r["mode"]] = modes.get(r["mode"], 0) + 1
    init_ms = [r["prep_ms"] + r["solve_ms"] for r in rows if r["mode"] == "init"]
    print(f"\nmodes: {modes}; init frame ms p50 {np.median(init_ms):.0f}, max {np.max(init_ms):.0f}")
