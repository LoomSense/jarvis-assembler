"""The stage list. Each stage is a thin wrapper that runs pipeline code in a subprocess.

The stages together must do everything `pipeline/run.py` does. When a step is added there, add it here
too: tests/test_server.py compares the two outputs and fails when the server falls behind.

Agent hooks (owned by person B). When these modules exist they replace the fallbacks below:
    pipeline/agent/extract.py   async def extract_plan(job_dir: Path, emit) -> None   writes plan.json (+ crops/)
    pipeline/agent/parts.py     async def build_parts(job_dir: Path, emit) -> None    writes parts/<id>.py
`emit(type, text, **fields)` appends an event to the feed (PLAN §4.4).
Until then a known guide uses its hand-checked plan from eval/golden/ (allowed for gate G3).
"""
from __future__ import annotations

import importlib
import json
import shutil
import sys
from pathlib import Path

from server.jobs import Job
from server.orchestrator.runner import Ctx, Stage, StageError
from server.settings import ROOT

PY = sys.executable
CLI = [PY, "-m", "server.orchestrator.pipeline_cli"]


def hook(module: str, name: str):
    try:
        return getattr(importlib.import_module(module), name)
    except (ImportError, AttributeError):
        return None


def validate_plan(plan) -> list[str]:
    """Structural check only. Count validation against the guide is the extractor's job (PLAN §4.2)."""
    problems = []
    if not isinstance(plan, dict):
        return ["the plan must be a JSON object"]
    for key in ("parts", "hardware", "steps"):
        if not isinstance(plan.get(key), list):
            problems.append(f"`{key}` must be a list")
    if not problems and not plan["steps"]:
        problems.append("the plan has no steps")
    if not problems and not plan["parts"]:
        problems.append("the plan has no parts")
    if not problems:
        for item in plan["parts"] + plan["hardware"]:
            script = item.get("script") if isinstance(item, dict) else None
            if script is None:
                continue
            path = Path(script) if isinstance(script, str) else None
            # the harness runs this file as Python, so it may only name a script inside the job's parts/ folder
            if path is None or path.is_absolute() or ".." in path.parts or path.parts[:1] != ("parts",) \
                    or len(path.parts) != 2 or path.suffix != ".py":
                problems.append(f"`script` must look like parts/<name>.py, found {script!r}")
    return problems


async def ingest(ctx: Ctx) -> dict:
    res = await ctx.run([*CLI, "ingest", ctx.job.dir])
    ctx.emit("image", f"Guide read: {res.get('pages')} pages", image_url=ctx.job.url("pages/page-001.png"))
    return res


async def extract_plan(ctx: Ctx) -> dict:
    job = ctx.job
    extract = hook("pipeline.agent.extract", "extract_plan")
    if extract:
        await extract(job.dir, ctx.emit)
    elif ctx.golden:
        shutil.copyfile(ctx.golden / "plan.json", job.plan)
        ctx.emit("thought", f"This guide is a known one: using its hand-checked plan ({ctx.golden.name}), "
                            "not a fresh extraction.", level="warn")
    else:
        raise StageError("Reading a new guide is not connected yet (task AGT-02). "
                         "Only the known test guides can be built for now.")
    try:
        plan = json.loads(job.plan.read_text())
    except (OSError, json.JSONDecodeError) as e:
        raise StageError(f"the extractor did not write a readable plan.json: {e}") from None
    problems = validate_plan(plan)
    if problems:
        raise StageError("the extracted plan is not usable: " + "; ".join(problems))
    job.set_status(title=plan.get("title"))
    flags = plan.get("flags") or []
    ctx.emit("thought", f"Plan: {len(plan['parts'])} part types, {len(plan['hardware'])} hardware types, "
                        f"{len(plan['steps'])} steps, {len(flags)} flags for a human to check.",
             level="warn" if flags else "info")
    return {"parts": len(plan["parts"]), "hardware": len(plan["hardware"]), "steps": len(plan["steps"]),
            "flags": len(flags)}


