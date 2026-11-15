"""Small rigid-body helpers shared by the replay generator, the reference tracker and score_track.

Conventions (see docs/QUEST_TESTING.md, "Replay datasets"):
- World: right-handed, Y up, metres (the OpenXR convention). The marker lies at the world origin.
- Camera: OpenCV axes (x right, y down, z forward). `cam_q_xr` gives the same camera in
  OpenXR axes (x right, y up, z backward), i.e. the pose an OpenXR runtime would report.
- Marker: OpenCV ArUco axes (x to the tag's right, y to its top edge, z out of the printed face).
- Quaternions are [x, y, z, w].
"""
from __future__ import annotations

import numpy as np

CV_TO_XR = np.diag([1.0, -1.0, -1.0])      # OpenCV camera axes -> OpenXR camera axes (and back)


def quat_from_R(R: np.ndarray) -> list[float]:
    R = np.asarray(R, dtype=float)
    t = np.trace(R)
    if t > 0:
        s = np.sqrt(t + 1.0) * 2
        w, x, y, z = 0.25 * s, (R[2, 1] - R[1, 2]) / s, (R[0, 2] - R[2, 0]) / s, (R[1, 0] - R[0, 1]) / s
    elif R[0, 0] > R[1, 1] and R[0, 0] > R[2, 2]:
        s = np.sqrt(1.0 + R[0, 0] - R[1, 1] - R[2, 2]) * 2
        w, x, y, z = (R[2, 1] - R[1, 2]) / s, 0.25 * s, (R[0, 1] + R[1, 0]) / s, (R[0, 2] + R[2, 0]) / s
    elif R[1, 1] > R[2, 2]:
        s = np.sqrt(1.0 + R[1, 1] - R[0, 0] - R[2, 2]) * 2
        w, x, y, z = (R[0, 2] - R[2, 0]) / s, (R[0, 1] + R[1, 0]) / s, 0.25 * s, (R[1, 2] + R[2, 1]) / s
    else:
        s = np.sqrt(1.0 + R[2, 2] - R[0, 0] - R[1, 1]) * 2
        w, x, y, z = (R[1, 0] - R[0, 1]) / s, (R[0, 2] + R[2, 0]) / s, (R[1, 2] + R[2, 1]) / s, 0.25 * s
    q = np.array([x, y, z, w])
    q /= np.linalg.norm(q)
    if q[3] < 0:
        q = -q
    return [float(v) for v in q]


def R_from_quat(q) -> np.ndarray:
    x, y, z, w = (float(v) for v in q)
    n = np.sqrt(x * x + y * y + z * z + w * w)
    x, y, z, w = x / n, y / n, z / n, w / n
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])


def look_at_cv(cam: np.ndarray, target: np.ndarray, up=(0.0, 1.0, 0.0)) -> np.ndarray:
    """World-from-camera rotation for an OpenCV camera at `cam` looking at `target` (world Y up)."""
    z = target - cam
    z = z / np.linalg.norm(z)
    x = np.cross(z, np.asarray(up, dtype=float))
    x = x / np.linalg.norm(x)
    y = np.cross(z, x)
    return np.stack([x, y, z], axis=1)


def rot_angle_deg(Ra: np.ndarray, Rb: np.ndarray) -> float:
    c = (np.trace(Ra.T @ Rb) - 1.0) / 2.0
    return float(np.degrees(np.arccos(np.clip(c, -1.0, 1.0))))


def yaw_deg_world(R_wm: np.ndarray) -> float:
    """Heading of the marker in the world: angle of its x axis around world +Y (0 = along +X)."""
    x = R_wm[:, 0]
    return float(np.degrees(np.arctan2(-x[2], x[0])))


# Marker lying face up at the world origin, its top edge pointing to world -Z.
R_WORLD_MARKER = np.array([[1.0, 0.0, 0.0],
                           [0.0, 0.0, 1.0],
                           [0.0, -1.0, 0.0]])
