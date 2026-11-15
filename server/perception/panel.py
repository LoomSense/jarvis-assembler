"""The drawer front lying flat on the table: its pose from ONE camera frame, no marker.

Solves the plane-constrained case only (x, y, yaw on the known table plane), not general 6-DoF.
Inputs: BGR image, intrinsics K OF THAT IMAGE, T_world_cam (OpenCV camera axes: x right, y down, z forward),
T_world_table (z = table normal, origin on the table surface). The image must be undistorted.

Method (only warp, threshold, contours, minAreaRect):
 1. Rectify the image to a top-down view of the plane z = THICK (the panel's top face), 2.5 mm per pixel.
    In that view the top face is a true-scale 380 x 130 mm rectangle whatever the camera angle.
 2. "Whiteness" = L - 1.5 * chroma (Lab). Sweep several thresholds, take external contours, fit minAreaRect.
 3. The blob is the top face plus the camera-facing side faces, which smear towards the camera by
    THICK * distance / camera height. So: check the rectangle size against 380 x 130 + predicted smear, then
    anchor a 380 x 130 rectangle at the corner FARTHEST from the camera (top-face silhouette, never smeared).
 4. Gates -> `trusted`. If not trusted the app must keep its last good pose.

Yaw is only known modulo 180 deg (a rectangle); pass `prev_yaw` to get the nearer one, otherwise it is in [0, pi).
Measured on renders only (tools/markerless/FRONT_PANEL.md); evaluation: tools/markerless/panel_pose.py.
The route is server/routes/panel.py. This is the only copy of the detector.
"""
from __future__ import annotations

import cv2
import numpy as np

LONG, SHORT, THICK = 0.380, 0.130, 0.016   # MALM drawer front, metres
MM_PER_PX = 2.5
HALF = 0.7            # half size of the rectified window on the table, metres
TOL_LONG, TOL_SHORT = 0.014, 0.012
MIN_RECT = 0.86       # contour area / rectangle area
MIN_CONTRAST = 18.0   # whiteness inside minus ring outside (0..255)


