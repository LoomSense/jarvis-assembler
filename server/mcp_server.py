"""MCP server for the Assembly Guide Agent: lets any MCP client (Claude Code, Claude Desktop, ...) turn a furniture
assembly guide (PDF) into a 3D step-by-step walkthrough, follow the build, approve the plan and read the steps.

It is a thin client of the HTTP API in README.md: it needs a running backend and never touches the job folder itself.

    python -m server.mcp_server                      # stdio (what Claude Code and Claude Desktop launch)
    python -m server.mcp_server --http --port 8765   # streamable HTTP at http://127.0.0.1:8765/mcp

Configuration: AGJ_BASE_URL (default http://127.0.0.1:8000) and AGJ_DEMO_KEY (else DEMO_KEY from the environment or
from the repo's .env). The key goes in the X-Demo-Key header and is never part of an error message.
"""
from __future__ import annotations

import argparse
import logging
import os
import re
import time
from collections import deque
from pathlib import Path
from typing import Any, Literal
from urllib.parse import urlparse

import httpx
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ResourceError, ToolError
from mcp.types import ToolAnnotations

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BASE = "http://127.0.0.1:8000"
JOB_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
LOCAL_HOSTS = {"127.0.0.1", "localhost", "::1", "0.0.0.0"}
POLL_WAIT_S = 20

READ = ToolAnnotations(read_only_hint=True, open_world_hint=False)

mcp = MCPServer(
    name="assembly-guide",
    title="Assembly Guide Agent",
    instructions=(
        "Turns a flat-pack furniture assembly guide (PDF) into a 3D step-by-step walkthrough. Usual flow: "
        "create_guide -> wait_for_guide(until='approval') -> get_plan (show the person the parts, hardware and "
        "flags) -> approve_plan -> wait_for_guide(until='done') -> get_steps and guide_links (give the person the "
        "phone link). list_guides shows existing guides; a finished one can be read with get_steps right away."
    ),
)


# ---- configuration and HTTP ----------------------------------------------------------------------

def base_url() -> str:
    return (os.environ.get("AGJ_BASE_URL") or DEFAULT_BASE).rstrip("/")


def demo_key() -> str:
    key = os.environ.get("AGJ_DEMO_KEY") or os.environ.get("DEMO_KEY")
    if not key and (ROOT / ".env").is_file():
        from dotenv import dotenv_values
        key = dotenv_values(ROOT / ".env").get("DEMO_KEY")
    return (key or "").strip()


def is_local() -> bool:
    return (urlparse(base_url()).hostname or "") in LOCAL_HOSTS


def check_job_id(job_id: str) -> str:
    job_id = (job_id or "").strip()
    if not JOB_ID_RE.match(job_id):
        raise ToolError(f"'{job_id[:80]}' is not a valid job id (letters, digits, '-' and '_', e.g. 1115-0453-7ca85e)")
    return job_id


def link(path: str) -> str:
    """A link to a backend path. With a local backend it is a full URL that opens in a browser (with ?key=);
    otherwise it is the path alone, so the key never leaves this machine inside a tool result."""
    if not path.startswith("/"):
        path = "/" + path
    if is_local():
        sep = "&" if "?" in path else "?"
        return f"{base_url()}{path}{sep}key={demo_key()}"
    return path


def _scrub(text: str) -> str:
    key = demo_key()
    return text.replace(key, "<key>") if key else text


def _detail(r: httpx.Response) -> str:
    try:
        body = r.json()
        detail = body.get("detail", body) if isinstance(body, dict) else body
    except ValueError:
        detail = r.text
    return _scrub(str(detail)[:500])


