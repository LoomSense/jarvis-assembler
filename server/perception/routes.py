"""WS /ws/perception/{job_id}?role=display|camera&key=... : the phase-2 perception socket (PLAN §4.5).

`server/app.py` includes `router` when this module exists. One session per job; every device of the job
joins it. A headset can be display, hands and camera on one socket; a phone joins as the fallback camera.
The protocol, message by message, is in `server/perception/README.md`.

Close codes: 4401 wrong or missing demo key (the handshake is refused: a browser sees a failed connection),
4400 unknown role, 4404 unknown job, 4409 the job has no manifest yet. For the last three the socket is
accepted first and an `error` message says why, because a browser cannot read the reason of a refused handshake.
"""
from __future__ import annotations

import json
import logging
import os
from contextlib import asynccontextmanager
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Request, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field

from server.auth import require_key, ws_has_key
from server.perception import locate as locator
from server.perception.config import ROLES
from server.perception.decider import DecisionError
from server.perception.guide import Guide
from server.perception.imaging import MAX_IMAGE_BYTES
from server.perception.session import Registry
from server.routes.identify import read_image, simulate

log = logging.getLogger("agj.perception")


def registry(app) -> Registry:
    """The app's sessions, made on first use (the app factory does not know this module)."""
    reg = getattr(app.state, "perception", None)
    if reg is None:
        reg = app.state.perception = Registry(app.state.settings, app.state.store)
    return reg


@asynccontextmanager
async def _lifespan(app):
    yield
    reg = getattr(app.state, "perception", None)
    if reg is not None:
        await reg.close()


router = APIRouter(lifespan=_lifespan)


async def _refuse(ws: WebSocket, close_code: int, code: str, message: str) -> None:
    await ws.accept()
    await ws.send_text(json.dumps({"type": "error", "code": code, "message": message}))
    await ws.close(code=close_code)


@router.websocket("/ws/perception/{job_id}")
async def perception(ws: WebSocket, job_id: str) -> None:
    if not ws_has_key(ws):
        return await ws.close(code=4401)
    role = ws.query_params.get("role", "display")
    if role not in ROLES:
        return await _refuse(ws, 4400, "unknown_role", "role must be display or camera")
    reg = registry(ws.app)
    job = reg.store.get(job_id)
    if job is None:
        return await _refuse(ws, 4404, "unknown_job", "No such job")
    try:
        session = reg.session(job.id, job.dir)
    except ValueError as e:
        return await _refuse(ws, 4409, "not_built", f"This job cannot be assembled yet: {e}")
    await ws.accept()
    conn = session.join(ws, role, ws.query_params)
    if reg.tuning_error:
        session.error(conn, "bad_tuning", reg.tuning_error)
    try:
        while True:
            message = await ws.receive()
            if message["type"] == "websocket.disconnect":
                break
            if message.get("bytes") is not None:
                session.on_bytes(conn, message["bytes"])
            elif message.get("text") is not None:
                session.on_text(conn, message["text"])
    except WebSocketDisconnect:
        pass
    finally:
        await session.leave(conn)


@router.get("/api/perception/{job_id}/position", dependencies=[Depends(require_key)])
async def position(job_id: str, request: Request) -> dict:
    """Where the guide of this job last was (step number, 0-based action, epoch ms), or nulls.

    Set by any display that reported a step or an action over the socket (the guide page while live guidance is on,
    the headset). It survives `stop` and the last device leaving, so the headset can continue where the phone was.
    Read only: it never makes a session.
    """
    reg = getattr(request.app.state, "perception", None)
    session = reg.sessions.get(job_id) if reg is not None else None
    pos = session.position if session is not None else None
    return {"job_id": job_id, "step": pos["step"] if pos else None, "action": pos["action"] if pos else None,
            "at": pos["at"] if pos else None}


class LocateBody(BaseModel):
    image: str = Field(min_length=4, max_length=MAX_IMAGE_BYTES * 4 // 3 + 64)   # base64 JPEG, no data: prefix
    lang: Literal["en", "el"] = "en"


LOCATE_STATUS = {"auth": 503, "billing": 503, "rate_limit": 429, "timeout": 504}


@router.post("/api/perception/{job_id}/locate", dependencies=[Depends(require_key)])
async def locate(job_id: str, body: LocateBody, request: Request) -> dict:
    """Which step the real assembly in the picture is at: one Decision 1 request per step, in parallel.
    `{next_step, action, done_through, confidence, steps: [{n, complete, p}], message}`. 404 unknown job,
    409 no manifest yet (as the socket's 4404 and 4409)."""
    job = request.app.state.store.get(job_id)
    if job is None:
        raise HTTPException(404, "No such job")
    try:
        guide = Guide(job.dir)
    except ValueError as e:
        raise HTTPException(409, f"This job cannot be assembled yet: {e}") from None
    jpeg = read_image(body.image)
    if simulate(request):
        return locator.simulated(guide, body.lang)
    os.environ.setdefault("GEMINI_API_KEY", request.app.state.settings.gemini_api_key)   # pipeline.check reads it there
    try:
        return await locator.locate(guide, jpeg, body.lang)
    except DecisionError as e:
        raise HTTPException(LOCATE_STATUS.get(e.kind, 502), f"Decision 1 could not check the steps ({e.kind}): {e}") from None
