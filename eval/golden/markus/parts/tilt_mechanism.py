"""Tilt mechanism (steel plate, cast body, gas-lift socket, height lever, tilt paddle, tension knob, rear back mount),
guide pages 6, 10-13 (steps 4-9). Local frame = seat sub-assembly frame: top of the plate at z=0 (touches the seat
underside), seat front towards -Y."""
from pipeline.cad.lib import rounded_box, cylinder, turned, bent_rod, drill, union, frame

PARAMS = {"hx": 85, "hy_f": -60, "hy_r": 120, "back_y": 260}


def build(p):
    plate = rounded_box(-105, -85, -12, 210, 230, 12, r_side=25, r_edge=3)
    body = rounded_box(-60, -40, -60, 120, 120, 56, r_side=20, r_edge=8)
    rear = rounded_box(-45, 130, -70, 90, p["back_y"] - 10 - 130, 64, r_side=6, r_edge=3)
    hub = cylinder((0, 0, -75), (0, 0, 1), 56, 70)
    knob = turned([(0, 0), (14, 0), (25, 18), (30, 42), (26, 66), (14, 82), (0, 86)], (0, -30, -40), (0, -0.6, -0.8))
    lever = bent_rod([(50, 0, -40), (190, -30, -45), (225, -55, -45)], 12)
    lever_pad = rounded_box(210, -100, -52, 30, 55, 12, r_side=10, r_edge=3)
    paddle = bent_rod([(-50, 40, -30), (-180, 55, -30)], 14)
    paddle_pad = rounded_box(-240, 30, -36, 70, 45, 10, r_side=12, r_edge=3)
    solid = union(plate, body, rear, hub, knob, lever, lever_pad, paddle, paddle_pad)
    solid = drill(solid, (0, 0, -75), (0, 0, -1), 29, 55)
    frames = {"socket": frame((0, 0, -20), (0, 0, -1))}
    k = 1
    for tag, y in (("f", p["hy_f"]), ("r", p["hy_r"])):
        for i, x in enumerate((-p["hx"], p["hx"]), 1):
            solid = drill(solid, (x, y, -12), (0, 0, -1), 7, 13)
            frames[f"seat_{k}"] = frame((x, y, 0), (0, 0, 1))
            frames[f"scr_{tag}{i}"] = frame((x, y, -12), (0, 0, -1))
            k += 1
    for i, (x, y) in enumerate(((-22, 230), (22, 230), (0, 180)), 1):
        o = (x, y, -70)
        solid = drill(solid, o, (0, 0, -1), 5, 20)
        frames[f"back_{i}"] = frame(o, (0, 0, -1))
    return solid, frames
