"""Device check reports. The headset page `/xr-check.html` posts its results here, so they land on the host
as files under `jobs/_diag/` instead of staying inside the headset's browser. Every route needs the demo key.
"""
from __future__ import annotations

import json
import re
import time
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Request

from server.auth import require_key
from server.jobs import write_atomic

router = APIRouter(dependencies=[Depends(require_key)])
MAX_BYTES = 256 * 1024          # a report with a few small camera thumbnails
KEEP = 50                       # oldest reports are deleted beyond this
NAME_RE = re.compile(r"^\d{8}-\d{6}-\d{3}-[a-z0-9-]{1,24}\.json$")


def _folder(request: Request) -> Path:
    # `_diag` does not match the job id pattern, so the job store never lists it as a job
    folder = request.app.state.settings.jobs_dir / "_diag"
    folder.mkdir(parents=True, exist_ok=True)
    return folder


def _names(folder: Path) -> list[str]:
    return sorted((p.name for p in folder.iterdir() if NAME_RE.match(p.name)), reverse=True)


@router.post("/api/diag")
async def save_report(request: Request) -> dict:
    raw = await request.body()
    if len(raw) > MAX_BYTES:
        raise HTTPException(413, "The report is larger than 256 KB")
    try:
        report = json.loads(raw)
    except ValueError:
        raise HTTPException(400, "The report is not JSON") from None
    if not isinstance(report, dict):
        raise HTTPException(400, "The report must be a JSON object")
    kind = re.sub(r"[^a-z0-9]+", "-", str(report.get("kind") or "report").lower()).strip("-")[:24] or "report"
    now = time.time()
    name = f"{time.strftime('%Y%m%d-%H%M%S', time.localtime(now))}-{int(now * 1000) % 1000:03d}-{kind}.json"
    folder = _folder(request)
    write_atomic(folder / name, json.dumps({"received": int(now * 1000), "kind": kind,
                                            "user_agent": request.headers.get("user-agent", "")[:300],
                                            "report": report}, indent=1, ensure_ascii=False))
    for old in _names(folder)[KEEP:]:
        (folder / old).unlink(missing_ok=True)
    return {"saved": name}


@router.get("/api/diag")
async def list_reports(request: Request) -> dict:
    """Newest first. `summary` is whatever the report put under that key."""
    folder, out = _folder(request), []
    for name in _names(folder):
        try:
            stored = json.loads((folder / name).read_text())
        except (OSError, ValueError):
            continue
        out.append({"name": name, "received": stored.get("received"), "kind": stored.get("kind"),
                    "user_agent": stored.get("user_agent"), "summary": stored.get("report", {}).get("summary")})
    return {"reports": out}


@router.get("/api/diag/{name}")
async def get_report(name: str, request: Request) -> dict:
    if not NAME_RE.match(name):
        raise HTTPException(404, "No such report")
    path = _folder(request) / name
    if not path.is_file():
        raise HTTPException(404, "No such report")
    return json.loads(path.read_text())
