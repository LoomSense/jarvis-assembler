"""Debug: fitness of the edge model at the GT pose; draws samples (green inlier,
red outlier) over the Canny edges of the working level."""
import sys

import cv2
import numpy as np

from common import load_frames
from edge_tracker import build_levels, evaluate, fitness
from model import load_state, visible_samples

root, out = sys.argv[1], sys.argv[2]
ids = sys.argv[3].split(",")
fr = {f["id"]: f for f in load_frames(root)}
tiles = []
for i in ids:
    f = fr[i]
    img = cv2.imread(f"{root}/img/{i}.jpg")
    K = np.array(f["K"]); T_cw = np.linalg.inv(np.array(f["T_world_cam"])); T_wo = np.array(f["T_world_obj"])
    m = load_state(f["state"])
    lev = build_levels(img, K)[0]
    idx, cont = visible_samples(m, lev.K, T_cw @ T_wo, lev.w, lev.h)
    c, inl, n = fitness(lev, m, T_cw, T_wo, idx, cont)
    d, inside = evaluate(lev, m, T_cw, T_wo, idx)
    from edge_tracker import _proj
    uv = _proj(lev.K, T_cw @ T_wo, m.P[idx])
    vis = cv2.cvtColor((lev.edges * 120).astype(np.uint8), cv2.COLOR_GRAY2BGR)
    for (u, v), dd, cc in zip(uv, d, cont):
        cv2.circle(vis, (int(u), int(v)), 2 if cc else 1, (0, 255, 0) if dd < 2 else (0, 0, 255), -1)
    cv2.putText(vis, f"{i} c={c:.2f} inl={inl:.2f} n={n}", (10, 25), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
    tiles.append(vis)
    print(i, c, inl, n)
while len(tiles) % 2:
    tiles.append(np.zeros_like(tiles[0]))
cv2.imwrite(out, np.vstack([np.hstack(tiles[k:k + 2]) for k in range(0, len(tiles), 2)]))
