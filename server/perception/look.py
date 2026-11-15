"""One careful look: is the board at its place the right way round?

    look(pack, jpeg)    one `gemini-4-hydrogen` call on one frame -> {"answer": <option key>, "why": ..., "latency_ms": ...}

The stream's model (decider.py) answers in half a second and cannot see which way a board faces: a side panel
of the drawer standing with its rail towards the middle of the front scored `ok` 0.76 on its `wrong_way` question
(real frames, Sun 15 Nov). The slower model can: it named that mistake, the wrong end of the front, and a panel
still in the hands on the same frames. It is not steady on which way a face looks (the same standing panel was
`ok` in one call and `side_rail_in` in the next), and it once called a panel carried over the front "on the
wrong end". So the session uses it only to confirm what the stream already suspects, and only for the mistakes
a step file lists under `wrong_way_look` (session.py, `_maybe_look`): then both models have named the same one.
"""
from __future__ import annotations

import json
import time

from pipeline.check import gemini

MODEL = "gemini-4-hydrogen"          # PLAN §3. Do not substitute an older model
THINKING = "low"
QUESTION = "wrong_way"
NOT_PLACED = "cannot_tell"

PROMPT = """One photo from a headset camera worn by a person who is assembling flat-pack furniture on the floor. \
Their hands may be in the picture.

{header}
{note}
{instructions}

The possible answers:
{options}

First work out what the picture shows: where the part is, which of its faces you can see, and which way that face \
looks. Then choose exactly one answer. Choose a wrong way only when the picture shows it; if the part is not at its \
place yet, choose {none}.
Answer JSON only: {{"seen": "<one short sentence: what you see>", "answer": "<one of: {keys}>"}}"""


def prompt_for(pack: dict, note: str | None = None) -> tuple[str, list[str]] | None:
    """The prompt and the option keys for a step's pack, or None when the step has no `wrong_way` question."""
    q = (pack.get("questions") or {}).get(QUESTION)
    options = (q or {}).get("criteria")
    if not isinstance(options, dict) or len(options) < 2:
        return None
    keys = list(options)
    header = str(pack.get("header") or "").replace("Image 1 is the live camera view of the user's work area.", "").strip()
    text = PROMPT.format(header=header, note=note or "", instructions=str(q.get("instructions") or "").replace("Image 1", "the photo"),
                         options="\n".join(f"- {k}: {str(v).replace('Image 1', 'the photo')}" for k, v in options.items()),
                         none=NOT_PLACED if NOT_PLACED in options else keys[-1], keys=", ".join(keys))
    return text, keys


def look(pack: dict, jpeg: bytes, note: str | None = None, timeout: float = 25.0, thinking: str = THINKING) -> dict:
    """Raises RuntimeError / OSError / ValueError when the model cannot be asked or does not answer with a key."""
    made = prompt_for(pack, note)
    if made is None:
        raise ValueError("the step has no wrong_way question")
    text, keys = made
    t0 = time.perf_counter()
    schema = {"type": "OBJECT", "properties": {"seen": {"type": "STRING"}, "answer": {"type": "STRING", "enum": keys}},
              "required": ["seen", "answer"]}
    out = json.loads(gemini.generate(MODEL, [text, ("image/jpeg", bytes(jpeg))], timeout=timeout,
                                     thinking_budget=thinking, schema=schema))
    answer = str(out.get("answer") or "")
    if answer not in keys:
        raise ValueError(f"the model answered {answer[:40]!r}")
    return {"answer": answer, "why": str(out.get("seen") or "")[:200], "latency_ms": round((time.perf_counter() - t0) * 1000)}
