"""POST /api/tts {text} → audio/wav: an instruction spoken by Gemini TTS (PLAN §3 fallback voice), for the headset.

The same contract as the dev server's route in client/vite.config.ts, so the Quest client works on the demo host too.
The key stays on the server. The URL is public, so besides the demo key: text 1–400 characters, 30 new syntheses a
minute in total, and every answer cached by text. Without GEMINI_API_KEY it answers 503 and the client falls back to
the browser's own voice.
"""
from __future__ import annotations

import base64
import collections
import os
import re
import struct
import time

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel

from server.auth import require_key

router = APIRouter(dependencies=[Depends(require_key)])
MODEL = os.environ.get("TTS_MODEL", "gemini-3.8-flash-lite-tts")
VOICE = os.environ.get("TTS_VOICE", "Schedar")
_cache: "collections.OrderedDict[str, bytes]" = collections.OrderedDict()
_calls: collections.deque[float] = collections.deque()


class Say(BaseModel):
    text: str


def wav(pcm: bytes, rate: int) -> bytes:
    """16-bit mono PCM in a WAV header."""
    return (b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
            + b"data" + struct.pack("<I", len(pcm)) + pcm)


async def synthesize(text: str, key: str) -> bytes:
    async with httpx.AsyncClient(timeout=30) as client:
        r = await client.post(f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent",
                              headers={"x-goog-api-key": key},
                              json={"contents": [{"parts": [{"text": text}]}],
                                    "generationConfig": {"responseModalities": ["AUDIO"], "speechConfig": {
                                        "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": VOICE}}}}})
    if r.status_code != 200:
        raise RuntimeError(f"Gemini TTS {r.status_code}")
    parts = ((r.json().get("candidates") or [{}])[0].get("content") or {}).get("parts") or []
    data = next((p["inlineData"] for p in parts if (p.get("inlineData") or {}).get("data")), None)
    if not data:
        raise RuntimeError("Gemini TTS returned no audio")
    raw = base64.b64decode(data["data"])
    if raw[:4] == b"RIFF":
        return raw
    m = re.search(r"rate=(\d+)", data.get("mimeType") or "")
    return wav(raw, int(m.group(1)) if m else 24000)


class RateLimited(RuntimeError):
    """More than 30 new syntheses in the last minute."""


async def speak(text: str, key: str) -> bytes:
    """The WAV for `text`, from the cache or one new synthesis (shared by /api/tts and /api/voice).
    RateLimited past 30 new syntheses a minute; RuntimeError or httpx.HTTPError when the synthesis fails."""
    audio = _cache.get(text)
    if audio is not None:
        return audio
    now = time.monotonic()
    while _calls and now - _calls[0] > 60:
        _calls.popleft()
    if len(_calls) >= 30:
        raise RateLimited("Voice rate limit, try again in a minute")
    _calls.append(now)
    audio = await synthesize(text, key)
    _cache[text] = audio
    while len(_cache) > 300:
        _cache.popitem(last=False)
    return audio


@router.post("/api/tts")
async def tts(body: Say, request: Request) -> Response:
    key = request.app.state.settings.gemini_api_key
    if not key:
        raise HTTPException(503, "No GEMINI_API_KEY on the server")
    text = body.text.strip()
    if not 1 <= len(text) <= 400:
        raise HTTPException(400, "text must be 1–400 characters")
    try:
        audio = await speak(text, key)
    except RateLimited as e:
        raise HTTPException(429, str(e)) from None
    except (RuntimeError, httpx.HTTPError) as e:
        raise HTTPException(502, f"Speech synthesis failed: {str(e)[:120]}") from None
    return Response(audio, media_type="audio/wav", headers={"Cache-Control": "no-store"})
