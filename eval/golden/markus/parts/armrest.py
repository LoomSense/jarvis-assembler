"""Armrest: moulded loop with a pad on top and a mounting plate at the foot, guide page 15 (step 11).
Local frame = seat sub-assembly frame; `side` = +1 for the right-hand armrest (at +X), -1 for the left one (at -X).
The plate's inner face lies on the seat's side at x = side*seat_w/2."""
from pipeline.cad.lib import rounded_box, bent_rod, drill, union, frame

PARAMS = {"side": 1, "seat_w": 500, "arm_y1": 60, "arm_y2": 120, "arm_z": 30}


def build(p):
    s = p["side"]
    x0 = p["seat_w"] / 2
    xr = x0 + 18
    plate = rounded_box(x0 if s > 0 else -x0 - 8, 40, 10, 8, 100, 80, r_side=0, r_edge=2)
    pts = [(40, 75), (-60, 110), (-135, 245), (-110, 300), (150, 300), (180, 260), (140, 140), (140, 75)]
    loop = bent_rod([(s * xr, y, z) for y, z in pts], 24)
    pad = rounded_box(xr - 16 if s > 0 else -xr - 16, -120, 298, 32, 250, 24, r_side=12, r_edge=6)
    solid = union(plate, loop, pad)
    frames = {}
    for i, y in enumerate((p["arm_y1"], p["arm_y2"]), 1):
        solid = drill(solid, (s * (x0 + 8), y, p["arm_z"]), (s, 0, 0), 6.5, 9)
        frames[f"seat_{i}"] = frame((s * x0, y, p["arm_z"]), (-s, 0, 0))
        frames[f"scr_{i}"] = frame((s * (x0 + 8), y, p["arm_z"]), (s, 0, 0))
    return solid, frames
