#!/usr/bin/env python3
"""Run the front-panel detector on a recording from the headset and show what it did.

  python eval_real.py <pulled-folder> [--out DIR] [--surface-offset-mm MM | A:B:STEP | a,b,c] [--sweep]
                      [--surface-y Y] [--prev-yaw own|ghost|none] [--undistort] [--pose matrix|quat]
                      [--limit N] [--no-overlays] [--no-grid]

<pulled-folder> is a session written by the app's frame recorder (quest-android vision/FrameDump.kt, launch
extra `--ez dump_frames true`) and fetched with tools/markerless/pull.sh. It is the project's replay format,
extended: `000000.jpg`, ..., `intrinsics.json`, and `poses.jsonl` with one JSON object per frame (file, capture
time, `fx fy cx cy` in pixels of that JPEG, the camera pose `cam_p`/`cam_q` and the same as `T_world_cam`,
`surface_y`, the ghost pose, step/action, the app's own tracker result). `frames.jsonl` is accepted as the name
of that file too.

For every frame it calls `server.perception.panel.detect_front_panel(img, K, T_world_cam, T_world_table)` with
the table plane at `surface_y` (+ `--surface-offset-mm`), and writes into --out (default: `eval/` next to the
folder):

  overlay/<frame>.jpg   the candidate rectangle (green = trusted, orange = not trusted), the reason, the numbers
                        behind the gates, the ghost's outline from the recorded pose (azure), the app's own
                        markerless result (white), and a 10 cm grid drawn ON the table plane: if the grid does not
                        stay glued to the real table while the head moves, the camera pose or the table height
                        is wrong, whatever the detector says
  contact_NN.jpg        every overlay as a thumbnail, bordered green / orange / grey (no candidate)
  results.jsonl         one line per frame with every number
  summary.txt / .json   trusted %, reasons, size-error / rectness / contrast distributions, pose stability across
                        consecutive trusted frames (mm, deg), ghost against detection, and what to change if a
                        gate is rejecting frames whose pose agrees with the trusted ones

`--surface-offset-mm` moves the table plane up (+) or down (-); with several values (`-40:40:10` or `-20,0,20`)
it prints one row per height and uses the best one for the overlays. `--sweep` does that height sweep and then
runs the detector again with each gate relaxed in turn (size tolerance, rectangle fill, contrast).

Conventions (stated in FrameDump.kt, tested there): WORLD is right-handed, +Y up, metres. `T_world_cam` is
WORLD <- CAMERA, 4 x 4 row-major, camera axes OpenCV (x right, y down, z forward). `T_world_table` is
[[1,0,0,0],[0,0,1,surface_y],[0,-1,0,0],[0,0,0,1]]: table x = world x, table y = world -z, table z = up.
Intrinsics are in pixels of the stored JPEG, origin at the top-left pixel. The pictures are used as they are
(the app does not undistort either); `--undistort` applies the recorded distortion first, to see if it matters.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import time
from contextlib import contextmanager
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent.parent))
from server.perception import panel as panel_mod  # noqa: E402
from server.perception.panel import LONG, SHORT, THICK, detect_front_panel  # noqa: E402

# ASSEMBLY <- PANEL for the MALM drawer front lying inside face up (quest-android vision/PanelPose.kt,
# PanelFrames.ASSEMBLY_FROM_PANEL): PANEL = panel centre on the table, x along the 380 mm side, z up.
A_FROM_P = np.array([[1, 0, 0, 0], [0, -1, 0, 0.05], [0, 0, -1, 0.016], [0, 0, 0, 1]], float)
PANEL_JOB = "malm-drawer"

R_SIZE = "size is not 380 x 130 mm"
R_RECT = "not a clean rectangle (hand or object over it)"
R_CUT = "panel partly out of the camera view"
R_CONTRAST = "low contrast against the table"
R_NO_POSE = "no camera pose"
R_NO_TABLE = "no table"
R_NO_IMAGE = "image missing"

AGREE_MM, AGREE_DEG = 15.0, 3.0     # a candidate "agrees" with the trusted poses within this
NEAR_S = 5.0                         # ... of the trusted frames this close in time (else all of them)
PAIR_GAP_S = 2.0                     # consecutive trusted frames further apart are not a stability pair

GREEN, ORANGE, AZURE, WHITE, GREY = (70, 200, 70), (0, 140, 255), (255, 190, 60), (255, 255, 255), (150, 150, 150)


# ---------------------------------------------------------------- geometry

def quat_to_R(q):
    x, y, z, w = (float(v) for v in q)
    n = math.sqrt(x * x + y * y + z * z + w * w) or 1.0
    x, y, z, w = x / n, y / n, z / n, w / n
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def pose_matrix(p, q):
    """[x, y, z] + [x, y, z, w] -> 4 x 4 (A <- B), None when either is missing."""
    if p is None or q is None:
        return None
    T = np.eye(4)
    T[:3, :3] = quat_to_R(q)
    T[:3, 3] = p
    return T


def table_matrix(surface_y):
    """WORLD <- TABLE for a level surface at WORLD height surface_y (FrameDump.kt DumpFormat.tableMatrix)."""
    return np.array([[1, 0, 0, 0], [0, 0, 1, surface_y], [0, -1, 0, 0], [0, 0, 0, 1]], float)


def ang_mod_pi(a, b):
    """|a - b| for directions known modulo 180 deg, radians."""
    d = (a - b) % math.pi
    return min(d, math.pi - d)


def mean_mod_pi(yaws):
    yaws = np.asarray(yaws, float)
    return 0.5 * math.atan2(np.sin(2 * yaws).mean(), np.cos(2 * yaws).mean())


def project(T_wc, K, pts_world):
    """WORLD points (N x 3) -> pixels (N x 2) and depths (N)."""
    T_cw = np.linalg.inv(T_wc)
    X = (T_cw[:3, :3] @ np.asarray(pts_world, float).T).T + T_cw[:3, 3]
    z = X[:, 2]
    zs = np.where(np.abs(z) < 1e-9, 1e-9, z)
    uv = np.stack([K[0, 0] * X[:, 0] / zs + K[0, 2], K[1, 1] * X[:, 1] / zs + K[1, 2]], 1)
    return uv, z


def panel_corners_world(T_world_panel):
    """Top-face corners of a 380 x 130 x 16 mm panel whose PANEL frame is given, in the detector's order."""
    c = np.array([[sx * LONG / 2, sy * SHORT / 2, THICK, 1.0] for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1))])
    return (T_world_panel @ c.T).T[:, :3]


# ---------------------------------------------------------------- the recording

