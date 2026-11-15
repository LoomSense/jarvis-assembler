"""Reference numbers for the Kotlin port (quest-android vision/PanelPose.kt).

Runs detect_front_panel on the sample frames (raw JPEGs, no extra degradation, the same noisy camera pose and
table prior as the evaluation) and writes inputs, the rectification homography and the outputs to JSON. `out` is
the drawer front alone (step 1), `out_hull` the same rectangle with `fill_hull` (steps 2 and 3: the sides stand
on the front), `out_box` the whole outline of the turned-over box (418 x 380 x 130 mm; not shipped, it only pins
that the port takes any rectangle):

  python export_kotlin_ref.py sample ../../quest-android/app/src/test/resources/panel_pose_ref.json
"""
import json
import os
import sys

import cv2
import numpy as np

from common import camera_pose_noisy, load_frames, table_prior
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
from server.perception.panel import HALF, MM_PER_PX, THICK, detect_footprint, detect_front_panel  # noqa: E402

root, dst = sys.argv[1], sys.argv[2]
rows = []
for f in load_frames(root):
    p = f"{root}/img/{f['id']}.jpg"
    if not os.path.exists(p):
        continue
    img = cv2.imread(p)
    K = np.array(f["K"], float)
    T_wc = camera_pose_noisy(np.array(f["T_world_cam"]), f["id"])
    T_wt = table_prior(f["seq"])
    # the same lines as detect_front_panel, to export the intermediate values
    T_tc = np.linalg.inv(T_wt) @ T_wc
    T_ct = np.linalg.inv(T_tc)
    R, t = T_ct[:3, :3], T_ct[:3, 3]
    cam, d = T_tc[:3, 3], T_tc[:3, 2]
    c = cam + min((THICK - cam[2]) / d[2], 1.2) * d
    x0, y0, s = c[0] - HALF, c[1] - HALF, MM_PER_PX / 1000.0
    H = K @ np.column_stack([R[:, 0] * s, R[:, 1] * s, R[:, 0] * x0 + R[:, 1] * y0 + R[:, 2] * THICK + t])
    r = detect_front_panel(img, K, T_wc, T_wt)
    r180 = detect_front_panel(img, K, T_wc, T_wt, prev_yaw=r["yaw"] + np.pi - 0.3) if "yaw" in r else None
    rows.append({
        "id": f["id"], "state": f["state"], "light": f["light"], "hand": f["hand"],
        "K": K.tolist(), "T_world_cam": T_wc.tolist(), "T_world_table": T_wt.tolist(),
        "T_table_cam": T_tc.tolist(), "N": int(2 * HALF / s), "x0": float(x0), "y0": float(y0), "H": H.tolist(),
        "cam_px": [float((cam[0] - x0) / s), float((cam[1] - y0) / s)], "hc": float(cam[2] - THICK),
        "gt_T_world_obj": f["T_world_obj"], "gt_obj_xy_yaw": f["obj_xy_yaw"],
        "out": r, "yaw_with_prev": None if r180 is None else r180["yaw"],
        "out_hull": detect_footprint(img, K, T_wc, T_wt, fill_hull=True),
        "out_box": detect_footprint(img, K, T_wc, T_wt, length=0.418, width=0.380, height=0.130),
    })
    h = rows[-1]["out_hull"]
    print(f["id"], f["state"], r["trusted"], r["reason"], r.get("x"), r.get("y"), r.get("yaw"), r.get("size_err_mm"), r.get("rectness"), r.get("contrast"),
          "| hull:", h["trusted"], h["reason"], "| box:", rows[-1]["out_box"]["trusted"], rows[-1]["out_box"]["reason"])
json.dump({"rows": rows}, open(dst, "w"), indent=1)
