"""Top board (seat) with rounded corners (guide page 6, step 3). It is the widest part: the full 430 mm width,
and about half as deep as it is wide. The label is on its underside.
Local frame = the finished ladder's frame; the underside is at z = H_under."""
from pipeline.cad.lib import drill, frame, rounded_box

PARAMS = {"tx": 22, "Win": 346, "H_under": 480, "rail_y1": 200, "rail_y2": 260,
          "top_w": 430, "top_y0": 110, "top_d": 230, "top_t": 20}


def build(p):
    tx, W, H = p["tx"], p["Win"], p["H_under"]
    x0 = (2 * tx + W - p["top_w"]) / 2
    solid = rounded_box(x0, p["top_y0"], H, p["top_w"], p["top_d"], p["top_t"], 14, 4)
    frames = {}
    for side, x in (("L", tx / 2), ("R", tx + W + tx / 2)):
        for n, y in (("a", p["rail_y1"]), ("b", p["rail_y2"])):
            solid = drill(solid, (x, y, H), (0, 0, -1), 3, 11)
            frames[f"under_{side}_{n}"] = frame((x, y, H), (0, 0, -1))
    return solid, frames
