"""Twin-wheel caster 10049021, guide pages 3-4 (step 1).
Local frame: floor at z = 0, wheel axles along X, wheels trailing towards -Y.
Top of the hood (where the stem starts) at z = H; the stem points up +Z into the base socket."""
from pipeline.cad.lib import frame, union, cylinder, rounded_box

PARAMS = {"H": 58, "wheel_d": 50, "stem_d": 11, "stem_l": 22}


def build(p):
    H, wd = p["H"], p["wheel_d"]
    w1 = cylinder((-21, -10, wd / 2), (1, 0, 0), wd, 17)
    w2 = cylinder((4, -10, wd / 2), (1, 0, 0), wd, 17)
    hood = rounded_box(-22, -34, 30, 44, 48, H - 8 - 30, r_side=10, r_edge=4)
    cap = cylinder((0, 0, H - 8), (0, 0, 1), 26, 8)
    stem = cylinder((0, 0, H), (0, 0, 1), p["stem_d"], p["stem_l"])
    solid = union(w1, w2, hood, cap, stem)
    frames = {"top": frame((0, 0, H), (0, 0, 1)), "top_b": frame((0, 6, H), (0, 0, 1))}
    return solid, frames