def load_recording(folder: Path, surface_y=None, pose_from="matrix"):
    """-> (frames, info). A frame: file, t, K, dist, T_wc, T_wt, surface_y, ghost, app_panel, step, ... raw."""
    rows_file = next((folder / n for n in ("poses.jsonl", "frames.jsonl") if (folder / n).exists()), None)
    if rows_file is None:
        raise SystemExit(f"{folder}: no poses.jsonl (or frames.jsonl). Is this a pulled dump session?")
    intr = json.loads((folder / "intrinsics.json").read_text()) if (folder / "intrinsics.json").exists() else {}
    session = json.loads((folder / "session.json").read_text()) if (folder / "session.json").exists() else {}
    frames, warnings = [], []
    for n, line in enumerate(rows_file.read_text().splitlines()):
        line = line.strip()
        if not line:
            continue
        try:
            r = json.loads(line)
        except ValueError:
            warnings.append(f"line {n + 1} of {rows_file.name} is not JSON (the app was stopped while writing?)")
            continue
        g = lambda k: r.get(k) if r.get(k) is not None else intr.get(k)  # noqa: E731
        if g("fx") is None:
            warnings.append(f"line {n + 1}: no intrinsics")
            continue
        K = np.array([[g("fx"), 0, g("cx")], [0, g("fy"), g("cy")], [0, 0, 1]], float)
        T_q = pose_matrix(r.get("cam_p"), r.get("cam_q"))
        T_m = np.array(r["T_world_cam"], float) if r.get("T_world_cam") is not None else None
        if T_q is not None and T_m is not None and np.abs(T_q - T_m).max() > 1e-4:
            warnings.append(f"{r.get('file')}: T_world_cam and cam_p/cam_q differ by {np.abs(T_q - T_m).max():.2g}")
        T_wc = (T_q if T_q is not None else T_m) if pose_from == "quat" else (T_m if T_m is not None else T_q)
        sy = surface_y if surface_y is not None else r.get("surface_y")
        if surface_y is None and r.get("T_world_table") is not None:
            T_wt = np.array(r["T_world_table"], float)
        else:
            T_wt = table_matrix(sy) if sy is not None else None
        gt = r.get("gt")
        frames.append({
            "n": len(frames), "frame": r.get("frame", n), "file": r.get("file") or f"{n:06d}.jpg",
            "id": r.get("id") or Path(r.get("file") or f"{n:06d}").stem,
            "t": r.get("t"), "K": K, "size": (g("width"), g("height")),
            "dist": r.get("distortion") if r.get("distortion") is not None else intr.get("distortion"),
            "T_wc": T_wc, "T_wt": T_wt, "surface_y": sy,
            "ghost": pose_matrix(r.get("ghost_p"), r.get("ghost_q")),
            "app_panel": pose_matrix(r.get("panel_p"), r.get("panel_q")),
            "anchor_source": r.get("anchor_source"), "job": r.get("job"), "step": r.get("step"),
            "action": r.get("action"), "track": r.get("track") or {},
            "gt": {"T_world_obj": np.array(gt["T_world_obj"], float), "yaw": gt.get("yaw")} if gt else None,
        })
    return frames, {"folder": str(folder), "rows_file": rows_file.name, "intrinsics": intr, "session": session,
                    "warnings": warnings}


def read_image(folder: Path, fr, undistort=False):
    img = cv2.imread(str(folder / fr["file"]), cv2.IMREAD_COLOR)
    if img is None:
        return None, fr["K"]
    K = fr["K"]
    w, h = fr["size"]
    if w and h and (img.shape[1], img.shape[0]) != (w, h):          # as Intrinsics.scaledTo in the app
        sx, sy = img.shape[1] / w, img.shape[0] / h
        K = K * np.array([[sx], [sy], [1.0]])
    if undistort and fr["dist"] and any(abs(d) > 1e-12 for d in fr["dist"]):
        img = cv2.undistort(img, K, np.array(fr["dist"], float))
    return img, K


# ---------------------------------------------------------------- the detector on every frame

@contextmanager
def constants(**kw):
    """Temporarily change the detector's gate constants (TOL_LONG, TOL_SHORT, MIN_RECT, MIN_CONTRAST)."""
    old = {k: getattr(panel_mod, k) for k in kw}
    for k, v in kw.items():
        setattr(panel_mod, k, v)
    try:
        yield
    finally:
        for k, v in old.items():
            setattr(panel_mod, k, v)


def ghost_panel(fr):
    """WORLD <- PANEL of the ghost's drawer front, None when the recording has no ghost pose for this job."""
    if fr["ghost"] is None or fr["job"] not in (None, PANEL_JOB):
        return None
    return fr["ghost"] @ A_FROM_P


def run(folder: Path, frames, offset_m=0.0, prev_yaw="own", undistort=False, keep=None):
    """detect_front_panel on every frame. `keep(fr, img, K, res, T_wt)` is called with the decoded picture."""
    out, last_yaw = [], None
    for fr in frames:
        res = {"n": fr["n"], "file": fr["file"], "id": fr["id"], "t": fr["t"], "step": fr["step"],
               "action": fr["action"], "ran": False, "trusted": False, "reason": None}
        T_wt = None
        if fr["T_wt"] is not None:
            T_wt = fr["T_wt"].copy()
            T_wt[:3, 3] += offset_m * T_wt[:3, 2]
            res["surface_y"] = float(T_wt[1, 3])
        img, K = read_image(folder, fr, undistort)
        if img is None:
            res["reason"] = R_NO_IMAGE
        elif fr["T_wc"] is None:
            res["reason"] = R_NO_POSE
        elif T_wt is None:
            res["reason"] = R_NO_TABLE
        else:
            T_tc = np.linalg.inv(T_wt) @ fr["T_wc"]
            res["cam_height_m"] = float(T_tc[2, 3])
            res["look_down_deg"] = float(np.degrees(np.arcsin(np.clip(-T_tc[2, 2], -1, 1))))
            prev = None
            if prev_yaw == "own":
                prev = last_yaw
            elif prev_yaw == "ghost" and ghost_panel(fr) is not None:
                G = np.linalg.inv(T_wt) @ ghost_panel(fr)
                prev = float(np.arctan2(G[1, 0], G[0, 0]))
            t0 = time.perf_counter()
            r = detect_front_panel(img, K, fr["T_wc"], T_wt, prev)
            res["ms"] = (time.perf_counter() - t0) * 1e3
            res.update(ran=True, trusted=bool(r["trusted"]), reason=r["reason"])
            if "x" in r:
                T_obj = np.array(r["T_world_obj"])
                res.update(x=r["x"], y=r["y"], yaw=r["yaw"], pos_world=T_obj[:3, 3].tolist(),
                           corners_px=r["corners_px"], size_err_mm=r["size_err_mm"], rectness=r["rectness"],
                           contrast=r["contrast"])
                if r["trusted"]:
                    last_yaw = r["yaw"]
                gp = ghost_panel(fr)
                if gp is not None:
                    d = np.linalg.inv(T_wt) @ gp                         # the ghost's panel in the table frame
                    res["ghost_mm"] = float(np.hypot(d[0, 3] - r["x"], d[1, 3] - r["y"]) * 1000)
                    res["ghost_yaw_deg"] = float(np.degrees(ang_mod_pi(np.arctan2(d[1, 0], d[0, 0]), r["yaw"])))
                    res["ghost_up_mm"] = float(d[2, 3] * 1000)
                if fr["gt"] is not None:
                    res["t_mm"] = float(np.linalg.norm(T_obj[:3, 3] - fr["gt"]["T_world_obj"][:3, 3]) * 1000)
                    if fr["gt"]["yaw"] is not None:
                        res["yaw_deg"] = float(np.degrees(ang_mod_pi(r["yaw"], fr["gt"]["yaw"])))
        if keep is not None:
            keep(fr, img, K, res, T_wt)
        out.append(res)
    return out


