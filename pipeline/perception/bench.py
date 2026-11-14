"""Stand-in benchmark for the per-step questions, before any real clip exists (LIV-02).

    .venv/bin/python -m pipeline.perception.bench <job_dir> [out.json]

There is no camera footage yet, so the "live frame" is a render of the assembly as it stands after step f,
from another side and height than the reference sheet and on a table-coloured background. Every frame f is
asked against every step s. Steps that start with a turn also get frame "b": the pieces of step s - 1 already
turned to step s's position (turned, nothing added yet), a harder "not done" than f == s - 1. What this can measure:
  step_complete   should be yes when f == s, no when f == s - 1 or f == "b" (the state just before the step)
  orientation_ok  should be yes when frame and step have the same working position (and for "b"), no otherwise
  hands_present   should be no everywhere; part_in_hand should be nothing_held everywhere
  latency         Decision 1 round trip from this machine
What it cannot measure: anything with hands, real light, clutter or a real camera. Those need eval/phase2 clips.
"""
from __future__ import annotations

import json
import statistics
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from pipeline import refs
from pipeline.perception import request

VIEW, BG = "-55,50", "0.62,0.58,0.52"
YES, NO = 0.85, 0.15


def frames(job: Path) -> None:
    refs.blender("render_refs.py", job, 768, VIEW, job / "synth", BG)
    refs.blender("render_refs.py", job, 768, VIEW, job / "synth", BG, "before")


def frame_file(job: Path, s: int, f) -> Path:
    return job / "synth" / (f"step-{s:02d}-before.png" if f == "b" else f"step-{f:02d}-ref.png")


def run(job: Path) -> dict:
    manifest = json.loads((job / "manifest.json").read_text())
    steps = manifest["steps"]
    pose = {s["n"]: (s.get("pose"), tuple(s["assemblyRotationDeg"])) for s in steps}
    frames(job)
    turned = [s["n"] for k, s in enumerate(steps) if k and pose[s["n"]] != pose[steps[k - 1]["n"]]]
    pairs = [(s["n"], f["n"]) for s in steps for f in steps] + [(n, "b") for n in turned]

    def one(p):
        s, f = p
        for attempt in range(3):
            try:
                out = request.ask(job, s, frame_file(job, s, f))
                return {"step": s, "frame": f, "latency_ms": out["latency_ms"], "answers": out["answers"]}
            except RuntimeError as e:
                err = str(e)[:200]
        return {"step": s, "frame": f, "error": err}

    with ThreadPoolExecutor(4) as ex:
        rows = list(ex.map(one, pairs))
    ok = [r for r in rows if "answers" in r]

    def noul(r, key):
        return r["answers"][key]["noul"]

    def tally(cases):   # cases: (value, expected yes?)
        hit = sum(1 for v, y in cases if (v >= YES if y else v <= NO))
        side = sum(1 for v, y in cases if (v >= 0.5) == y)
        ys, ns = [v for v, y in cases if y], [v for v, y in cases if not y]
        # how well the answer orders the cases, whatever the threshold: AUC, and the threshold that gets most right
        auc = sum((y > n) + 0.5 * (y == n) for y in ys for n in ns) / max(1, len(ys) * len(ns))
        best = max(((sum((v >= t) == y for v, y in cases), t) for t in sorted({v for v, _ in cases})), default=(0, None))
        return {"n": len(cases), "past_threshold": hit, "right_side_of_0.5": side, "auc": round(auc, 3),
                "best_threshold": {"at_or_above": best[1] and round(best[1], 2), "right": best[0]},
                "yes_values": sorted(round(v, 2) for v, y in cases if y), "no_values": sorted(round(v, 2) for v, y in cases if not y)}

    near = [r for r in ok if r["frame"] == "b" or r["frame"] in (r["step"], r["step"] - 1)]
    lat = sorted(r["latency_ms"] for r in ok)
    held = [r["answers"]["part_in_hand"]["choice"] for r in ok if "part_in_hand" in r["answers"]]
    return {
        "job": job.name, "requests": len(rows), "failed": len(rows) - len(ok), "view_az_el_deg": VIEW, "thresholds": [NO, YES],
        "latency_ms": {"p50": statistics.median(lat), "p95": lat[int(len(lat) * 0.95) - 1], "min": lat[0], "max": lat[-1]},
        "step_complete": tally([(noul(r, "step_complete"), r["frame"] == r["step"]) for r in near]),
        "orientation_ok": tally([(noul(r, "orientation_ok"), r["frame"] == "b" or pose[r["frame"]] == pose[r["step"]]) for r in near]),
        "hands_present_false_alarms": sum(1 for r in ok if noul(r, "hands_present") >= 0.5),
        "part_in_hand": {c: held.count(c) for c in sorted(set(held))},
        "step_complete_matrix": [[next((round(noul(r, "step_complete"), 2) for r in ok if r["step"] == s["n"] and r["frame"] == f["n"]), None)
                                  for f in steps] for s in steps],
        "rows": rows,
    }


if __name__ == "__main__":
    job = Path(sys.argv[1])
    res = run(job)
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else job / "synth" / "bench.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(res, indent=1))
    print("step_complete, rows = step asked, columns = frame shown")
    for row in res["step_complete_matrix"]:
        print("  " + " ".join("  -- " if v is None else f"{v:5.2f}" for v in row))
    print("RESULT " + json.dumps({k: v for k, v in res.items() if k not in ("rows", "step_complete_matrix")}))
