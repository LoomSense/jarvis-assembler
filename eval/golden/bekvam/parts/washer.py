"""Washer 110097 (guide page 3). It sits between the wood and the folding stay (step 5).
Local frame: `seat` at the origin against the wood, axis +Z into the wood; the washer's body is on -Z."""
from pipeline.cad.lib import frame, tube

PARAMS = {"washer_t": 2, "od": 14, "id": 6}


def build(p):
    t = p["washer_t"]
    solid = tube((0, 0, -t), (0, 0, 1), p["od"], p["id"], t)
    return solid, {"seat": frame((0, 0, 0), (0, 0, 1)), "top": frame((0, 0, -t), (0, 0, -1))}
