"""Who answers the questions of one request.

    GeminiDecider     `pipeline.check.decisions.ask`: the client the measurements were made with
                      (`gemini-decision-1`, one kept-open HTTPS connection per worker thread)
    SimulatedDecider  answers from a script that a debug message can change; no network, no key

Both are plain blocking calls, made from the worker threads of `pipeline.perception.stream.Stream`, and
both return the API's response shape, `{"answers": {key: {...}}, "latency_ms": ...}`. Both raise
`DecisionError` with a `kind` the failure handling understands. The simulated one exists so a client and
the whole event path can be exercised without a key. It is only used when `PERCEPTION_SIMULATE` is set,
and then every server message says so.

`pipeline.check.decisions` reports a failure as `RuntimeError("Decisions API <status>: <body>")`, so the
kind is read from that text (`classify`). A test fails if that format changes.
"""
from __future__ import annotations

import os
import re
import time

from pipeline.check import decisions
from server.perception.config import Tuning
from server.perception.questions import NOT_A_CODE, Ask

SAFETY_CODES = ("safety", "prohibited_content", "blocklist", "image_safety", "content_blocked")
KINDS = ("timeout", "server_error", "network", "rate_limit", "safety", "auth", "billing", "bad_request")
REFUSED = {"auth": "The Decisions API refused the server's key.",
           "billing": "The Decisions API refused the request: the project's credit is used up (HTTP 402)."}
_STATUS = re.compile(r"Decisions API (\d{3}): (.*)", re.S)
_SAFETY = re.compile(r'"code"\s*:\s*"(' + "|".join(SAFETY_CODES) + r')"')


class DecisionError(Exception):
    """`kind` is one of KINDS. The message never contains the key."""

    def __init__(self, kind: str, message: str = ""):
        super().__init__(message or kind)
        self.kind = kind


def classify(error: Exception) -> DecisionError:
    """What a failure of `pipeline.check.decisions.ask` means for the perception loop."""
    text = str(error)
    m = _STATUS.match(text)
    if m:
        status, body = int(m.group(1)), m.group(2)
        if status == 429:
            return DecisionError("rate_limit", "HTTP 429")
        if status >= 500:
            return DecisionError("server_error", f"HTTP {status}")
        if status == 402:
            return DecisionError("billing", "HTTP 402")
        if status in (401, 403) or "api key" in body.lower():
            return DecisionError("auth", f"HTTP {status}")
        safety = _SAFETY.search(body)
        if status == 400 and safety:
            return DecisionError("safety", safety.group(1))
        return DecisionError("bad_request", f"HTTP {status}: {body[:160]}")
    if "GEMINI_API_KEY" in text:
        return DecisionError("auth", "no key")
    if "timed out" in text.lower():
        return DecisionError("timeout", "the Decisions API did not answer in time")
    return DecisionError("network", text[:160])


class GeminiDecider:
    simulated = False

    def __init__(self, api_key: str, tuning: Tuning | None = None):
        self.timeout = (tuning or Tuning()).decision_timeout_s
        # pipeline.check.decisions reads the key from the environment (or .env) on every call. The server may have
        # been given it another way, so make sure both see the same one. An existing value is left alone.
        if api_key:
            os.environ.setdefault("GEMINI_API_KEY", api_key)

    def decide(self, ask: Ask) -> dict:
        try:
            out = decisions.ask(ask.inputs, ask.questions, resolution=ask.resolution, timeout=self.timeout)
        except RuntimeError as e:
            raise classify(e) from None
        except ValueError:                                # a reply that is not JSON
            raise DecisionError("server_error", "the Decisions API reply is not JSON") from None
        except OSError as e:
            raise DecisionError("network", type(e).__name__) from None
        if not isinstance(out, dict) or not isinstance(out.get("answers"), dict):
            raise DecisionError("server_error", "the Decisions API reply has no answers")
        return out

    def close(self) -> None:
        return None


SIM_DEFAULTS = {
    "holding": None,           # a part id or a hardware code; None: empty hands
    "step_complete": False,
    "orientation_ok": True,    # is the assembly positioned the way the step needs
    "next_step_part": False,
    "hands": None,             # None: hands are visible exactly when something is held
    "view_blocked": False,
    "image_poor": False,
    "setup_ok": True,
    "unsure": False,           # torn between candidates: no confident answer about what is held
    "confidence": 0.93,
    "latency_ms": 40,
    "fail": None,              # one of KINDS
    "on_base": False,          # accepted for older clients; no question uses it any more
}


