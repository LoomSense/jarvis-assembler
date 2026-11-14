"""Thin client for the Gemini Decisions API (gemini-decision-1), standard library only.

    ask(inputs, questions) -> {question_key: answer}

`inputs` is a list of strings and image paths, in the order the model should see them.
"""
from __future__ import annotations

import base64
import http.client
import json
import os
import threading
import time
from pathlib import Path

HOST, PATH = "generativelanguage.googleapis.com", "/v1beta/decisions"
URL = f"https://{HOST}{PATH}"
MIME = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp"}


def api_key() -> str:
    key = os.environ.get("GEMINI_API_KEY")
    if not key:
        env = Path(__file__).resolve().parents[2] / ".env"
        for line in env.read_text().splitlines() if env.exists() else []:
            if line.startswith("GEMINI_API_KEY="):
                key = line.split("=", 1)[1].strip()
    if not key:
        raise RuntimeError("GEMINI_API_KEY is not set (put it in .env)")
    return key


_local = threading.local()


def _post(body: bytes, timeout: float) -> tuple[int, bytes]:
    """One POST on a connection kept open per thread: a new TLS handshake per frame costs about 0.7 s."""
    for attempt in (0, 1):
        conn = getattr(_local, "conn", None)
        if conn is None:
            conn = _local.conn = http.client.HTTPSConnection(HOST, timeout=timeout)
        try:
            conn.request("POST", PATH, body, {"x-goog-api-key": api_key(), "Content-Type": "application/json"})
            r = conn.getresponse()
            return r.status, r.read()
        except (http.client.HTTPException, OSError) as e:      # the server closed an idle connection: open a new one, once
            conn.close()
            _local.conn = None
            if attempt:
                raise RuntimeError(f"Decisions API connection failed: {e}") from None
    raise AssertionError


def ask(inputs: list, questions: dict, resolution: str = "medium", timeout: float = 60) -> dict:
    """`inputs`: strings, image paths, or (mime_type, bytes) pairs for images already in memory."""
    blocks = []
    for item in inputs:
        if isinstance(item, tuple):
            blocks.append({"type": "image", "mime_type": item[0], "resolution": resolution, "data": base64.b64encode(item[1]).decode()})
        elif isinstance(item, Path) or (isinstance(item, str) and Path(item).suffix.lower() in MIME and Path(item).exists()):
            p = Path(item)
            blocks.append({"type": "image", "mime_type": MIME[p.suffix.lower()], "resolution": resolution,
                           "data": base64.b64encode(p.read_bytes()).decode()})
        else:
            blocks.append({"type": "text", "text": str(item)})
    body = json.dumps({"model": "gemini-decision-1", "input": blocks, "questions": questions}).encode()
    t0 = time.perf_counter()
    status, raw = _post(body, timeout)
    if status != 200:
        raise RuntimeError(f"Decisions API {status}: {raw.decode(errors='replace')[:400]}")
    out = json.loads(raw)
    out["latency_ms"] = round((time.perf_counter() - t0) * 1000)
    return out
