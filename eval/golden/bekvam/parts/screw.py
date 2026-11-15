"""Screw (guide pages 3 and 4: 100215, 122824, 109180). Seat (underside of the head) at the origin, shank +Z."""
from pipeline.cad.lib import frame, screw

PARAMS = {"length": 16, "shank_d": 5, "head_d": 9, "head_h": 3.5}


def build(p):
    return screw(p["length"], p["shank_d"], p["head_d"], p["head_h"]), {"seat": frame((0, 0, 0), (0, 0, 1))}
