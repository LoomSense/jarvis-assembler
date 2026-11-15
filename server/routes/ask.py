"""POST /api/jobs/{id}/ask: the person asks the guide a question during assembly (pipeline/ask.py)."""
from __future__ import annotations

import base64
import binascii
import os
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from pipeline import ask
from server.auth import require_key
from server.jobs import Job
from server.routes.jobs import get_job

router = APIRouter(dependencies=[Depends(require_key)])
MAX_FRAME_BYTES = 2 * 1024 * 1024           # a phase-2 frame is 768 px at q0.7, about 100 KB


class Turn(BaseModel):
    q: str = Field(max_length=ask.MAX_QUESTION)
    a: str = Field("", max_length=2 * ask.MAX_QUESTION)


class AskBody(BaseModel):
    step: int
    question: str = Field(min_length=1, max_length=ask.MAX_QUESTION)
    lang: Literal["el", "en"] = "el"
    frame: str | None = Field(None, max_length=MAX_FRAME_BYTES * 4 // 3 + 64)   # base64 JPEG, data: URL allowed
    history: list[Turn] = Field(default_factory=list, max_length=20)          # only the last 4 are used


def decode_frame(frame: str | None) -> bytes | None:
    if not frame:
        return None
    if frame.startswith("data:"):
        frame = frame.partition(",")[2]
    try:
        data = base64.b64decode(frame, validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(422, "frame must be a base64 JPEG") from None
    if len(data) > MAX_FRAME_BYTES:
        raise HTTPException(413, f"frame is larger than {MAX_FRAME_BYTES // (1024 * 1024)} MB")
    if not data.startswith(b"\xff\xd8"):
        raise HTTPException(422, "frame must be a JPEG")
    return data


@router.post("/api/jobs/{job_id}/ask")
async def ask_guide(body: AskBody, request: Request, job: Job = Depends(get_job)) -> dict:
    """`{answer, actions, undo, cites: {page, step}, latency_ms, model}`. The model call runs in a thread."""
    if not job.manifest.is_file():
        raise HTTPException(409, "This guide has no walkthrough yet, so there is nothing to ask about")
    key = request.app.state.settings.gemini_api_key
    if not key:
        raise HTTPException(503, "The server has no GEMINI_API_KEY, so questions are unavailable")
    os.environ.setdefault("GEMINI_API_KEY", key)          # pipeline.check reads it from the environment
    frame = decode_frame(body.frame)
    history = [t.model_dump() for t in body.history[-ask.MAX_HISTORY:]]
    try:
        return await run_in_threadpool(ask.answer, job.dir, body.step, body.question, body.lang, frame, history)
    except ask.NoManifest:
        raise HTTPException(409, "This guide has no walkthrough yet, so there is nothing to ask about") from None
    except ValueError as e:
        raise HTTPException(422, str(e)) from None
    except RuntimeError as e:
        raise HTTPException(502, f"The guide could not answer right now: {e}") from None
