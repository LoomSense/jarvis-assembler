"""Long side panel, pre-assembled: 3 horizontal slats stapled to 2 vertical corner battens (guide p.3 step 1,
lying flat; p.4 step 3). Local frame = finished-box frame for the side at y=0: x along the box length,
y across (outer face at y=0, battens on the inner side), z up. Slats run x in [t, L-t]; the side stands on the
bottom panel's slats at z = bt + t."""
from pipeline.cad.lib import board, drill, frame, union

PARAMS = {"L": 460, "W": 310, "H": 250, "t": 12, "bt": 22, "bw": 34, "gap_side": 12}


def build(p):
    L, H, t, bt, bw, g = p["L"], p["H"], p["t"], p["bt"], p["bw"], p["gap_side"]
    zb = bt + t                      # top of the bottom panel's slats
    hs = H - zb                      # side height
    ws = (hs - 2 * g) / 3            # slat width
    pieces = [board(t, 0, zb + k * (ws + g), L - 2 * t, t, ws) for k in range(3)]
    pieces.append(board(t, t, zb, bw, bt, hs))                 # corner batten at x = t
    pieces.append(board(L - t - bw, t, zb, bw, bt, hs))        # corner batten at x = L - t
    solid = union(*pieces)

    cy = t + bt / 2
    xl, xr = t + bw / 2, L - t - bw / 2
    frames = {
        "post_end_L": frame((xl, cy, zb), (0, 0, -1)),
        "post_end_R": frame((xr, cy, zb), (0, 0, -1)),
    }
    solid = drill(solid, (xl, cy, zb), (0, 0, -1), 3.0, 30)    # pilot holes for the long screws
    solid = drill(solid, (xr, cy, zb), (0, 0, -1), 3.0, 30)
    for k in range(3):
        z0 = zb + k * (ws + g)
        for tag, z in (("lo", z0 + ws / 4), ("hi", z0 + 3 * ws / 4)):
            frames[f"postL_{k + 1}_{tag}"] = frame((t, cy, z), (-1, 0, 0))
            frames[f"postR_{k + 1}_{tag}"] = frame((L - t, cy, z), (1, 0, 0))
            solid = drill(solid, (t, cy, z), (-1, 0, 0), 2.5, 18)
            solid = drill(solid, (L - t, cy, z), (1, 0, 0), 2.5, 18)
    return solid, frames