# ---------------------------------------------------------------- numbers

def pcts(v, qs=(10, 50, 90)):
    v = np.asarray([x for x in v if x is not None], float)
    return None if v.size == 0 else [round(float(np.percentile(v, q)), 2) for q in qs]


def fmt(p, unit=""):
    return "-" if p is None else " / ".join(f"{x:g}" for x in p) + (f" {unit}" if unit else "")


def stability(results):
    """Consecutive trusted frames: how far the pose moved between them. The panel lies still, so this is noise."""
    tr = [r for r in results if r["trusted"]]
    dpos, dyaw = [], []
    for a, b in zip(tr, tr[1:]):
        if a["t"] is not None and b["t"] is not None and b["t"] - a["t"] > PAIR_GAP_S:
            continue
        dpos.append(float(np.hypot(b["x"] - a["x"], b["y"] - a["y"]) * 1000))
        dyaw.append(float(np.degrees(ang_mod_pi(b["yaw"], a["yaw"]))))
    spread = None
    if len(tr) >= 2:
        mx, my = np.median([r["x"] for r in tr]), np.median([r["y"] for r in tr])
        spread = pcts([np.hypot(r["x"] - mx, r["y"] - my) * 1000 for r in tr], (50, 90, 100))
    return {"pairs": len(dpos), "step_mm_p50_p90_max": pcts(dpos, (50, 90, 100)),
            "step_deg_p50_p90_max": pcts(dyaw, (50, 90, 100)), "from_median_mm_p50_p90_max": spread}


def mark_agreement(results):
    """Sets r['agrees'] (True / False / None) for every candidate: is its pose where the trusted ones are?

    Reference for a frame = the median of the trusted frames within NEAR_S seconds (not itself), else of all the
    trusted frames; with fewer than 3 trusted frames, the median of ALL candidates if at least half of them sit
    together. Returns which reference was used: 'trusted', 'candidates' or None.
    """
    cand = [r for r in results if "x" in r]
    tr = [r for r in cand if r["trusted"]]
    for r in cand:
        r["agrees"] = None

    def close(r, ref):
        return (np.hypot(r["x"] - ref[0], r["y"] - ref[1]) * 1000 <= AGREE_MM
                and np.degrees(ang_mod_pi(r["yaw"], ref[2])) <= AGREE_DEG)

    def ref_of(rs):
        return (float(np.median([q["x"] for q in rs])), float(np.median([q["y"] for q in rs])),
                mean_mod_pi([q["yaw"] for q in rs]))

    if len(tr) >= 3:
        for r in cand:
            others = [q for q in tr if q is not r]
            near = [q for q in others if r["t"] is not None and q["t"] is not None and abs(q["t"] - r["t"]) <= NEAR_S]
            use = near if len(near) >= 2 else others
            if len(use) >= 2:
                r["agrees"] = bool(close(r, ref_of(use)))
        return "trusted"
    if len(cand) >= 5:
        ref = ref_of(cand)
        inl = [r for r in cand if close(r, ref)]
        if len(inl) >= max(5, len(cand) / 2):
            ref = ref_of(inl)
            for r in cand:
                r["agrees"] = bool(close(r, ref))
            return "candidates"
    return None


def height_hint(results):
    """A size error that is the same fraction of both sides looks like a wrong plane height: estimate it (mm)."""
    est = []
    for r in results:
        if "size_err_mm" not in r or r.get("agrees") is False or "cam_height_m" not in r:
            continue
        if not (r["trusted"] or r.get("agrees") or r["reason"] == R_SIZE):
            continue
        eps = r["size_err_mm"][0] / (LONG * 1000)
        est.append((r["cam_height_m"] - THICK) * eps / (1 + eps) * 1000)
    if len(est) < 5:
        return None
    est = np.array(est)
    med = float(np.median(est))
    same = float((np.sign(est) == np.sign(med)).mean())
    return {"offset_mm": round(med, 1), "frames": int(est.size), "same_sign": round(same, 2),
            "iqr_mm": round(float(np.percentile(est, 75) - np.percentile(est, 25)), 1)}