async def api(method: str, path: str, *, timeout: float = 30.0, ok404: bool = False, **kw: Any) -> Any:
    """Calls the backend and returns its JSON. Failures become ToolErrors with a readable message."""
    key = demo_key()
    if not key:
        raise ToolError("No demo key: set AGJ_DEMO_KEY (or DEMO_KEY in the repo's .env) for the MCP server")
    try:
        async with httpx.AsyncClient(base_url=base_url(), headers={"X-Demo-Key": key}, timeout=timeout) as c:
            r = await c.request(method, path, **kw)
    except httpx.TimeoutException:
        raise ToolError(f"The backend at {base_url()} did not answer {method} {path} in time") from None
    except httpx.HTTPError as e:
        raise ToolError(f"Cannot reach the Assembly Guide backend at {base_url()} ({type(e).__name__}). "
                        "Is it running? Set AGJ_BASE_URL if it is elsewhere.") from None
    if r.status_code == 404 and ok404:
        return None
    if r.status_code == 401:
        raise ToolError("The backend refused the demo key: set AGJ_DEMO_KEY to the server's DEMO_KEY")
    if r.status_code == 404:
        raise ToolError(f"Not found: {_detail(r)} ({method} {path})")
    if r.status_code >= 400:
        raise ToolError(f"The backend answered {r.status_code}: {_detail(r)}")
    try:
        return r.json()
    except ValueError:
        raise ToolError(f"The backend answered {method} {path} with something that is not JSON") from None


def _event_line(e: dict) -> dict:
    return {"seq": e.get("seq"), "stage": e.get("stage"), "type": e.get("type"), "text": e.get("text")}


NEXT = {
    "waiting_approval": "Call get_plan, show the person the parts, hardware and flags, then approve_plan.",
    "done": "Call get_steps for the steps and guide_links for the phone and headset links.",
    "error": "The job stopped with an error; read `error`. A known guide can be retried from the web app.",
}


def _with_next(status: dict) -> dict:
    hint = NEXT.get(status.get("status") or "")
    return {**status, "next": hint} if hint else status


# ---- tools ---------------------------------------------------------------------------------------

@mcp.tool()
async def create_guide(pdf_path: str, size: str | None = None) -> dict[str, Any]:
    """Start turning a furniture assembly guide into a 3D walkthrough.

    pdf_path: path to a local PDF assembly guide (e.g. an IKEA instruction leaflet).
    size: optional overall size of the finished product as the person reads it from the shop page,
          e.g. "46 x 31 x 25 cm". It gives the model its true scale; without it every size is an estimate.

    Returns the new job_id. Next call wait_for_guide(job_id, until="approval").
    """
    path = Path(pdf_path).expanduser()
    if not path.is_file():
        raise ToolError(f"No file at {path}")
    data = path.read_bytes()
    if b"%PDF-" not in data[:1024]:
        raise ToolError(f"{path.name} is not a PDF")
    form = {"size": size.strip()} if size and size.strip() else None
    out = await api("POST", "/api/jobs", timeout=120.0, data=form,
                    files={"file": (path.name, data, "application/pdf")})
    return {**out, "next": "Call wait_for_guide(job_id, until='approval'); known guides get there in seconds."}


@mcp.tool(annotations=READ)
async def guide_status(job_id: str) -> dict[str, Any]:
    """Current state of one guide job: stage, status (queued, running, waiting_approval, done, error), progress 0-1,
    title, error message if any, and a hint for what to do next."""
    return _with_next(await api("GET", f"/api/jobs/{check_job_id(job_id)}"))


async def _settled(job_id: str, status: dict, until: str) -> str | None:
    s = status.get("status")
    if s in ("done", "error"):
        return s
    if s == "waiting_approval":
        if until == "approval":
            return "approval"
        plan = await api("GET", f"/api/jobs/{job_id}/plan", ok404=True)
        if not (plan or {}).get("approved"):   # just approved, the runner has not picked it up yet: keep waiting
            return "approval"
    return None


