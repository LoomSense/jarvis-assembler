"""Method B: learning-free keypoint templates -> PnP (+ table constraint).

Templates: for each assembly state, keyframes with a known pose from the *other*
lighting sequences of that state (stand-in for template views rendered or
captured offline). Keypoints inside the object's projected mask are lifted to
3D in the object frame with the model z-buffer. Query: detect + match (ratio
test) against all templates of the state, solvePnPRansac, then project the pose
onto the table (keep x, y, yaw) as the table constraint.

  python eval_keypoints.py DATA_DIR OUT.json [--det orb|sift] [--stride 3]
"""
import argparse
import json
import time

import cv2
import numpy as np

from common import camera_pose_noisy, degrade, load_frames, pose_errors, table_prior
from edge_tracker import pose_from_params
from model import load_state, zbuffer

ap = argparse.ArgumentParser()
ap.add_argument("data"); ap.add_argument("out")
ap.add_argument("--det", default="orb")
ap.add_argument("--stride", type=int, default=3)
a = ap.parse_args()


def detector():
    if a.det == "sift":  # AKAZE is not in this OpenCV 5 build; SIFT as the float-descriptor baseline
        return cv2.SIFT_create(nfeatures=3000), cv2.NORM_L2
    return cv2.ORB_create(nfeatures=3000, fastThreshold=10), cv2.NORM_HAMMING


det, norm = detector()
frames = load_frames(a.data)


def obj_depth(f, m):
    K = np.array(f["K"]); T_co = np.linalg.inv(np.array(f["T_world_cam"])) @ np.array(f["T_world_obj"])
    Vc = m.V @ T_co[:3, :3].T + T_co[:3, 3]
    Z = zbuffer(Vc, m.F, K[0, 0], K[1, 1], K[0, 2], K[1, 2], f["W"], f["H"])
    return Z, K, T_co


templates = {}
for st in sorted({f["state"] for f in frames}):
    m = load_state(st)
    for f in [f for f in frames if f["state"] == st and f["frame"] % 5 == 0]:
        img = cv2.cvtColor(cv2.imread(f"{a.data}/img/{f['id']}.jpg"), cv2.COLOR_BGR2GRAY)
        Z, K, T_co = obj_depth(f, m)
        mask = np.isfinite(Z).astype(np.uint8) * 255
        kps, des = det.detectAndCompute(img, mask)
        if des is None or len(kps) == 0:
            continue
        uv = np.array([k.pt for k in kps])
        z = Z[uv[:, 1].astype(int), uv[:, 0].astype(int)]
        good = np.isfinite(z)
        Xc = np.c_[(uv[:, 0] - K[0, 2]) / K[0, 0] * z, (uv[:, 1] - K[1, 2]) / K[1, 1] * z, z][good]
        T_oc = np.linalg.inv(T_co)
        Xo = Xc @ T_oc[:3, :3].T + T_oc[:3, 3]
        templates.setdefault(st, []).append({"light": f["light"], "des": des[good], "X": Xo})
    print("state", st, "templates", len(templates.get(st, [])),
          "object keypoints/template", [len(t["X"]) for t in templates.get(st, [])], flush=True)

bf = cv2.BFMatcher(norm)
rows = []
for f in frames[:: a.stride]:
    st = f["state"]
    m = load_state(st)
    img = degrade(cv2.imread(f"{a.data}/img/{f['id']}.jpg"), f["id"], dim=f["light"] == "dim")
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    Z, K, _ = obj_depth(f, m)
    T_wc = camera_pose_noisy(np.array(f["T_world_cam"]), f["id"])
    T_wt = table_prior(f["seq"])
    t0 = time.perf_counter()
    kps, des = det.detectAndCompute(gray, None)
    tp = [t for t in templates[st] if t["light"] != f["light"]]
    D = np.vstack([t["des"] for t in tp]); X = np.vstack([t["X"] for t in tp])
    obj_kp = 0 if not kps else int(sum(np.isfinite(Z[int(k.pt[1]), int(k.pt[0])]) for k in kps))
    ok, ninl, T_est = False, 0, None
    if des is not None and len(kps) >= 6:
        mt = bf.knnMatch(des, D, k=2)
        good = [p[0] for p in mt if len(p) == 2 and p[0].distance < 0.8 * p[1].distance]
        if len(good) >= 6:
            obj = X[[g.trainIdx for g in good]].astype(np.float64)
            img_pts = np.array([kps[g.queryIdx].pt for g in good], np.float64)
            res = cv2.solvePnPRansac(obj, img_pts, K, None, iterationsCount=500, reprojectionError=4.0,
                                     flags=cv2.SOLVEPNP_EPNP)
            if res[0] and res[3] is not None and len(res[3]) >= 8:
                R, _ = cv2.Rodrigues(res[1])
                T_co = np.eye(4); T_co[:3, :3] = R; T_co[:3, 3] = res[2].ravel()
                T_wo = T_wc @ T_co
                # table constraint: keep x, y, yaw in the table frame
                T_to = np.linalg.inv(T_wt) @ T_wo
                yaw = np.arctan2(T_to[1, 0], T_to[0, 0])
                T_est = pose_from_params(T_wt, [T_to[0, 3], T_to[1, 3], yaw])
                ok, ninl = True, len(res[3])
    ms = (time.perf_counter() - t0) * 1e3
    if T_est is None:
        dt, dr, dy = float("inf"), 180.0, 180.0
    else:
        dt, dr, dy = pose_errors(T_est, np.array(f["T_world_obj"]))
    rows.append({"id": f["id"], "state": st, "light": f["light"], "hand": f["hand"], "obj_kp": obj_kp,
                 "inliers": ninl, "ok": ok, "t_mm": dt, "r_deg": dr, "yaw_deg": dy, "ms": ms})
    print(f"{f['id']} st{st} {f['light']:6s} kp_on_obj={obj_kp:4d} inl={ninl:3d} t={dt:8.1f} r={dr:5.1f} {ms:.0f}ms",
          flush=True)
json.dump({"args": vars(a), "rows": rows}, open(a.out, "w"), indent=0)
t = np.array([r["t_mm"] for r in rows]); rr = np.array([r["r_deg"] for r in rows])
print(f"KEYPOINTS {a.det}: n={len(rows)} succ={np.mean((t < 10) & (rr < 5)) * 100:.1f}% "
      f"pose found={np.mean([r['ok'] for r in rows]) * 100:.1f}% "
      f"t p50={np.median(t):.1f}mm r p50={np.median(rr):.2f}deg "
      f"kp on object p50={np.median([r['obj_kp'] for r in rows]):.0f} "
      f"ms p50={np.median([r['ms'] for r in rows]):.0f}")