def suggestions(results, ref_kind):
    """What the numbers say about each gate. Text lines + a machine-readable list."""
    lines, items = [], []
    cand = [r for r in results if "x" in r]
    tol_l, tol_s = panel_mod.TOL_LONG * 1000, panel_mod.TOL_SHORT * 1000
    min_rect, min_con = panel_mod.MIN_RECT, panel_mod.MIN_CONTRAST
    if not cand:
        ran = [r for r in results if r["ran"]]
        if not ran:
            lines.append("The detector did not run on any frame (see the reasons): fix the recording first "
                         "(camera pose and surface height must be in poses.jsonl).")
        else:
            lines.append("No candidate rectangle in any frame: nothing about 380 x 130 mm and whiter than the "
                         "table was found. Look at the overlays: is the panel in view, is the grid on the table?")
        return lines, items
    if ref_kind is None:
        lines.append(f"Fewer than 3 trusted frames and the {len(cand)} candidates do not sit in one place: "
                     "there is no reference to tell good rejected frames from bad ones. Look at the overlays.")
    else:
        lines.append(f"Reference for 'agrees': the {'trusted poses' if ref_kind == 'trusted' else 'cluster of all candidates (too few trusted frames)'}"
                     f", within {AGREE_MM:g} mm and {AGREE_DEG:g} deg.")
        bad_tr = [r for r in cand if r["trusted"] and r.get("agrees") is False]
        if bad_tr:
            lines.append(f"WARNING: {len(bad_tr)} trusted frames do NOT agree with the other trusted frames "
                         f"({', '.join(r['file'] for r in bad_tr[:6])}{' ...' if len(bad_tr) > 6 else ''}): "
                         "either the panel was moved, or the camera pose / capture time is off in those frames, "
                         "or a gate is too loose.")

    def would_pass(r, tl, ts, mr, mc):
        e = r["size_err_mm"]
        if abs(e[0]) > tl or abs(e[1]) > ts or r["rectness"] < mr:
            return False
        if r["reason"] == R_CUT:
            return False                                   # the 3 % ring limit is not a returned number
        return r["contrast"] >= mc

    def report(gate, rej, new, label, detail):
        good = [r for r in rej if r.get("agrees")]
        lines.append(f"{gate}: {len(rej)} frames rejected, {len(good)} of them agree with the reference. {detail}")
        if len(good) < 3:
            if rej and ref_kind is not None:
                lines.append("  -> no change suggested (fewer than 3 rejected frames agree).")
            return
        got = [r for r in results if "x" in r and not r["trusted"] and would_pass(r, *new)]
        gg = sum(1 for r in got if r.get("agrees"))
        gb = sum(1 for r in got if r.get("agrees") is False)
        gu = len(got) - gg - gb
        lines.append(f"  -> {label} would trust {len(got)} more frames: {gg} that agree, {gb} that do not"
                     + (f", {gu} unknown" if gu else "") + ". Confirm with --sweep (it re-runs the detector).")
        items.append({"gate": gate, "suggest": label, "gain_agree": gg, "gain_disagree": gb, "gain_unknown": gu})

    rej = [r for r in cand if r["reason"] == R_SIZE]
    good = [r for r in rej if r.get("agrees")]
    detail = f"Their size error (long / short, mm): p50 {fmt(pcts([r['size_err_mm'][0] for r in good], (50,)))} / " \
             f"{fmt(pcts([r['size_err_mm'][1] for r in good], (50,)))}." if good else ""
    nl = max(tol_l, math.ceil(np.percentile([abs(r["size_err_mm"][0]) for r in good], 90) + 1)) if good else tol_l
    ns = max(tol_s, math.ceil(np.percentile([abs(r["size_err_mm"][1]) for r in good], 90) + 1)) if good else tol_s
    report("size gate", rej, (nl, ns, min_rect, min_con),
           f"TOL_LONG {tol_l:g} -> {nl:g} mm, TOL_SHORT {tol_s:g} -> {ns:g} mm", detail)

    rej = [r for r in cand if r["reason"] == R_RECT]
    good = [r for r in rej if r.get("agrees")]
    nr = min(min_rect, math.floor(np.percentile([r["rectness"] for r in good], 10) * 100) / 100) if good else min_rect
    report("rectangle gate", rej, (tol_l, tol_s, nr, min_con), f"MIN_RECT {min_rect:g} -> {nr:g}",
           f"Their fill: p10 / p50 {fmt(pcts([r['rectness'] for r in good], (10, 50)))}." if good else "")

    rej = [r for r in cand if r["reason"] == R_CONTRAST]
    good = [r for r in rej if r.get("agrees")]
    nc = min(min_con, math.floor(np.percentile([r["contrast"] for r in good], 10))) if good else min_con
    report("contrast gate", rej, (tol_l, tol_s, min_rect, nc), f"MIN_CONTRAST {min_con:g} -> {nc:g}",
           f"Their contrast: p10 / p50 {fmt(pcts([r['contrast'] for r in good], (10, 50)))}." if good else "")

    rej = [r for r in cand if r["reason"] == R_CUT]
    good = [r for r in rej if r.get("agrees")]
    lines.append(f"out-of-view gate: {len(rej)} frames rejected, {len(good)} of them agree with the reference."
                 + (" The 3 % limit is a literal in panel.py (`cut > 0.03`); the value per frame is not returned,"
                    " so no number is suggested: look at those overlays." if len(good) >= 3 else ""))

    hh = height_hint(results)
    if hh and abs(hh["offset_mm"]) >= 5 and hh["same_sign"] >= 0.75:
        lines.append(f"surface height: the long side is off by the same sign in {hh['same_sign'] * 100:.0f} % of "
                     f"{hh['frames']} frames, as a table plane {abs(hh['offset_mm']):g} mm too "
                     f"{'low' if hh['offset_mm'] > 0 else 'high'} would do (spread {hh['iqr_mm']:g} mm IQR). "
                     f"Try --surface-offset-mm {hh['offset_mm']:+g} or --sweep. A focal length that is off "
                     "looks similar.")
        items.append({"gate": "surface height", "suggest_offset_mm": hh["offset_mm"], **hh})
    st = stability(results)
    if st["step_mm_p50_p90_max"] and st["step_mm_p50_p90_max"][1] > 10:
        lines.append(f"stability: trusted poses move {st['step_mm_p50_p90_max'][1]:g} mm (p90) between "
                     "consecutive frames although the panel lies still. That is not a gate problem: suspect the "
                     "capture time against the head pose (vision.json head_time_offset_ms), the lens pose "
                     "convention, or the surface height (the error then changes with the viewing angle).")
    if not items and ref_kind is not None and not any("WARNING" in s for s in lines):
        lines.append("No gate is rejecting frames that agree with the reference: no threshold change suggested.")
    return lines, items


