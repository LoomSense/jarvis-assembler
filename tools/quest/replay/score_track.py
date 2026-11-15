#!/usr/bin/env python3
"""Score an app's `track` events against a replay dataset's ground truth (poses.jsonl).

  score_track.py <run_dir | agq.jsonl> [--dataset marker-synthetic] [--frame auto|world|camera] [--unity]
                 [--q-order auto|xyzw|wxyz] [--out DIR]
  score_track.py --make-fake fake.jsonl [--dataset ...] [--frame world|camera] [--noise-mm 0] [--no-frame-field]
                 [--fake-q none|xyzw|wxyz] [--unity]

The tracked object is the STRIP CENTRE (tools/quest/marker/marker.json), whichever tag the app detects.
What the app reports in replay mode (any of these works):
  world  : pos_m (+ q) = strip centre in the app's world, using poses.jsonl as the camera pose.
           The dataset's strip centre sits at the world origin, so the position truth is (0, 0, 0) and yaw 0
           in any Y-up convention. Scored: bias (distance of the mean from the origin), error per event,
           jitter in the still phase, and the rotation against the strip's world orientation when `q` is given.
  camera : pos_m (+ q) = strip centre in the camera frame (camera-from-object, what PnP returns after the tag
           offset). Scored per frame against marker_p, trying OpenCV (x right, y down, z fwd), Unity (y up,
           z fwd) and OpenXR (y up, z back) camera axes and keeping the best.
Track event fields read: `pos_m` (alias `pos`) metres; `q` (aliases `rot`, `quat_xyzw`, `quat_wxyz`) unit
quaternion, canonical order [x, y, z, w]; for `q`/`rot` the order can also be [w, x, y, z]: --q-order (or a
`q_order` field on the event) says which, `auto` (default) scores both and keeps the better one.
--unity: positions and quaternions are in Unity's left-handed Y-up convention (as Unity logs them). The scorer
mirrors them back to right-handed (x, y, -z) and q (-x, -y, z, w). Without it they must be right-handed Y-up
(OpenXR / Android / poses.jsonl).
Event-to-frame matching uses the event's integer `frame` field when present (recommended in replay mode),
else time since the first track event at the dataset fps, with the best offset in +-2 s.
Writes score.json and score.md next to the log (or into --out).
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
from agq import first, load, num, vec3  # noqa: E402
from geom import CV_TO_XR, R_WORLD_MARKER, R_from_quat, quat_from_R, rot_angle_deg  # noqa: E402

CAM_CONV = {"opencv": np.array([1.0, 1.0, 1.0]), "unity": np.array([1.0, -1.0, 1.0]),
            "openxr": np.array([1.0, -1.0, -1.0])}
T0 = 1_794_750_000.0
MIRROR_Z = np.diag([1.0, 1.0, -1.0])        # right-handed <-> Unity left-handed (same for points and frames)
Q_FIELDS = (("q", None), ("rot", None), ("quat_xyzw", "xyzw"), ("quat_wxyz", "wxyz"))


def mirror_q(q):
    """Quaternion [x, y, z, w] of R, mirrored through z (R -> M R M): Unity <-> right-handed."""
    x, y, z, w = q
    return [-x, -y, z, w]


def ev_pos(e: dict, unity: bool) -> np.ndarray | None:
    v = vec3(first(e, "pos_m", "pos"))
    if v is None:
        return None
    p = np.asarray(v, dtype=float)
    return MIRROR_Z @ p if unity else p


def ev_quats(e: dict, order: str, unity: bool) -> dict:
    """{order: [x, y, z, w] right-handed} for each order the event's quaternion could be in (1 or 2 entries)."""
    for key, fixed in Q_FIELDS:
        q = e.get(key)
        if isinstance(q, (list, tuple)) and len(q) == 4 and all(num(v) is not None for v in q):
            q = [float(v) for v in q]
            if np.linalg.norm(q) < 1e-6:
                return {}
            ev_order = e.get("q_order") if e.get("q_order") in ("xyzw", "wxyz") else None
            pick = fixed or ev_order or (None if order == "auto" else order)
            out = {}
            for o in ([pick] if pick else ["xyzw", "wxyz"]):
                xyzw = q if o == "xyzw" else [q[1], q[2], q[3], q[0]]
                out[o] = mirror_q(xyzw) if unity else xyzw
            return out
    return {}


