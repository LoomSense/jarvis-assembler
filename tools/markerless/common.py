"""Dataset loading, image degradation, pose priors with noise, error metrics."""
import hashlib
import json
import os

import cv2
import numpy as np


def load_frames(root):
    return [json.loads(l) for l in open(os.path.join(root, "frames.jsonl"))]


def seeded(name, salt=""):
    return np.random.default_rng(int(hashlib.md5((name + salt).encode()).hexdigest()[:8], 16))


def degrade(img, name, dim=False):
    """Sensor-like degradation: motion blur, gaussian noise, JPEG."""
    r = seeded(name, "deg")
    L = int(r.integers(0, 10))  # motion blur length in full-res px (head motion)
    if L >= 2:
        k = np.zeros((L, L), np.float32)
        a = r.uniform(0, np.pi)
        c = (L - 1) / 2
        for t in np.linspace(-c, c, 4 * L):
            k[int(round(c + t * np.sin(a))), int(round(c + t * np.cos(a)))] = 1
        img = cv2.filter2D(img, -1, k / k.sum())
    sigma = r.uniform(2, 6) * (2.0 if dim else 1.0)
    img = np.clip(img.astype(np.float32) + r.normal(0, sigma, img.shape), 0, 255).astype(np.uint8)
    q = int(r.integers(60, 91))
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, q])
    return cv2.imdecode(buf, cv2.IMREAD_COLOR)


def rot_err_deg(R1, R2):
    c = (np.trace(R1.T @ R2) - 1) / 2
    return float(np.degrees(np.arccos(np.clip(c, -1, 1))))


def pose_errors(T_est, T_gt):
    dt = float(np.linalg.norm(T_est[:3, 3] - T_gt[:3, 3]) * 1000)
    dr = rot_err_deg(T_est[:3, :3], T_gt[:3, :3])
    # yaw about the table normal (z) only
    ye = np.arctan2(T_est[1, 0], T_est[0, 0]) - np.arctan2(T_gt[1, 0], T_gt[0, 0])
    ye = float(np.degrees(abs((ye + np.pi) % (2 * np.pi) - np.pi)))
    return dt, dr, ye


def table_prior(seq, noise=True):
    """T_world_table as scene understanding would give it: true table z=0 plus
    a per-sequence height error (sigma 3 mm) and tilt (sigma 0.4 deg)."""
    T = np.eye(4)
    if not noise:
        return T
    r = np.random.default_rng(1000 + seq)
    T[2, 3] = r.normal(0, 0.003)
    ax, ay = np.radians(r.normal(0, 0.4, 2))
    cx, sx, cy, sy = np.cos(ax), np.sin(ax), np.cos(ay), np.sin(ay)
    T[:3, :3] = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]]) @ np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    return T


def camera_pose_noisy(T_wc, name, noise=True):
    """Head-tracking + camera extrinsic error: sigma 1.5 mm, 0.15 deg per frame."""
    if not noise:
        return T_wc
    r = seeded(name, "cam")
    T = T_wc.copy()
    T[:3, 3] += r.normal(0, 0.0015, 3)
    w = np.radians(r.normal(0, 0.15, 3))
    th = np.linalg.norm(w)
    if th > 0:
        k = w / th
        Kx = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
        R = np.eye(3) + np.sin(th) * Kx + (1 - np.cos(th)) * Kx @ Kx
        T[:3, :3] = R @ T[:3, :3]
    return T
