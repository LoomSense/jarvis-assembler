"""Rear frame, pre-assembled (guide page 7, step 4): two legs, a top rail, a lower rail and two crossed braces.
As wide as the front frame; its legs are about as long as the front legs; the lower rail sits at about a third
of the height. Local frame = the finished ladder's frame, with the frame leaning back as on the open ladder."""
import cadquery as cq
from pipeline.cad.lib import drill, frame, union

PARAMS = {"tx": 22, "Win": 346, "rail_end": 280, "rear_top_z": 478, "rear_run": 72, "rear_T": 36,
          "leaf_s1h": 64, "leaf_s2h": 94, "stay_z": 243.36, "stay_y_rear": 333.55}


def build(p):
    tx, W, re, zt, rr, T = p["tx"], p["Win"], p["rail_end"], p["rear_top_z"], p["rear_run"], p["rear_T"]
    Lr = (rr * rr + zt * zt) ** 0.5
    ty, tz = rr / Lr, -zt / Lr           # down the slope
    ny, nz = zt / Lr, rr / Lr            # from the front face towards the back

    def prism(pts, d0, d1):
        pl = cq.Plane(origin=(0, re + d0 * ny, zt + d0 * nz), xDir=(1, 0, 0), normal=(0, ny, nz))
        return cq.Workplane(pl).polyline(pts).close().extrude(d1 - d0)

    def rect(x0, x1, s0, s1, d0, d1):
        return prism([(x0, s0), (x1, s0), (x1, s1), (x0, s1)], d0, d1)

    x1, x2 = tx - 1, tx + W + 1
    legs = union(rect(0, tx, -15, Lr + 15, 0, T), rect(tx + W, 2 * tx + W, -15, Lr + 15, 0, T))
    rails = union(rect(x1, x2, 15, 60, 8, 28), rect(x1, x2, 300, 345, 8, 28))
    b1 = prism([(x1, 58), (x1 + 30, 58), (x2, 302), (x2 - 30, 302)], 12, 24)
    b2 = prism([(x2, 58), (x2 - 30, 58), (x1, 302), (x1 + 30, 302)], 12, 24)
    solid = union(legs, rails, b1, b2)
    solid = solid.intersect(cq.Workplane("XY").box(3000, 3000, zt, centered=(True, True, False)))
    frames = {}
    for side, x in (("L", tx / 2), ("R", tx + W + tx / 2)):
        for i, s in ((1, p["leaf_s1h"]), (2, p["leaf_s2h"])):
            o = (x, re + s * ty, zt + s * tz)
            solid = drill(solid, o, (0, -ny, -nz), 3, 10)
            frames[f"hinge_{side}_{i}"] = frame(o, (0, -ny, -nz))
    zs, ys = p["stay_z"], p["stay_y_rear"]
    solid = drill(solid, (0, ys, zs), (-1, 0, 0), 3.5, 12)
    solid = drill(solid, (2 * tx + W, ys, zs), (1, 0, 0), 3.5, 12)
    frames["stay_L"] = frame((0, ys, zs), (-1, 0, 0))
    frames["stay_R"] = frame((2 * tx + W, ys, zs), (1, 0, 0))
    return solid, frames
