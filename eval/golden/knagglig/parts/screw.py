"""Pan-head wood screw. Hardware 108363 (long) and 108560 (short), guide page 2.
Local frame: seat (underside of the head) at the origin, shank along +Z."""
from pipeline.cad.lib import frame, screw

PARAMS = {"length": 30, "shank_d": 3.5, "head_d": 7.0, "head_h": 2.8}


def build(p):
    return screw(p["length"], p["shank_d"], p["head_d"], p["head_h"]), {"seat": frame((0, 0, 0), (0, 0, 1))}
