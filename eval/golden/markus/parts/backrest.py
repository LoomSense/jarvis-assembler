"""High backrest with headrest and its steel mounting bar (one pre-assembled piece),
guide pages 12-13 (steps 7-9), page 1 and 14 for its outline.
Local frame: the SEAT UNIT's frame (seat underside z = 0, front towards -Y).
The bar's bracket plate hangs below the seat behind it; its front face (y = recv_y) lies against the
tilt mechanism's receiver and takes three screws from behind (step 8)."""
from pipeline.cad.lib import board, drill, frame, union, rounded_box

PARAMS = {"recv_y": 265, "plate_t": 10, "back_w": 480, "back_h": 620, "back_t": 70, "back_z0": 110}


def build(p):
    ry, pt = p["recv_y"], p["plate_t"]
    plate = board(-45, ry, -70, 90, pt, 65)
    bar = board(-30, ry, -5, 60, 20, p["back_z0"] + 20)
    w, h, t, z0 = p["back_w"], p["back_h"], p["back_t"], p["back_z0"]
    back = rounded_box(-w / 2, ry, z0, w, t, h, r_side=30, r_edge=20)
    # waist: the drawing shows the back narrower just above the seat
    lumbar = rounded_box(-w / 2 + 25, ry + 5, z0 - 40, w - 50, t - 10, 60, r_side=25, r_edge=15)
    neck = board(-70, ry + 20, z0 + h - 10, 140, 35, 30)
    head = rounded_box(-175, ry - 10, z0 + h + 15, 350, 85, 125, r_side=25, r_edge=20)
    solid = union(plate, bar, back, lumbar, neck, head)
    frames = {}
    for name, x, z in (("a", 20, -20), ("b", -20, -20), ("c", 0, -50)):
        frames["mount_" + name] = frame((x, ry, z), (0, -1, 0))
        frames["scr_" + name] = frame((x, ry + pt, z), (0, 1, 0))
        solid = drill(solid, (x, ry + pt, z), (0, 1, 0), 8.5, pt + 1)
    return solid, frames
