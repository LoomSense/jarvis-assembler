#!/usr/bin/env python3
"""Render a synthetic replay dataset: the two-tag marker strip on a wooden table, seen by a moving camera.

  .venv/bin/python tools/quest/replay/make_marker_synthetic.py [--out DIR] [--frames 300] [--size 1280x960]

Writes into tools/quest/replay/marker-synthetic/ (git-ignored, ~25 MB; regenerate in ~15 s):
  000000.jpg ...      frames (JPEG q85)
  intrinsics.json     {fx, fy, cx, cy, width, height, fps, distortion: none}
  poses.jsonl         one line per frame: camera pose in the world + ground-truth STRIP CENTRE pose in the camera
                      (+ each tag's corners in pixels); format spec: docs/QUEST_TESTING.md "poses.jsonl"
  dataset.json        strip + tags (from tools/quest/marker/marker.json), conventions, trajectory phases

Trajectory (30 fps, 10 s): frames 0-179 orbit the marker (azimuth -55..+55 deg, elevation 55..38 deg,
0.55 m); 180-259 slow sideways translation and approach to 0.42 m; 260-299 perfectly still (jitter window).
The strip is the printable one in tools/quest/marker/ (210 x 105 mm: 36h11 id 0 at x = -50 mm, tagStandard41h12
id 0 at x = +50 mm), drawn with the same code; both tags stay fully in view in every frame. The strip centre is the
world origin and the tracked object: both apps must report the same pose whichever tag they detect.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "marker"))
from geom import CV_TO_XR, R_WORLD_MARKER, look_at_cv, quat_from_R  # noqa: E402
from make_marker import render_strip  # noqa: E402

MARKER = json.loads((HERE.parent / "marker" / "marker.json").read_text())
TEX_MM = (700, 500)             # table patch
PX_MM = 2                       # texture pixels per mm (0.5 mm texels)


def smoothstep(a: float) -> float:
    a = min(max(a, 0.0), 1.0)
    return a * a * (3 - 2 * a)


def table_texture(rng: np.random.Generator) -> np.ndarray:
    """BGR wood-ish texture (PX_MM px per mm) with the printed strip (white paper) at the centre."""
    W, H = TEX_MM[0] * PX_MM, TEX_MM[1] * PX_MM
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32) / PX_MM
    noise = cv2.resize(cv2.GaussianBlur(rng.standard_normal(TEX_MM[::-1]).astype(np.float32), (0, 0), 6) * 3, (W, H))
    grain = np.sin((xx + 25 * noise + 8 * np.sin(yy / 37)) / 5.5) * 0.5 + 0.5
    fine = cv2.GaussianBlur(rng.standard_normal((H, W)).astype(np.float32), (0, 0), 1.2 * PX_MM) * 0.08
    t = np.clip(0.55 + 0.25 * grain + fine, 0, 1)
    base = np.array([60, 110, 165], np.float32)        # BGR light oak
    dark = np.array([30, 65, 110], np.float32)
    tex = (dark[None, None] * (1 - t[..., None]) + base[None, None] * t[..., None]).astype(np.uint8)
    # Strip: rendered at 4x, area-averaged down (exact module edges); row 0 = TOP edge = toward world -Z.
    strip = cv2.resize(render_strip(4 * PX_MM).astype(np.float32), None, fx=0.25, fy=0.25, interpolation=cv2.INTER_AREA)
    shade = (22 + (240 - 22) * strip / 255.0).astype(np.uint8)
    sh, sw = shade.shape
    v0, u0 = H // 2 - sh // 2, W // 2 - sw // 2
    paper = np.array([236, 238, 240], np.float32) / 240
    tex[v0:v0 + sh, u0:u0 + sw] = np.clip(shade[..., None] * paper[None, None], 0, 255).astype(np.uint8)
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
    TW, TH = TEX_MM[0] * PX_MM, TEX_MM[1] * PX_MM
    s = 0.001 / PX_MM                                       # metres per texture pixel
    # texture pixel (u, v, 1) -> world (X, 0, Z): X = (u + 0.5 - TW/2) s, Z = (v + 0.5 - TH/2) s
    A = np.array([[s, 0, (0.5 - TW / 2) * s], [0, 0, 0], [0, s, (0.5 - TH / 2) * s]])   # pixel centres
    bg = np.tile(np.linspace(70, 40, H, dtype=np.float32)[:, None, None], (1, W, 3)).astype(np.uint8)

    out = a.out
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("*.jpg"):
        old.unlink()
    poses = []
    tag_corners = {}                                        # tag key -> its 4 detector-edge corners in strip axes
    for tg in MARKER["tags"]:
        h, o = tg["size_m"] / 2, np.asarray(tg["offset_m"], dtype=float)
        tag_corners[tg["key"]] = o + np.array([[-h, h, 0], [h, h, 0], [h, -h, 0], [-h, -h, 0]])
    min_px = {k: 1e9 for k in tag_corners}
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
        t_cm = t_cw                                         # strip centre = world origin
        corners_px = {}
        for key, cm in tag_corners.items():
            proj, _ = cv2.projectPoints(cm, cv2.Rodrigues(R_cm)[0], t_cm, K, None)
            proj = proj.reshape(-1, 2)
            assert (proj[:, 0] > 0).all() and (proj[:, 0] < W).all() and (proj[:, 1] > 0).all() and \
                (proj[:, 1] < H).all(), f"{key} leaves the image at frame {i}"
            min_px[key] = min(min_px[key], float(np.linalg.norm(proj[0] - proj[1])))
            corners_px[key] = [[round(float(x), 2), round(float(y), 2)] for x, y in proj]
        poses.append({
            "frame": i, "t": round(t, 4), "file": f"{i:06d}.jpg",
            "cam_p": [round(float(v), 6) for v in cam],
            "cam_q": [round(v, 7) for v in quat_from_R(R_wc)],
            "cam_q_xr": [round(v, 7) for v in quat_from_R(R_wc @ CV_TO_XR)],
            "marker_p": [round(float(v), 6) for v in t_cm],
            "marker_q": [round(v, 7) for v in quat_from_R(R_cm)],
            "tag_corners_px": corners_px,
        })
    (out / "intrinsics.json").write_text(json.dumps(
        {"fx": round(fx, 4), "fy": round(fy, 4), "cx": round(cx, 4), "cy": round(cy, 4), "width": W, "height": H,
         "fps": a.fps, "distortion": None}, indent=2))
    (out / "poses.jsonl").write_text("".join(json.dumps(p, separators=(",", ":")) + "\n" for p in poses))
    (out / "dataset.json").write_text(json.dumps({
        "name": "marker-synthetic", "frames": a.frames, "fps": a.fps, "seed": a.seed,
        "marker": {"object": "strip_centre", "tags": MARKER["tags"], "strip_m": MARKER["strip_m"],
                   "marker_axes": MARKER["marker_axes"],
                   "world_p": [0, 0, 0], "world_q": quat_from_R(R_WORLD_MARKER),
                   "world_note": "strip lying face up, its centre at the world origin, its TOP edge pointing to "
                                 "world -Z, its x axis (36h11 -> 41h12) along world +X"},
        "conventions": {
            "world": "right-handed, Y up, metres (OpenXR)",
            "cam_p/cam_q": "world-from-camera: camera pose in the world, OpenCV camera axes "
                           "(x right, y down, z forward); p_world = R(cam_q) p_cam + cam_p",
            "cam_q_xr": "same camera rotation in OpenXR camera axes (x right, y up, z backward)",
            "marker_p/marker_q": "camera-from-object: STRIP CENTRE pose in the OpenCV camera frame "
                                 "(what solvePnP returns after the tag offset), marker axes x along the strip "
                                 "(36h11 -> 41h12), y to the TOP edge, z out of the face; "
                                 "p_cam = R(marker_q) p_strip + marker_p",
            "tag_corners_px": "per tag key, the 4 detector-edge corners in pixels: top-left, top-right, "
                              "bottom-right, bottom-left of the upright tag (marker axes)",
            "quaternion": "[x, y, z, w], Hamilton, w >= 0"},
        "phases": {"orbit": [0, 179], "translate": [180, 259], "still": [260, a.frames - 1]},
    }, indent=2))
    size_mb = sum(p.stat().st_size for p in out.iterdir()) / 1e6
    print(f"wrote {a.frames} frames {W}x{H} to {out} ({size_mb:.1f} MB); smallest detector edge "
          + ", ".join(f"{k} {v:.0f} px" for k, v in min_px.items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