@mcp.tool(annotations=READ)
async def wait_for_guide(job_id: str, until: Literal["approval", "done"] = "done",
                         timeout_s: int = 900) -> dict[str, Any]:
    """Wait for a guide job to reach a point where the person or you must act.

    until="approval": return when the plan is ready for review (or the job finished or failed).
    until="done": return when the 3D walkthrough is ready, the job failed, or the plan still needs approval.
    timeout_s: give up after this many seconds (default 900; known guides take under a minute after approval,
               new guides about 10 minutes). On timeout, call it again.

    Returns `reached` (approval, done, error or timeout), the final status and the last few progress messages.
    """
    job_id = check_job_id(job_id)
    timeout_s = max(1, min(int(timeout_s), 3600))
    deadline = time.monotonic() + timeout_s
    recent: deque[dict] = deque(maxlen=8)
    status = await api("GET", f"/api/jobs/{job_id}")
    if (reached := await _settled(job_id, status, until)) is None:
        since = int(status.get("last_seq") or 0)
    else:                                      # already there: show what led to it
        since = max(0, int(status.get("last_seq") or 0) - 8)
    while reached is None:
        left = deadline - time.monotonic()
        if left <= 0:
            reached = "timeout"
            break
        wait = max(1, min(POLL_WAIT_S, int(left)))
        out = await api("GET", f"/api/jobs/{job_id}/events/poll", params={"since": since, "wait": wait},
                        timeout=wait + 15)
        for e in out.get("events", []):
            recent.append(_event_line(e))
        since = max(since, int(out.get("last_seq") or since))
        status = await api("GET", f"/api/jobs/{job_id}")
        reached = await _settled(job_id, status, until)
    if not recent:
        out = await api("GET", f"/api/jobs/{job_id}/events/poll", params={"since": since, "wait": 0})
        recent.extend(_event_line(e) for e in out.get("events", [])[-8:])
    return {"reached": reached, "status": _with_next(status), "recent_events": list(recent)}


@mcp.tool(annotations=READ)
async def get_plan(job_id: str) -> dict[str, Any]:
    """The build plan the agent read from the guide, for the person to check before the 3D build:
    parts (id, name, count), hardware (printed code, kind, count), steps (number, title, what each adds),
    flags (things the agent was unsure about: show these to the person), whether it is approved, links to the
    guide page images and crops, and the raw plan JSON under `plan`."""
    job_id = check_job_id(job_id)
    out = await api("GET", f"/api/jobs/{job_id}/plan")
    plan = out.get("plan") or {}
    return {
        "job_id": job_id,
        "title": plan.get("title"),
        "approved": out.get("approved"),
        "parts": [{"id": p.get("id"), "name": p.get("name"), "count": p.get("count")} for p in plan.get("parts", [])],
        "hardware": [{"code": h.get("code"), "name": h.get("name") or h.get("kind"), "kind": h.get("kind"),
                      "count": h.get("count")} for h in plan.get("hardware", [])],
        "steps": [{"n": s.get("n"), "title": s.get("title"), "page": (s.get("cite") or {}).get("page"),
                   "parts_added": s.get("parts_added", []), "hardware_used": s.get("hardware_used", {})}
                  for s in plan.get("steps", [])],
        "flags": plan.get("flags", []),
        "pages": [link(u) for u in out.get("pages", [])],
        "crops": [{"ref": c.get("ref"), "url": link(c.get("url", ""))} for c in out.get("crops", [])],
        "plan": plan,
        "next": None if out.get("approved") else "If the person agrees, call approve_plan(job_id).",
    }


@mcp.tool()
async def approve_plan(job_id: str, note: str | None = None) -> dict[str, Any]:
    """Approve the plan of a job that is waiting for approval, so the 3D build starts.
    Only call this after the person has seen the plan (get_plan) and agreed, or asked you to go ahead.
    note: optional remark recorded with the approval."""
    job_id = check_job_id(job_id)
    out = await api("POST", f"/api/jobs/{job_id}/plan/approve", json={"note": note} if note else {})
    return {**out, "job_id": job_id, "next": "Call wait_for_guide(job_id, until='done')."}


def _name(d: dict, lang: str) -> str:
    names = d.get("names") or {}
    return names.get(lang) or names.get("en") or d.get("short") or d.get("name") or str(d.get("id") or d.get("code"))


