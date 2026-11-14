"""POST /api/live/token: a single-use token for the voice session (PLAN D9)."""
from __future__ import annotations

import time

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel

from server import live_token
from server.auth import require_key

router = APIRouter(dependencies=[Depends(require_key)])
_last_mint: dict[str, float] = {}


class TokenBody(BaseModel):
    job_id: str


def client_ip(request: Request) -> str:
    """The caller's address. Behind the boxd proxy or a tunnel the socket peer is the proxy, so read the header."""
    forwarded = request.headers.get("cf-connecting-ip") or request.headers.get("x-forwarded-for", "").split(",")[0]
    return forwarded.strip() or (request.client.host if request.client else "unknown")


@router.post("/api/live/token")
async def create_live_token(body: TokenBody, request: Request) -> dict:
    settings = request.app.state.settings
    job = request.app.state.store.get(body.job_id)
    if job is None:
        raise HTTPException(404, "No such job")
    if not settings.gemini_api_key:
        raise HTTPException(503, "The server has no GEMINI_API_KEY, so voice is unavailable")
    ip, now = client_ip(request), time.monotonic()
    wait = settings.live_token_min_interval_s - (now - _last_mint.get(ip, -1e9))
    if wait > 0:
        raise HTTPException(429, "One voice token per 10 seconds. Try again shortly.",
                            headers={"Retry-After": str(int(wait) + 1)})
    _last_mint[ip] = now
    try:
        return await live_token.mint(settings, job)
    except RuntimeError as e:
        _last_mint.pop(ip, None)                      # a failed mint does not use up the caller's slot
        raise HTTPException(502, str(e)) from None
