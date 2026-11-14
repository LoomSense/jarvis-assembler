"""LACK leg, guide page 1 (the square post turned onto the screw in the right-hand circle).

Local frame: the leg stands upright, square section leg_w x leg_w centred on the Z axis,
floor end at z = 0, top end at z = H - T (it meets the table top's underside there).
The top end has a pilot hole for the lower half of the double-ended screw.
Mate frames on the top end (axis +Z, out of the material):
  top      the hole centre
  top_key  a point 10 mm towards +X from it
"""
from pipeline.cad.lib import board, drill, frame

PARAMS = {"H": 450, "T": 50, "leg_w": 40, "pilot_d": 5.0, "pilot_depth": 40}


def build(p):
    h = p["leg_w"] / 2
    z = p["H"] - p["T"]
    solid = board(-h, -h, 0, p["leg_w"], p["leg_w"], z)
    solid = drill(solid, (0, 0, z), (0, 0, 1), p["pilot_d"], p["pilot_depth"])
    frames = {"top": frame((0, 0, z), (0, 0, 1)), "top_key": frame((10, 0, z), (0, 0, 1))}
    return solid, frames
