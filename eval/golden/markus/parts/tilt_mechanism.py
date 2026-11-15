"""Tilt mechanism with height lever, tilt-lock lever, tension knob and backrest receiver,
guide pages 6, 10-12 (steps 4-8).
Local frame: the SEAT UNIT's frame (top plate's upper face at z = 0 lies against the seat underside,
front towards -Y). The gas-lift socket opens downwards at (0, -40); the backrest receiver sticks out
behind the seat, its rear face at y = recv_y, where the backrest bracket is screwed on (step 8)."""
from pipeline.cad.lib import board, drill, frame, union, cylinder, rounded_box, bent_rod, turned

PARAMS = {"mx": 85, "my_f": -130, "my_r": 125, "plate_t": 12, "recv_y": 265, "sock_y": -40}


def build(p):
    t = p["plate_t"]
    ry = p["recv_y"]
    sy = p["sock_y"]
    plate = board(-110, -150, -t, 220, 290, t)
    body = rounded_box(-65, -110, -60, 130, 230, 60 - t, r_side=15, r_edge=0)
    hub = cylinder((0, sy, -75), (0, 0, 1), 60, 75 - t)
    arm = board(-40, 120, -60, 80, ry - 120, 60 - t)
    knob = turned([(18, 0), (26, 15), (26, 40), (14, 60)], (0, 70, -60), (0, 0, -1))
    lever_r = bent_rod([(60, -60, -40), (200, -60, -40), (225, -95, -40)], 12)
    pad_r = rounded_box(205, -130, -45, 40, 40, 10, r_side=12, r_edge=3)
    lever_l = bent_rod([(-60, 40, -40), (-200, 40, -40), (-225, 5, -40)], 12)
    pad_l = rounded_box(-245, -30, -45, 40, 40, 10, r_side=12, r_edge=3)
    solid = union(plate, body, hub, arm, knob, lever_r, pad_r, lever_l, pad_l)
    solid = drill(solid, (0, sy, -75), (0, 0, -1), 28.6, 30)
    frames = {"socket_a": frame((10, sy, -45), (0, 0, -1)), "socket_b": frame((0, sy + 10, -45), (0, 0, -1))}
    for name, x, y in (("front_L", -p["mx"], p["my_f"]), ("front_R", p["mx"], p["my_f"]),
                       ("rear_L", -p["mx"], p["my_r"]), ("rear_R", p["mx"], p["my_r"])):
        frames["seat_" + name] = frame((x, y, 0), (0, 0, 1))          # against the seat underside
        frames["scr_" + name] = frame((x, y, -t), (0, 0, -1))         # screw head side
        solid = drill(solid, (x, y, -t), (0, 0, -1), 8.5, t + 1)
    for name, x, z in (("a", 20, -20), ("b", -20, -20), ("c", 0, -50)):
        frames["recv_" + name] = frame((x, ry, z), (0, 1, 0))
        solid = drill(solid, (x, ry, z), (0, 1, 0), 7, 15)
    return solid, frames
