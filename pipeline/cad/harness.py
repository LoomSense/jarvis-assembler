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
import os
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
    built, boxed = {}, []
    for kind, items, key in (("part", plan["parts"], "id"), ("hardware", plan["hardware"], "code")):
        for it in items:
            if it.get("generate"):                                # drawn from the guide by an image-to-3D model
                from pipeline.cad import generated
                built[it[key]] = generated.build(plan_path, plan, it, out, kind)
                continue
            args = (plan_path.parent / it["script"], it[key], out, kind, it.get("params"), plan.get("dims"))
            if os.environ.get("CAD_SANDBOX") == "1":             # scripts a model wrote: run each in the sandbox
                built[it[key]] = None                            # keeps the plan's order
                boxed.append((it, key, args))
            else:
                built[it[key]] = build_part(*args)
    if boxed:
        # Each script gets a fresh process, and importing CadQuery there costs 1.5 to 2 s. Run them side by side:
        # the wait is one import, not one per part.
        from concurrent.futures import ThreadPoolExecutor

        from pipeline.cad import sandbox
        with ThreadPoolExecutor(max_workers=min(len(boxed), os.cpu_count() or 2, 8)) as pool:
            results = list(pool.map(lambda b: sandbox.run_part(*b[2]), boxed))
        for (it, key, _), r in zip(boxed, results):               # report the first failure in plan order
            if not r["ok"]:
                raise RuntimeError(f"{it[key]} ({it['script']}): {r['error']}\n{r.get('traceback', '')}")
            built[it[key]] = r["info"]
    return built


if __name__ == "__main__":
    res = build_all(Path(sys.argv[1]), Path(sys.argv[2]))
    print("RESULT " + json.dumps({k: {"frames": len(v["frames"]), "ms": v["build_ms"]} for k, v in res.items()}))
