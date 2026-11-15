"""The build agent: guide pages -> plan.json and CadQuery part scripts, with the Claude Agent SDK (AGT-02, AGT-05, AGT-06).

    .venv/bin/python -m pipeline.agent.extract <guide.pdf> <job_dir> ["extra facts, e.g. the overall size"]

One agent session does the plan and the parts together, because the plan's mates name frames that the part
scripts define. It works in <job>/agent/ with the brief (BRIEF.md), the guide pages as images and one
command, `./build`, which runs the real pipeline on what it wrote and prints the fit problems. It repeats
until the build passes, then runs `./check`, which asks a verifier (pipeline/check/step_check.py) whether each step's
render matches the guide's drawing, and fixes the steps that fail (at most 3 fixes each, PLAN D8). The bake-off (eval/bakeoff/) is why this
is an agent with tools and not one large model call.

What the agent can do: read, write and edit files in its workspace and run `./build` and `./check [N]`. Nothing else: a
guard refuses every other shell command and every path outside the workspace, and it has no web tools. The part scripts it writes run through pipeline/cad/sandbox.py.

The server calls `extract_plan(job_dir, emit)` and `build_parts` (pipeline/agent/parts.py) when the
environment has AGJ_BUILD_AGENT=claude. Without it the server keeps its hand-checked plans for known guides.
Sign-in: ANTHROPIC_API_KEY if set, otherwise the machine's Claude Code login.
Settings: AGJ_AGENT_MODEL (default claude-opus-5-5), AGJ_AGENT_MAX_USD (8), AGJ_AGENT_MAX_MIN (25).
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

from claude_agent_sdk import (AssistantMessage, ClaudeAgentOptions, ClaudeSDKError, HookMatcher, ResultMessage, TextBlock,
                              ToolResultBlock, ToolUseBlock, UserMessage, query)

from pipeline.check import step_check

ROOT = Path(__file__).resolve().parent.parent.parent
MODEL = os.environ.get("AGJ_AGENT_MODEL", "claude-opus-5-5")
MAX_USD = float(os.environ.get("AGJ_AGENT_MAX_USD", "8"))
MAX_MIN = float(os.environ.get("AGJ_AGENT_MAX_MIN", "25"))
PROMPT = "Read BRIEF.md and FACTS.md in this directory and do the task they describe."
BUILD_LINE = "    ./py -m pipeline.run job/plan.json job/out"
CHECK_LINE = "    ./py -m pipeline.check.step_check job/plan.json job/out [N]"
FACTS = """# Facts for this job

- The guide's pages are `guide/page-1.png` … `guide/page-{pages}.png` ({pages} pages). Read every page before you write anything.
- Run the pipeline with `./build` (no arguments). It builds `job/plan.json` into `job/out/` and prints
  `RESULT {{...}}` or the problems.
- After a passing build, run `./check` (all steps) or `./check N` (step N). It compares each step's render with the
  guide's drawing of that step and prints one line per step. `./build` and `./check` are the only commands you can run.
