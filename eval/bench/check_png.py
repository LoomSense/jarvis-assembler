"""python check_png.py A.png ... -> per-image pixel stats (mean, std, fraction non-black)."""
import json, sys
import numpy as np
from PIL import Image
for p in sys.argv[1:]:
    a = np.asarray(Image.open(p).convert("RGB")).astype(float)
    lum = a.mean(axis=2)
    print(json.dumps({"file": p.split("/")[-1], "mean": round(lum.mean(), 1), "std": round(lum.std(), 1),
                      "frac_nonblack": round(float((lum > 8).mean()), 3), "non_black": bool(lum.max() > 8 and lum.std() > 1)}))
