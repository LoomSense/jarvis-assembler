"""Front leg with its short top rail (guide page 5, step 1). One piece: a long slanted leg mitred to a short
rail that runs front-to-back under the top board. The rail is about a quarter of the leg's length.
Local frame = the finished ladder's frame: X across the width (this leg's outer face at x = 0 when it is the
left leg), Y from front to back, Z up, floor at z = 0. The shape is symmetric about its mid-plane, so the same
part serves as left and right leg; every side hole has a frame on both faces (_xm at x = 0, _xp at x = tx)."""
import cadquery as cq
from pipeline.cad.lib import drill, frame

PARAMS = {"tx": 22, "H_under": 480, "run": 140, "wy": 47, "rail_h": 45, "rail_end": 280,
          "z_up": 324, "z_lo": 158, "stay_z": 243.36, "stay_y_front": 94.48,
          "hinge_d1": 25, "hinge_d2": 60, "rail_y1": 200, "rail_y2": 260}


def build(p):
    tx, H, run, wy = p["tx"], p["H_under"], p["run"], p["wy"]
    k = run / H
    zr = H - p["rail_h"]
    re = p["rail_end"]
    pts = [(0, 0), (wy, 0), (wy + zr * k, zr), (re, zr), (re, H), (run, H)]
    solid = cq.Workplane("YZ").polyline(pts).close().extrude(tx)
    frames = {}
    # tread screw holes, through the leg along X
    for name, z in (("upper", p["z_up"]), ("lower", p["z_lo"])):
        yc = z * k + wy / 2
        solid = drill(solid, (0, yc, z), (-1, 0, 0), 7, tx + 1)
        frames[f"{name}_xm"] = frame((0, yc, z), (-1, 0, 0))
        frames[f"{name}_xp"] = frame((tx, yc, z), (1, 0, 0))
        frames[f"{name}_b_xm"] = frame((0, yc + 12, z), (-1, 0, 0))
        frames[f"{name}_b_xp"] = frame((tx, yc + 12, z), (1, 0, 0))
    # folding stay screw, pilot through
    zs, ys = p["stay_z"], p["stay_y_front"]
    solid = drill(solid, (0, ys, zs), (-1, 0, 0), 3.5, tx + 1)
    frames["stay_xm"] = frame((0, ys, zs), (-1, 0, 0))
    frames["stay_xp"] = frame((tx, ys, zs), (1, 0, 0))
    # hinge bracket arm on the leg's back face, just below the rail
    L = (run * run + H * H) ** 0.5
    uy, uz = -run / L, -H / L            # down the back face
    ny, nz = H / L, -run / L             # out of the back face
    ay, az = wy + zr * k, zr
    for i, d in ((1, p["hinge_d1"]), (2, p["hinge_d2"])):
        o = (tx / 2, ay + d * uy, az + d * uz)
        solid = drill(solid, o, (0, ny, nz), 3, 10)
        frames[f"back_{i}"] = frame(o, (0, ny, nz))
    # long screws go up through the rail into the top board
    for n, y in (("a", p["rail_y1"]), ("b", p["rail_y2"])):
        solid = drill(solid, (tx / 2, y, zr), (0, 0, -1), 5, p["rail_h"] + 1)
        frames[f"rail_top_{n}"] = frame((tx / 2, y, H), (0, 0, 1))
    return solid, frames
