"""POST /api/voice/{job_id}: one spoken (or typed) utterance from the headset → intent, transcript and a short reply.

For the native Quest apps: hands-free next / back / repeat / where-am-I / identify, and spoken questions answered
from the job's own facts (server/voice.py). With `speak: true` the reply comes back as a WAV too, made the same
way as POST /api/tts. Without GEMINI_API_KEY, or with PERCEPTION_SIMULATE, `text` is matched by keywords and the
answer says `simulated: true`.
"""
from __future__ import annotations

import base64
import binascii
import collections
import os
import time
from typing import Literal

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from pipeline import ask
from server import voice
from server.auth import require_key
from server.jobs import Job
from server.routes import tts
from server.routes.jobs import get_job

router = APIRouter(dependencies=[Depends(require_key)])
MAX_AUDIO_BYTES = 1536 * 1024          # about 48 s of 16 kHz mono PCM16; a command is 1 to 3 s
CALLS_PER_MINUTE = 60                  # model calls over all callers: the URL is public
WAV_MIMES = {"audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave"}
PCM_MIMES = {"audio/pcm", "audio/l16", "audio/x-pcm"}
OGG_MIMES = {"audio/ogg", "audio/opus", "audio/x-opus"}
_calls: collections.deque[float] = collections.deque()


class VoiceBody(BaseModel):
    audio: str | None = Field(None, max_length=MAX_AUDIO_BYTES * 4 // 3 + 64)   # base64, data: URL allowed
    mime: str = "audio/wav"
    rate: int = Field(16000, ge=8000, le=48000)          # only for headerless PCM16
    text: str | None = Field(None, max_length=ask.MAX_QUESTION)   # a transcript instead of audio (tests, simulate)
    lang: Literal["el", "en"] = "el"
    step: int | None = None
    action: int = 0
    speak: bool = False


def read_audio(body: VoiceBody) -> tuple[str, bytes]:
    """(mime, bytes) the model accepts. Headerless PCM16 (sent as audio/wav or audio/pcm) is wrapped in a WAV."""
    data = body.audio or ""
    if data.startswith("data:"):
        data = data.partition(",")[2]
    try:
        raw = base64.b64decode(data, validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(422, "audio must be base64") from None
    if not raw:
        raise HTTPException(422, "audio is empty")
    if len(raw) > MAX_AUDIO_BYTES:
        raise HTTPException(413, f"audio is larger than {MAX_AUDIO_BYTES // 1024} KB")
    mime = body.mime.split(";")[0].strip().lower()
    if mime in WAV_MIMES or mime in PCM_MIMES:
        if raw[:4] == b"RIFF" and raw[8:12] == b"WAVE":
            return "audio/wav", raw
        if len(raw) % 2:
            raise HTTPException(422, "PCM16 audio must have an even number of bytes")
        return "audio/wav", tts.wav(raw, body.rate)
    if mime in OGG_MIMES:
        if raw[:4] != b"OggS":
            raise HTTPException(422, "audio/ogg must be an Ogg stream")
        return "audio/ogg", raw
    raise HTTPException(415, "mime must be audio/wav (or PCM16) or audio/ogg")


def simulate(request: Request) -> bool:
    s = request.app.state.settings
    return bool(s.perception_simulate or not s.gemini_api_key)


def count_call() -> None:
    now = time.monotonic()
    while _calls and now - _calls[0] > 60:
        _calls.popleft()
    if len(_calls) >= CALLS_PER_MINUTE:
        raise HTTPException(429, "Voice rate limit, try again in a minute", headers={"Retry-After": "60"})
    _calls.append(now)


@router.post("/api/voice/{job_id}")
async def understand(body: VoiceBody, request: Request, job: Job = Depends(get_job)) -> dict:
    """`{transcript, intent, answer, tts?, target, actions, latency_ms, model, simulated}`."""
    if not job.manifest.is_file():
        raise HTTPException(409, "This guide has no walkthrough yet, so there is nothing to control")
    if not body.audio and not (body.text or "").strip():
        raise HTTPException(422, "send audio (base64) or text")
    audio = read_audio(body) if body.audio else None
    sim = simulate(request)
    key = request.app.state.settings.gemini_api_key
    if not sim:
        os.environ.setdefault("GEMINI_API_KEY", key)          # pipeline.check reads it from the environment
        if audio is not None or voice.keywords(body.text or "") == "ask":
            count_call()
    try:
        out = await run_in_threadpool(voice.understand, job.dir, body.step, body.action, body.lang,
                                      audio=audio, text=None if audio else body.text, use_model=not sim)
    except ask.NoManifest:
        raise HTTPException(409, "This guide has no walkthrough yet, so there is nothing to control") from None
    except ValueError as e:
        raise HTTPException(422, str(e)) from None
    except RuntimeError as e:
        raise HTTPException(502, f"The voice model did not answer: {str(e)[:200]}") from None
    if body.speak and out["answer"] and key and not sim:
        t0 = time.perf_counter()
        try:
            out["tts"] = base64.b64encode(await tts.speak(out["answer"][:400], key)).decode()
        except (tts.RateLimited, RuntimeError, httpx.HTTPError) as e:
            out["tts"] = None                     # the headset falls back to its own voice
            out["tts_error"] = str(e)[:120]
        out["tts_ms"] = round((time.perf_counter() - t0) * 1000)
    return out