@mcp.tool(annotations=READ)
async def get_steps(job_id: str, lang: Literal["en", "el"] = "en") -> dict[str, Any]:
    """The finished walkthrough, step by step, for reading out or guiding the person through the build.

    lang: "en" (English) or "el" (Greek) for part names and action texts.

    Per step: number, title, guide page, parts to pick up, hardware with printed code and quantity, the ordered
    actions as sentences, the drawing-check score if any, and links to the step's render and the guide's drawing.
    Works once wait_for_guide reports done.
    """
    job_id = check_job_id(job_id)
    m = await api("GET", f"/files/{job_id}/manifest.json", ok404=True)
    if m is None:
        status = await api("GET", f"/api/jobs/{job_id}")
        raise ToolError(f"The walkthrough is not ready: the job is at stage {status.get('stage')} "
                        f"({status.get('status')}). Call wait_for_guide(job_id, until='done').")
    parts = {p.get("id"): p for p in m.get("parts", [])}
    hardware = {h.get("code"): h for h in m.get("hardware", [])}
    files = f"/files/{job_id}/"
    steps = []
    for s in m.get("steps", []):
        check = s.get("check") or {}
        steps.append({
            "n": s.get("n"),
            "title": s.get("title"),
            "page": (s.get("cite") or {}).get("page"),
            "pick_up": [{"id": pid, "name": _name(parts.get(pid, {"id": pid}), lang)} for pid in s.get("pickParts", [])],
            "hardware": [{"code": h.get("code"), "name": _name(hardware.get(h.get("code"), {"code": h.get("code")}), lang),
                          "qty": h.get("qty")} for h in s.get("hardware", [])],
            "actions": [(a.get("text") or {}).get(lang) or (a.get("text") or {}).get("en", "")
                        for a in s.get("actions", [])],
            "check_score": check.get("score"),
            "render": link(files + s["refRender"]) if s.get("refRender") else None,
            "guide_crop": link(files + s["crop"]) if s.get("crop") else None,
            "flags": s.get("flags", []),
        })
    return {"job_id": job_id, "title": m.get("title"), "lang": lang, "step_count": len(steps),
            "glb": link(files + m["glb"]) if m.get("glb") else None, "flags": m.get("flags", []), "steps": steps}


@mcp.tool(annotations=READ)
async def guide_links(job_id: str) -> dict[str, str]:
    """Links to give the person: `phone` opens the 3D walkthrough on a phone or laptop browser, `headset` opens the
    mixed-reality version in the Meta Quest browser. They carry the demo key, so share them only with that person."""
    job_id = check_job_id(job_id)
    await api("GET", f"/api/jobs/{job_id}")             # the job must exist
    phone = f"{base_url()}/?job={job_id}&key={demo_key()}"
    out = {"phone": phone, "headset": phone + "&mode=xr", "phone_greek": phone + "&lang=el"}
    if is_local():
        out["note"] = "These point at a local backend; a phone needs the public URL (AGJ_BASE_URL) instead."
    return out


@mcp.tool(annotations=READ)
async def list_guides() -> dict[str, Any]:
    """All guide jobs on the backend, newest first (job_id, title, stage, status), plus the catalog of ready-made
    guides when the backend offers one. A job with status done can be read with get_steps right away."""
    jobs = (await api("GET", "/api/jobs")).get("jobs", [])
    catalog = await api("GET", "/api/catalog", ok404=True)
    keep = ("job_id", "title", "stage", "status", "progress", "filename", "created", "error")
    return {"jobs": [{k: j.get(k) for k in keep if j.get(k) is not None} for j in jobs],
            "catalog": catalog}


@mcp.resource("guide://{job_id}/manifest", name="manifest", mime_type="application/json",
              description="The finished walkthrough's manifest.json (parts, hardware, steps, actions, cameras).")
async def manifest_resource(job_id: str) -> str:
    if not JOB_ID_RE.match(job_id or ""):
        raise ResourceError("Not a valid job id")
    try:
        key = demo_key()
        async with httpx.AsyncClient(base_url=base_url(), headers={"X-Demo-Key": key}, timeout=30) as c:
            r = await c.get(f"/files/{job_id}/manifest.json")
    except httpx.HTTPError:
        raise ResourceError(f"Cannot reach the backend at {base_url()}") from None
    if r.status_code != 200:
        raise ResourceError(f"No manifest for job {job_id} ({r.status_code})")
    return r.text


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--http", action="store_true", help="serve streamable HTTP instead of stdio")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8765)
    args = ap.parse_args(argv)
    logging.getLogger("httpx").setLevel(logging.WARNING)   # one INFO line per request would flood the client's log
    if args.http:
        mcp.run("streamable-http", host=args.host, port=args.port)
    else:
        mcp.run()


if __name__ == "__main__":
    main()
