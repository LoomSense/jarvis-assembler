"""Step board (tread) that sits between the two front legs (guide page 5, step 1). Rounded corners in plan.
About 0.8 of the top board's width; the lower tread is deeper than the upper one.
Local frame = the finished ladder's frame (X across, Y front to back, Z up); the left end is at x = tx."""
from pipeline.cad.lib import drill, frame, rounded_box

PARAMS = {"tx": 22, "Win": 346, "H_under": 480, "run": 140, "wy": 47,
          "z_top": 333, "z_hole": 324, "y_front": 92, "depth": 125, "th": 18}


def build(p):
    tx, W = p["tx"], p["Win"]
    k = p["run"] / p["H_under"]
    z = p["z_hole"]
    yc = z * k + p["wy"] / 2
    solid = rounded_box(tx, p["y_front"], p["z_top"] - p["th"], W, p["depth"], p["th"], 10, 3)
    solid = drill(solid, (tx, yc, z), (-1, 0, 0), 4, 45)
    solid = drill(solid, (tx + W, yc, z), (1, 0, 0), 4, 45)
    frames = {
        "end_L": frame((tx, yc, z), (-1, 0, 0)),
        "end_L_b": frame((tx, yc + 12, z), (-1, 0, 0)),
        "end_R": frame((tx + W, yc, z), (1, 0, 0)),
        "end_R_b": frame((tx + W, yc + 12, z), (1, 0, 0)),
    }
    return solid, frames
