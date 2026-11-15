"""Five-star base, guide pages 4-5 (steps 1 and 3).
Local frame: hub axis = Z, underside of the arms at z = 0, hub top at z = hub_h.
Arm k points at angle -90 + 72k degrees (arm 1 towards -Y, the chair's front).
Caster sockets on the arm undersides near the tips; the gas-lift hole runs through the hub."""
import math
import cadquery as cq
from pipeline.cad.lib import drill, frame, union, cylinder, radial

PARAMS = {"arm_r": 315, "arm_w": 50, "arm_h": 35, "hub_d": 90, "hub_h": 60,
          "caster_r": 300, "lift_hole_d": 28.6, "stem_hole_d": 11.4}


def build(p):
    w2 = p["arm_w"] / 2
    # arm tapering in plan from the hub to the tip, with a rounded tip end
    arm = (cq.Workplane("XY").polyline([(0, -w2 - 10), (p["arm_r"] - w2, -w2), (p["arm_r"] - w2, w2), (0, w2 + 10)])
           .close().extrude(p["arm_h"]))
    arm = union(arm, cylinder((p["arm_r"] - w2, 0, 0), (0, 0, 1), p["arm_w"], p["arm_h"]))
    arm = arm.rotate((0, 0, 0), (0, 0, 1), -90)
    solid = radial(arm, 5)
    solid = union(solid, cylinder((0, 0, 0), (0, 0, 1), p["hub_d"], p["hub_h"]))
    solid = drill(solid, (0, 0, p["hub_h"]), (0, 0, 1), p["lift_hole_d"], p["hub_h"] + 1)
    frames = {"lift_a": frame((20, 0, p["hub_h"]), (0, 0, 1)),
              "lift_b": frame((0, 20, p["hub_h"]), (0, 0, 1))}
    for k in range(5):
        a = math.radians(-90 + 72 * k)
        u = (math.cos(a), math.sin(a))
        r = p["caster_r"]
        o = (r * u[0], r * u[1], 0)
        solid = drill(solid, o, (0, 0, -1), p["stem_hole_d"], 25)
        frames[f"caster_{k + 1}"] = frame(o, (0, 0, -1))
        frames[f"caster_{k + 1}_b"] = frame(((r + 6) * u[0], (r + 6) * u[1], 0), (0, 0, -1))
    return solid, frames
