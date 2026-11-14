"""Short countersunk screw 108560 (guide p.2, 24x; used in steps 5-8). The head sinks flush, so the frame
`seat` is at the top of the head (on the board's surface); shank along +Z."""
import cadquery as cq
from pipeline.cad.lib import frame, union

PARAMS = {"length": 25, "shank_d": 3.5, "head_d": 7, "head_h": 2.5}


def build(p):
    L, sd, hd, hh = p["length"], p["shank_d"], p["head_d"], p["head_h"]
    head = cq.Workplane("XY").circle(hd / 2).workplane(offset=hh).circle(sd / 2).loft()
    shank = cq.Workplane("XY").workplane(offset=hh).circle(sd / 2).extrude(L - hh - sd)
    tip = (cq.Workplane("XY").workplane(offset=L - sd).circle(sd / 2)
           .workplane(offset=sd).circle(0.3).loft())
    s = union(head, shank, tip)
    for w, l in ((hd * 0.14, hd * 0.62), (hd * 0.62, hd * 0.14)):
        s = s.cut(cq.Workplane("XY").box(w, l, hh * 0.6, centered=(True, True, False)).translate((0, 0, -0.01)))
    return s, {"seat": frame((0, 0, 0), (0, 0, 1))}
