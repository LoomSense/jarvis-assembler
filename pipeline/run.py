"""Plan -> parts -> placement -> fit checks -> animated GLB, manifest, check renders, part pictures, reference sheets,
packed GLB and guide crops.

    .venv/bin/python -m pipeline.run eval/golden/knagglig/plan.json jobs/knagglig

Stops with a non-zero exit code and a list of problems if the parts do not fit.
Set BLENDER_BIN if Blender is not in /Applications.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from pipeline import crops, pack, refs
from pipeline.assemble import collide, placement
from pipeline.cad import harness

# AGJ_FAST_BUILD=1: stop after the animated scene and its step renders. Part pictures, phase-2 reference sheets,
# the packed GLB and the guide crops are left out; the server's own stages make them once the plan is approved.
FAST = os.environ.get("AGJ_FAST_BUILD") == "1"
BLENDER = os.environ.get("BLENDER_BIN") or shutil.which("blender") or "/Applications/Blender.app/Contents/MacOS/Blender"


def main(plan_path: Path, out: Path) -> int:
    t = {}
    t0 = time.perf_counter()
    plan = json.loads(plan_path.read_text())
    parts_dir = out / "parts"
    built = harness.build_all(plan_path, parts_dir)
    t["parts_s"] = time.perf_counter() - t0

    t1 = time.perf_counter()
    placed = placement.solve(plan, parts_dir)
    (out / "placements.json").write_text(json.dumps(placed, indent=1))
    problems = list(placed["errors"])
    for c in collide.check(parts_dir, placed):
        problems.append(f"{c['a']} and {c['b']} overlap by {c['overlap_mm3']} mm3")
    # hardware used in the steps must equal the guide's parts list
    for h in plan["hardware"]:
        n = sum(1 for i in placed["instances"] if i.split("#")[0] == h["code"])
        if n != h["count"]:
            problems.append(f"hardware {h['code']}: guide lists {h['count']}, steps use {n}")
    for p in plan["parts"]:
        n = sum(1 for i in placed["instances"] if i.split("#")[0] == p["id"])
        if n != p["count"]:
            problems.append(f"part {p['id']}: plan lists {p['count']}, steps use {n}")
    t["fit_s"] = time.perf_counter() - t1
    if problems:
        print("FIT PROBLEMS:\n  " + "\n  ".join(problems))
        (out / "problems.json").write_text(json.dumps(problems, indent=1))
        return 1

    t2 = time.perf_counter()
    script = Path(__file__).parent / "blender" / "assemble.py"
    r = subprocess.run([BLENDER, "-b", "--factory-startup", "-P", str(script), "--", str(plan_path), str(parts_dir),
                        str(out / "placements.json"), str(out)], capture_output=True, text=True, timeout=300)
    res = [l for l in r.stdout.splitlines() if l.startswith("RESULT ")]
    if r.returncode or not res:
        print(r.stdout[-2000:], r.stderr[-2000:])
        return 2
    t["blender_s"] = time.perf_counter() - t2
    if FAST:                                                     # a build the agent iterates on: fit and step renders only
        print("RESULT " + json.dumps(dict(json.loads(res[-1][7:]), unique_parts=len(built), fast=True,
                                          worst_residual_mm=max(v["residual_mm"] for v in placed["instances"].values()),
                                          timings_s={k: round(v, 2) for k, v in t.items()})))
        return 0
    t3 = time.perf_counter()
    try:                                                         # part pictures and phase-2 reference sheets
        pictures = refs.build(plan_path, out)
    except Exception as e:
        print(e)
        return 3
    t["refs_s"] = time.perf_counter() - t3
    packed = pack.build(out)                                     # optional: skipped when gltfpack is not installed
    cut = crops.build(plan_path, out)                            # optional: skipped when the guide PDF is not there
    summary = dict(json.loads(res[-1][7:]), unique_parts=len(built), part_renders=pictures["part_renders"],
                   ref_sheets=pictures["sheets"], packed_glb_bytes=packed.get("bytes"), step_crops=cut["crops"],
                   worst_residual_mm=max(v["residual_mm"] for v in placed["instances"].values()),
                   timings_s={k: round(v, 2) for k, v in t.items()})
    print("RESULT " + json.dumps(summary))
    return 0


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1]), Path(sys.argv[2])))
