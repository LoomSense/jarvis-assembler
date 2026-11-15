"""Demo-key check (PLAN §4.5, §8.2). The URL is public, so the key is what protects the Gemini spend.

The key travels in the `X-Demo-Key` header, or in `?key=` where headers cannot be set
(EventSource, WebSocket, <img>, QR links).
"""
from __future__ import annotations

import json
import logging
import re
import secrets
from urllib.parse import parse_qs

from fastapi import HTTPException, Request, WebSocket


_KEY_IN_URL = re.compile(r"""([?&]key=)[^&\s"']+""")


class HideKeyInLogs(logging.Filter):
    """The key travels in `?key=` for EventSource, WebSocket, <img> and QR links, and uvicorn logs every request
    line and WebSocket URL with its query string. This rewrites those log lines so the key never reaches a log."""

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str):
            record.msg = _KEY_IN_URL.sub(r"\1<hidden>", record.msg)
        if isinstance(record.args, tuple):
            record.args = tuple(_KEY_IN_URL.sub(r"\1<hidden>", a) if isinstance(a, str) else a for a in record.args)
        return True


def hide_key_in_logs() -> None:
    """Install the filter on the loggers that print URLs. Safe to call more than once."""
    for name in ("uvicorn.access", "uvicorn.error", "agj"):
        logger = logging.getLogger(name)
        if not any(isinstance(f, HideKeyInLogs) for f in logger.filters):
            logger.addFilter(HideKeyInLogs())


def _matches(candidate: str | None, expected: str) -> bool:
    return bool(candidate) and bool(expected) and secrets.compare_digest(candidate.encode(), expected.encode())


def has_key(request: Request) -> bool:
    expected = request.app.state.settings.demo_key
    return _matches(request.headers.get("x-demo-key"), expected) or _matches(request.query_params.get("key"), expected)


def require_key(request: Request) -> None:
    if not request.app.state.settings.public_demo and not has_key(request):
        raise HTTPException(status_code=401, detail="Missing or wrong demo key")


class KeyGate:
    """ASGI middleware: turns away /api, /files and /ws requests without the key before any body is read,
    and caps the request body. The route-level `require_key` stays as a second check."""

    PROTECTED = ("/api/", "/files/", "/ws/")

    def __init__(self, app, demo_key: str, max_body_bytes: int, public_demo: bool = False):
        self.app, self.demo_key, self.max_body = app, demo_key, max_body_bytes
        self.public_demo = public_demo

    async def __call__(self, scope, receive, send):
        if scope["type"] not in ("http", "websocket") or not scope["path"].startswith(self.PROTECTED) \
                or scope.get("method") == "OPTIONS":                 # a CORS preflight carries no key
            return await self.app(scope, receive, send)
        headers = dict(scope["headers"])
        query = parse_qs(scope["query_string"].decode("latin-1"))
        if not self.public_demo and not (_matches(headers.get(b"x-demo-key", b"").decode("latin-1"), self.demo_key)
                or _matches((query.get("key") or [""])[0], self.demo_key)):
            if scope["type"] == "websocket":
                await receive()
                return await send({"type": "websocket.close", "code": 4401})
            return await self._reject(send, 401, "Missing or wrong demo key")
        if scope["type"] == "websocket":
            return await self.app(scope, receive, send)
        declared = headers.get(b"content-length", b"")
        if declared.isdigit() and int(declared) > self.max_body:
            return await self._reject(send, 413, "The request body is too large")
        seen = 0

        async def capped_receive():
            nonlocal seen
            message = await receive()
            if message["type"] == "http.request":
                seen += len(message.get("body", b""))
                if seen > self.max_body:                             # a chunked upload with no declared length
                    return {"type": "http.disconnect"}
            return message

        await self.app(scope, capped_receive, send)

    @staticmethod
    async def _reject(send, status: int, detail: str) -> None:
        body = json.dumps({"detail": detail}).encode()
        await send({"type": "http.response.start", "status": status,
                    "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())]})
        await send({"type": "http.response.body", "body": body})


def ws_has_key(ws: WebSocket) -> bool:
    """For WebSocket routes: close with code 4401 when this returns False."""
    if ws.app.state.settings.public_demo:
        return True
    expected = ws.app.state.settings.demo_key
    return _matches(ws.query_params.get("key"), expected) or _matches(ws.headers.get("x-demo-key"), expected)
