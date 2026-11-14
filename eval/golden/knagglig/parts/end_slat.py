"""KNAGGLIG end slat: a loose board screwed across the ends of the two side
panels (guide pages 5-8). With handle=True the lower edge has the grip cut-out
seen on the top slat of each end. Local frame: X along the slat, Y from the
outer face (y=0) inwards, Z up."""
import cadquery as cq

from pipeline.cad.lib import board, drill, frame

PARAMS = {"W": 310, "t": 12, "slat_h": 70, "up_y": 28, "hole_d": 4.2, "screw_dz": 18,
          "handle": False, "handle_w": 120, "handle_d": 24}


def build(p):
    s = board(0, 0, 0, p["W"], p["t"], p["slat_h"])
    if p["handle"]:
        w, d, c = p["handle_w"], p["handle_d"], p["W"] / 2
        notch = (cq.Workplane("XZ").polyline([(c - w / 2 - d * 0.6, -1), (c - w / 2, d), (c + w / 2, d),
                                              (c + w / 2 + d * 0.6, -1)]).close().extrude(-p["t"] - 2)
                 .translate((0, -1, 0)))
        s = s.cut(notch)
    frames = {}
    xo = p["t"] + p["up_y"] / 2                       # screw line sits over the upright centre
    for side, x in (("L", xo), ("R", p["W"] - xo)):
        for ab, dz in (("a", -p["screw_dz"]), ("b", p["screw_dz"])):
            z = p["slat_h"] / 2 + dz
            frames[f"in_{side}_{ab}"] = frame((x, 0, z), (0, -1, 0))
            frames[f"out_{side}_{ab}"] = frame((x, p["t"], z), (0, 1, 0))
            s = drill(s, (x, 0, z), (0, -1, 0), p["hole_d"], p["t"] + 1)
    return s, frames
