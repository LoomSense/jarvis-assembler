"""Upholstered seat, guide pages 6, 10-11 (steps 4-6, drawn upside down) and 15 (step 11).
Local frame (= the seat unit's frame): seat underside at z = 0, padded top up +Z, centred on x = y = 0,
front edge towards -Y, back towards +Y, width along X.
Underside: threaded holes for the tilt mechanism (2 front, 2 rear).
Sides: two threaded inserts per side for the armrests (step 11)."""
from pipeline.cad.lib import frame, drill, cushion

PARAMS = {"seat_w": 500, "seat_d": 480, "seat_h": 90,
          "mx": 85, "my_f": -130, "my_r": 125, "arm_y_a": -120, "arm_y_b": 60, "arm_z": 35}


def build(p):
    w, d = p["seat_w"], p["seat_d"]
    solid = cushion(0, 0, 0, w, d, p["seat_h"], corner=80, crown=30)
    frames = {}
    for name, x, y in (("mech_front_L", -p["mx"], p["my_f"]), ("mech_front_R", p["mx"], p["my_f"]),
                       ("mech_rear_L", -p["mx"], p["my_r"]), ("mech_rear_R", p["mx"], p["my_r"])):
        frames[name] = frame((x, y, 0), (0, 0, -1))
        solid = drill(solid, (x, y, 0), (0, 0, -1), 6, 20)
    for side, sx in (("R", 1), ("L", -1)):
        for tag, y in (("a", p["arm_y_a"]), ("b", p["arm_y_b"])):
            o = (sx * w / 2, y, p["arm_z"])
            frames[f"arm_{side}_{tag}"] = frame(o, (sx, 0, 0))
            solid = drill(solid, o, (sx, 0, 0), 5, 20)
    return solid, frames