- `pipeline/` here is a copy of the pipeline's code for you to read (`cad/lib.py`, `assemble/placement.py`, …). `./build` runs the original, so editing the copy changes nothing.
- Do not write `guide` or `guide_id` paths that point outside this directory. Leave `guide` out of the plan.
- Give every part and hardware item `name`, `short`, the Greek `el` (and `el_pron` for parts), and every turn a `turn_text` in `en` and `el`.
{extra}
"""


def setup_workspace(job: Path, extra: str) -> Path:
    """<job>/agent/: brief, facts, guide pages, an empty job/ and the ./build and ./check commands. Kept after the run."""
    ws = job / "agent"
    pages = sorted((job / "pages").glob("page-*.png"))
    if not pages:
        raise RuntimeError(f"no page images in {job / 'pages'}: run the ingest stage first")
    (ws / "guide").mkdir(parents=True, exist_ok=True)
    (ws / "job" / "parts").mkdir(parents=True, exist_ok=True)
    for n, p in enumerate(pages, start=1):
        shutil.copyfile(p, ws / "guide" / f"page-{n}.png")
    for d in ("cad", "assemble", "blender"):                    # copies to read: ./build runs the repo's own code
        (ws / "pipeline" / d).mkdir(parents=True, exist_ok=True)
        for f in (ROOT / "pipeline" / d).glob("*.py"):
            shutil.copyfile(f, ws / "pipeline" / d / f.name)
    shutil.copyfile(ROOT / "pipeline" / "run.py", ws / "pipeline" / "run.py")
    brief = (ROOT / "pipeline" / "agent" / "BRIEF.md").read_text()
    (ws / "BRIEF.md").write_text(brief.replace(BUILD_LINE, "    ./build").replace(CHECK_LINE, "    ./check [N]"))
    photo = job / "product.jpg"                                # from the maker's site (server/routes/catalog.py)
    if photo.is_file():
        shutil.copyfile(photo, ws / "guide" / "product.jpg")
        info = json.loads((job / "product.json").read_text()) if (job / "product.json").is_file() else {}
        finish = ", ".join(x for x in [info.get("design"), ", ".join(c.get("name", "") for c in info.get("colors", []))] if x)
        extra = (extra + "\n" if extra else "") + (
            "- `guide/product.jpg` is the maker's photo of the finished product" + (f" (finish: {finish})" if finish else "")
            + ". Give every part and every hardware item a `color` (\"#RRGGBB\") read from it, so the 3D model has the "
            "real colours: frame, fabric, wheels and screws each as they look.")
    if extra.startswith("- `guide/product.jpg`"):
        pass
    elif extra:
        extra = "- From the person who uploaded the guide: " + extra
    else:
        extra = ("- Nobody gave the product's overall size. Use a size printed in the guide if there is one, otherwise "
                 "estimate it from what the product is, and add a flag saying the size is an estimate.")
    (ws / "FACTS.md").write_text(FACTS.format(pages=len(pages), extra=extra))
    # ./build runs the repo's pipeline (not a copy the agent could edit), with part scripts in the sandbox
    build = ws / "build"
    build.write_text(f'#!/bin/sh\nWS=$(cd "$(dirname "$0")" && pwd)\ncd "{ROOT}" || exit 9\n'
                     f'CAD_SANDBOX=1 exec "{sys.executable}" -m pipeline.run "$WS/job/plan.json" "$WS/job/out"\n')
    build.chmod(0o755)
    check = ws / "check"                                       # the same for the step check (pipeline/check/step_check.py)
    check.write_text(f'#!/bin/sh\nWS=$(cd "$(dirname "$0")" && pwd)\ncd "{ROOT}" || exit 9\n'
                     f'exec "{sys.executable}" -m pipeline.check.step_check "$WS/job/plan.json" "$WS/job/out" "$@"\n')
    check.chmod(0o755)
    return ws


CHECK_CMD = re.compile(r"^\./check( [0-9]{1,3})?$")


def run_build(ws: Path) -> tuple[bool, str]:
    r = subprocess.run([str(ws / "build")], capture_output=True, text=True, timeout=600)
    out = (r.stdout + r.stderr).strip()
    return r.returncode == 0 and "RESULT " in r.stdout, out[-1500:]


def _short(path: str, ws: Path) -> str:
    try:
        return str(Path(path).resolve().relative_to(ws.resolve()))
    except ValueError:
        return Path(path).name


def _report(message, ws: Path, emit, state: dict) -> None:
    """Turn the agent's messages into feed events (PLAN §4.4)."""
    if isinstance(message, AssistantMessage):
        for b in message.content:
            if isinstance(b, TextBlock) and b.text.strip():
                emit("thought", b.text.strip()[:400])
            elif isinstance(b, ToolUseBlock):
                arg = b.input.get("file_path") or b.input.get("command") or b.input.get("pattern") or ""
                if b.name == "Bash" and CHECK_CMD.match(arg.strip()):
                    state["checks"] += 1
                    state["check"].add(b.id)
                    which = arg.strip()[len("./check"):].strip()
                    emit("tool_call", f"Comparing step {which} with the guide's drawing" if which else
                         "Comparing every step with the guide's drawings", tool="check")
                    continue
                if b.name == "Bash" and arg.strip() != "./build":
                    continue                                   # refused by the guard below; nothing to show
                if b.name == "Bash":
                    state["builds"] += 1
                    state["bash"].add(b.id)
                    emit("tool_call", f"Building the parts and checking the fit (run {state['builds']})", tool="build")
                elif b.name in ("Write", "Edit"):
                    emit("tool_call", f"{'Writing' if b.name == 'Write' else 'Fixing'} {_short(arg, ws)}", tool=b.name.lower())
                elif b.name == "Read":
                    emit("tool_call", f"Looking at {_short(arg, ws)}", tool="read")
    elif isinstance(message, UserMessage) and isinstance(message.content, list):
        for b in message.content:
            if not isinstance(b, ToolResultBlock):
                continue
            text = b.content if isinstance(b.content, str) else " ".join(
                c.get("text", "") for c in (b.content or []) if isinstance(c, dict))
            if b.tool_use_id in state["check"]:
                line, level = _check_line(text)
                emit("tool_result", line, tool="check", level=level)
            elif b.tool_use_id in state["bash"]:
                if "RESULT " in text and "FIT PROBLEMS" not in text:
                    state["passed"] += 1
                    emit("tool_result", "The build passes: every part builds and fits.", tool="build", level="success")
                else:
                    lines = [l.strip() for l in text.splitlines() if l.strip()]
                    emit("tool_result", ("Not yet: " + "; ".join(lines[-4:]))[:400], tool="build", level="warn")


