"""Minimal Gemini text+image call over REST (standard library only)."""
from __future__ import annotations

import base64
import json
import time
import urllib.request
from pathlib import Path

from pipeline.check.decisions import MIME, api_key


def generate(model: str, parts: list, json_out: bool = False, timeout: float = 300) -> str:
    """parts: strings and image paths, in order. Returns the model's text."""
    body_parts = []
    for p in parts:
        if isinstance(p, Path) or (isinstance(p, str) and Path(p).suffix.lower() in MIME and Path(p).exists()):
            p = Path(p)
            body_parts.append({"inline_data": {"mime_type": MIME[p.suffix.lower()],
                                               "data": base64.b64encode(p.read_bytes()).decode()}})
        else:
            body_parts.append({"text": str(p)})
    body = {"contents": [{"role": "user", "parts": body_parts}]}
    if json_out:
        body["generationConfig"] = {"responseMimeType": "application/json"}
    req = urllib.request.Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        data=json.dumps(body).encode(), headers={"x-goog-api-key": api_key(), "Content-Type": "application/json"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            out = json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{model} {e.code}: {e.read().decode()[:500]}") from None
    text = "".join(p.get("text", "") for p in out["candidates"][0]["content"]["parts"])
    generate.last = {"latency_s": round(time.perf_counter() - t0, 1), "usage": out.get("usageMetadata")}
    return text