async def parts(ctx: Ctx) -> dict:
    job = ctx.job
    plan = json.loads(job.approved_plan.read_text())
    build = hook("pipeline.agent.parts", "build_parts")
    if build:
        await build(job.dir, ctx.emit)
    scripts = [it["script"] for it in plan["parts"] + plan["hardware"] if it.get("script")]
    n_generated = sum(1 for it in plan["parts"] + plan["hardware"] if it.get("generate"))
    missing = [s for s in scripts if not (job.dir / s).is_file()]
    if missing and ctx.golden:
        for s in missing:
            if (ctx.golden / s).is_file():
                (job.dir / s).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ctx.golden / s, job.dir / s)
        ctx.emit("thought", f"Using the hand-checked part scripts for {len(missing)} parts ({ctx.golden.name}).",
                 level="warn")
        missing = [s for s in scripts if not (job.dir / s).is_file()]
    if missing or len(scripts) + n_generated < len(plan["parts"]) + len(plan["hardware"]):
        raise StageError("no CadQuery script for: " + (", ".join(missing) or "parts without a `script` entry")
                         + ". The part-building agent is not connected yet (task AGT-05).")
    res = await ctx.run([PY, "-m", "pipeline.cad.harness", job.approved_plan, job.dir / "parts"])
    for part_id, info in res.items():
        ctx.emit("part_status", f"Built {part_id}: {info.get('frames')} mate points", partId=part_id,
                 level="success", ms=info.get("ms"))
    return {"built": len(res)}


async def place(ctx: Ctx) -> dict:
    return await ctx.run([*CLI, "place", ctx.job.dir])


async def steps(ctx: Ctx) -> dict:
    job = ctx.job
    script = ROOT / "pipeline" / "blender" / "assemble.py"
    res = await ctx.run([ctx.settings.blender, "-b", "--factory-startup", "-P", script, "--",
                         job.approved_plan, job.dir / "parts", job.dir / "placements.json", job.dir])
    if not job.glb.is_file() or not job.manifest.is_file():
        raise StageError("Blender finished but walkthrough.glb or manifest.json is missing")
    ctx.emit("image" if (job.dir / "renders/cover.png").is_file() else "thought",
             f"Assembled {res.get('instances', '?')} pieces and animated {res.get('steps', '?')} steps as "
             f"{res.get('actions', '?')} actions ({res.get('glb_bytes', 0) / 1e6:.1f} MB).",
             image_url=job.url("renders/cover.png") if (job.dir / "renders/cover.png").is_file() else None)
    return res


async def refs(ctx: Ctx) -> dict:
    job = ctx.job
    res = await ctx.run([PY, "-m", "pipeline.refs", job.approved_plan, job.dir])
    sheet = "refs/step-01-sheet.png"
    ctx.emit("image" if (job.dir / sheet).is_file() else "thought",
             f"Rendered {res.get('part_renders', '?')} part pictures and {res.get('sheets', '?')} reference sheets "
             "for the live checks.", image_url=job.url(sheet) if (job.dir / sheet).is_file() else None)
    return res


async def export(ctx: Ctx) -> dict:
    return await ctx.run([*CLI, "export", ctx.job.dir, ctx.job.id])


def _globbed(job: Job, *patterns: str) -> list[Path]:
    return [p for pattern in patterns for p in sorted(job.dir.glob(pattern))]


STAGES = [
    Stage("ingest", ingest, lambda j: [j.guide], progress=0.05, timeout_s=180),
    Stage("extract_plan", extract_plan, lambda j: [j.guide, j.dir / "pages" / "index.json"], progress=0.20,
          timeout_s=900),
    Stage("await_approval", None, lambda j: [j.plan], progress=0.25),
    Stage("parts", parts, lambda j: [j.approved_plan], progress=0.60, timeout_s=1800),
    Stage("place", place, lambda j: [j.approved_plan, *_globbed(j, "parts/*.mates.json")], progress=0.70,
          timeout_s=180),
    Stage("steps", steps, lambda j: [j.approved_plan, j.dir / "placements.json", *_globbed(j, "parts/*.stl")],
          progress=0.88, timeout_s=600),
    Stage("refs", refs, lambda j: [j.approved_plan, j.dir / "placements.json", j.glb], progress=0.96,
          timeout_s=600),
    Stage("export", export, lambda j: [j.approved_plan, j.dir / "placements.json", j.glb, j.guide], progress=1.0,
          timeout_s=420),
]
