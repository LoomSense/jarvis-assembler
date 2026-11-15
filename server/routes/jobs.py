"""REST + SSE routes for jobs (PLAN §4.5). Every route here needs the demo key."""
from __future__ import annotations

import json
import mimetypes
import re

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool

from pipeline import inventory
from server import photos
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
           "title": s.get("title"), "filename": s.get("filename"), "created": s.get("created"),
           "source": s.get("source", "pdf")}
    if s.get("error"):
        out["error"] = s["error"]
    return out


@router.post("/api/jobs")
async def create_job(request: Request, file: UploadFile | None = File(None),
                     pages: list[UploadFile] | None = File(None), size: str | None = Form(None)) -> dict:
    """The guide is either a PDF in `file`, or photos of its pages (JPEG, PNG, WebP, HEIC when supported) in the
    repeated field `pages`, in page order; one photo may also come in `file`. Photos become guide.pdf (server/photos.py).
    `size`: optional overall size typed by the person, e.g. "62 x 60 x 135 cm". Guides print no dimensions, so this
    is what gives the model its true scale (needed for the headset); without it every size is an estimate."""
    limit = request.app.state.settings.max_upload_mb * 1024 * 1024
    uploads = ([file] if file is not None else []) + list(pages or [])
    if not uploads:
        raise HTTPException(422, "Send the guide as a PDF in `file` or photos of its pages in `pages`")
    if len(uploads) > photos.MAX_PHOTOS:
        raise HTTPException(422, f"Send at most {photos.MAX_PHOTOS} photos; got {len(uploads)}")
    blobs, total = [], 0
    for up in uploads:
        data = await up.read(limit + 1 - total)
        total += len(data)
        if total > limit:
            raise HTTPException(413, f"The upload is larger than {limit // (1024 * 1024)} MB")
        blobs.append(data)
    is_pdf = [b"%PDF-" in data[:1024] for data in blobs]
    if any(is_pdf) and len(blobs) > 1:
        raise HTTPException(422, "Send either one guide PDF or photos of its pages, not both")

    if is_pdf[0]:
        job = start_job(request, blobs[0], file.filename or "guide.pdf", size, source="pdf")
    else:
        if file is not None and photos.sniff(blobs[0]) is None:
            raise HTTPException(415, "The guide must be a PDF file or photos of its pages")
        try:
            pdf, kinds, flattened = await run_in_threadpool(photos.to_pdf, blobs)
        except photos.PhotoError as e:
            raise HTTPException(e.status, str(e))
        name = (uploads[0].filename or "photos") if len(uploads) == 1 else f"{len(uploads)} photos"
        job = start_job(request, pdf, name, size, source="photos", pages_uploaded=len(blobs), pages_flattened=flattened)
        (job.dir / "photos").mkdir(exist_ok=True)
        for i, (data, kind) in enumerate(zip(blobs, kinds), start=1):
            (job.dir / "photos" / f"page-{i:03d}.{kind}").write_bytes(data)
    return {"job_id": job.id, "stage": "ingesting"}


def start_job(request: Request, pdf: bytes, filename: str, size: str | None, files: dict[str, bytes] | None = None,
              **status) -> Job:
    """Create the job folder and start the build. Shared by uploads and the catalog. `files` are written into the
    job folder first (the catalog's product photo)."""
    job = request.app.state.store.create(pdf, filename)
    for name, data in (files or {}).items():
        (job.dir / name).write_bytes(data)
    size = (size or "").strip()[:120]
    if size:
        (job.dir / "facts.txt").write_text(
            f"- The person who uploaded the guide gave the product's overall size: {size}. "
            "Use it as the true scale; it overrides any size you would estimate.\n", encoding="utf-8")
        status["size"] = size
    if status:
        job.set_status(**status)
    request.app.state.orch.start(job)
    return job


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


@router.get("/api/jobs/{job_id}/events/poll")
async def poll_events(request: Request, since: int = 0, wait: float = 20.0, job: Job = Depends(get_job)) -> dict:
    """Long-poll fallback for proxies that buffer SSE (a trycloudflare quick tunnel does).
    Returns the events with seq > `since` at once, or waits up to `wait` seconds (max 25) for the next one."""
    log = request.app.state.store.log(job)
    events = log.read(since)
    if not events and wait > 0:
        async for event in log.follow(since, heartbeat=min(wait, 25.0)):
            events = log.read(since) if event else []
            break
    return {"events": events, "last_seq": log.last_seq}


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


_inventory_running: set[str] = set()


@router.post("/api/jobs/{job_id}/inventory")
async def run_inventory(request: Request, files: list[UploadFile] | None = File(None),
                        job: Job = Depends(get_job)) -> dict:
    """Parts check before assembly: 1-6 photos (`files`) of everything from the box laid out on a table, counted
    against the plan's parts list by pipeline/inventory.py. Writes inventory.json and adds an `inventory` event."""
    if not (job.approved_plan.is_file() or job.plan.is_file()):
        raise HTTPException(409, "This job has no plan yet, so there is no parts list to check against")
    uploads = list(files or [])
    if not 1 <= len(uploads) <= inventory.MAX_PHOTOS:
        raise HTTPException(422, f"Send 1 to {inventory.MAX_PHOTOS} photos in `files`; got {len(uploads)}")
    limit, each = request.app.state.settings.max_upload_mb * 1024 * 1024, photos.MAX_PHOTO_MB * 1024 * 1024
    blobs, total = [], 0
    for up in uploads:
        data = await up.read(min(each, limit - total) + 1)
        total += len(data)
        if len(data) > each or total > limit:
            raise HTTPException(413, f"Each photo may be up to {photos.MAX_PHOTO_MB} MB, "
                                     f"all together up to {limit // (1024 * 1024)} MB")
        blobs.append(data)
    if job.id in _inventory_running:
        raise HTTPException(409, "An inventory check is already running for this job")
    _inventory_running.add(job.id)
    try:
        result = await run_in_threadpool(inventory.check, job.dir, blobs)
    except photos.PhotoError as e:
        raise HTTPException(e.status, str(e))
    except RuntimeError as e:
        raise HTTPException(502, f"The inventory check failed: {str(e)[:300]}")
    finally:
        _inventory_running.discard(job.id)
    request.app.state.store.log(job).emit(
        "inventory", result["summary"]["en"], stage="inventory", level="success" if result["all_present"] else "warn",
        text_el=result["summary"]["el"], all_present=result["all_present"], ms=result["latency_ms"],
        image_url=job.url(result["photos"][0]), url=job.url("inventory.json"))
    return result


@router.get("/api/jobs/{job_id}/inventory")
async def last_inventory(job: Job = Depends(get_job)) -> dict:
    path = job.dir / "inventory.json"
    if not path.is_file():
        raise HTTPException(404, "No inventory check for this job yet")
    return json.loads(path.read_text())


@router.get("/files/{job_id}/{path:path}")
async def job_file(path: str, job: Job = Depends(get_job)) -> FileResponse:
    target = job.resolve(path)
    if target is None:
        raise HTTPException(404, "No such file")
    return FileResponse(target, media_type=mimetypes.guess_type(target.name)[0],
                        headers={"Cache-Control": "no-cache"})