class SimulatedDecider:
    """Answers every question from `script`. Change it with the debug message `{type:"sim", ...}`:
    `{type:"sim", holding:"108363"}`, `{type:"sim", step_complete:true, hands:false}`, `{type:"sim", reset:true}`."""
    simulated = True

    def __init__(self, tuning: Tuning | None = None):
        self.tuning = tuning or Tuning()
        self.script = dict(SIM_DEFAULTS)

    def update(self, changes: dict) -> dict:
        """Apply a debug message. Unknown fields raise ValueError."""
        changes = {k: v for k, v in changes.items() if k != "type"}
        unknown = sorted(set(changes) - set(SIM_DEFAULTS) - {"reset"})
        if unknown:
            raise ValueError(f"unknown script field: {', '.join(unknown)}. Known: {', '.join(SIM_DEFAULTS)}, reset")
        if changes.get("fail") not in (None, *KINDS):
            raise ValueError(f"fail must be null or one of {', '.join(KINDS)}")
        for name in ("confidence", "latency_ms"):
            if name in changes and (isinstance(changes[name], bool) or not isinstance(changes[name], (int, float))):
                raise ValueError(f"{name} must be a number")
        script = dict(SIM_DEFAULTS) if changes.pop("reset", False) else dict(self.script)
        if changes.get("holding") is not None:
            changes["holding"] = str(changes["holding"])
        script.update(changes)
        self.script = script                              # replaced in one step: worker threads read it
        return dict(script)

    def decide(self, ask: Ask) -> dict:
        s = self.script
        t0 = time.perf_counter()
        time.sleep(max(0.0, float(s["latency_ms"])) / 1000)
        if s["fail"] == "timeout":
            time.sleep(self.tuning.decision_timeout_s)    # as the real client: it gives up after the timeout
        if s["fail"]:
            raise DecisionError(s["fail"], f"simulated {s['fail']}")
        yes = min(0.99, max(0.5, float(s["confidence"])))
        held = s["holding"]
        truth = {
            "right_part": held in ask.parts, "right_hardware": held in (ask.codes or ask.hardware),
            "orientation_ok": bool(s["orientation_ok"]), "step_complete": bool(s["step_complete"]),
            "next_step_part_used": bool(s["next_step_part"]),
            "hands_present": bool(s["hands"]) if s["hands"] is not None else held is not None,
            "view_blocked": bool(s["view_blocked"]), "image_poor": bool(s["image_poor"]),
            "frame_matches_setup": bool(s["setup_ok"]),
        }
        codes = (ask.questions.get("hardware_in_hand") or {}).get("criteria") or {}
        small = held in ask.hardware or (held in codes and held not in NOT_A_CODE)     # is the held thing hardware
        answers = {}
        for key, question in ask.questions.items():
            if question.get("type") == "choice" and key not in ("part_in_hand", "hardware_in_hand"):
                answers[key] = self._way(question, bool(s["orientation_ok"]), yes)     # `wrong_way`
                continue
            if question.get("type") == "choice":
                answers[key] = self._choice(key, question, held, small, yes, bool(s["unsure"]))
                continue
            p = 0.5 if key not in truth or (s["unsure"] and key in ("right_part", "right_hardware")) else (
                yes if truth[key] else round(1 - yes, 3))
            answers[key] = {"type": "noul", "noul": p, "confidence": max(p, round(1 - p, 3))}
        return {"answers": answers, "simulated": True, "latency_ms": round((time.perf_counter() - t0) * 1000),
                "usage": {"total_input_tokens": 0, "total_output_tokens": 0, "total_tokens": 0}}

    @staticmethod
    def _way(question: dict, ok: bool, yes: float) -> dict:
        """The step's `wrong_way` choice: `ok` while the script says `orientation_ok`, else its first mistake."""
        options = list(question.get("criteria") or {})
        wrong = [o for o in options if o not in ("ok", "cannot_tell", "other")]
        top = wrong[0] if (wrong and not ok) else "ok" if "ok" in options else options[0]
        rest = [o for o in options if o != top]
        share = {top: yes, **{o: round((1 - yes) / max(1, len(rest)), 4) for o in rest}}
        return {"type": "choice", "choice": top, "probabilities": share, "confidence": yes}

    @staticmethod
    def _choice(key: str, question: dict, held, small: bool, yes: float, unsure: bool) -> dict:
        """Option keys are the labels on the sheet, as `pipeline.perception.questions` writes them."""
        options = sorted(question.get("criteria") or {})
        if key == "hardware_in_hand":
            top = held if held in options else "no_hardware"
        elif held is None:
            top = "nothing_held"
        elif held in options and not small:
            top = held
        else:                                             # hardware, or something this question does not list
            top = "hardware_only" if small else "other"
        if top not in options:
            top = options[0]
        rest = [o for o in options if o != top]
        if unsure and held is not None and rest:
            second = next((o for o in rest if o not in NOT_A_CODE and o not in ("hardware_only", "nothing_held")), rest[0])
            share = {top: 0.45, second: 0.40}
            others = [o for o in rest if o != second]
            share.update({o: round(0.15 / max(1, len(others)), 4) for o in others})
        else:
            share = {top: yes}
            share.update({o: round((1 - yes) / max(1, len(rest)), 4) for o in rest})
        return {"type": "choice", "choice": top, "probabilities": share, "confidence": share[top]}

    def close(self) -> None:
        return None
