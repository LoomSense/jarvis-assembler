"""Gas lift, guide page 5 (steps 2-3). Local frame: bottom of the body at z=0, axis +Z."""
from pipeline.cad.lib import turned, frame

PARAMS = {"body_d": 49, "body_len": 230, "piston_d": 28, "top": 330}


def build(p):
    R, rp = p["body_d"] / 2, p["piston_d"] / 2
    L = p["body_len"]
    prof = [(0, 0), (R - 1, 0), (R, 1), (R, L * 0.55), (R + 0.6, L * 0.56), (R + 0.6, L - 4), (R - 2, L),
            (rp, L), (rp, 300), (11, p["top"] - 2), (9, p["top"]), (0, p["top"])]
    solid = turned(prof)
    frames = {"bottom": frame((0, 0, 0), (0, 0, -1)), "top": frame((0, 0, p["top"]), (0, 0, 1))}
    return solid, frames