def resolve_dataset(name: str) -> Path:
    p = Path(name)
    if not p.is_dir():
        p = HERE / name
    if not (p / "poses.jsonl").exists():
        raise SystemExit(f"no poses.jsonl in {p}: generate it with tools/quest/replay/make_marker_synthetic.py")
    return p


def load_gt(ds: Path) -> tuple[list[dict], dict]:
    gt = [json.loads(ln) for ln in (ds / "poses.jsonl").read_text().splitlines() if ln.strip()]
    meta = json.loads((ds / "dataset.json").read_text()) if (ds / "dataset.json").exists() else {}
    intr = json.loads((ds / "intrinsics.json").read_text())
    meta.setdefault("fps", intr.get("fps", 30.0))
    return gt, meta


def pctl(xs, p):
    return None if len(xs) == 0 else round(float(np.percentile(np.asarray(xs), p)), 3)


def make_fake(out: Path, ds: Path, frame_mode: str, noise_mm: float, frame_field: bool, seed: int = 0,
              fake_q: str = "none", unity: bool = False) -> None:
    """A perfect tracker plus noise, logged the way an app would (world: OpenXR world; camera: OpenCV camera
    axes; with unity=True: Unity world / Unity camera axes, left-handed)."""
    gt, meta = load_gt(ds)
    rng = np.random.default_rng(seed)
    fps = float(meta["fps"])
    lines = [{"t": T0, "app": "fake", "ev": "start", "replay": True}]
    for g in gt:
        if frame_mode == "world":
            p, R = np.zeros(3), R_WORLD_MARKER
        elif unity:                                  # Unity camera axes = OpenXR camera axes mirrored through z
            p, R = CV_TO_XR @ np.asarray(g["marker_p"]), CV_TO_XR @ R_from_quat(g["marker_q"])
        else:
            p, R = np.asarray(g["marker_p"]), R_from_quat(g["marker_q"])
        p = p + rng.normal(0, noise_mm / 1000, 3)
        q = quat_from_R(R)
        if unity:
            p, q = MIRROR_Z @ p, mirror_q(q)
        ev = {"t": round(T0 + 0.5 + g["frame"] / fps, 4), "app": "fake", "ev": "track", "fps": 72.0, "tracked": True,
              "pos_m": [round(float(v), 6) for v in p], "yaw_deg": round(float(rng.normal(0, noise_mm / 10)), 3),
              "reproj_px": 0.0, "source": "marker"}
        if fake_q != "none":
            ev["q"] = [round(float(v), 7) for v in (q if fake_q == "xyzw" else [q[3], q[0], q[1], q[2]])]
        if frame_field:
            ev["frame"] = g["frame"]
        lines.append(ev)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(json.dumps(e, separators=(",", ":")) + "\n" for e in lines))
    print(f"wrote {out}: {len(lines) - 1} {frame_mode}-frame track events, noise {noise_mm} mm, frame field "
          f"{frame_field}, q {fake_q}, {'unity' if unity else 'right-handed'}")


def match_frames(track: list[dict], gt: list[dict], fps: float, gt_cam: np.ndarray | None) -> tuple[list[int], float | None]:
    """Frame index per event. Uses `frame` when every event has it; else time with the best offset."""
    n = len(gt)
    if all(isinstance(e.get("frame"), int) for e in track):
        return [e["frame"] for e in track], None
    t_first = track[0]["_t"]
    rel = np.array([e["_t"] - t_first for e in track])
    if gt_cam is None:                       # world mode: truth is constant, alignment does not matter
        return [int(round(r * fps)) for r in rel], 0.0
    pos = np.array([e["_P"] for e in track], dtype=float)
    best = (np.inf, 0.0)
    for off in np.arange(-2.0, 2.0 + 1e-9, 1.0 / fps):
        idx = np.round((rel + off) * fps).astype(int)
        ok = (idx >= 0) & (idx < n) & ~np.isnan(pos[:, 0])
        if ok.sum() < max(5, 0.3 * len(track)):
            continue
        err = np.median(np.linalg.norm(pos[ok] - gt_cam[idx[ok]], axis=1))
        if err < best[0]:
            best = (err, off)
    off = best[1]
    return [int(round((r + off) * fps)) for r in rel], float(off)


