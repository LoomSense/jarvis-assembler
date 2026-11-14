"""Demo-key check (PLAN §4.5, §8.2). The URL is public, so the key is what protects the Gemini spend.

The key travels in the `X-Demo-Key` header, or in `?key=` where headers cannot be set
(EventSource, WebSocket, <img>, QR links).
"""
from __future__ import annotations

import secrets

from fastapi import HTTPException, Request, WebSocket


def _matches(candidate: str | None, expected: str) -> bool:
    return bool(candidate) and bool(expected) and secrets.compare_digest(candidate.encode(), expected.encode())


def has_key(request: Request) -> bool:
    expected = request.app.state.settings.demo_key
    return _matches(request.headers.get("x-demo-key"), expected) or _matches(request.query_params.get("key"), expected)


def require_key(request: Request) -> None:
    if not has_key(request):
        raise HTTPException(status_code=401, detail="Missing or wrong demo key")


def ws_has_key(ws: WebSocket) -> bool:
    """For WebSocket routes: close with code 4401 when this returns False."""
    expected = ws.app.state.settings.demo_key
    return _matches(ws.query_params.get("key"), expected) or _matches(ws.headers.get("x-demo-key"), expected)
