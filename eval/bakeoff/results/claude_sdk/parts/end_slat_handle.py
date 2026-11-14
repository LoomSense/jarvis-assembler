"""End slat with hand grip: the top slat of each end, with a cut-out in its lower edge (guide p.6 step 6,
p.8 step 8, cover). Local frame as end_slat: outer face x=0, inner face x=t, length along y, width along z;
the grip is cut into the z=0 edge, centred."""
import cadquery as cq
from pipeline.cad.lib import board, drill, frame

PARAMS = {"W": 310, "H": 250, "t": 12, "bt": 22, "bw": 34, "gap_side": 12, "grip_w": 110, "grip_d": 22}


def build(p):
    W, H, t, bt, g = p["W"], p["H"], p["t"], p["bt"], p["gap_side"]
    ws = (H - bt - t - 2 * g) / 3
    solid = board(0, 0, 0, t, W, ws)
    cut = cq.Workplane("XY").box(t + 2, p["grip_w"], p["grip_d"] + 1, centered=False).translate(
        (-1, W / 2 - p["grip_w"] / 2, -1))
    solid = solid.cut(cut)
    cy = t + bt / 2
    frames = {}
    for i, y in ((1, cy), (2, W - cy)):
        for tag, z in (("lo", ws / 4), ("hi", 3 * ws / 4)):
            frames[f"in{i}_{tag}"] = frame((t, y, z), (1, 0, 0))
            frames[f"out{i}_{tag}"] = frame((0, y, z), (-1, 0, 0))
            solid = drill(solid, (0, y, z), (-1, 0, 0), 4.0, t + 1)
    return solid, frames
