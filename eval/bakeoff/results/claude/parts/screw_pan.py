"""Long pan-head screw 108363 (guide p.2). Seat (underside of the head) at the origin, shank along +Z."""
from pipeline.cad.lib import frame, screw

PARAMS = {"length": 60, "shank_d": 4.5, "head_d": 9, "head_h": 3.5}


def build(p):
    return screw(p["length"], p["shank_d"], p["head_d"], p["head_h"]), {"seat": frame((0, 0, 0), (0, 0, 1))}
