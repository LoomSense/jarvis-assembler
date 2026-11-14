"""Build part scripts into meshes plus mate frames.

    python -m pipeline.cad.harness <plan.json> <out_dir>

For each unique part and hardware item in the plan this runs its script's
build(PARAMS) and writes  <out>/<id>.stl, <id>.step, <id>.mates.json.
All parts are built in one process: importing CadQuery costs about 1.4 s, the
geometry itself well under 0.2 s per part.
"""
from __future__ import annotations

import importlib.util
import json
import sys
import time
from pathlib import Path

import cadquery as cq

TOL = {"part": (0.1, 0.3), "hardware": (0.25, 0.6)}  # (linear mm, angular rad)


def load_script(path: Path):
    spec = importlib.util.spec_from_file_location(f"part_{path.stem}", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    for name in ("PARAMS", "build"):
        if not hasattr(mod, name):
            raise ValueError(f"{path.name}: missing `{name}`")
    return mod


def build_part(script: Path, part_id: str, out: Path, kind: str = "part", params: dict | None = None,
               dims: dict | None = None) -> dict:
    t0 = time.perf_counter()
    mod = load_script(script)
    # shared plan dimensions override script defaults, so mating parts always agree
    p = dict(mod.PARAMS)
    p.update({k: v for k, v in (dims or {}).items() if k in mod.PARAMS})
    p.update(params or {})
    solid, frames = mod.build(p)
    shape = solid.val()
    if not shape.isValid():
        raise ValueError(f"{part_id}: CadQuery produced an invalid solid")
    for name, f in frames.items():
        if set(f) != {"origin", "axis"} or len(f["origin"]) != 3 or len(f["axis"]) != 3:
            raise ValueError(f"{part_id}: bad frame `{name}`")
    lin, ang = TOL[kind]
    shape.exportStl(str(out / f"{part_id}.stl"), tolerance=lin, angularTolerance=ang)
    cq.exporters.export(solid, str(out / f"{part_id}.step"))
    bb = shape.BoundingBox()
    info = {
        "id": part_id, "kind": kind, "params": p, "frames": frames,
        "bbox_mm": {"min": [bb.xmin, bb.ymin, bb.zmin], "max": [bb.xmax, bb.ymax, bb.zmax]},
        "volume_mm3": shape.Volume(), "build_ms": round((time.perf_counter() - t0) * 1000, 1),
    }
    (out / f"{part_id}.mates.json").write_text(json.dumps(info, indent=1))
    return info


def build_all(plan_path: Path, out: Path) -> dict:
    plan = json.loads(plan_path.read_text())
    out.mkdir(parents=True, exist_ok=True)
    built = {}
    for kind, items, key in (("part", plan["parts"], "id"), ("hardware", plan["hardware"], "code")):
        for it in items:
            built[it[key]] = build_part(plan_path.parent / it["script"], it[key], out, kind, it.get("params"), plan.get("dims"))
    return built


if __name__ == "__main__":
    res = build_all(Path(sys.argv[1]), Path(sys.argv[2]))
    print("RESULT " + json.dumps({k: {"frames": len(v["frames"]), "ms": v["build_ms"]} for k, v in res.items()}))
