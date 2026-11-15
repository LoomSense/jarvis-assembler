#!/usr/bin/env python3
"""Baseline marker tracker on a replay dataset (AprilTag + IPPE PnP) that writes an AGQ log.

  .venv/bin/python tools/quest/replay/reference_tracker.py [--dataset marker-synthetic] [--frame world|camera]
                                                            [--tag 36h11|41h12]

Default: the 36h11 tag with OpenCV aruco (what the Android app does). --tag 41h12 uses pupil-apriltags (the
apriltag C library that jp.keijiro.apriltag wraps; `.venv/bin/pip install pupil-apriltags`): the Unity app's bar.
Either way the reported pose is the STRIP CENTRE: tag pose * Translation(-offset_m) from the dataset's tags list
(= tools/quest/marker/marker.json), so both tags must give the same answer.

Output: tools/quest/runs/reference-<dataset>[-41h12]/agq.jsonl (a normal run folder: summarize.py and
score_track.py work on it). It is the bar the apps' trackers should meet on the same frames, and a check that
the dataset itself is consistent (detections + PnP agree with poses.jsonl).
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from geom import R_from_quat, R_WORLD_MARKER, quat_from_R, yaw_deg_world  # noqa: E402

T0 = 1_794_750_000.0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dataset", default="marker-synthetic")
    ap.add_argument("--frame", choices=["world", "camera"], default="world")
    ap.add_argument("--tag", choices=["36h11", "41h12"], default="36h11")
    ap.add_argument("--out", type=Path)
    a = ap.parse_args()
    ds = Path(a.dataset) if Path(a.dataset).is_dir() else HERE / a.dataset
    intr = json.loads((ds / "intrinsics.json").read_text())
    meta = json.loads((ds / "dataset.json").read_text())
    poses = [json.loads(ln) for ln in (ds / "poses.jsonl").read_text().splitlines() if ln.strip()]
    K = np.array([[intr["fx"], 0, intr["cx"]], [0, intr["fy"], intr["cy"]], [0, 0, 1]])
    fps = float(intr.get("fps", 30))
    family = {"36h11": "tag36h11", "41h12": "tagStandard41h12"}[a.tag]
    tag = next(t for t in meta["marker"]["tags"] if t["family"] == family)
    L, tid, offset = tag["size_m"], tag["id"], np.asarray(tag["offset_m"], dtype=float)
    # Tag corners in its own (marker) axes: top-left, top-right, bottom-right, bottom-left (OpenCV ArUco order).
    obj = np.array([[-L / 2, L / 2, 0], [L / 2, L / 2, 0], [L / 2, -L / 2, 0], [-L / 2, -L / 2, 0]], np.float32)
    if a.tag == "36h11":
        params = cv2.aruco.DetectorParameters()
        params.cornerRefinementMethod = cv2.aruco.CORNER_REFINE_SUBPIX
        aruco = cv2.aruco.ArucoDetector(cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_APRILTAG_36h11), params)

        def find(img):
            corners, ids, _ = aruco.detectMarkers(img)
            if ids is None or tid not in ids.flatten():
                return None
            return corners[list(ids.flatten()).index(tid)].reshape(4, 2)
    else:
        from pupil_apriltags import Detector
        april = Detector(families=family, quad_decimate=1.0)

        def find(img):
            for d in april.detect(img):
                if d.tag_id == tid:              # apriltag order: bottom-left, bottom-right, top-right, top-left
                    return np.asarray(d.corners, np.float32)[::-1].copy()
            return None
    out = a.out or HERE.parent / "runs" / (f"reference-{ds.name}" + ("" if a.tag == "36h11" else "-41h12"))
    out.mkdir(parents=True, exist_ok=True)
    lines = [{"t": T0, "app": "reference", "ev": "start", "replay": True, "job": ds.name, "tag": tag["key"],
              "offset_m": tag["offset_m"]}]
    hits, t_proc = 0, []
    for p in poses:
        img = cv2.imread(str(ds / p["file"]), cv2.IMREAD_GRAYSCALE)
        t1 = time.perf_counter()
        c = find(img)
        ev = {"t": round(T0 + 0.5 + p["frame"] / fps, 4), "app": "reference", "ev": "track", "frame": p["frame"],
              "fps": None, "tracked": False, "pos_m": None, "yaw_deg": None, "reproj_px": None, "source": "none"}
        if c is not None:
            ok, rvec, tvec = cv2.solvePnP(obj, c, K, None, flags=cv2.SOLVEPNP_IPPE_SQUARE)
            if ok:
                proj, _ = cv2.projectPoints(obj, rvec, tvec, K, None)
                reproj = float(np.sqrt(np.mean(np.sum((proj.reshape(4, 2) - c) ** 2, axis=1))))
                R_cm = cv2.Rodrigues(rvec)[0]                  # tag axes = strip axes (offset_q is identity)
                t_cm = tvec.reshape(3) - R_cm @ offset         # tag centre -> strip centre
                if a.frame == "world":
                    R_wc = R_from_quat(p["cam_q"])
                    pos = R_wc @ t_cm + np.asarray(p["cam_p"])
                    R_wm = R_wc @ R_cm
                    q, yaw = quat_from_R(R_wm), yaw_deg_world(R_wm) - yaw_deg_world(R_WORLD_MARKER)
                else:
                    pos, q, yaw = t_cm, quat_from_R(R_cm), None
                ev.update(tracked=True, pos_m=[round(float(v), 6) for v in pos], q=[round(v, 6) for v in q],
                          yaw_deg=None if yaw is None else round(float(yaw), 3), reproj_px=round(reproj, 3), source="marker")
                hits += 1
        t_proc.append(time.perf_counter() - t1)
        ev["fps"] = round(1.0 / max(t_proc[-1], 1e-4), 1)          # tracker throughput on this Mac, not render fps
        lines.append(ev)
    (out / "agq.jsonl").write_text("".join(json.dumps(e, separators=(",", ":")) + "\n" for e in lines))
    (out / "meta.json").write_text(json.dumps({"app": "reference", "job": ds.name, "replay": True,
                                               "note": f"reference tracker, {family} id {tid}, strip centre, {a.frame} frame"}, indent=2))
    print(f"detected {hits}/{len(poses)} frames, {1000 * np.median(t_proc):.1f} ms/frame -> {out / 'agq.jsonl'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
