"""Debug: does LM converge from near the GT, and where does the global search rank it?
  python diag_init.py DATA_DIR FRAME_ID [FRAME_ID ...]"""
import sys

import cv2
import numpy as np

from common import load_frames
from edge_tracker import EdgeTracker, build_levels
from model import load_state

root = sys.argv[1]
fr = {f["id"]: f for f in load_frames(root)}
for fid in sys.argv[2:]:
    f = fr[fid]
    m = load_state(f["state"])
    trk = EdgeTracker(m, np.eye(4))
    img = cv2.imread(f"{root}/img/{fid}.jpg")
    K = np.array(f["K"])
    T_cw = np.linalg.inv(np.array(f["T_world_cam"]))
    lv = build_levels(img, K)
    gt = np.array(f["obj_xy_yaw"])
    thg, cg, ig = trk._refine(lv, T_cw, gt)
    print(fid, "GT refine", np.round(thg[:3] - gt, 3), "c", round(cg, 3), "inl", round(ig, 3),
          "verify", round(trk.verify(lv, T_cw, thg, cg, ig), 3))
    for dx, dyaw in [(0.03, 0.0), (0.0, np.radians(8)), (0.025, np.radians(-8))]:
        th, c, i = trk._refine(lv, T_cw, [gt[0] + dx, gt[1] - dx, gt[2] + dyaw])
        print("   from +%.3f/%.0fdeg ->" % (dx, np.degrees(dyaw)), np.round(th[:3] - gt, 3), round(c, 3), round(i, 3))
    for r in trk.global_search(lv, T_cw)[:5]:
        d = np.r_[r[1][:2] - gt[:2], (r[1][2] - gt[2] + np.pi) % (2 * np.pi) - np.pi]
        print("   cand score %.3f c %.3f inl %.3f err" % (r[0], r[2], r[3]), np.round(d, 3))
