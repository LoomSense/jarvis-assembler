"""Five-star base, guide pages 4-5 (steps 1 and 3).
Local frame: hub axis on Z, z=0 = underside of the arms, first arm points to -Y (chair front)."""
import math
from pipeline.cad.lib import rounded_box, cylinder, drill, union, frame

PARAMS = {"arm_r": 300, "arm_len": 325, "arm_w": 50, "arm_h": 40, "hub_d": 80, "hub_top": 60,
          "socket_d": 50, "caster_hole_d": 11}


def build(p):
    arm = rounded_box(20, -p["arm_w"] / 2, 0, p["arm_len"] - 20, p["arm_w"], p["arm_h"], r_side=20, r_edge=6)
    hub = cylinder((0, 0, -10), (0, 0, 1), p["hub_d"], p["hub_top"] + 10)
    solid = hub
    frames = {}
    for k in range(5):
        ang = -90 + 72 * k
        solid = union(solid, arm.rotate((0, 0, 0), (0, 0, 1), ang))
    solid = drill(solid, (0, 0, p["hub_top"]), (0, 0, 1), p["socket_d"], p["hub_top"])
    frames["socket"] = frame((0, 0, 0), (0, 0, 1))
    for k in range(5):
        a = math.radians(-90 + 72 * k)
        c, s = math.cos(a), math.sin(a)
        r = p["arm_r"]
        solid = drill(solid, (r * c, r * s, 0), (0, 0, -1), p["caster_hole_d"], 25)
        frames[f"caster_{k + 1}"] = frame((r * c, r * s, 0), (0, 0, -1))
        frames[f"caster_{k + 1}_in"] = frame(((r - 15) * c, (r - 15) * s, 0), (0, 0, -1))
    return solid, frames
