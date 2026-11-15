"""Upholstered seat cushion, guide pages 6, 10-12 (steps 4-9).
Local frame (= seat sub-assembly frame): underside at z=0, centred, front edge towards -Y."""
from pipeline.cad.lib import cushion, drill, frame

PARAMS = {"seat_w": 500, "seat_d": 480, "seat_h": 90, "hx": 85, "hy_f": -60, "hy_r": 120, "arm_y1": 60, "arm_y2": 120,
          "arm_z": 30}


def build(p):
    w = p["seat_w"]
    solid = cushion(0, 0, 0, w, p["seat_d"], p["seat_h"], corner=60, crown=36)
    frames = {}
    k = 1
    for y in (p["hy_f"], p["hy_r"]):
        for x in (-p["hx"], p["hx"]):
            solid = drill(solid, (x, y, 0), (0, 0, -1), 5, 20)
            frames[f"mech_{k}"] = frame((x, y, 0), (0, 0, -1))
            k += 1
    for side, tag in ((1, "R"), (-1, "L")):
        for i, y in enumerate((p["arm_y1"], p["arm_y2"]), 1):
            o = (side * w / 2, y, p["arm_z"])
            solid = drill(solid, o, (side, 0, 0), 5, 20)
            frames[f"arm_{tag}{i}"] = frame(o, (side, 0, 0))
    return solid, frames
