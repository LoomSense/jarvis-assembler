"""KNAGGLIG bottom panel (pre-assembled in the box): slats running along the
length, with two cross battens on top at the ends. Guide page 3, step 1.
Local frame = assembly frame: X length, Y width, Z up, origin at the inner
corner of the long panel on the Y=0 side."""
from pipeline.cad.lib import board, drill, frame, union

PARAMS = {"L": 460, "W": 310, "t": 12, "bot_t": 10, "bot_slats": 4, "bot_gap": 8,
          "bot_inset": 14, "batten_w": 20, "batten_h": 20, "up_y": 28, "pilot_d": 3.0}


def build(p):
    plen = p["L"] - 2 * p["t"]                       # between the two end slats
    y0, y1 = p["bot_inset"], p["W"] - p["bot_inset"]
    n, g = p["bot_slats"], p["bot_gap"]
    ws = (y1 - y0 - (n - 1) * g) / n
    solids = [board(0, y0 + i * (ws + g), 0, plen, ws, p["bot_t"]) for i in range(n)]
    by0, by1 = p["t"] + p["up_y"], p["W"] - p["t"] - p["up_y"]   # battens end at the uprights
    zc = p["bot_t"] + p["batten_h"] / 2
    frames = {}
    for k, x0 in ((1, 0), (2, plen - p["batten_w"])):
        solids.append(board(x0, by0, p["bot_t"], p["batten_w"], by1 - by0, p["batten_h"]))
        xc = x0 + p["batten_w"] / 2
        frames[f"pilot_A{k}"] = frame((xc, by0, zc), (0, -1, 0))
        frames[f"pilot_B{k}"] = frame((xc, by1, zc), (0, 1, 0))
    s = union(*solids)
    for f in frames.values():
        s = drill(s, f["origin"], f["axis"], p["pilot_d"], 25)
    return s, frames
