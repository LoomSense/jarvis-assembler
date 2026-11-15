"""Armrest loop with padded top, guide page 15 (step 11) and page 1. Two identical pieces.
Local frame: the loop lies in the plane x = 0 and is symmetric about it, so the same piece fits either side.
y and z are the seat unit's y and z (front towards -Y, seat underside z = 0).
A flat foot bar runs along the seat side; two holes through it take the screws into the seat's inserts.
Frames on both faces of the bar: `*_m_*` on the x = -12.5 face, `*_p_*` on the x = +12.5 face."""
from pipeline.cad.lib import board, drill, frame, union, rounded_box, bent_rod

PARAMS = {"bar_t": 25, "arm_y_a": -120, "arm_y_b": 60, "arm_z": 35}


def build(p):
    h = p["bar_t"] / 2
    foot = board(-h, -165, 15, 2 * h, 280, 40)
    loop = bent_rod([(0, 100, 50), (0, 70, 245), (0, -110, 255), (0, -150, 230), (0, -150, 50)], 25)
    pad = rounded_box(-25, -175, 245, 50, 270, 30, r_side=15, r_edge=8)
    solid = union(foot, loop, pad)
    frames = {}
    for tag, y in (("a", p["arm_y_a"]), ("b", p["arm_y_b"])):
        z = p["arm_z"]
        frames[f"side_m_{tag}"] = frame((-h, y, z), (-1, 0, 0))
        frames[f"side_p_{tag}"] = frame((h, y, z), (1, 0, 0))
        solid = drill(solid, (h, y, z), (1, 0, 0), 6.5, 2 * h + 1)
    return solid, frames
