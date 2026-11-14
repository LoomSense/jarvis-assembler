"""Thin client for the Gemini Decisions API (gemini-decision-1), standard library only.

    ask(inputs, questions) -> {question_key: answer}

`inputs` is a list of strings and image paths, in the order the model should see them.
"""
from __future__ import annotations

import base64
import json
import os
import time
import urllib.request
from pathlib import Path

URL = "https://generativelanguage.googleapis.com/v1beta/decisions"
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


def ask(inputs: list, questions: dict, resolution: str = "medium", timeout: float = 60) -> dict:
    blocks = []
    for item in inputs:
        if isinstance(item, Path) or (isinstance(item, str) and Path(item).suffix.lower() in MIME and Path(item).exists()):
            p = Path(item)
            blocks.append({"type": "image", "mime_type": MIME[p.suffix.lower()], "resolution": resolution,
                           "data": base64.b64encode(p.read_bytes()).decode()})
        else:
            blocks.append({"type": "text", "text": str(item)})
    body = json.dumps({"model": "gemini-decision-1", "input": blocks, "questions": questions}).encode()
    req = urllib.request.Request(URL, data=body, headers={"x-goog-api-key": api_key(), "Content-Type": "application/json"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            out = json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"Decisions API {e.code}: {e.read().decode()[:400]}") from None
    out["latency_ms"] = round((time.perf_counter() - t0) * 1000)
    return out
