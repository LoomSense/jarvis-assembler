#!/usr/bin/env python3
"""Render a synthetic replay dataset: an AprilTag 36h11 marker on a wooden table, seen by a moving camera.

  .venv/bin/python tools/quest/replay/make_marker_synthetic.py [--out DIR] [--frames 300] [--size 1280x960]

Writes into tools/quest/replay/marker-synthetic/ (git-ignored, ~25 MB; regenerate in ~15 s):
  000000.jpg ...      frames (JPEG q85)
  intrinsics.json     {fx, fy, cx, cy, width, height, fps, distortion: none}
  poses.jsonl         one line per frame: camera pose in the world + ground-truth marker pose in the camera
  dataset.json        marker family/id/size, conventions, trajectory phases

Trajectory (30 fps, 10 s): frames 0-179 orbit the marker (azimuth -55..+55 deg, elevation 55..38 deg,
0.55 m); 180-259 slow sideways translation and approach to 0.42 m; 260-299 perfectly still (jitter window).
The marker's black square is 80 mm (same as the printable marker in tools/quest/marker/).
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from geom import CV_TO_XR, R_WORLD_MARKER, look_at_cv, quat_from_R  # noqa: E402

HERE = Path(__file__).resolve().parent
FAMILY, MARKER_ID, MARKER_MM = "apriltag_36h11", 0, 80.0
TEX_MM = (700, 500)             # table patch, 1 px = 1 mm


def smoothstep(a: float) -> float:
    a = min(max(a, 0.0), 1.0)
    return a * a * (3 - 2 * a)


def marker_bits() -> np.ndarray:
    d = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_APRILTAG_36h11)
    return cv2.aruco.generateImageMarker(d, MARKER_ID, 8, borderBits=1)   # 8x8 modules, 1 px each


def table_texture(rng: np.random.Generator) -> np.ndarray:
    """BGR wood-ish texture (1 px = 1 mm) with the printed marker on white paper at the centre."""
    W, H = TEX_MM
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    noise = cv2.GaussianBlur(rng.standard_normal((H, W)).astype(np.float32), (0, 0), 6) * 3
    grain = np.sin((xx + 25 * noise + 8 * np.sin(yy / 37)) / 5.5) * 0.5 + 0.5
    fine = cv2.GaussianBlur(rng.standard_normal((H, W)).astype(np.float32), (0, 0), 1.2) * 0.08
    t = np.clip(0.55 + 0.25 * grain + fine, 0, 1)
    base = np.array([60, 110, 165], np.float32)        # BGR light oak
    dark = np.array([30, 65, 110], np.float32)
    tex = (dark[None, None] * (1 - t[..., None]) + base[None, None] * t[..., None]).astype(np.uint8)
    # Paper 140 x 140 mm, marker 80 mm (10 mm modules) centred.
    cx, cy = W // 2, H // 2
    tex[cy - 70:cy + 70, cx - 70:cx + 70] = (236, 238, 240)
    m = cv2.resize(marker_bits(), (int(MARKER_MM), int(MARKER_MM)), interpolation=cv2.INTER_NEAREST)
    h = int(MARKER_MM) // 2
    tex[cy - h:cy + h, cx - h:cx + h] = np.where(m[..., None] > 0, 240, 22).astype(np.uint8)
    return tex


def trajectory(n: int, fps: float):
    for i in range(n):
        if i < 180:
            a = i / 179
            az = math.radians(-55 + 110 * smoothstep(a))
            el = math.radians(55 - 17 * a)
            r = 0.55
            tgt = np.array([0.03 * math.sin(2 * math.pi * a), 0.0, 0.02 * math.cos(2 * math.pi * a)])
            shift = np.zeros(3)
        else:
            a = smoothstep(min((i - 180) / 79, 1.0))
            az, el, r = math.radians(55 - 15 * a), math.radians(38 + 4 * a), 0.55 - 0.13 * a
            tgt = np.array([0.0, 0.0, 0.02])
            shift = np.array([-0.10 * a, 0.0, 0.0])
        cam = np.array([r * math.cos(el) * math.sin(az), r * math.sin(el), r * math.cos(el) * math.cos(az)]) + shift
        yield i, i / fps, cam, tgt + shift * 0.5


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, default=HERE / "marker-synthetic")
    ap.add_argument("--frames", type=int, default=300)
    ap.add_argument("--fps", type=float, default=30.0)
    ap.add_argument("--size", default="1280x960", help="WxH (Quest 3 passthrough camera max is 1280x960)")
    ap.add_argument("--hfov", type=float, default=72.0, help="horizontal field of view, degrees")
    ap.add_argument("--quality", type=int, default=85)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    W, H = (int(v) for v in a.size.lower().split("x"))
    fx = fy = (W / 2) / math.tan(math.radians(a.hfov) / 2)
    cx, cy = W / 2 - 0.5 + 3.2, H / 2 - 0.5 - 2.1          # slightly off-centre, like a real calibration
    K = np.array([[fx, 0, cx], [0, fy, cy], [0, 0, 1]])
    rng = np.random.default_rng(a.seed)
    tex = table_texture(rng)
    TW, TH = TEX_MM
    s = 0.001                                               # metres per texture pixel
    # texture pixel (u, v, 1) -> world (X, 0, Z): X = (u + 0.5 - TW/2) s, Z = (v + 0.5 - TH/2) s
    A = np.array([[s, 0, (0.5 - TW / 2) * s], [0, 0, 0], [0, s, (0.5 - TH / 2) * s]])   # pixel centres
    bg = np.tile(np.linspace(70, 40, H, dtype=np.float32)[:, None, None], (1, W, 3)).astype(np.uint8)

    out = a.out
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("*.jpg"):
        old.unlink()
    poses = []
    half = MARKER_MM / 2000
    corners_m = np.array([[-half, half, 0], [half, half, 0], [half, -half, 0], [-half, -half, 0]])
    min_px = 1e9
    for i, t, cam, tgt in trajectory(a.frames, a.fps):
        R_wc = look_at_cv(cam, tgt)
        R_cw = R_wc.T
        t_cw = -R_cw @ cam
        Hm = K @ (R_cw @ A + np.outer(t_cw, [0, 0, 1]))
        img = cv2.warpPerspective(tex, Hm, (W, H), dst=bg.copy(), flags=cv2.INTER_LINEAR,
                                  borderMode=cv2.BORDER_TRANSPARENT)
        # Camera realism: lighting drift, vignette, slight blur, sensor noise.
        gain = 0.92 + 0.08 * math.sin(i / 23.0)
        yy, xx = np.mgrid[0:H, 0:W]
        vig = 1 - 0.25 * (((xx - W / 2) / (W / 2)) ** 2 + ((yy - H / 2) / (H / 2)) ** 2) / 2
        img = img.astype(np.float32) * (gain * vig)[..., None]
        img = cv2.GaussianBlur(img, (0, 0), 0.7)
        img += rng.normal(0, 2.0, img.shape).astype(np.float32)
        img = np.clip(img, 0, 255).astype(np.uint8)
        cv2.imwrite(str(out / f"{i:06d}.jpg"), img, [cv2.IMWRITE_JPEG_QUALITY, a.quality])

        R_cm = R_cw @ R_WORLD_MARKER
        t_cm = t_cw                                         # marker origin = world origin
        proj, _ = cv2.projectPoints(corners_m, cv2.Rodrigues(R_cm)[0], t_cm, K, None)
        proj = proj.reshape(-1, 2)
        assert (proj[:, 0] > 0).all() and (proj[:, 0] < W).all() and (proj[:, 1] > 0).all() and (proj[:, 1] < H).all(), \
            f"marker leaves the image at frame {i}"
        min_px = min(min_px, float(np.linalg.norm(proj[0] - proj[1])))
        poses.append({
            "frame": i, "t": round(t, 4), "file": f"{i:06d}.jpg",
            "cam_p": [round(float(v), 6) for v in cam],
            "cam_q": [round(v, 7) for v in quat_from_R(R_wc)],
            "cam_q_xr": [round(v, 7) for v in quat_from_R(R_wc @ CV_TO_XR)],
            "marker_p": [round(float(v), 6) for v in t_cm],
            "marker_q": [round(v, 7) for v in quat_from_R(R_cm)],
            "marker_corners_px": [[round(float(x), 2), round(float(y), 2)] for x, y in proj],
        })
    (out / "intrinsics.json").write_text(json.dumps(
        {"fx": round(fx, 4), "fy": round(fy, 4), "cx": round(cx, 4), "cy": round(cy, 4), "width": W, "height": H,
         "fps": a.fps, "distortion": None}, indent=2))
    (out / "poses.jsonl").write_text("".join(json.dumps(p, separators=(",", ":")) + "\n" for p in poses))
    (out / "dataset.json").write_text(json.dumps({
        "name": "marker-synthetic", "frames": a.frames, "fps": a.fps, "seed": a.seed,
        "marker": {"family": FAMILY, "id": MARKER_ID, "size_m": MARKER_MM / 1000,
                   "size_note": "edge of the black square (8 x 8 modules of 10 mm incl. the black border)",
                   "world_p": [0, 0, 0], "world_q": quat_from_R(R_WORLD_MARKER),
                   "world_note": "lying face up at the world origin, its top edge pointing to world -Z"},
        "conventions": {
            "world": "right-handed, Y up, metres (OpenXR)",
            "cam_p/cam_q": "camera pose in the world, OpenCV camera axes (x right, y down, z forward)",
            "cam_q_xr": "same camera rotation in OpenXR camera axes (x right, y up, z backward)",
            "marker_p/marker_q": "marker pose in the OpenCV camera frame (what solvePnP returns), "
                                 "marker axes x right, y to the top edge, z out of the face",
            "quaternion": "[x, y, z, w]"},
        "phases": {"orbit": [0, 179], "translate": [180, 259], "still": [260, a.frames - 1]},
    }, indent=2))
    size_mb = sum(p.stat().st_size for p in out.iterdir()) / 1e6
    print(f"wrote {a.frames} frames {W}x{H} to {out} ({size_mb:.1f} MB); smallest marker edge {min_px:.0f} px")
    return 0


if __name__ == "__main__":
    sys.exit(main())
