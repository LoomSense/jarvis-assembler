"""Contact sheet: dataset frames with the GT model edges projected (sanity check)."""
import json
import sys

import cv2
import numpy as np

from model import load_state, project

root, out = sys.argv[1], sys.argv[2]
ids = sys.argv[3].split(",") if len(sys.argv) > 3 else None
rows = [json.loads(l) for l in open(f"{root}/frames.jsonl")]
if ids:
    rows = [r for r in rows if r["id"] in ids]
else:
    rows = rows[:: max(1, len(rows) // 12)][:12]
tiles = []
for r in rows:
    img = cv2.imread(f"{root}/img/{r['id']}.jpg")
    m = load_state(r["state"])
    T_wc = np.array(r["T_world_cam"]); T_wo = np.array(r["T_world_obj"])
    T_co = np.linalg.inv(T_wc) @ T_wo
    K = np.array(r["K"])
    for a, b in m.edges:
        pa, pb = project(K, T_co, m.V[[a, b]])
        cv2.line(img, tuple(int(x) for x in pa), tuple(int(x) for x in pb), (0, 0, 255), 1)
    cv2.putText(img, f"{r['id']} st{r['state']} {r['light']} hand={r['hand']}", (10, 40),
                cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 255, 255), 2)
    tiles.append(cv2.resize(img, (640, 480)))
while len(tiles) % 3:
    tiles.append(np.zeros_like(tiles[0]))
sheet = np.vstack([np.hstack(tiles[i:i + 3]) for i in range(0, len(tiles), 3)])
cv2.imwrite(out, sheet)
