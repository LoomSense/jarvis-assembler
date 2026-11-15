"""Hinge bracket 122127 (guide page 4; fitted in step 2, page 6). Three links: an arm that lies on the back of the
front leg (2 holes), a long plate under the short top rail (2 countersunk holes), bent to the arm at about 106
degrees, and at the plate's rear end a knuckle with a short leaf (2 holes) that is screwed to the rear frame.
The plate is about as long as the rail (a quarter of the leg); the leaf is about half the plate.
Local frame: the finished ladder's Y (front to back) and Z (up); X centred on the leg's thickness."""
import cadquery as cq
from pipeline.cad.lib import cylinder, drill, frame, union

PARAMS = {"H_under": 480, "run": 140, "wy": 47, "rail_h": 45, "rail_end": 280,
          "rear_top_z": 478, "rear_run": 72, "hinge_d1": 25, "hinge_d2": 60, "hinge_t": 2.5,
          "leaf_s1h": 64, "leaf_s2h": 94, "rail_y1": 200, "rail_y2": 260,
          "w": 16, "arm_len": 80, "leaf_s0": 46, "leaf_s1": 110}


def build(p):
    H, run, wy, t, w = p["H_under"], p["run"], p["wy"], p["hinge_t"], p["w"]
    k = run / H
    zr = H - p["rail_h"]
    re = p["rail_end"]
    L = (run * run + H * H) ** 0.5
    uy, uz = -run / L, -H / L
    ny, nz = H / L, -run / L
    ay, az = wy + zr * k, zr
    dy, dz = ay + t * ny, az + t * nz
    lam = (zr - t - dz) / uz
    e = (dy + lam * uy, zr - t)
    b = (ay + p["arm_len"] * uy, az + p["arm_len"] * uz)
    c = (b[0] + t * ny, b[1] + t * nz)
    body = cq.Workplane("YZ").polyline([(ay, az), (re + 1, zr), (re + 1, zr - t), e, c, b]).close().extrude(w)
    # leaf on the rear leg's front face
    zt, rr = p["rear_top_z"], p["rear_run"]
    Lr = (rr * rr + zt * zt) ** 0.5
    ty, tz = rr / Lr, -zt / Lr           # down the rear leg
    fy, fz = -zt / Lr, -rr / Lr          # out of the rear leg's front face

    def P(s):
        return (re + s * ty, zt + s * tz)

    p0, p1 = P(p["leaf_s0"]), P(p["leaf_s1"])
    leaf = cq.Workplane("YZ").polyline([p0, p1, (p1[0] + t * fy, p1[1] + t * fz),
                                        (p0[0] + t * fy, p0[1] + t * fz)]).close().extrude(w)
    knuckle = cylinder((0, re + 2.5, zr - 4), (1, 0, 0), 6, w)
    solid = union(body, leaf, knuckle).translate((-w / 2, 0, 0))
    frames = {}
    for i, d in ((1, p["hinge_d1"]), (2, p["hinge_d2"])):
        y, z = ay + d * uy, az + d * uz
        o = (0, y + t * ny, z + t * nz)
        solid = drill(solid, o, (0, ny, nz), 5.5, t + 0.3)
        frames[f"arm_in_{i}"] = frame((0, y, z), (0, -ny, -nz))
        frames[f"arm_out_{i}"] = frame(o, (0, ny, nz))
    for n, y in (("a", p["rail_y1"]), ("b", p["rail_y2"])):
        solid = drill(solid, (0, y, zr - t), (0, 0, -1), 5.5, t + 0.3)
        frames[f"plate_out_{n}"] = frame((0, y, zr - t), (0, 0, -1))
    for i, s in ((1, p["leaf_s1h"]), (2, p["leaf_s2h"])):
        y, z = P(s)
        o = (0, y + t * fy, z + t * fz)
        solid = drill(solid, o, (0, fy, fz), 5.5, t + 0.3)
        frames[f"leaf_in_{i}"] = frame((0, y, z), (0, -fy, -fz))
        frames[f"leaf_out_{i}"] = frame(o, (0, fy, fz))
    return solid, frames
