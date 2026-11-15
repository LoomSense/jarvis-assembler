"""Folding stay 122128 (left) / 122148 (right) (guide page 4; fitted in step 5, page 8). Two flat links joined
by a rivet: a short link (about a third of the length) fixed to the rear leg and a long link fixed to the front
leg, with a locking clip over the joint. Modelled opened out and locked, as on the open ladder.
The stay is a bit longer than half the ladder's depth. `side` -1 = left (122128), +1 = right (122148).
Local frame = the finished ladder's frame; the stay lies outside the legs on its washers."""
from pipeline.cad.lib import board, cylinder, drill, frame, union

PARAMS = {"tx": 22, "Win": 346, "washer_t": 2, "stay_z": 243.36, "stay_y_rear": 333.55, "stay_y_front": 94.48,
          "side": -1}


def build(p):
    sd = p["side"]
    z, yr, yf = p["stay_z"], p["stay_y_rear"], p["stay_y_front"]
    xin = -p["washer_t"] if sd < 0 else 2 * p["tx"] + p["Win"] + p["washer_t"]
    yj = yr - (yr - yf) / 3

    def bx(a, b, y0, dy, h):             # a box from a to b mm outwards of the stay's inner face
        x0 = xin - b if sd < 0 else xin + a
        return board(x0, y0, z - h / 2, b - a, dy, h)

    def disc(y, d, a, b):
        return cylinder((xin + sd * a, y, z), (sd, 0, 0), d, b - a)

    long_link = union(bx(0, 3, yf, yj - yf, 14), disc(yf, 14, 0, 3), disc(yj, 14, 0, 3))
    short_link = union(bx(0, 4, yj + 12, yr - yj - 12, 16), disc(yr, 16, 0, 4))
    clip = bx(3, 8, yj - 10, 40, 22)
    rivet = disc(yj, 7, 0, 9.5)
    solid = union(long_link, short_link, clip, rivet)
    solid = drill(solid, (xin + sd * 4, yr, z), (sd, 0, 0), 5.5, 4.3)
    solid = drill(solid, (xin + sd * 3, yf, z), (sd, 0, 0), 5.5, 3.3)
    frames = {
        "rear_in": frame((xin, yr, z), (-sd, 0, 0)),
        "front_in": frame((xin, yf, z), (-sd, 0, 0)),
        "rear_out": frame((xin + sd * 4, yr, z), (sd, 0, 0)),
        "front_out": frame((xin + sd * 3, yf, z), (sd, 0, 0)),
    }
    return solid, frames