def score(log: Path, ds: Path, frame_mode: str, unity: bool = False, q_order: str = "auto") -> dict:
    events, bad = load(log)
    gt, meta = load_gt(ds)
    fps = float(meta["fps"])
    n = len(gt)
    still = meta.get("phases", {}).get("still", [int(n * 0.87), n - 1])
    track = [e for e in events if e["ev"] == "track"]
    if not track:
        return {"error": "no track events", "bad_lines": bad}
    tracked = []
    for e in track:
        p = ev_pos(e, unity) if e.get("tracked") is True else None
        if p is not None:
            e["_P"], e["_Q"] = p, ev_quats(e, q_order, unity)
            tracked.append(e)
    if not tracked:
        return {"error": "no tracked track events with a position", "bad_lines": bad}
    gt_cam = np.array([g["marker_p"] for g in gt])
    gt_R = [R_from_quat(g["marker_q"]) for g in gt]
    results = {}
    modes = ["world", "camera"] if frame_mode == "auto" else [frame_mode]
    for mode in modes:
        convs = ["-"] if mode == "world" else list(CAM_CONV)
        for conv in convs:
            target = None if mode == "world" else gt_cam * CAM_CONV[conv]
            frames, offset = match_frames(tracked, gt, fps, target)
            rows = [(f, e["_P"], num(e.get("yaw_deg")), e) for f, e in zip(frames, tracked) if 0 <= f < n]
            if not rows:
                continue
            P = np.array([r[1] for r in rows])
            F = np.array([r[0] for r in rows])
            truth = np.zeros_like(P) if mode == "world" else target[F]
            err = np.linalg.norm(P - truth, axis=1) * 1000
            in_still = (F >= still[0]) & (F <= still[1])
            Ps = P[in_still]
            still_jit = float(np.sqrt(np.mean(np.sum((Ps - Ps.mean(0)) ** 2, axis=1))) * 1000) if len(Ps) > 1 else None
            if mode == "camera" and len(Ps) > 1:      # truth is constant in the still phase too
                still_jit = float(np.sqrt(np.mean(np.sum(((Ps - truth[in_still]) - (Ps - truth[in_still]).mean(0)) ** 2,
                                                         axis=1))) * 1000)
            yaws = np.array([r[2] for r in rows if r[2] is not None])
            # Rotation truth: the strip's world orientation, or its rotation in these camera axes. Unity camera
            # axes are a reflection of OpenCV's: no rotation to compare (use --unity, which lands on openxr).
            rot, used_order = [], None
            if mode == "world" or conv in ("opencv", "openxr"):
                by_order = {}
                for f, _, _, e in rows:
                    Rt = R_WORLD_MARKER if mode == "world" else (gt_R[f] if conv == "opencv" else CV_TO_XR @ gt_R[f])
                    for o, q in e["_Q"].items():
                        by_order.setdefault(o, []).append(rot_angle_deg(R_from_quat(q), Rt))
                if by_order:
                    used_order = min(by_order, key=lambda o: (-len(by_order[o]), np.median(by_order[o])))
                    rot = by_order[used_order]
            res = {
                "mode": mode, "camera_axes": None if mode == "world" else conv,
                "time_offset_s": None if offset is None else round(offset, 3),
                "matched_events": len(rows),
                "coverage": round(len(set(F.tolist())) / n, 3),
                "pos_err_mm_p50": pctl(err, 50), "pos_err_mm_p90": pctl(err, 90),
                "pos_err_mm_max": round(float(err.max()), 3),
                "bias_mm": round(float(np.linalg.norm((P - truth).mean(0)) * 1000), 3),
                "still_jitter_mm": None if still_jit is None else round(still_jit, 3),
                "still_events": int(in_still.sum()),
                "yaw_err_deg_p50": pctl(np.abs((yaws + 180) % 360 - 180), 50) if mode == "world" and len(yaws) else None,
                "rot_err_deg_p50": pctl(rot, 50) if rot else None,
                "rot_err_deg_p90": pctl(rot, 90) if rot else None,
                "rot_events": len(rot), "q_order": used_order, "unity_input": unity,
            }
            key = mode if mode == "world" else f"camera/{conv}"
            results[key] = res
    if not results:
        return {"error": "no track events could be matched to dataset frames", "bad_lines": bad}
    best_key = min(results, key=lambda k: results[k]["pos_err_mm_p50"])
    return {"log": str(log), "dataset": str(ds), "frames": n, "track_events": len(track),
            "tracked_events": len(tracked), "bad_lines": bad, "best": best_key, **results[best_key],
            "all": results}


