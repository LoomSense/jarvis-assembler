"""Machine screw (hex socket), guide page 3. Seat (underside of the head) at the origin, shank along +Z."""
from pipeline.cad.lib import screw, frame

PARAMS = {"length": 20, "shank_d": 6, "head_d": 12, "head_h": 3.5}


def build(p):
    solid = screw(p["length"], p["shank_d"], p["head_d"], p["head_h"], recess=False)
    return solid, {"seat": frame((0, 0, 0), (0, 0, 1))}
