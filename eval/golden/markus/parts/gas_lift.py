"""Gas lift, guide page 5 (steps 2-3), shown with its protective cap already removed.
Local frame: axis = Z. Lower stem z 0..60 (goes into the base hub), body tube z 60..260,
piston z 260..330 (goes into the tilt mechanism's socket), release pin on top."""
from pipeline.cad.lib import frame, union, cylinder, turned

PARAMS = {"stem_d": 28, "stem_l": 60, "body_d": 50, "body_l": 200, "piston_d": 28, "piston_l": 70}


def build(p):
    z1 = p["stem_l"]
    z2 = z1 + p["body_l"]
    z3 = z2 + p["piston_l"]
    stem = cylinder((0, 0, 0), (0, 0, 1), p["stem_d"], z1)
    body = turned([(p["body_d"] / 2, 0), (p["body_d"] / 2, p["body_l"] - 6), (p["body_d"] / 2 - 4, p["body_l"])],
                  (0, 0, z1), (0, 0, 1))
    ring = cylinder((0, 0, z1 + 70), (0, 0, 1), p["body_d"] + 2, 3)
    ring2 = cylinder((0, 0, z1 + 130), (0, 0, 1), p["body_d"] + 2, 3)
    piston = cylinder((0, 0, z2), (0, 0, 1), p["piston_d"], p["piston_l"])
    solid = union(stem, body, ring, ring2, piston)
    frames = {"foot_a": frame((20, 0, z1), (0, 0, -1)), "foot_b": frame((0, 20, z1), (0, 0, -1)),
              "top_a": frame((10, 0, z3), (0, 0, 1)), "top_b": frame((0, 10, z3), (0, 0, 1))}
    return solid, frames