def summarise(results, frames, info, a, offset_mm, ref_kind):
    n = len(results)
    ran = [r for r in results if r["ran"]]
    cand = [r for r in results if "x" in r]
    tr = [r for r in results if r["trusted"]]
    reasons = {}
    for r in results:
        reasons[r["reason"]] = reasons.get(r["reason"], 0) + 1
    ts = [r["t"] for r in results if r["t"] is not None]

    def dist(rs):
        return {"n": len(rs),
                "size_err_long_mm_p10_p50_p90": pcts([r["size_err_mm"][0] for r in rs]),
                "size_err_short_mm_p10_p50_p90": pcts([r["size_err_mm"][1] for r in rs]),
                "rectness_p10_p50_p90": pcts([r["rectness"] for r in rs]),
                "contrast_p10_p50_p90": pcts([r["contrast"] for r in rs])}

    sug_lines, sug_items = suggestions(results, ref_kind)
    s = {
        "folder": info["folder"], "frames": n, "duration_s": round(max(ts) - min(ts), 1) if ts else None,
        "with_camera_pose": sum(1 for f in frames if f["T_wc"] is not None),
        "with_surface_height": sum(1 for f in frames if f["T_wt"] is not None),
        "with_ghost_pose": sum(1 for f in frames if f["ghost"] is not None),
        "image_size": list(frames[0]["size"]) if frames else None,
        "intrinsics_fx_fy_cx_cy": [round(float(v), 2) for v in (frames[0]["K"][0, 0], frames[0]["K"][1, 1],
                                                              frames[0]["K"][0, 2], frames[0]["K"][1, 2])] if frames else None,
        "distortion": frames[0]["dist"] if frames else None, "undistorted": bool(a.undistort),
        "steps_seen": sorted({f["step"] for f in frames if f["step"] is not None}),
        "surface_y_p10_p50_p90": pcts([f["surface_y"] for f in frames]),
        "surface_offset_mm": offset_mm,
        "camera_height_m_p10_p50_p90": pcts([r.get("cam_height_m") for r in results]),
        "look_down_deg_p10_p50_p90": pcts([r.get("look_down_deg") for r in results]),
        "detector_ran": len(ran), "candidates": len(cand), "trusted": len(tr),
        "trusted_pct_of_all": round(100 * len(tr) / max(1, n), 1),
        "trusted_pct_of_ran": round(100 * len(tr) / max(1, len(ran)), 1),
        "reasons": dict(sorted(reasons.items(), key=lambda kv: -kv[1])),
        "gates": {"TOL_LONG_mm": panel_mod.TOL_LONG * 1000, "TOL_SHORT_mm": panel_mod.TOL_SHORT * 1000,
                  "MIN_RECT": panel_mod.MIN_RECT, "MIN_CONTRAST": panel_mod.MIN_CONTRAST},
        "candidates_all": dist(cand), "candidates_trusted": dist(tr),
        "candidates_untrusted": dist([r for r in cand if not r["trusted"]]),
        "stability_trusted": stability(results),
        "ghost_vs_detection_trusted": {
            "n": sum(1 for r in tr if "ghost_mm" in r),
            "mm_p10_p50_p90": pcts([r.get("ghost_mm") for r in tr]),
            "yaw_deg_p10_p50_p90": pcts([r.get("ghost_yaw_deg") for r in tr]),
            "ghost_above_surface_mm_p50": pcts([r.get("ghost_up_mm") for r in tr], (50,))},
        "app_tracker": {
            "states": _count(f["track"].get("state") for f in frames), "methods": _count(f["track"].get("method") for f in frames),
            "anchor_sources": _count(f["anchor_source"] for f in frames),
            "frames_with_app_panel_pose": sum(1 for f in frames if f["app_panel"] is not None)},
        "agreement_reference": ref_kind,
        "trusted_agreeing": sum(1 for r in tr if r.get("agrees")),
        "trusted_disagreeing": sum(1 for r in tr if r.get("agrees") is False),
        "untrusted_agreeing": sum(1 for r in cand if not r["trusted"] and r.get("agrees")),
        "ms_per_frame_p50_p90": pcts([r.get("ms") for r in results], (50, 90)),
        "suggestions": sug_items, "suggestions_text": sug_lines, "warnings": info["warnings"][:20],
    }
    gt = [r for r in results if "t_mm" in r]
    if gt:
        gtt = [r for r in gt if r["trusted"]]
        s["ground_truth"] = {"frames": len(gt), "trusted": len(gtt),
                             "trusted_pos_mm_p50_p90_max": pcts([r["t_mm"] for r in gtt], (50, 90, 100)),
                             "trusted_yaw_deg_p50_p90_max": pcts([r.get("yaw_deg") for r in gtt], (50, 90, 100))}
    return s


def _count(values):
    out = {}
    for v in values:
        if v is not None:
            out[str(v)] = out.get(str(v), 0) + 1
    return out


def summary_text(s, sweeps=None):
    L = []
    add = L.append
    add(f"Recording  {s['folder']}")
    add(f"  {s['frames']} frames over {s['duration_s']} s, {s['image_size']} px, fx fy cx cy {s['intrinsics_fx_fy_cx_cy']}, "
        f"distortion {s['distortion']}{' (applied)' if s['undistorted'] else ''}")
    add(f"  with camera pose {s['with_camera_pose']}, with surface height {s['with_surface_height']}, "
        f"with ghost pose {s['with_ghost_pose']}; steps seen {s['steps_seen']}")
    add(f"  surface_y p10/p50/p90 {fmt(s['surface_y_p10_p50_p90'], 'm')}  (offset applied {s['surface_offset_mm']:+g} mm); "
        f"camera above it {fmt(s['camera_height_m_p10_p50_p90'], 'm')}, looking down {fmt(s['look_down_deg_p10_p50_p90'], 'deg')}")
    add(f"  app tracker: states {s['app_tracker']['states']}, methods {s['app_tracker']['methods']}, "
        f"anchor {s['app_tracker']['anchor_sources']}, its own panel pose in {s['app_tracker']['frames_with_app_panel_pose']} frames")
    add("")
    add(f"Detector   ran on {s['detector_ran']} frames, a candidate in {s['candidates']}, TRUSTED {s['trusted']} "
        f"= {s['trusted_pct_of_all']} % of all frames ({s['trusted_pct_of_ran']} % of those it ran on); "
        f"{fmt(s['ms_per_frame_p50_p90'], 'ms')} per frame p50/p90")
    add("  reasons:")
    for k, v in s["reasons"].items():
        add(f"    {v:4d}  {k}")
    g = s["gates"]
    add(f"  gates: size within {g['TOL_LONG_mm']:g} / {g['TOL_SHORT_mm']:g} mm (long / short), fill >= {g['MIN_RECT']:g}, "
        f"contrast >= {g['MIN_CONTRAST']:g}, ring in view >= 97 %")
    add("  best candidate per frame, p10 / p50 / p90:")
    add(f"    {'':12s} {'n':>4s}  {'size err long mm':>22s}  {'size err short mm':>22s}  {'fill':>18s}  {'contrast':>18s}")
    for label, key in (("all", "candidates_all"), ("trusted", "candidates_trusted"), ("not trusted", "candidates_untrusted")):
        d = s[key]
        add(f"    {label:12s} {d['n']:4d}  {fmt(d['size_err_long_mm_p10_p50_p90']):>22s}  "
            f"{fmt(d['size_err_short_mm_p10_p50_p90']):>22s}  {fmt(d['rectness_p10_p50_p90']):>18s}  "
            f"{fmt(d['contrast_p10_p50_p90']):>18s}")
    st = s["stability_trusted"]
    add("")
    add(f"Stability  consecutive trusted frames ({st['pairs']} pairs): moved p50/p90/max "
        f"{fmt(st['step_mm_p50_p90_max'], 'mm')}, turned {fmt(st['step_deg_p50_p90_max'], 'deg')}; "
        f"distance from the median position {fmt(st['from_median_mm_p50_p90_max'], 'mm')}")
    add(f"  agreement ({s['agreement_reference'] or 'no reference'}): trusted agreeing {s['trusted_agreeing']}, "
        f"trusted NOT agreeing {s['trusted_disagreeing']}, rejected but agreeing {s['untrusted_agreeing']}")
    gv = s["ghost_vs_detection_trusted"]
    add(f"Ghost      against the detection in {gv['n']} trusted frames: p10/p50/p90 {fmt(gv['mm_p10_p50_p90'], 'mm')}, "
        f"{fmt(gv['yaw_deg_p10_p50_p90'], 'deg')}; ghost panel {fmt(gv['ghost_above_surface_mm_p50'], 'mm')} above the surface (p50)")
    if "ground_truth" in s:
        t = s["ground_truth"]
        add(f"Truth      {t['frames']} frames with ground truth, {t['trusted']} trusted: position error p50/p90/max "
            f"{fmt(t['trusted_pos_mm_p50_p90_max'], 'mm')}, yaw {fmt(t['trusted_yaw_deg_p50_p90_max'], 'deg')}")
    add("")
    add("What to change")
    for line in s["suggestions_text"]:
        add("  " + line)
    for w in s["warnings"]:
        add("  note: " + w)
    for title, head, rows in sweeps or []:
        add("")
        add(title)
        add("  " + head)
        for row in rows:
            add("  " + row)
    return "\n".join(L) + "\n"


