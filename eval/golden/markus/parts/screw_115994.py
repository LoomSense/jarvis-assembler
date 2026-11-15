"""Screw 115994: M8 hex-socket flange bolt with serrated flange, guide page 3 (5x).
Seat (underside of the flange) at the origin, shank along +Z, hex head towards -Z."""
from pipeline.cad.lib import frame, union, cylinder
import cadquery as cq

PARAMS = {"length": 16, "shank_d": 8, "flange_d": 17, "hex_d": 13, "head_h": 7}


def build(p):
    shank = cylinder((0, 0, 0), (0, 0, 1), p["shank_d"], p["length"])
    flange = cylinder((0, 0, -1.5), (0, 0, 1), p["flange_d"], 1.5)
    hexhead = (cq.Workplane("XY").workplane(offset=-p["head_h"]).polygon(6, p["hex_d"] / 0.866)
               .extrude(p["head_h"] - 1.5))
    sock = cq.Workplane("XY").workplane(offset=-p["head_h"] - 0.1).polygon(6, 5).extrude(3)
    s = union(shank, flange, hexhead).cut(sock)
    return s, {"seat": frame((0, 0, 0), (0, 0, 1))}
