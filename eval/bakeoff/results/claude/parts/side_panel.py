"""Long side panel, pre-assembled: 3 lengthwise slats stapled to 2 square posts (guide p.3, step 1, lying flat).

Local frame = finished-box frame: X along the box length, Y across the width, Z up.
This instance is the side at Y = 0: slats' outer face on Y = 0, posts on the inside (+Y),
post ends at the bottom stand on the bottom panel's boards.
"""
from pipeline.cad.lib import board, drill, frame, union

PARAMS = {"L": 460, "H": 250, "t": 12, "post": 22, "batten_t": 12, "side_slat_w": 70, "side_gap": 8,
          "post_drop": 6, "end_slat_w": 74, "end_gap": 8, "end_screw_inset": 17, "end_screw_edge": 18,
          "long_pilot_d": 3.0, "long_pilot_depth": 40, "short_pilot_d": 2.5, "short_pilot_depth": 20}


def build(p):
    t, post = p["t"], p["post"]
    z0 = p["batten_t"] + t                      # top of the bottom boards
    x0, x1 = t, p["L"] - t                      # end slats cover the slat ends
    pieces = [board(x0, 0, z0 + i * (p["side_slat_w"] + p["side_gap"]), x1 - x0, t, p["side_slat_w"]) for i in range(3)]
    post_len = p["H"] - z0 - p["post_drop"]
    pieces += [board(x0, t, z0, post, post, post_len), board(x1 - post, t, z0, post, post, post_len)]
    solid = union(*pieces)

    frames = {}
    c = t + post / 2                            # post centre line (same in X from the end and in Y)
    for name, x in (("post_end_1", c), ("post_end_2", p["L"] - c)):
        frames[name] = frame((x, c, z0), (0, 0, -1))
        solid = drill(solid, (x, c, z0), (0, 0, -1), p["long_pilot_d"], p["long_pilot_depth"])
    # where the end slats' screws enter the posts: 2 per slat, 3 slats, on each post's end-facing face
    y = p["end_screw_inset"]
    k = 0
    for i in range(3):
        base = p["batten_t"] + i * (p["end_slat_w"] + p["end_gap"])
        for z in (base + p["end_screw_edge"], base + p["end_slat_w"] - p["end_screw_edge"]):
            k += 1
            for side, x, ax in (("A", x0, (-1, 0, 0)), ("B", x1, (1, 0, 0))):
                frames[f"endface_{side}_{k}"] = frame((x, y, z), ax)
                solid = drill(solid, (x, y, z), ax, p["short_pilot_d"], p["short_pilot_depth"])
    return solid, frames