# ---------------------------------------------------------------- pictures

def _poly(img, uv, z, colour, thick=2, closed=True):
    if (z < 0.05).any() or not np.isfinite(uv).all() or np.abs(uv).max() > 2e4:
        return False
    cv2.polylines(img, [np.round(uv).astype(np.int32)], closed, colour, thick, cv2.LINE_AA)
    return True


def _text_block(img, lines, org=(10, 10), scale=0.62):
    h = int(26 * scale / 0.62)
    w = max(cv2.getTextSize(t, cv2.FONT_HERSHEY_SIMPLEX, scale, 1)[0][0] for t, _ in lines) + 16
    x, y = org
    box = img[y:y + h * len(lines) + 10, x:x + w]
    box[:] = (box * 0.35).astype(np.uint8)
    for i, (t, c) in enumerate(lines):
        cv2.putText(img, t, (x + 8, y + h * (i + 1)), cv2.FONT_HERSHEY_SIMPLEX, scale, c, 1 if scale < 0.7 else 2, cv2.LINE_AA)


def draw_grid(img, K, T_wc, T_wt, step=0.1, half=0.6):
    """Lines every 10 cm ON the table plane, fixed in the world, round where the camera looks."""
    T_tc = np.linalg.inv(T_wt) @ T_wc
    cam, d = T_tc[:3, 3], T_tc[:3, 2]
    if cam[2] < 0.05 or d[2] > -0.05:
        return
    c = cam + min(-cam[2] / d[2], 1.5) * d
    x0, y0 = math.floor((c[0] - half) / step) * step, math.floor((c[1] - half) / step) * step
    n = int(round(2 * half / step)) + 1
    s = np.linspace(0, 2 * half, 25)
    for k in range(n):
        for pts in (np.stack([np.full_like(s, x0 + k * step), y0 + s, np.zeros_like(s)], 1),
                    np.stack([x0 + s, np.full_like(s, y0 + k * step), np.zeros_like(s)], 1)):
            world = (T_wt[:3, :3] @ pts.T).T + T_wt[:3, 3]
            uv, z = project(T_wc, K, world)
            major = abs(round((pts[0, 0] if pts[0, 0] == pts[-1, 0] else pts[0, 1]) / 0.5) * 0.5
                        - (pts[0, 0] if pts[0, 0] == pts[-1, 0] else pts[0, 1])) < 1e-6
            for i in range(len(s) - 1):
                if z[i] > 0.05 and z[i + 1] > 0.05 and np.abs(uv[i:i + 2]).max() < 2e4:
                    cv2.line(img, tuple(np.round(uv[i]).astype(int).tolist()), tuple(np.round(uv[i + 1]).astype(int).tolist()),
                             (200, 200, 200) if major else GREY, 1, cv2.LINE_AA)


def draw_overlay(fr, img, K, res, T_wt, grid=True):
    out = img.copy()
    scale = max(0.45, 0.62 * out.shape[1] / 1280)
    if grid and fr["T_wc"] is not None and T_wt is not None:
        draw_grid(out, K, fr["T_wc"], T_wt)
    if fr["T_wc"] is not None:
        for T, colour, label in ((ghost_panel(fr), AZURE, "ghost"),
                                 (fr["app_panel"] @ A_FROM_P if fr["app_panel"] is not None else None, WHITE, "app")):
            if T is None:
                continue
            uv, z = project(fr["T_wc"], K, panel_corners_world(T))
            if _poly(out, uv, z, colour, 2):
                cv2.putText(out, label, tuple(np.round(uv[3] + [4, -6]).astype(int).tolist()), cv2.FONT_HERSHEY_SIMPLEX,
                            scale * 0.9, colour, 1, cv2.LINE_AA)
    colour = GREEN if res["trusted"] else ORANGE
    if "corners_px" in res:
        c = np.array(res["corners_px"], float)
        if np.isfinite(c).all() and np.abs(c).max() < 2e4:
            cv2.polylines(out, [np.round(c).astype(np.int32)], True, colour, 3, cv2.LINE_AA)
            for p in c:
                cv2.circle(out, tuple(np.round(p).astype(int).tolist()), 5, colour, -1, cv2.LINE_AA)
            mid, tip = c.mean(0), (c[1] + c[2]) / 2                  # centre -> the +x end of the long side
            cv2.arrowedLine(out, tuple(np.round(mid).astype(int).tolist()), tuple(np.round(tip).astype(int).tolist()),
                            colour, 2, cv2.LINE_AA, tipLength=0.08)
    tr = fr["track"]
    lines = [(f"{fr['file']}  t={fr['t'] if fr['t'] is not None else 0:.1f}s  step {fr['step']}.{fr['action']}  "
              f"app: {tr.get('state', '-')}/{tr.get('method', '-')}  anchor: {fr['anchor_source'] or '-'}", WHITE),
             ("TRUSTED" if res["trusted"] else f"NOT TRUSTED: {res['reason']}", colour)]
    if "size_err_mm" in res:
        e = res["size_err_mm"]
        lines.append((f"size err long {e[0]:+.1f} short {e[1]:+.1f} mm   fill {res['rectness']:.2f}   "
                      f"contrast {res['contrast']:.0f}", WHITE))
    if "ghost_mm" in res:
        lines.append((f"ghost vs detection: {res['ghost_mm']:.0f} mm, {res['ghost_yaw_deg']:.1f} deg "
                      f"(ghost {res['ghost_up_mm']:+.0f} mm above the surface)", AZURE))
    if "cam_height_m" in res:
        lines.append((f"surface_y {res['surface_y']:.3f} m   camera {res['cam_height_m']:.2f} m above it, "
                      f"looking {res['look_down_deg']:.0f} deg down", WHITE))
    if "t_mm" in res:
        lines.append((f"truth: {res['t_mm']:.1f} mm, {res.get('yaw_deg', float('nan')):.2f} deg", WHITE))
    _text_block(out, lines, scale=scale)
    legend = [("candidate, trusted", GREEN), ("candidate, not trusted", ORANGE), ("ghost (recorded pose)", AZURE),
              ("app's own panel result", WHITE), ("10 cm grid on the table plane", GREY)]
    lh = int(22 * scale / 0.62)
    _text_block(out, legend, org=(10, out.shape[0] - lh * len(legend) - 24), scale=scale * 0.85)
    return out


