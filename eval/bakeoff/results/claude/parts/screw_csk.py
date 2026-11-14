"""Short countersunk screw 108560 (guide p.2). The flat top of the head is at the origin (flush with the
wood surface when driven home); the conical head and the shank run along +Z."""
import cadquery as cq
from pipeline.cad.lib import frame, union

PARAMS = {"length": 30, "shank_d": 3.5, "head_d": 8, "head_h": 3}


def build(p):
    L, d, D, h = p["length"], p["shank_d"], p["head_d"], p["head_h"]
    head = cq.Workplane("XY").circle(D / 2).workplane(offset=h).circle(d / 2).loft()
    shank = cq.Workplane("XY").workplane(offset=h).circle(d / 2).extrude(L - h - d)
    tip = cq.Workplane("XY").workplane(offset=L - d).circle(d / 2).workplane(offset=d).circle(0.3).loft()
    s = union(head, shank, tip)
    for a, b in ((D * 0.14, D * 0.6), (D * 0.6, D * 0.14)):          # cross recess
        s = s.cut(cq.Workplane("XY").box(a, b, h * 0.5, centered=(True, True, False)).translate((0, 0, -0.01)))
    return s, {"seat": frame((0, 0, 0), (0, 0, 1))}