def to_md(s: dict) -> str:
    if "error" in s:
        return f"## Track score\n\n{s['error']}\n"
    rows = [("Dataset", f"{Path(s['dataset']).name} ({s['frames']} frames)"),
            ("Track events / tracked / matched", f"{s['track_events']} / {s['tracked_events']} / {s['matched_events']}"),
            ("Interpretation", f"{s['mode']}" + (f" frame, {s['camera_axes']} camera axes" if s['camera_axes'] else " frame")),
            ("Frame coverage", s["coverage"]),
            ("Position error p50 / p90 / max", f"{s['pos_err_mm_p50']} / {s['pos_err_mm_p90']} / {s['pos_err_mm_max']} mm"),
            ("Bias (mean error vector)", f"{s['bias_mm']} mm"),
            ("Still-phase jitter (RMS)", f"{s['still_jitter_mm']} mm over {s['still_events']} events"),
            ("Yaw error p50", s["yaw_err_deg_p50"]),
            ("Rotation error p50 / p90", None if s["rot_err_deg_p50"] is None else
             f"{s['rot_err_deg_p50']} / {s['rot_err_deg_p90']} deg over {s['rot_events']} events (q {s['q_order']})"),
            ("Input handedness", "Unity left-handed, mirrored back (--unity)" if s["unity_input"] else "right-handed"),
            ("Time offset (no frame field)", s["time_offset_s"])]
    md = ["## Track score", "", "| Metric | Value |", "|---|---|"] + [f"| {k} | {'–' if v is None else v} |" for k, v in rows]
    if len(s["all"]) > 1:
        md += ["", "Other interpretations (p50 mm): " + ", ".join(f"{k} {v['pos_err_mm_p50']}" for k, v in s["all"].items())]
    return "\n".join(md) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("target", nargs="?", type=Path)
    ap.add_argument("--dataset", default="marker-synthetic")
    ap.add_argument("--frame", choices=["auto", "world", "camera"], default="auto")
    ap.add_argument("--out", type=Path)
    ap.add_argument("--make-fake", type=Path, metavar="OUT.jsonl")
    ap.add_argument("--noise-mm", type=float, default=0.0)
    ap.add_argument("--no-frame-field", action="store_true")
    ap.add_argument("--unity", action="store_true",
                    help="track events are in Unity's left-handed Y-up convention (flip z, mirror q)")
    ap.add_argument("--q-order", choices=["auto", "xyzw", "wxyz"], default="auto",
                    help="order of `q`/`rot` in track events (canonical xyzw); auto keeps the better fit")
    ap.add_argument("--fake-q", choices=["none", "xyzw", "wxyz"], default="xyzw", help="--make-fake: write q")
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args()
    ds = resolve_dataset(a.dataset)
    if a.make_fake:
        make_fake(a.make_fake, ds, "camera" if a.frame == "camera" else "world", a.noise_mm, not a.no_frame_field,
                  fake_q=a.fake_q, unity=a.unity)
        return 0
    if not a.target:
        ap.error("give a run folder or agq .jsonl (or --make-fake)")
    log = a.target / "agq.jsonl" if a.target.is_dir() else a.target
    out = a.out or (a.target if a.target.is_dir() else a.target.parent)
    s = score(log, ds, a.frame, unity=a.unity, q_order=a.q_order)
    out.mkdir(parents=True, exist_ok=True)
    stem = "score" if a.target.is_dir() else f"{log.stem}.score"
    (out / f"{stem}.json").write_text(json.dumps(s, indent=2))
    md = to_md(s)
    (out / f"{stem}.md").write_text(md)
    if not a.quiet:
        print(md)
    print(f"wrote {out / (stem + '.json')}")
    return 1 if "error" in s else 0


if __name__ == "__main__":
    sys.exit(main())
