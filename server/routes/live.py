"""POST /api/live/token: a single-use token for the voice session (PLAN D9)."""
from __future__ import annotations

import collections
import time
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel

from server import live_token
from server.auth import require_key

router = APIRouter(dependencies=[Depends(require_key)])
_last_mint: dict[str, float] = {}
_recent: collections.deque[float] = collections.deque()     # mint times over all callers
TOTAL_LIMIT, TOTAL_WINDOW_S = 20, 300.0                      # forwarded addresses can be forged, so cap the total too


class TokenBody(BaseModel):
    job_id: str
    mode: Literal["voice", "see"] = "voice"           # see: the model gets camera pictures and coaches from them
    lang: Literal["en", "el"] = "en"


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
    for address, t in list(_last_mint.items()):
        if now - t > settings.live_token_min_interval_s:
            del _last_mint[address]
    while _recent and now - _recent[0] > TOTAL_WINDOW_S:
        _recent.popleft()
    if ip in _last_mint:
        wait = settings.live_token_min_interval_s - (now - _last_mint[ip])
        raise HTTPException(429, "One voice token per 10 seconds. Try again shortly.",
                            headers={"Retry-After": str(int(wait) + 1)})
    if len(_recent) >= TOTAL_LIMIT:
        raise HTTPException(429, "Too many voice sessions were started. Try again in a few minutes.",
                            headers={"Retry-After": str(int(TOTAL_WINDOW_S - (now - _recent[0])) + 1)})
    _last_mint[ip] = now
    _recent.append(now)
    try:
        return await live_token.mint(settings, job, body.mode, body.lang)
    except RuntimeError as e:
        _last_mint.pop(ip, None)                      # a failed mint does not use up the caller's slot
        _recent.pop()
        raise HTTPException(502, str(e)) from None
