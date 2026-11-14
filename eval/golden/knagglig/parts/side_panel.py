"""KNAGGLIG long side panel (pre-assembled): three slats on two inner uprights.
Guide page 3, steps 1-2. Local frame: X along the panel, Y from the outer face
(y=0) inwards, Z up from the lower edge of the bottom slat."""
from pipeline.cad.lib import board, drill, frame, union

PARAMS = {"L": 460, "t": 12, "slat_h": 70, "row_gap": 17, "z0": 6, "bot_t": 10,
          "batten_h": 20, "up_x": 20, "up_y": 28, "bolt_d": 5.0, "pilot_d": 2.5, "screw_dz": 18}


def build(p):
    plen = p["L"] - 2 * p["t"]
    rows = [i * (p["slat_h"] + p["row_gap"]) for i in range(3)]
    top = rows[-1] + p["slat_h"]
    up_z0 = p["bot_t"] - p["z0"]                      # uprights start on top of the bottom slats
    solids = [board(0, 0, z, plen, p["t"], p["slat_h"]) for z in rows]
    for x0 in (0, plen - p["up_x"]):
        solids.append(board(x0, p["t"], up_z0, p["up_x"], p["up_y"], top - up_z0))
    s = union(*solids)
    frames = {}
    zb = p["bot_t"] + p["batten_h"] / 2 - p["z0"]     # height of the batten centre line
    for k, xc in ((1, p["up_x"] / 2), (2, plen - p["up_x"] / 2)):
        frames[f"bolt_in_{k}"] = frame((xc, 0, zb), (0, -1, 0))
        frames[f"bolt_out_{k}"] = frame((xc, p["t"] + p["up_y"], zb), (0, 1, 0))
        s = drill(s, (xc, 0, zb), (0, -1, 0), p["bolt_d"], p["t"] + p["up_y"] + 1)
    yc = p["t"] + p["up_y"] / 2
    for e, (x, ax) in ((1, (0, -1)), (2, (plen, 1))):
        for r, z in enumerate(rows, start=1):
            for ab, dz in (("a", -p["screw_dz"]), ("b", p["screw_dz"])):
                f = frame((x, yc, z + p["slat_h"] / 2 + dz), (ax, 0, 0))
                frames[f"end{e}_r{r}{ab}"] = f
                s = drill(s, f["origin"], f["axis"], p["pilot_d"], 16)
    # factory staples holding the slats to the uprights: small marks on the outer face
    for xc in (p["up_x"] / 2, plen - p["up_x"] / 2):
        for z in rows:
            for dz in (-22, 22):
                zc = z + p["slat_h"] / 2 + dz
                if abs(zc - zb) > 8:
                    s = drill(s, (xc, 0, zc), (0, -1, 0), 2.6, 1.2)
    return s, frames
