"""Bake-off runner: a hand-built build loop around a Gemini model.

    .venv/bin/python -m eval.bakeoff.run_gemini <workspace> [model] [max_rounds]

Round 1: brief + guide pages -> plan.json and part scripts. Each later round the
model gets its own files back with the pipeline's problems (or, once the
pipeline passes, the step renders to compare with the guide) and returns fixes.
Writes <workspace>/log.json.
"""
import json
import subprocess
import sys
import time
from pathlib import Path

from pipeline.check.gemini import generate

ws = Path(sys.argv[1]).resolve()
model = sys.argv[2] if len(sys.argv) > 2 else "gemini-4-hydrogen"
max_rounds = int(sys.argv[3]) if len(sys.argv) > 3 else 5
pages = sorted((ws / "guide").glob("page-*.png"), key=lambda p: int(p.stem.split("-")[1]))
OUT = ('Reply with one JSON object: {"files": {"job/plan.json": "<full file content>", "job/parts/<name>.py": "<full file content>", ...}, '
       '"done": false, "notes": "<short>"}. Give complete file contents for every file you create or change. '
       'You cannot run commands: the pipeline is run for you after each reply and you get its output back.')
base = [f"{(ws / 'BRIEF.md').read_text()}\n\n# Facts\n{(ws / 'FACTS.md').read_text()}\n\n# pipeline/cad/lib.py\n```python\n"
        f"{(ws / 'pipeline/cad/lib.py').read_text()}\n```\n\n# Guide pages, in order"] + pages
log, t_start, passed_once = [], time.perf_counter(), False


def run_pipeline():
    r = subprocess.run(["./py", "-m", "pipeline.run", "job/plan.json", "job/out"], cwd=ws, capture_output=True, text=True, timeout=600)
    return r.returncode, (r.stdout + r.stderr)[-4000:]


def current_files():
    fs = [ws / "job/plan.json"] + sorted((ws / "job/parts").glob("*.py"))
    return "\n\n".join(f"## {f.relative_to(ws)}\n```\n{f.read_text()}\n```" for f in fs if f.exists())


parts = base + [OUT]
for rnd in range(1, max_rounds + 1):
    try:
        text = generate(model, parts, json_out=True, timeout=900, thinking_budget="low")
        reply = json.loads(text)
    except Exception as e:                                    # noqa: BLE001
        log.append({"round": rnd, "error": str(e)[:500], **getattr(generate, "last", {})}); break
    for rel, content in (reply.get("files") or {}).items():
        dest = (ws / rel).resolve()
        if ws / "job" in dest.parents and dest.suffix in (".json", ".py"):
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(content if isinstance(content, str) else json.dumps(content, indent=1, ensure_ascii=False))
    try:
        code, out = run_pipeline()
    except Exception as e:                                    # noqa: BLE001
        code, out = 99, str(e)[:2000]
    log.append({"round": rnd, **generate.last, "files": list((reply.get("files") or {})), "done": reply.get("done"),
                "notes": reply.get("notes"), "exit": code, "pipeline": out[-1500:]})
    print(f"round {rnd}: {generate.last['latency_s']} s, exit {code}", flush=True)
    (ws / "log.json").write_text(json.dumps({"model": model, "total_s": round(time.perf_counter() - t_start, 1), "rounds": log}, indent=1, ensure_ascii=False))
    if code == 0 and (passed_once or reply.get("done")):
        break
    if code == 0:
        passed_once = True
        renders = sorted((ws / "job/out/renders").glob("*.png"))
        parts = base + ["# Your current files\n" + current_files(),
                        "The pipeline PASSES. Below are its renders (step_NN = the state after step NN, new pieces tinted blue; cover = the finished product). "
                        "Compare each with the guide's drawing of that step. If everything matches the guide, reply with {\"files\": {}, \"done\": true}. "
                        "Otherwise return the corrected files."] + [x for r in renders for x in (r.name, r)] + [OUT]
    else:
        parts = base + ["# Your current files\n" + current_files(), "# Pipeline output\n```\n" + out + "\n```\nFix the problems.", OUT]
(ws / "log.json").write_text(json.dumps({"model": model, "total_s": round(time.perf_counter() - t_start, 1), "rounds": log}, indent=1, ensure_ascii=False))
