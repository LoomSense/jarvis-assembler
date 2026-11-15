"""Command line for the Assembly Guide Agent, for scripts and for agents that run shell commands.

    python -m server.cli find "KNAGGLIG" --wait          # name, article number, barcode or link -> a finished guide
    python -m server.cli build guide.pdf --wait          # a PDF on disk -> a finished guide
    python -m server.cli search chair
    python -m server.cli list
    python -m server.cli status <job_id>
    python -m server.cli wait <job_id> [--until approval|done] [--timeout 900]
    python -m server.cli plan <job_id>
    python -m server.cli approve <job_id>
    python -m server.cli steps <job_id> [--lang el]
    python -m server.cli links <job_id>
    python -m server.cli ask <job_id> <step> "which screw goes here?" [--lang el]

Every command prints one JSON object on stdout and exits 0. A failure prints {"error": "..."} on stderr and
exits 1 (2 for a wrong command line). `--wait` follows the build to the end and approves the plan on the way, so
one command goes from a product name or a PDF to the steps and the links.

It is a thin client of the HTTP API, through the same functions as the MCP server (server/mcp_server.py):
AGJ_BASE_URL (default http://127.0.0.1:8000) and AGJ_DEMO_KEY (else DEMO_KEY from the environment or .env).
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys

from server import mcp_server as M


async def finish(job_id: str, timeout_s: int, lang: str) -> dict:
    """Follow a build to the end, approving the plan when it asks, then return the steps and the links."""
    status = await M.guide_status(job_id)
    if status.get("status") != "done":
        out = await M.wait_for_guide(job_id, until="approval", timeout_s=timeout_s)
        if (out.get("status") or {}).get("status") == "waiting_approval" or out.get("reached") == "approval":
            await M.approve_plan(job_id, note="approved by the command line (--wait)")
        out = await M.wait_for_guide(job_id, until="done", timeout_s=timeout_s)
        status = await M.guide_status(job_id)
        if status.get("status") != "done":
            return {"job_id": job_id, "done": False, "status": status, "last": out}
    steps = await M.get_steps(job_id, lang=lang)
    return {"job_id": job_id, "done": True, "title": steps.get("title"), "step_count": steps.get("step_count"),
            "links": await M.guide_links(job_id), "steps": steps.get("steps")}


def started(out: dict) -> dict:
    """What the first call said, without the fields that are stale once the build has been followed to the end."""
    return {k: v for k, v in out.items() if k not in ("next", "stage", "ready")}


async def run(a: argparse.Namespace) -> dict:
    if a.cmd == "find":
        out = await M.find_guide(a.query, size=a.size)
        if a.wait and out.get("found"):
            return {**started(out), **await finish(out["job_id"], a.timeout, a.lang)}
        return out
    if a.cmd == "build":
        out = await M.create_guide(a.pdf, size=a.size)
        return {**started(out), **await finish(out["job_id"], a.timeout, a.lang)} if a.wait else out
    if a.cmd == "search":
        return await M.search_guides(a.query)
    if a.cmd == "list":
        return await M.list_guides()
    if a.cmd == "status":
        return await M.guide_status(a.job_id)
    if a.cmd == "wait":
        return await M.wait_for_guide(a.job_id, until=a.until, timeout_s=a.timeout)
    if a.cmd == "plan":
        return await M.get_plan(a.job_id)
    if a.cmd == "approve":
        return await M.approve_plan(a.job_id, note=a.note)
    if a.cmd == "steps":
        return await M.get_steps(a.job_id, lang=a.lang)
    if a.cmd == "links":
        return await M.guide_links(a.job_id)
    if a.cmd == "ask":
        return await M.ask_guide(a.job_id, a.step, a.question, lang=a.lang)
    raise M.ToolError(f"unknown command {a.cmd}")


def parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="python -m server.cli", description=__doc__.split("\n\n")[0],
                                 epilog="Settings: AGJ_BASE_URL, AGJ_DEMO_KEY. Output: one JSON object per command.")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def add(name: str, help: str, job: bool = False) -> argparse.ArgumentParser:
        p = sub.add_parser(name, help=help)
        if job:
            p.add_argument("job_id")
        return p

    def waits(p: argparse.ArgumentParser) -> None:
        p.add_argument("--size", help='overall size of the finished product, e.g. "46 x 31 x 25 cm"')
        p.add_argument("--wait", action="store_true", help="follow the build to the end and approve the plan")
        p.add_argument("--timeout", type=int, default=900, help="seconds to wait for each stage (default 900)")
        p.add_argument("--lang", choices=["en", "el"], default="en")

    p = add("find", "open the guide for a product name, article number, barcode or link")
    p.add_argument("query")
    waits(p)
    p = add("build", "turn a PDF guide into a walkthrough")
    p.add_argument("pdf")
    waits(p)
    add("search", "search the catalog without starting anything").add_argument("query", nargs="?", default="")
    add("list", "all jobs, newest first, and the catalog")
    add("status", "stage, status and progress of a job", job=True)
    p = add("wait", "wait until the plan needs approval or the guide is done", job=True)
    p.add_argument("--until", choices=["approval", "done"], default="done")
    p.add_argument("--timeout", type=int, default=900)
    add("plan", "the plan: parts, hardware, steps, flags", job=True)
    add("approve", "approve the plan so the 3D build starts", job=True).add_argument("--note")
    add("steps", "the steps of a finished guide", job=True).add_argument("--lang", choices=["en", "el"], default="en")
    add("links", "phone and headset links for the person", job=True)
    p = add("ask", "ask a question about one step", job=True)
    p.add_argument("step", type=int)
    p.add_argument("question")
    p.add_argument("--lang", choices=["en", "el"], default="en")
    return ap


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    try:
        out = asyncio.run(run(args))
    except M.ToolError as e:
        print(json.dumps({"error": str(e)}, ensure_ascii=False), file=sys.stderr)
        return 1
    print(json.dumps(out, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
