"""SET-02: call every Gemini model once and say which ones work with our key.  `make models`

    gemini-4-carbon, gemini-4-hydrogen   one text reply
    gemini-decision-1                    one yes/no question through /v1beta/decisions
    gemini-3.8-live                      mint a single-use token, open a session with it, wait for audio

Exit 0 only when all four pass. Needs GEMINI_API_KEY in .env. Costs well under one cent.
"""
from __future__ import annotations

import asyncio
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from pipeline.check import decisions, gemini  # noqa: E402
from server import live_token  # noqa: E402
from server.jobs import Job  # noqa: E402
from server.settings import Settings  # noqa: E402


def text_model(model: str) -> str:
    reply = gemini.generate(model, ["Reply with the single word OK."], timeout=120, thinking_budget="low")
    if "OK" not in reply.upper():
        raise RuntimeError(f"unexpected reply: {reply[:80]!r} ({gemini.generate.last})")
    return f"replied in {gemini.generate.last['latency_s']} s"


def decision_model() -> str:
    out = decisions.ask(
        ["A wooden box stands on a table. Its lid is open."],
        {"lid_open": {"type": "noul", "instructions": "Is the lid of the box open?",
                      "criteria": {"true_description": "The lid is open", "false_description": "The lid is closed"}}})
    answer = (out.get("answers") or {}).get("lid_open")
    if not answer:
        raise RuntimeError(f"no answer in the response: {str(out)[:200]}")
    return f"answered {answer} in {out['latency_ms']} ms"


async def live_model(settings: Settings) -> str:
    from google import genai
    from google.genai import types

    token = await live_token.mint(settings, Job(REPO / "jobs", "smoke-no-job"))
    client = genai.Client(api_key=token["token"], http_options=types.HttpOptions(api_version=token["api_version"]))
    t0, audio = time.perf_counter(), 0
    # The token locks the model, prompt and tools; the client sets only session resumption (as the phone will).
    async with client.aio.live.connect(model=token["model"],
                                       config=types.LiveConnectConfig(session_resumption=types.SessionResumptionConfig())) as session:
        await session.send_client_content(
            turns=types.Content(role="user", parts=[types.Part(text="Which step are we on? Answer in five words.")]),
            turn_complete=True)
        async with asyncio.timeout(30):
            async for message in session.receive():
                audio += len(message.data or b"")
                if message.tool_call:
                    return f"token ok ({token['api_version']}), the model called a tool after {time.perf_counter() - t0:.1f} s"
                if message.server_content and message.server_content.turn_complete:
                    break
    if not audio:
        raise RuntimeError("the session opened but no audio came back")
    return f"token ok ({token['api_version']}), {audio} bytes of audio, first turn {time.perf_counter() - t0:.1f} s"


def main() -> int:
    settings = Settings()
    if not settings.gemini_api_key:
        print("GEMINI_API_KEY is not set in .env")
        return 2
    checks = [("gemini-4-carbon", lambda: text_model("gemini-4-carbon")),
              ("gemini-4-hydrogen", lambda: text_model("gemini-4-hydrogen")),
              ("gemini-decision-1", decision_model),
              ("gemini-3.8-live", lambda: asyncio.run(live_model(settings)))]
    failed = 0
    for name, check in checks:
        try:
            print(f"  ok    {name}: {check()}", flush=True)
        except Exception as e:  # noqa: BLE001 - report every model, then fail
            failed += 1
            print(f"  FAIL  {name}: {type(e).__name__}: {str(e)[:400]}", flush=True)
    print("PASS: all four models answer" if not failed else f"FAIL: {failed} of {len(checks)} models did not answer")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
