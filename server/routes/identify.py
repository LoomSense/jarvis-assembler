"""POST /api/identify: which product the camera sees (a box, a label, a finished piece, loose parts).

The answer names a guide built on this server (`match`), or a `query` to open with POST /api/catalog/open.
Without GEMINI_API_KEY, or with PERCEPTION_SIMULATE, it answers without a model and says `simulated: true`.
"""
from __future__ import annotations

import os
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from server import identify as vision
from server.auth import require_key
from server.perception.imaging import MAX_IMAGE_BYTES, decode_jpeg_b64, probe

router = APIRouter(dependencies=[Depends(require_key)])


class ImageBody(BaseModel):
    image: str = Field(min_length=4, max_length=MAX_IMAGE_BYTES * 4 // 3 + 64)   # base64 JPEG, no data: prefix
    lang: Literal["en", "el"] = "en"


def read_image(image: str) -> bytes:
    try:
        data = decode_jpeg_b64(image)
    except OverflowError as e:
        raise HTTPException(413, str(e)) from None
    except ValueError as e:
        raise HTTPException(422, str(e)) from None
    if probe(data) is None:
        raise HTTPException(422, "image is not a readable JPEG")
    return data


def simulate(request: Request) -> bool:
    """No model calls: PERCEPTION_SIMULATE is on, or the server has no key."""
    s = request.app.state.settings
    return bool(s.perception_simulate or not s.gemini_api_key)


@router.post("/api/identify")
async def identify(body: ImageBody, request: Request) -> dict:
    """`{seen: {kind, name, type, article, text}, match: {job_id, product, ready} | null, query, confidence, message}`."""
    jpeg = read_image(body.image)
    store = request.app.state.store
    if simulate(request):
        return await run_in_threadpool(vision.simulated, store, body.lang)
    os.environ.setdefault("GEMINI_API_KEY", request.app.state.settings.gemini_api_key)   # pipeline.check reads it there
    try:
        return await run_in_threadpool(vision.identify, store, jpeg, body.lang)
    except RuntimeError as e:
        raise HTTPException(502, f"The vision model did not answer: {str(e)[:200]}") from None
