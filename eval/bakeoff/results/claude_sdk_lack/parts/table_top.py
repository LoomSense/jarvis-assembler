"""LACK table top, guide page 1 (drawn upside down on a soft surface; corner shown in the zoom circles).

Local frame: the top lies flat as in use, min corner at the origin, X and Y along the
edges, the underside at z = 0 and the top face at z = T. A leg sits flush in each
corner. At each leg position there is a pilot hole in the underside for the
double-ended screw, and two mate frames on the underside (axis -Z, out of the material):
  leg_<k>      the hole centre (screw seat and leg centre)
  leg_<k>_key  a point 10 mm towards +X from it, so the leg cannot spin
"""
from pipeline.cad.lib import board, drill, frame

PARAMS = {"L": 550, "W": 550, "T": 50, "leg_w": 40, "pilot_d": 5.0, "pilot_depth": 40}


def build(p):
    solid = board(0, 0, 0, p["L"], p["W"], p["T"])
    h = p["leg_w"] / 2
    corners = [(h, h), (p["L"] - h, h), (p["L"] - h, p["W"] - h), (h, p["W"] - h)]
    frames = {}
    for k, (x, y) in enumerate(corners, start=1):
        solid = drill(solid, (x, y, 0), (0, 0, -1), p["pilot_d"], p["pilot_depth"])
        frames[f"leg_{k}"] = frame((x, y, 0), (0, 0, -1))
        frames[f"leg_{k}_key"] = frame((x + 10, y, 0), (0, 0, -1))
    return solid, frames
