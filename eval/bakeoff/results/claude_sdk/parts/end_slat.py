"""Plain end slat, a loose board screwed across the end of the box into the two corner battens (guide p.5 step 5,
p.7 step 7). Local frame: thickness along x (outer face x=0, inner face x=t), length along y (0..W),
width along z (0..ws). Screw holes 2 per end, on the corner battens' centre line."""
from pipeline.cad.lib import board, drill, frame

PARAMS = {"W": 310, "H": 250, "t": 12, "bt": 22, "bw": 34, "gap_side": 12}


def build(p):
    W, H, t, bt, g = p["W"], p["H"], p["t"], p["bt"], p["gap_side"]
    ws = (H - bt - t - 2 * g) / 3
    solid = board(0, 0, 0, t, W, ws)
    cy = t + bt / 2
    frames = {}
    for i, y in ((1, cy), (2, W - cy)):
        for tag, z in (("lo", ws / 4), ("hi", 3 * ws / 4)):
            frames[f"in{i}_{tag}"] = frame((t, y, z), (1, 0, 0))
            frames[f"out{i}_{tag}"] = frame((0, y, z), (-1, 0, 0))
            solid = drill(solid, (0, y, z), (-1, 0, 0), 4.0, t + 1)
    return solid, frames
