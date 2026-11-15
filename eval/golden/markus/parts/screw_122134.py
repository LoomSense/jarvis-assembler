"""Screw 122134 / 10005843: M6 button-head hex-socket machine screw, guide page 3 (6x).
Seat (underside of the head) at the origin, shank along +Z."""
from pipeline.cad.lib import screw, frame

PARAMS = {"length": 30, "shank_d": 6, "head_d": 13, "head_h": 3.5}


def build(p):
    s = screw(p["length"], p["shank_d"], p["head_d"], p["head_h"], recess=False)
    return s, {"seat": frame((0, 0, 0), (0, 0, 1))}
