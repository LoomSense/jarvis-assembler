"""Long pan-head screw 108363 (guide p.2, 4x; used p.3 step 2 and p.4 step 4). Seat at origin, shank along +Z."""
from pipeline.cad.lib import frame, screw

PARAMS = {"length": 60, "shank_d": 4.5, "head_d": 10, "head_h": 3.5}


def build(p):
    s = screw(p["length"], p["shank_d"], p["head_d"], p["head_h"])
    return s, {"seat": frame((0, 0, 0), (0, 0, 1))}
