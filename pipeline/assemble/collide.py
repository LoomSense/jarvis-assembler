"""Exact interference check between placed parts.

    python -m pipeline.assemble.collide <parts_dir> <placements.json>

Loads each part's STEP solid, moves it to its solved pose and intersects every
pair of parts. Touching faces are fine; a shared volume means two boards occupy
the same space, so the model could not be built. Fasteners are skipped: a screw
is meant to bite into the wood around its pilot hole.
"""
from __future__ import annotations

import itertools
import json
import sys
from pathlib import Path

import cadquery as cq
from OCP.gp import gp_Trsf

MAX_OVERLAP_MM3 = 5.0


def check(parts_dir: Path, placements: dict) -> list[dict]:
    solids, hits = {}, []
    parts = {k: v for k, v in placements["instances"].items() if v["kind"] == "part"}
    generated = {p.stem.replace(".mates", "") for p in parts_dir.glob("*.mates.json")
                 if json.loads(p.read_text()).get("source") == "generate"}
    parts = {k: v for k, v in parts.items() if v["part"] not in generated}   # a generated mesh has no exact solid
    for inst, pl in parts.items():
        if pl["part"] not in solids:
            solids[pl["part"]] = cq.importers.importStep(str(parts_dir / f"{pl['part']}.step")).val()
    def move(shape, m):
        trsf = gp_Trsf()
        trsf.SetValues(*m[0], *m[1], *m[2])          # re-orthogonalises the rounded rotation
        return shape.moved(cq.Location(trsf))

    placed = {i: move(solids[p["part"]], p["matrix_mm"]) for i, p in parts.items()}
    for a, b in itertools.combinations(placed, 2):
        ba, bb = placed[a].BoundingBox(), placed[b].BoundingBox()
        if (ba.xmax < bb.xmin or bb.xmax < ba.xmin or ba.ymax < bb.ymin or bb.ymax < ba.ymin
                or ba.zmax < bb.zmin or bb.zmax < ba.zmin):
            continue
        vol = placed[a].intersect(placed[b]).Volume()
        if vol > MAX_OVERLAP_MM3:
            hits.append({"a": a, "b": b, "overlap_mm3": round(vol, 1)})
    return hits


if __name__ == "__main__":
    parts_dir, place_path = Path(sys.argv[1]), Path(sys.argv[2])
    hits = check(parts_dir, json.loads(place_path.read_text()))
    print("RESULT " + json.dumps({"collisions": hits}))
    sys.exit(1 if hits else 0)
