#!/usr/bin/env python3
"""Write the rendered sample frames as a recording in exactly the app recorder's format (no headset needed).

  python make_fake_recording.py [OUT_DIR] [--sample tools/markerless/sample] [--surface-y 0.74]
                                [--ghost-offset-mm 20 -10] [--ghost-yaw-deg 4] [--hz 3]

The recorder is quest-android vision/FrameDump.kt (`DumpFormat`): `000000.jpg`, ..., `intrinsics.json`,
`poses.jsonl`, `session.json`. This script produces the same files from tools/markerless/sample (the committed
renders with ground truth), so `eval_real.py` can be proven end to end against the numbers that
`panel_pose.py` wrote to out/panel_pose.json for the same frames:

 - the picture is the degraded JPEG `panel_pose.py` evaluates (`common.degrade_jpeg`: blur, noise, JPEG), byte
   for byte, so it decodes to the same pixels;
 - the camera pose and the table are the noisy ones `panel_pose.py` uses (`camera_pose_noisy`, `table_prior`),
   moved into a headset-like WORLD (right-handed, +Y up) in which that table is the level plane y = surface_y:
   WORLD <- SYNTH = table_matrix(surface_y) @ inv(T_synth_table). The detector only sees
   inv(T_world_table) @ T_world_cam, which is unchanged by that move;
 - poses are rounded to 32-bit floats and `T_world_cam` is rebuilt from the rounded quaternion, as the app does
   (its `Pose3` holds floats);
 - the ghost is the true panel pose pushed aside by --ghost-offset-mm / --ghost-yaw-deg, so the tool's
   "ghost against detection" has a known answer;
 - two keys the app does not write: `id` (the sample frame) and `gt` (the true pose, for the error columns).

Default OUT_DIR: tools/quest/runs/fake-panel-sample/dump (git-ignored).
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from common import camera_pose_noisy, degrade_jpeg, load_frames, table_prior  # noqa: E402
from eval_real import A_FROM_P, quat_to_R, table_matrix  # noqa: E402

# The keys of one poses.jsonl line, in the order DumpFormat.fields writes them (FrameDumpTest pins the same list).
LINE_KEYS = ["frame", "t", "file", "ts_ns", "wall_ms", "index", "width", "height", "fx", "fy", "cx", "cy",
             "distortion", "cam_p", "cam_q", "T_world_cam", "surface_y", "T_world_table", "anchor_p", "anchor_q",
             "anchor_source", "ghost_p", "ghost_q", "job", "step", "action", "track", "panel_p", "panel_q"]


def f32(v):
    return [float(np.float32(x)) for x in v]


def quat_from_R(R):
    """Rotation matrix -> [x, y, z, w], w >= 0 (as Frames.matToQuat + Frames.canonical in the app)."""
    tr = R[0, 0] + R[1, 1] + R[2, 2]
    if tr > 0:
        s = np.sqrt(tr + 1.0) * 2
        q = [(R[2, 1] - R[1, 2]) / s, (R[0, 2] - R[2, 0]) / s, (R[1, 0] - R[0, 1]) / s, 0.25 * s]
    elif R[0, 0] > R[1, 1] and R[0, 0] > R[2, 2]:
        s = np.sqrt(1.0 + R[0, 0] - R[1, 1] - R[2, 2]) * 2
        q = [0.25 * s, (R[0, 1] + R[1, 0]) / s, (R[0, 2] + R[2, 0]) / s, (R[2, 1] - R[1, 2]) / s]
    elif R[1, 1] > R[2, 2]:
        s = np.sqrt(1.0 + R[1, 1] - R[0, 0] - R[2, 2]) * 2
        q = [(R[0, 1] + R[1, 0]) / s, 0.25 * s, (R[1, 2] + R[2, 1]) / s, (R[0, 2] - R[2, 0]) / s]
    else:
        s = np.sqrt(1.0 + R[2, 2] - R[0, 0] - R[1, 1]) * 2
        q = [(R[0, 2] + R[2, 0]) / s, (R[1, 2] + R[2, 1]) / s, 0.25 * s, (R[1, 0] - R[0, 1]) / s]
    q = np.array(q) / np.linalg.norm(q)
    return (-q if q[3] < 0 else q).tolist()


def pose_fields(T):
    """4 x 4 -> (p, q) as the app stores them (32-bit floats) and the matrix rebuilt from those."""
    p, q = f32(T[:3, 3]), f32(quat_from_R(T[:3, :3]))
    M = np.eye(4)
    M[:3, :3] = quat_to_R(q)
    M[:3, 3] = p
    return p, q, M


def make(out: Path, sample: Path = HERE / "sample", surface_y=0.74, ghost_offset_mm=(20.0, -10.0),
         ghost_yaw_deg=4.0, hz=3.0):
    """Returns the list of sample frame ids written, in file order."""
    out.mkdir(parents=True, exist_ok=True)
    for old in list(out.glob("*.jpg")) + [out / n for n in ("poses.jsonl", "intrinsics.json", "session.json")]:
        if old.exists():
            old.unlink()
    sy = float(np.float32(surface_y))
    T_wt_dev = table_matrix(sy)
    a = np.radians(ghost_yaw_deg)
    push = np.eye(4)
    push[:2, :2] = [[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]]
    push[:2, 3] = np.array(ghost_offset_mm) / 1000.0
    ids, lines, intr = [], [], None
    for f in load_frames(str(sample)):
        src = sample / "img" / f"{f['id']}.jpg"
        if not src.exists():
            continue
        n = len(ids)
        name = f"{n:06d}.jpg"
        jpeg = degrade_jpeg(cv2.imread(str(src)), f["id"], dim=f["light"] == "dim")
        (out / name).write_bytes(jpeg.tobytes())
        K = np.array(f["K"], float)
        world_from_synth = T_wt_dev @ np.linalg.inv(table_prior(f["seq"]))
        cam_p, cam_q, T_wc = pose_fields(world_from_synth @ camera_pose_noisy(np.array(f["T_world_cam"]), f["id"]))
        G = world_from_synth @ np.array(f["T_world_obj"])                       # WORLD <- PANEL, the truth
        ghost_p, ghost_q, _ = pose_fields(G @ push @ np.linalg.inv(A_FROM_P))   # WORLD <- ASSEMBLY, pushed aside
        anchor_p, anchor_q = f32([G[0, 3], sy, G[2, 3]]), [0.0, 0.0, 0.0, 1.0]   # on the surface under the panel
        t = n / hz
        row = {
            "frame": n, "t": t, "file": name, "ts_ns": int(1_000_000_000 + t * 1e9), "wall_ms": int(1_794_750_000_000 + t * 1e3),
            "index": int(round(t * 50)), "width": f["W"], "height": f["H"],
            "fx": K[0, 0], "fy": K[1, 1], "cx": K[0, 2], "cy": K[1, 2], "distortion": None,
            "cam_p": cam_p, "cam_q": cam_q, "T_world_cam": T_wc.tolist(),
            "surface_y": sy, "T_world_table": T_wt_dev.tolist(),
            "anchor_p": anchor_p, "anchor_q": anchor_q, "anchor_source": "table",
            "ghost_p": ghost_p, "ghost_q": ghost_q,
            "job": "malm-drawer", "step": f["state"], "action": 0,
            "track": {"state": "held", "method": "table", "tracked": False, "reproj_px": None, "solve_ms": None,
                      "age_ms": 0.0, "asm_p": ghost_p, "asm_q": ghost_q},
            "panel_p": None, "panel_q": None,
            # not written by the app: the sample frame and its ground truth
            "id": f["id"], "gt": {"T_world_obj": G.tolist(), "yaw": f["obj_xy_yaw"][2]},
        }
        assert list(row)[:len(LINE_KEYS)] == LINE_KEYS
        lines.append(json.dumps(row))
        ids.append(f["id"])
        intr = {"fx": K[0, 0], "fy": K[1, 1], "cx": K[0, 2], "cy": K[1, 2], "width": f["W"], "height": f["H"],
                "fps": hz, "distortion": None}
    if not ids:
        raise SystemExit(f"no sample pictures in {sample / 'img'}")
    (out / "poses.jsonl").write_text("\n".join(lines) + "\n")
    (out / "intrinsics.json").write_text(json.dumps(intr))
    (out / "session.json").write_text(json.dumps({
        "format": "agj-dump-1", "session": out.name, "started_ms": 1_794_750_000_000, "hz": hz, "max": len(ids),
        "jpeg_quality": None, "poses": "poses.jsonl", "fake": True,
        "source": "tools/markerless/sample through make_fake_recording.py (renders, not a headset)",
        "ghost_offset_mm": list(ghost_offset_mm), "ghost_yaw_deg": ghost_yaw_deg}))
    return ids


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out", nargs="?", type=Path, default=HERE.parent / "quest" / "runs" / "fake-panel-sample" / "dump")
    ap.add_argument("--sample", type=Path, default=HERE / "sample")
    ap.add_argument("--surface-y", type=float, default=0.74)
    ap.add_argument("--ghost-offset-mm", type=float, nargs=2, default=(20.0, -10.0))
    ap.add_argument("--ghost-yaw-deg", type=float, default=4.0)
    ap.add_argument("--hz", type=float, default=3.0)
    a = ap.parse_args()
    ids = make(a.out, a.sample, a.surface_y, tuple(a.ghost_offset_mm), a.ghost_yaw_deg, a.hz)
    print(f"{len(ids)} frames -> {a.out}  ({', '.join(ids)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
