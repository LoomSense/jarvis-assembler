"""Double-ended dowel screw 115980, guide page 1 (4x): threaded both ends, a collar in the middle.

Local frame: the collar's upper face (the seat) at the origin. One threaded half runs along
+Z (into the piece it is driven into first, the table top); the other half runs along -Z
(the leg is turned onto it). Frame `seat`: origin (0, 0, 0), axis (0, 0, 1).
"""
import cadquery as cq

from pipeline.cad.lib import frame, union

PARAMS = {"half_len": 38, "shank_d": 6.0, "collar_d": 9.0, "collar_h": 1.5}


def _half(length, d, up):
    """Threaded half with a pointed tip, starting at z = 0, going up (+Z) or down (-Z)."""
    body = cq.Workplane("XY").circle(d / 2).extrude(length - d)
    tip = (cq.Workplane("XY").workplane(offset=length - d)
           .circle(d / 2).workplane(offset=d).circle(0.3).loft())
    s = union(body, tip)
    return s if up else s.mirror("XY")


def build(p):
    upper = _half(p["half_len"], p["shank_d"], True)
    lower = _half(p["half_len"], p["shank_d"], False)
    collar = cq.Workplane("XY").workplane(offset=-p["collar_h"]).circle(p["collar_d"] / 2).extrude(p["collar_h"])
    return union(upper, lower, collar), {"seat": frame((0, 0, 0), (0, 0, 1))}
