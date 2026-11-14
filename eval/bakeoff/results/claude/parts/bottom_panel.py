"""Bottom panel, pre-assembled: 4 lengthwise boards stapled to 2 cross battens (guide p.3, step 1, standing).

Local frame = finished-box frame: X along the box length, Y across the width, Z up.
Battens are underneath (Z 0..batten_t) and act as feet; the boards lie on them.
The long screws go up through batten + board into the ends of the side panels' posts.
"""
from pipeline.cad.lib import board, drill, frame, union

PARAMS = {"L": 460, "W": 310, "t": 12, "post": 22, "batten_t": 12, "batten_w": 18, "batten_inset": 5,
          "board_gap": 4, "long_hole_d": 5.0}


def build(p):
    t, bt = p["t"], p["batten_t"]
    x0, x1 = t, p["L"] - t                      # end slats cover the board ends
    bw = (p["W"] - 3 * p["board_gap"]) / 4
    pieces = [board(x0, i * (bw + p["board_gap"]), bt, x1 - x0, bw, t) for i in range(4)]
    c = t + p["post"] / 2                       # post centre line
    for x in (c, p["L"] - c):
        pieces.append(board(x - p["batten_w"] / 2, p["batten_inset"], 0, p["batten_w"], p["W"] - 2 * p["batten_inset"], bt))
    solid = union(*pieces)
    frames = {}
    for name, x, y in (("A1", c, c), ("A2", p["L"] - c, c), ("B1", p["L"] - c, p["W"] - c), ("B2", c, p["W"] - c)):
        frames[f"seat_{name}"] = frame((x, y, bt + t), (0, 0, 1))      # a post end sits here, on the boards
        frames[f"hole_{name}"] = frame((x, y, 0), (0, 0, -1))          # the screw head seats here, under the batten
        solid = drill(solid, (x, y, 0), (0, 0, -1), p["long_hole_d"], bt + t + 1)
    return solid, frames
