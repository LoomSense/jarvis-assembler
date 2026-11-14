"""Bottom panel, pre-assembled: 4 slats along the length stapled to 2 cross battens underneath, which act as feet
(guide p.3 step 1 standing; p.5 step 5 seen from outside with the 4 long screw heads at its corners).
Local frame = finished-box frame: x along the length, y across, z up; battens z in [0, bt], slats z in [bt, bt+t]."""
from pipeline.cad.lib import board, drill, frame, union

PARAMS = {"L": 460, "W": 310, "H": 250, "t": 12, "bt": 22, "bw": 34, "gap_bottom": 10}


def build(p):
    L, W, t, bt, bw, g = p["L"], p["W"], p["t"], p["bt"], p["bw"], p["gap_bottom"]
    wb = (W - 3 * g) / 4
    zb = bt + t
    pieces = [board(0, i * (wb + g), bt, L, wb, t) for i in range(4)]
    pieces.append(board(t, 0, 0, bw, W, bt))
    pieces.append(board(L - t - bw, 0, 0, bw, W, bt))
    solid = union(*pieces)

    cy = t + bt / 2
    xl, xr = t + bw / 2, L - t - bw / 2
    spots = {"A1": (xl, cy), "A2": (xr, cy), "B1": (xr, W - cy), "B2": (xl, W - cy)}
    frames = {}
    for k, (x, y) in spots.items():
        frames[f"seat_{k}"] = frame((x, y, zb), (0, 0, 1))        # where a side's corner batten stands
        frames[f"screw_{k}"] = frame((x, y, 0), (0, 0, -1))       # long screw goes in from underneath
        solid = drill(solid, (x, y, 0), (0, 0, -1), 4.5, zb + 1)
    return solid, frames
