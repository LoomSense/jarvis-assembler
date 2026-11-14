"""End slat with the handle cut-out (guide p.6, step 6): the top slat of each box end.

Local frame: as it sits on the box end at X = 0. Outer face on X = 0, inner face on X = t,
length along Y (0..W), width along Z (0..end_slat_w). The cut-out is in the Z = 0 edge
(the lower edge on the finished box, facing the other two slats), centred along the length.
"""
import cadquery as cq
from pipeline.cad.lib import board, drill, frame

PARAMS = {"W": 310, "t": 12, "end_slat_w": 74, "end_screw_inset": 17, "end_screw_edge": 18,
          "hole_d": 4.0, "csk_d": 8.4, "csk_h": 3.2,
          "handle_w": 100, "handle_top_w": 76, "handle_depth": 24}


def build(p):
    t, w, W = p["t"], p["end_slat_w"], p["W"]
    solid = board(0, 0, 0, t, W, w)
    m, a, b, d = W / 2, p["handle_w"] / 2, p["handle_top_w"] / 2, p["handle_depth"]
    cut = (cq.Workplane("YZ").workplane(offset=-1)
           .polyline([(m - a, -1), (m - b, d), (m + b, d), (m + a, -1)]).close().extrude(t + 2))
    solid = solid.cut(cut)
    frames = {}
    i, e = p["end_screw_inset"], p["end_screw_edge"]
    for name, y, z in (("L_lo", i, e), ("L_hi", i, w - e), ("R_hi", W - i, w - e), ("R_lo", W - i, e)):
        frames[f"in_{name}"] = frame((t, y, z), (1, 0, 0))             # against the post
        frames[f"hole_{name}"] = frame((0, y, z), (-1, 0, 0))          # screw head, outer face
        solid = drill(solid, (0, y, z), (-1, 0, 0), p["hole_d"], t + 1)
        csk = cq.Solid.makeCone(p["csk_d"] / 2, p["hole_d"] / 2, p["csk_h"], cq.Vector(0, y, z), cq.Vector(1, 0, 0))
        solid = solid.cut(cq.Workplane(obj=csk))
    return solid, frames