def contact_sheets(thumbs, out_dir: Path, cols=6, per_page=48, cell=(320, 240)):
    """thumbs: [(image, status, label)], status in trusted / untrusted / none."""
    paths = []
    border = {"trusted": GREEN, "untrusted": ORANGE, "none": (90, 90, 90)}
    for page, k in enumerate(range(0, len(thumbs), per_page)):
        part = thumbs[k:k + per_page]
        rows = math.ceil(len(part) / cols)
        sheet = np.full((rows * (cell[1] + 8) + 8, cols * (cell[0] + 8) + 8, 3), 24, np.uint8)
        for i, (im, status, label) in enumerate(part):
            y, x = 8 + (i // cols) * (cell[1] + 8), 8 + (i % cols) * (cell[0] + 8)
            sheet[y - 4:y + cell[1] + 4, x - 4:x + cell[0] + 4] = border[status]
            sheet[y:y + cell[1], x:x + cell[0]] = im
            cv2.putText(sheet, label, (x + 6, y + cell[1] - 8), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 3, cv2.LINE_AA)
            cv2.putText(sheet, label, (x + 6, y + cell[1] - 8), cv2.FONT_HERSHEY_SIMPLEX, 0.5, WHITE, 1, cv2.LINE_AA)
        p = out_dir / f"contact_{page + 1:02d}.jpg"
        cv2.imwrite(str(p), sheet, [cv2.IMWRITE_JPEG_QUALITY, 88])
        paths.append(p)
    return paths


# ---------------------------------------------------------------- sweeps

def parse_offsets(text):
    """'5' -> [5.0]; '-40:40:10' -> [-40, -30, ..., 40]; '-20,0,20' -> those."""
    if text is None:
        return [0.0]
    if ":" in text:
        a, b, st = (float(v) for v in text.split(":"))
        n = int(round((b - a) / st))
        return [round(a + i * st, 3) for i in range(n + 1)]
    return [float(v) for v in text.split(",") if v.strip()]


def _row_numbers(res):
    cand = [r for r in res if "x" in r]
    tr = [r for r in res if r["trusted"]]
    st = stability(res)
    el = pcts([abs(r["size_err_mm"][0]) for r in cand], (50,))
    es = pcts([abs(r["size_err_mm"][1]) for r in cand], (50,))
    return {"trusted": len(tr), "pct": 100 * len(tr) / max(1, len(res)),
            "abs_err_long_p50": el[0] if el else None, "abs_err_short_p50": es[0] if es else None,
            "step_p90": st["step_mm_p50_p90_max"][1] if st["step_mm_p50_p90_max"] else None}


def height_sweep(folder, frames, offsets, a):
    rows, best = [], None
    for off in offsets:
        res = run(folder, frames, off / 1000.0, a.prev_yaw, a.undistort)
        m = _row_numbers(res)
        key = (m["trusted"], -(m["abs_err_long_p50"] if m["abs_err_long_p50"] is not None else 1e9))
        if best is None or key > best[0]:
            best = (key, off)
        rows.append((off, m))
    f = lambda v: "-" if v is None else f"{v:.1f}"  # noqa: E731
    text = [f"{off:+9.0f}  {m['trusted']:7d}  {m['pct']:6.1f}  {f(m['abs_err_long_p50']):>14s}  "
            f"{f(m['abs_err_short_p50']):>15s}  {f(m['step_p90']):>12s}" + ("   <- best" if off == best[1] else "")
            for off, m in rows]
    head = f"{'offset mm':>9s}  {'trusted':>7s}  {'%':>6s}  {'|err long| p50':>14s}  {'|err short| p50':>15s}  {'step p90 mm':>12s}"
    return best[1], ("Surface height sweep (table plane moved up + / down -)", head, text), \
        [{"offset_mm": off, **m} for off, m in rows]


def gate_sweep(folder, frames, base, offset_m, a):
    """Each gate relaxed in turn, the detector re-run. `base` must already carry `agrees`."""
    tl, ts, mr, mc = panel_mod.TOL_LONG, panel_mod.TOL_SHORT, panel_mod.MIN_RECT, panel_mod.MIN_CONTRAST
    settings = [("as shipped", {})]
    settings += [(f"size tolerance x{k:g} ({tl * k * 1000:g} / {ts * k * 1000:g} mm)", {"TOL_LONG": tl * k, "TOL_SHORT": ts * k})
                 for k in (1.5, 2.0, 3.0)]
    settings += [(f"MIN_RECT {v:g}", {"MIN_RECT": v}) for v in (0.82, 0.78, 0.72) if v < mr]
    settings += [(f"MIN_CONTRAST {v:g}", {"MIN_CONTRAST": v}) for v in (14.0, 10.0, 6.0) if v < mc]
    base_tr = {r["n"] for r in base if r["trusted"]}
    ref = [r for r in base if r["trusted"]]
    rows, data = [], []
    for label, kw in settings:
        with constants(**kw):
            res = base if not kw else run(folder, frames, offset_m, a.prev_yaw, a.undistort)
        m = _row_numbers(res)
        gained = [r for r in res if r["trusted"] and r["n"] not in base_tr]
        lost = len(base_tr - {r["n"] for r in res if r["trusted"]})
        ga = gd = 0
        if len(ref) >= 3:
            rx, ry = np.median([q["x"] for q in ref]), np.median([q["y"] for q in ref])
            ryaw = mean_mod_pi([q["yaw"] for q in ref])
            for r in gained:
                near = [q for q in ref if r["t"] is not None and q["t"] is not None and abs(q["t"] - r["t"]) <= NEAR_S]
                x, y, yw = (np.median([q["x"] for q in near]), np.median([q["y"] for q in near]),
                            mean_mod_pi([q["yaw"] for q in near])) if len(near) >= 2 else (rx, ry, ryaw)
                ok = np.hypot(r["x"] - x, r["y"] - y) * 1000 <= AGREE_MM and np.degrees(ang_mod_pi(r["yaw"], yw)) <= AGREE_DEG
                ga += bool(ok)
                gd += not ok
        sp = "-" if m["step_p90"] is None else f"{m['step_p90']:.1f}"
        rows.append(f"{label:38s}  {m['trusted']:7d}  {m['pct']:6.1f}  {len(gained):6d}  "
                    f"{(str(ga) if len(ref) >= 3 else '?'):>6s}  {(str(gd) if len(ref) >= 3 else '?'):>9s}  {lost:4d}  {sp:>12s}")
        data.append({"setting": label, **kw, **m, "gained": len(gained), "gained_agree": ga if len(ref) >= 3 else None,
                     "gained_disagree": gd if len(ref) >= 3 else None, "lost": lost})
    head = f"{'setting':38s}  {'trusted':>7s}  {'%':>6s}  {'gained':>6s}  {'agree':>6s}  {'disagree':>9s}  {'lost':>4s}  {'step p90 mm':>12s}"
    return ("Gate sweep (one gate relaxed at a time, detector re-run; agree = within "
            f"{AGREE_MM:g} mm / {AGREE_DEG:g} deg of the trusted poses as shipped)", head, rows), data


# ---------------------------------------------------------------- main

def evaluate(folder, out_dir=None, surface_offset_mm=None, sweep=False, surface_y=None, prev_yaw="own",
             undistort=False, pose="matrix", limit=None, overlays=True, grid=True, quiet=False):
    """The whole tool as a function (the tests call it). Returns (summary, results)."""
    folder = Path(folder)
    a = argparse.Namespace(prev_yaw=prev_yaw, undistort=undistort)
    frames, info = load_recording(folder, surface_y, pose)
    if limit:
        frames = frames[:limit]
    if not frames:
        raise SystemExit(f"{folder}: no frames in {info['rows_file']}")
    out_dir = Path(out_dir) if out_dir else folder.parent / "eval"
    out_dir.mkdir(parents=True, exist_ok=True)

    offsets = parse_offsets(surface_offset_mm)
    if sweep and len(offsets) == 1:
        offsets = sorted(set(parse_offsets("-40:40:10")) | {offsets[0]})
    sweeps, sweep_data, offset_mm = [], {}, offsets[0]
    if len(offsets) > 1:
        offset_mm, table, sweep_data["surface_height"] = height_sweep(folder, frames, offsets, a)
        sweeps.append(table)

    thumbs = []

    def keep(fr, img, K, res, T_wt):
        if not overlays or img is None:
            return
        ov = draw_overlay(fr, img, K, res, T_wt, grid)
        (out_dir / "overlay").mkdir(exist_ok=True)
        cv2.imwrite(str(out_dir / "overlay" / (Path(fr["file"]).stem + ".jpg")), ov, [cv2.IMWRITE_JPEG_QUALITY, 85])
        status = "trusted" if res["trusted"] else ("untrusted" if "x" in res else "none")
        thumbs.append((cv2.resize(ov, (320, 240), interpolation=cv2.INTER_AREA), status, Path(fr["file"]).stem))

    results = run(folder, frames, offset_mm / 1000.0, prev_yaw, undistort, keep)
    ref_kind = mark_agreement(results)
    if sweep:
        table, sweep_data["gates"] = gate_sweep(folder, frames, results, offset_mm / 1000.0, a)
        sweeps.append(table)
    s = summarise(results, frames, info, a, offset_mm, ref_kind)
    s["sweeps"] = sweep_data
    s["contact_sheets"] = [str(p) for p in contact_sheets(thumbs, out_dir)] if thumbs else []
    s["out"] = str(out_dir)
    text = summary_text(s, sweeps)
    (out_dir / "summary.txt").write_text(text)
    (out_dir / "summary.json").write_text(json.dumps(s, indent=1))
    with open(out_dir / "results.jsonl", "w") as f:
        for r in results:
            f.write(json.dumps(r) + "\n")
    if not quiet:
        print(text)
        print(f"overlays: {out_dir / 'overlay'}/   contact sheets: {', '.join(Path(p).name for p in s['contact_sheets']) or '-'}"
              f"   summary: {out_dir / 'summary.txt'}")
    return s, results


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", type=Path, help="pulled dump session (JPEGs + poses.jsonl + intrinsics.json)")
    ap.add_argument("--out", type=Path, help="output folder (default: eval/ next to the session folder)")
    ap.add_argument("--surface-offset-mm", help="move the table plane: one value, a,b,c or A:B:STEP (mm, + = up)")
    ap.add_argument("--sweep", action="store_true", help="height sweep, then each gate relaxed in turn")
    ap.add_argument("--surface-y", type=float, help="use this WORLD height (m) instead of the recorded surface_y")
    ap.add_argument("--prev-yaw", choices=["own", "ghost", "none"], default="own",
                    help="what resolves the 180 deg ambiguity: the last trusted yaw (default), the ghost's, nothing")
    ap.add_argument("--undistort", action="store_true", help="apply the recorded distortion coefficients first")
    ap.add_argument("--pose", choices=["matrix", "quat"], default="matrix",
                    help="take the camera pose from T_world_cam (default) or from cam_p/cam_q")
    ap.add_argument("--limit", type=int, help="only the first N frames")
    ap.add_argument("--no-overlays", action="store_true")
    ap.add_argument("--no-grid", action="store_true")
    a = ap.parse_args()
    if not a.folder.is_dir():
        raise SystemExit(f"{a.folder} is not a folder")
    evaluate(a.folder, a.out, a.surface_offset_mm, a.sweep, a.surface_y, a.prev_yaw, a.undistort, a.pose, a.limit,
             not a.no_overlays, not a.no_grid)
    return 0


if __name__ == "__main__":
    sys.exit(main())
