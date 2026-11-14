"""REST + SSE routes for jobs (PLAN §4.5). Every route here needs the demo key."""
from __future__ import annotations

import json
import mimetypes
import re

from fastapi import APIRouter, Depends, File, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel

from server.auth import require_key
from server.jobs import STAGES, Job
from server.orchestrator.stages import validate_plan

mimetypes.add_type("model/gltf-binary", ".glb")
mimetypes.add_type("application/jsonl", ".jsonl")

router = APIRouter(dependencies=[Depends(require_key)])
CROP_RE = re.compile(r"^(part|step)-(.+)\.png$")


class ApproveBody(BaseModel):
    plan: dict | None = None        # the edited plan; omit to approve plan.json as extracted
    note: str | None = None


class RetryBody(BaseModel):
    from_stage: str


def get_job(job_id: str, request: Request) -> Job:
    job = request.app.state.store.get(job_id)
    if job is None:
        raise HTTPException(404, "No such job")
    return job


def summary(job: Job, request: Request) -> dict:
    s = job.status()
    out = {"job_id": job.id, "stage": s.get("stage"), "status": s.get("status"),
           "progress": s.get("progress", 0.0), "last_seq": request.app.state.store.log(job).last_seq,
           "title": s.get("title"), "filename": s.get("filename"), "created": s.get("created")}
    if s.get("error"):
        out["error"] = s["error"]
    return out


@router.post("/api/jobs")
async def create_job(request: Request, file: UploadFile = File(...)) -> dict:
    limit = request.app.state.settings.max_upload_mb * 1024 * 1024
    data = await file.read(limit + 1)
    if len(data) > limit:
        raise HTTPException(413, f"The guide is larger than {limit // (1024 * 1024)} MB")
    if b"%PDF-" not in data[:1024]:
        raise HTTPException(415, "The guide must be a PDF file")
    job = request.app.state.store.create(data, file.filename or "guide.pdf")
    request.app.state.orch.start(job)
    return {"job_id": job.id, "stage": "ingesting"}


@router.get("/api/jobs")
async def list_jobs(request: Request) -> dict:
    return {"jobs": [summary(j, request) for j in request.app.state.store.all()[:50]]}


@router.get("/api/jobs/{job_id}")
async def job_status(request: Request, job: Job = Depends(get_job)) -> dict:
    return summary(job, request)


@router.get("/api/jobs/{job_id}/events")
async def job_events(request: Request, since: int | None = None, job: Job = Depends(get_job)) -> StreamingResponse:
    """SSE stream of §4.4 events. `id:` is the event's seq, so a reconnecting EventSource resumes by itself
    (`Last-Event-ID`); `?since=<seq>` does the same by hand."""
    try:
        after = since if since is not None else int(request.headers.get("last-event-id") or 0)
    except ValueError:
        after = 0
    log = request.app.state.store.log(job)

    async def stream():
        yield "retry: 2000\n\n"
        async for event in log.follow(after):
            if event is None:
                yield ": ping\n\n"
            else:
                yield f"id: {event['seq']}\ndata: {json.dumps(event, ensure_ascii=False)}\n\n"

    return StreamingResponse(stream(), media_type="text/event-stream", headers={
        "Cache-Control": "no-cache, no-transform", "X-Accel-Buffering": "no", "Connection": "keep-alive"})


@router.get("/api/jobs/{job_id}/plan")
async def job_plan(job: Job = Depends(get_job)) -> dict:
    """The plan (the approved one once it exists), with the URL of every crop and page image."""
    approved = job.approved_plan.is_file()
    source = job.approved_plan if approved else job.plan
    if not source.is_file():
        raise HTTPException(404, "The plan is not ready yet")
    crops = []
    for p in sorted((job.dir / "crops").glob("*.png")):
        m = CROP_RE.match(p.name)
        if m:
            ref_id = m.group(2).lstrip("0") or "0" if m.group(1) == "step" else m.group(2)
            crops.append({"ref": f"{m.group(1)}:{ref_id}", "url": job.url(f"crops/{p.name}")})
    pages = [job.url(f"pages/{p.name}") for p in sorted((job.dir / "pages").glob("page-*.png"))]
    return {"job_id": job.id, "approved": approved, "plan": json.loads(source.read_text()),
            "crops": crops, "pages": pages}


@router.post("/api/jobs/{job_id}/plan/approve")
async def approve_plan(request: Request, body: ApproveBody | None = None, job: Job = Depends(get_job)) -> dict:
    if job.status().get("status") != "waiting_approval":
        raise HTTPException(409, "This job is not waiting for approval")
    plan = (body.plan if body else None) or json.loads(job.plan.read_text())
    problems = validate_plan(plan)
    if problems:
        raise HTTPException(422, "The plan cannot be approved: " + "; ".join(problems))
    request.app.state.orch.approve(job, plan, body.note if body else None)
    return {"stage": "building"}


@router.post("/api/jobs/{job_id}/retry")
async def retry_job(request: Request, body: RetryBody, job: Job = Depends(get_job)) -> dict:
    if body.from_stage not in STAGES:
        raise HTTPException(422, f"from_stage must be one of: {', '.join(STAGES)}")
    await request.app.state.orch.retry(job, body.from_stage)
    return {"stage": body.from_stage}


@router.get("/files/{job_id}/{path:path}")
async def job_file(path: str, job: Job = Depends(get_job)) -> FileResponse:
    target = job.resolve(path)
    if target is None:
        raise HTTPException(404, "No such file")
    return FileResponse(target, media_type=mimetypes.guess_type(target.name)[0],
                        headers={"Cache-Control": "no-cache"})
