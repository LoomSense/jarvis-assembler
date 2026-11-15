"""Twin-wheel caster 10049021, guide pages 3-4 (step 1).
Local frame: top of the hood at z=0 (touches the arm underside), stem up along +Z, wheels trail along +X."""
from pipeline.cad.lib import rounded_box, cylinder, turned, union, frame

PARAMS = {"wheel_d": 50, "stem_d": 10, "stem_len": 22}


def build(p):
    r = p["wheel_d"] / 2
    stem = cylinder((0, 0, 0), (0, 0, 1), p["stem_d"], p["stem_len"])
    collar = cylinder((0, 0, p["stem_len"] - 6), (0, 0, 1), p["stem_d"] + 0.6, 3)
    hood = rounded_box(-20, -23, -9, 42, 46, 9, r_side=12, r_edge=3)
    fork = rounded_box(-14, -6, -40, 38, 12, 34, r_side=5)
    ax = (12, 0, -37)
    prof = [(0, 0), (r - 4, 0), (r, 3), (r, 13), (r - 4, 16), (0, 16)]
    w1 = turned(prof, (ax[0], 6, ax[2]), (0, 1, 0))
    w2 = turned(prof, (ax[0], -6, ax[2]), (0, -1, 0))
    solid = union(hood, fork, stem, collar, w1, w2)
    frames = {"stem": frame((0, 0, 0), (0, 0, 1)), "body_in": frame((-15, 0, 0), (0, 0, 1))}
    return solid, frames
