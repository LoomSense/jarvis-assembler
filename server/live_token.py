"""Single-use tokens for the Gemini Live voice session (PLAN D9, §6.4).

The browser never sees GEMINI_API_KEY. It gets a token that can open one Live session within
1 minute and lives 30 minutes. The model, the system instruction, the tools and the voice-activity
settings are locked inside the token, so a client cannot change the prompt.

`session_resumption` is deliberately left for the client to set: it must send `{}` on the first
connect and `{handle}` on a reconnect, which a locked field would not allow.
"""
from __future__ import annotations

import datetime as dt
import json

from google import genai
from google.genai import types

from server.jobs import Job
from server.settings import ROOT, Settings

PROMPT_FILE = ROOT / "prompts" / "live_voice.md"
PROMPT_VERSION = "live_voice.md@1"
API_VERSIONS = ("v1beta", "v1alpha")     # PLAN says v1beta; SDK 3.3 documents token creation as v1alpha

_NO_ARGS = {"type": "object", "properties": {}}
_NAV = types.Behavior.NON_BLOCKING       # navigation is answered when idle (§6.4)
TOOLS = [types.Tool(function_declarations=[
    types.FunctionDeclaration(name="get_current_step", parameters_json_schema=_NO_ARGS,
                              description="Return the current step: number, page, parts, hardware and instruction."),
    types.FunctionDeclaration(name="next_step", behavior=_NAV, parameters_json_schema=_NO_ARGS,
                              description="Move to the next step of the guide."),
    types.FunctionDeclaration(name="previous_step", behavior=_NAV, parameters_json_schema=_NO_ARGS,
                              description="Move back one step."),
    types.FunctionDeclaration(name="go_to_step", behavior=_NAV, description="Jump to a step by its number.",
                              parameters_json_schema={"type": "object", "required": ["step"],
                                                      "properties": {"step": {"type": "integer", "minimum": 1}}}),
    types.FunctionDeclaration(name="highlight_part", behavior=_NAV,
                              description="Make one part glow in the 3D view so the person can find it.",
                              parameters_json_schema={"type": "object", "required": ["part_id"],
                                                      "properties": {"part_id": {"type": "string"}}}),
    types.FunctionDeclaration(name="repeat_instruction", parameters_json_schema=_NO_ARGS,
                              description="Return the current step's instruction so it can be said again."),
    types.FunctionDeclaration(name="show_part_request",
                              description="Ask the vision system for one close check of the part the person holds up.",
                              parameters_json_schema={"type": "object",
                                                      "properties": {"expected_part_id": {"type": "string"}}}),
])]


def guide_context(job: Job) -> str:
    """A compact text of the guide's steps for the system instruction, from manifest.json."""
    try:
        m = json.loads(job.manifest.read_text())
    except (OSError, json.JSONDecodeError):
        return "The guide is not built yet. Use get_current_step to read the current step."
    names = {p["id"]: p.get("name", p["id"]) for p in m.get("parts", [])}
    lines = [f"Product: {m.get('title', 'unknown')}. {len(m.get('steps', []))} steps."]
    for s in m.get("steps", []):
        parts = ", ".join(names.get(p, p) for p in s.get("pickParts", [])) or "no new part"
        hardware = ", ".join(f"{h.get('qty', h.get('count', '?'))} x {h.get('code')}" for h in s.get("hardware", []))
        page = (s.get("cite") or {}).get("page", "?")
        lines.append(f"Step {s['n']} (page {page}): {s.get('title', '')}. Parts: {parts}."
                     + (f" Hardware: {hardware}." if hardware else ""))
    return "\n".join(lines)


def system_instruction(job: Job) -> str:
    return PROMPT_FILE.read_text(encoding="utf-8").replace("{guide}", guide_context(job))


async def mint(settings: Settings, job: Job) -> dict:
    """Create the token. Raises RuntimeError with a message that is safe to show (never contains the key)."""
    now = dt.datetime.now(dt.timezone.utc)
    expires = now + dt.timedelta(minutes=30)
    config = types.CreateAuthTokenConfig(
        uses=1,
        expire_time=expires,
        new_session_expire_time=now + dt.timedelta(minutes=1),
        lock_additional_fields=[],                    # lock exactly the fields set below
        live_connect_constraints=types.LiveConnectConstraints(
            model=settings.live_model,
            config=types.LiveConnectConfig(
                response_modalities=[types.Modality.AUDIO],
                system_instruction=system_instruction(job),
                tools=TOOLS,
                context_window_compression=types.ContextWindowCompressionConfig(sliding_window=types.SlidingWindow()),
                realtime_input_config=types.RealtimeInputConfig(
                    automatic_activity_detection=types.AutomaticActivityDetection(
                        start_of_speech_sensitivity=types.StartSensitivity.START_SENSITIVITY_LOW,
                        silence_duration_ms=600)),
            ),
        ),
    )
    errors = []
    for version in API_VERSIONS:
        client = genai.Client(api_key=settings.gemini_api_key, http_options=types.HttpOptions(api_version=version))
        try:
            token = await client.aio.auth_tokens.create(config=config)
            return {"token": token.name, "expires_at": expires.isoformat(), "model": settings.live_model,
                    "api_version": version, "prompt_version": PROMPT_VERSION}
        except Exception as e:                        # noqa: BLE001 - try the next API version, then report
            errors.append(f"{version}: {type(e).__name__}: {str(e)[:200]}")
    raise RuntimeError("Gemini refused to create a Live token (" + " | ".join(errors) + ")")