def _check_line(text: str) -> tuple[str, str]:
    """The step check's output -> one short feed line and its level."""
    res = next((l for l in reversed(text.splitlines()) if l.startswith("RESULT ")), None)
    try:
        r = json.loads(res[7:]) if res else None
    except json.JSONDecodeError:
        r = None
    if not r:
        return "The drawing check did not run: " + " ".join(text.split())[-200:], "warn"
    return _check_summary(r), _check_level(r)


def _check_level(r: dict) -> str:
    return "success" if r["checked"] and not r["failed"] and not r["errors"] else "warn"


def _check_summary(r: dict) -> str:
    if not r["checked"]:
        return f"The drawing check could not run: {len(r['errors'])} steps not checked."
    text = f"{r['passed']} of {r['checked']} steps match the guide's drawings"
    if r["failed"]:
        text += "; differ: step " + ", ".join(str(n) for n in r["failed"])
    if r["errors"]:
        text += f"; {len(r['errors'])} not checked"
    return text + "."


def guard(ws: Path):
    """Checked before every tool call, whatever the permission mode: the only commands are ./build and ./check, and file
    tools stay inside the workspace. (The CLI would otherwise let read-only shell commands such as cat through.)"""
    root = ws.resolve()

    def deny(reason: str) -> dict:
        return {"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                       "permissionDecisionReason": reason}}

    async def check(data, tool_use_id, context) -> dict:
        name, args = data["tool_name"], data["tool_input"]
        if name == "Bash":
            cmd = args.get("command", "").strip()
            return {} if cmd == "./build" or CHECK_CMD.match(cmd) else deny(
                "The only commands available are ./build (no arguments) and ./check or ./check N. "
                "Use Read, Glob and Grep to look at files.")
        path = args.get("file_path") or args.get("path")
        if path:
            target = (root / path).resolve()
            if target != root and root not in target.parents:
                return deny("Only files inside this directory are available.")
        return {}

    return check


async def run_agent(job: Path, emit, extra: str = "") -> dict:
    ws = setup_workspace(job, extra)
    options = ClaudeAgentOptions(
        cwd=str(ws), model=MODEL,
        tools=["Read", "Write", "Edit", "Glob", "Grep", "Bash"],
        allowed_tools=["Read", "Write", "Edit", "Glob", "Grep", "Bash(./build)", "Bash(./check)", "Bash(./check:*)"],
        permission_mode="acceptEdits",       # file edits inside the workspace; anything else unlisted is refused
        setting_sources=[],                  # none of this machine's own Claude Code settings, hooks or memory
        hooks={"PreToolUse": [HookMatcher(matcher=None, hooks=[guard(ws)])]},
        max_turns=120, max_budget_usd=MAX_USD,
        max_buffer_size=64 << 20,            # page images come back through the stream; the default 1 MB is too small
        env={"CLAUDE_AGENT_SDK_CLIENT_APP": "assembly-guide-agent"},
    )
    emit("thought", f"Reading the guide and writing the plan and the parts ({MODEL}). This takes several minutes.")
    state = {"builds": 0, "passed": 0, "bash": set(), "checks": 0, "check": set()}
    result, t0 = None, time.perf_counter()
    try:
        async with asyncio.timeout(MAX_MIN * 60):
            async for message in query(prompt=PROMPT, options=options):
                _report(message, ws, emit, state)
                if isinstance(message, ResultMessage):
                    result = message
    except TimeoutError:
        emit("thought", f"The agent was stopped after {MAX_MIN:.0f} minutes.", level="warn")
    except ClaudeSDKError as e:              # the session broke; what it wrote so far is still checked below
        state["sdk_error"] = f"{type(e).__name__}: {e}"[:300]
        emit("thought", f"The agent session ended early ({type(e).__name__}).", level="warn")
    stats = {"model": MODEL, "wall_s": round(time.perf_counter() - t0, 1), "builds": state["builds"],
             "builds_passed": state["passed"], "checks": state["checks"], "turns": getattr(result, "num_turns", None),
             "cost_usd": getattr(result, "total_cost_usd", None), "subtype": getattr(result, "subtype", "no result"),
             "denied": len(getattr(result, "permission_denials", None) or []), "sdk_error": state.get("sdk_error")}
    # trust the pipeline, not the agent's word: build once more ourselves
    plan_path = ws / "job" / "plan.json"
    if not plan_path.is_file():
        (ws / "run.json").write_text(json.dumps(stats, indent=1))
        raise RuntimeError(f"the agent wrote no plan ({stats['subtype']}, {stats['turns']} turns)")
    ok, tail = await asyncio.to_thread(run_build, ws)
    stats["final_build_ok"] = ok
    if not ok:
        (ws / "run.json").write_text(json.dumps(stats, indent=1))
        raise RuntimeError("the agent's plan and parts do not pass the fit checks: " + tail[-600:])
    plan = json.loads(plan_path.read_text())
    stats["step_check"] = await asyncio.to_thread(final_check, ws, plan, emit)
    (ws / "run.json").write_text(json.dumps(stats, indent=1))
    plan.pop("guide", None)
    if (job / "guide.pdf").is_file():
        plan["guide"] = str((job / "guide.pdf").resolve())
    (job / "parts").mkdir(exist_ok=True)
    for f in (ws / "job" / "parts").glob("*.py"):
        shutil.copyfile(f, job / "parts" / f.name)
    (job / "plan.json").write_text(json.dumps(plan, indent=1, ensure_ascii=False))
    cost = f", ${stats['cost_usd']:.2f}" if stats["cost_usd"] is not None else ""
    emit("thought", f"Plan and parts written and checked: {stats['wall_s']:.0f} s, {stats['turns']} turns, "
                    f"{stats['builds']} builds{cost}.", level="success")
    return stats


def final_check(ws: Path, plan: dict, emit) -> dict:
    """Check every step once more against the guide's drawings. A failing step gets a flag in `plan` (for the
    person who approves it) and a warning; it never fails the job."""
    try:
        r = step_check.run(ws / "job" / "plan.json", ws / "job" / "out")
    except Exception as e:                                     # noqa: BLE001 - no key, verifier down: advice only
        emit("tool_result", f"The drawing check did not run ({type(e).__name__}).", tool="check", level="warn")
        return {"error": f"{type(e).__name__}: {e}"[:300]}
    flags = plan.setdefault("flags", [])
    for res in r["results"]:
        if "pass" in res and not res["pass"] and not step_check.flagged(plan, res["n"]):
            flags.append({"ref": f"step:{res['n']}", "kind": "unclear_drawing",
                          "message": f"{step_check.FLAG_PREFIX} the render may differ from the guide: " + "; ".join(res["fix"])})
    emit("tool_result", "Final drawing check: " + _check_summary(r), tool="check", level=_check_level(r))
    return {k: r[k] for k in ("verifier", "checked", "passed", "mean_score", "failed", "errors", "wall_s")} | {
        "scores": {str(x["n"]): x["score"] for x in r["results"] if "score" in x}}


if os.environ.get("AGJ_BUILD_AGENT") == "claude":           # the server looks for this name (server/orchestrator/stages.py)
    async def extract_plan(job_dir: Path, emit) -> None:
        facts = job_dir / "facts.txt"
        await run_agent(Path(job_dir), emit, facts.read_text().strip() if facts.is_file() else "")


def _cli(guide: Path, job: Path, extra: str) -> int:
    import pymupdf
    job.mkdir(parents=True, exist_ok=True)
    (job / "pages").mkdir(exist_ok=True)
    shutil.copyfile(guide, job / "guide.pdf")
    doc = pymupdf.open(guide)
    for i, page in enumerate(doc, start=1):                    # 200 dpi, as the server's ingest stage does
        page.get_pixmap(dpi=200, alpha=False).save(job / "pages" / f"page-{i:03d}.png")
    log = (job / "agent_events.jsonl").open("w")

    def emit(type, text="", **fields):
        log.write(json.dumps({"t": round(time.time(), 1), "type": type, "text": text, **fields}) + "\n"); log.flush()
        print(f"[{type}] {text}", flush=True)

    try:
        stats = asyncio.run(run_agent(job, emit, extra))
    except RuntimeError as e:
        print(f"FAILED {e}")
        return 1
    print("RESULT " + json.dumps(stats))
    return 0


if __name__ == "__main__":
    sys.exit(_cli(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3] if len(sys.argv) > 3 else ""))