def detect_front_panel(img, K, T_world_cam, T_world_table, prev_yaw=None):
    out = {"trusted": False, "reason": "no candidate"}
    T_tc = np.linalg.inv(T_world_table) @ T_world_cam      # camera in table frame
    T_ct = np.linalg.inv(T_tc)
    R, t = T_ct[:3, :3], T_ct[:3, 3]
    cam = T_tc[:3, 3]
    d = T_tc[:3, 2]                                         # optical axis in table frame
    if cam[2] < THICK + 0.05 or d[2] > -0.15:
        out["reason"] = "camera not looking down at the table"
        return out
    s_ = (THICK - cam[2]) / d[2]
    c = cam + min(s_, 1.2) * d
    x0, y0, s = c[0] - HALF, c[1] - HALF, MM_PER_PX / 1000.0
    N = int(2 * HALF / s)
    # bird pixel (i, j, 1) -> image pixel (homogeneous); 3rd row = depth
    H = K @ np.column_stack([R[:, 0] * s, R[:, 1] * s, R[:, 0] * x0 + R[:, 1] * y0 + R[:, 2] * THICK + t])
    bird = cv2.warpPerspective(img, H, (N, N), flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP)
    jj, ii = np.mgrid[0:N, 0:N]
    pw = H[2, 0] * ii + H[2, 1] * jj + H[2, 2]
    pu = (H[0, 0] * ii + H[0, 1] * jj + H[0, 2]) / np.maximum(pw, 1e-6)
    pv = (H[1, 0] * ii + H[1, 1] * jj + H[1, 2]) / np.maximum(pw, 1e-6)
    valid = (pw > 0.1) & (pu > 2) & (pu < img.shape[1] - 3) & (pv > 2) & (pv < img.shape[0] - 3)
    if valid.mean() < 0.05:
        out["reason"] = "table not in view"
        return out
    lab = cv2.cvtColor(cv2.GaussianBlur(bird, (5, 5), 0), cv2.COLOR_BGR2LAB).astype(np.float32)
    white = np.clip(lab[..., 0] - 1.5 * np.hypot(lab[..., 1] - 128, lab[..., 2] - 128), 0, 255)
    white[~valid] = 0
    vals = white[valid]
    area_px = LONG * SHORT / s ** 2
    cam_px = np.array([(cam[0] - x0) / s, (cam[1] - y0) / s])
    hc = cam[2] - THICK
    best = None
    for thr in np.unique(np.percentile(vals, [50, 60, 70, 78, 85, 90, 94, 97])):
        m = (white > thr).astype(np.uint8)
        m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
        cnts, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for cnt in cnts:
            a = cv2.contourArea(cnt)
            if a < 0.6 * area_px or a > 2.2 * area_px:
                continue
            rect = cv2.minAreaRect(cnt)
            box = cv2.boxPoints(rect)                        # 4 corners, bird px
            e0, e1 = box[1] - box[0], box[2] - box[1]
            l0, l1 = np.linalg.norm(e0), np.linalg.norm(e1)
            if l0 < 1 or l1 < 1:
                continue
            (al, ll), (as_, ls) = ((e0 / l0, l0), (e1 / l1, l1)) if l0 >= l1 else ((e1 / l1, l1), (e0 / l0, l0))
            ctr = box.mean(0)
            v = (cam_px - ctr) * s                           # rect centre -> camera, metres
            smear_l = THICK * max(abs(v @ al) - ll * s / 2, 0) / hc
            smear_s = THICK * max(abs(v @ as_) - ls * s / 2, 0) / hc
            err_l = ll * s - smear_l - LONG
            err_s = ls * s - smear_s - SHORT
            rectness = a / (l0 * l1)
            score = abs(err_l) / TOL_LONG + abs(err_s) / TOL_SHORT + max(0, MIN_RECT - rectness) * 10
            if best is None or score < best[0]:
                best = (score, box, al, as_, ctr, err_l, err_s, rectness, cnt, thr)
    if best is None:
        return out
    score, box, al, as_, ctr, err_l, err_s, rectness, cnt, thr = best
    far = box[np.argmax(np.linalg.norm(box - cam_px, axis=1))]
    al = al * np.sign((ctr - far) @ al)
    as_ = as_ * np.sign((ctr - far) @ as_)
    centre_px = far + al * (LONG / s / 2) + as_ * (SHORT / s / 2)
    centre = np.array([x0 + centre_px[0] * s, y0 + centre_px[1] * s])
    yaw = float(np.arctan2(al[1], al[0]) % np.pi)
    if prev_yaw is not None and abs(((yaw - prev_yaw + np.pi) % (2 * np.pi)) - np.pi) > np.pi / 2:
        yaw += np.pi
    # gates
    mask_in = np.zeros((N, N), np.uint8)
    cv2.drawContours(mask_in, [cnt], -1, 1, -1)
    ring = cv2.dilate(mask_in, np.ones((15, 15), np.uint8)) - cv2.dilate(mask_in, np.ones((5, 5), np.uint8))
    cut = float((ring.astype(bool) & ~valid).sum()) / max(1, int(ring.sum()))
    contrast = float(white[mask_in > 0].mean() - white[(ring > 0) & valid].mean()) if (ring > 0).any() else 0.0
    reason = None
    if abs(err_l) > TOL_LONG or abs(err_s) > TOL_SHORT:
        reason = "size is not 380 x 130 mm"
    elif rectness < MIN_RECT:
        reason = "not a clean rectangle (hand or object over it)"
    elif cut > 0.03:
        reason = "panel partly out of the camera view"
    elif contrast < MIN_CONTRAST:
        reason = "low contrast against the table"
    ca, sa = np.cos(yaw), np.sin(yaw)
    T_to = np.eye(4)
    T_to[:3, :3] = [[ca, -sa, 0], [sa, ca, 0], [0, 0, 1]]
    T_to[:2, 3] = centre
    corners_t = np.array([[sx * LONG / 2, sy * SHORT / 2, THICK, 1.0] for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1))])
    Xc = (T_ct @ T_to @ corners_t.T).T
    corners_px = (Xc[:, :2] / Xc[:, 2:3]) * [K[0, 0], K[1, 1]] + [K[0, 2], K[1, 2]]
    out.update({"trusted": reason is None, "reason": reason or "ok",
                "x": float(centre[0]), "y": float(centre[1]), "yaw": yaw, "yaw_mod_180": True,
                "T_world_obj": (T_world_table @ T_to).tolist(),   # origin: panel centre ON the table plane
                "corners_px": corners_px.tolist(),                # top-face corners in the input image
                "size_err_mm": [float(err_l * 1000), float(err_s * 1000)],
                "rectness": float(rectness), "contrast": contrast})
    return out
