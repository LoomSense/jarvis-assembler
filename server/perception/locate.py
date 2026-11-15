"""Which step of the guide a real assembly is at, from one camera picture.

    locate(guide, jpeg, decide)      one Decision 1 request per step, at most WORKERS at once
    run_of(steps, order)             done_through and next_step from the per-step answers (no network)

Each request is the benchmarked one for that step (`questions.build_ask`: header, frame, sheet note, sheet),
with only its `step_complete` question: "are the parts this step adds attached?". A step is reported
`complete` at `done_yes` (0.45, the Watcher's measured line).

`done_through` is the most likely point of progress, given that steps are done in order: of every split "steps
1..n done, the rest not", the one the answers agree with most. A strict "1..n all complete" run was tried
first and failed on real requests (Sun 15 Nov, MALM drawer renders): the parts an early step adds are often
hidden by later ones, so step 1 scored 0.17 on the finished drawer while steps 2-7 scored 0.52-0.92. The fit
reads that as "all done"; an isolated "done" after a run of "not done" (or one low step inside a run of done
ones) is still read as noise, and an uncertain answer or a failed request weighs little either way.
"""
from __future__ import annotations

import asyncio
import logging
import math
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Callable

from pipeline.check import decisions
from pipeline.perception.logic import Config
from server.perception.decider import DecisionError, classify
from server.perception.guide import Guide
from server.perception.questions import build_ask, load_pack

log = logging.getLogger("agj.perception")
WORKERS = 6                      # requests at once; each worker thread keeps its own HTTPS connection open
TIMEOUT_S = 15.0
DONE = Config().done_yes         # 0.45: finished steps scored 0.31 to 0.89, unfinished 0.13 to 0.38 (bench, Sun 15 Nov)
FLOOR = 0.05                     # no single answer is taken as certain
QUESTION = "step_complete"
_pool = ThreadPoolExecutor(max_workers=WORKERS, thread_name_prefix="locate")

MESSAGES = {
    "en": {"none": "Nothing looks done yet. Start with step {next}.",
           "one": "Step {first} looks done. Next: step {next}.",
           "run": "Steps {first}–{last} look done. Next: step {next}.",
           "all": "All {total} steps look done.",
           "simulated": "Simulated: no vision model on this server. Start with step {next}."},
    "el": {"none": "Τίποτα δεν φαίνεται έτοιμο ακόμα. Ξεκίνα από το βήμα {next}.",
           "one": "Το βήμα {first} φαίνεται έτοιμο. Επόμενο: βήμα {next}.",
           "run": "Τα βήματα {first}–{last} φαίνονται έτοιμα. Επόμενο: βήμα {next}.",
           "all": "Και τα {total} βήματα φαίνονται έτοιμα.",
           "simulated": "Προσομοίωση: ο server δεν έχει μοντέλο όρασης. Ξεκίνα από το βήμα {next}."},
}


def complete(p: float | None) -> bool:
    return p is not None and p >= DONE


def _belief(p: float | None) -> float:
    """p moved so that the measured line `DONE` sits at 0.5, kept off 0 and 1. None (a failed request): 0.5."""
    return 0.5 if p is None else min(1 - FLOOR, max(FLOOR, p + 0.5 - DONE))


def run_of(steps: list[dict], order: list[int]) -> dict:
    """`steps`: [{n, p}] (p None when the request failed). Returns done_through, next_step, confidence:
    the split "the first k steps done, the rest not" with the highest likelihood; ties go to the earlier split
    (redoing a step costs less than skipping one). `confidence` is that split's share of all splits."""
    by_n = {s["n"]: s["p"] for s in steps}
    b = [_belief(by_n.get(n)) for n in order]
    fits = [sum(math.log(x) for x in b[:k]) + sum(math.log(1 - x) for x in b[k:]) for k in range(len(order) + 1)]
    k = max(range(len(fits)), key=lambda i: (fits[i], -i))
    done_through = order[k - 1] if k else 0
    next_step = order[min(k, len(order) - 1)]
    top = max(fits)
    share = 1 / sum(math.exp(f - top) for f in fits)
    return {"done_through": done_through, "next_step": next_step, "confidence": round(share, 2)}


def message(lang: str, order: list[int], done_through: int, next_step: int, simulated: bool = False) -> str:
    m = MESSAGES.get(lang, MESSAGES["en"])
    if simulated:
        return m["simulated"].format(next=next_step)
    if done_through == order[-1]:
        return m["all"].format(total=len(order))
    if not done_through:
        return m["none"].format(next=next_step)
    first = order[0]
    return (m["one"] if done_through == first else m["run"]).format(first=first, last=done_through, next=next_step)


def gemini_decide(inputs: list, questions: dict, resolution: str) -> dict:
    """`pipeline.check.decisions.ask`, with its failures as DecisionError."""
    try:
        return decisions.ask(inputs, questions, resolution=resolution, timeout=TIMEOUT_S)
    except RuntimeError as e:
        raise classify(e) from None
    except ValueError:
        raise DecisionError("server_error", "the Decisions API reply is not JSON") from None
    except OSError as e:
        raise DecisionError("network", type(e).__name__) from None


def _one(guide: Guide, n: int, jpeg: bytes, decide: Callable) -> dict:
    try:
        ask = build_ask(guide, load_pack(guide, n), jpeg, only=(QUESTION,))
    except (ValueError, OSError) as e:
        log.warning("locate: no step_complete question for step %s: %s", n, e)
        return {"n": n, "p": None, "error": "no_questions"}
    if QUESTION not in ask.questions:
        return {"n": n, "p": None, "error": "no_questions"}
    try:
        out = decide(ask.inputs, ask.questions, ask.resolution)
    except DecisionError as e:
        return {"n": n, "p": None, "error": e.kind}
    p = ((out.get("answers") or {}).get(QUESTION) or {}).get("noul")
    if not isinstance(p, (int, float)) or isinstance(p, bool):
        return {"n": n, "p": None, "error": "no_answer"}
    return {"n": n, "p": float(p)}


async def locate(guide: Guide, jpeg: bytes, lang: str = "en", decide: Callable = gemini_decide) -> dict:
    """The response body. Raises DecisionError when every request failed for the same reason the key or the
    credit gives (auth, billing), or when none came back at all."""
    t0 = time.perf_counter()
    loop = asyncio.get_running_loop()
    got = await asyncio.gather(*(loop.run_in_executor(_pool, _one, guide, n, jpeg, decide) for n in guide.order))
    answered = [s for s in got if s["p"] is not None]
    if not answered:
        kinds = {s.get("error") for s in got}
        kind = kinds.pop() if len(kinds) == 1 else "server_error"
        raise DecisionError(kind if kind in ("auth", "billing", "rate_limit", "timeout", "network", "safety")
                            else "server_error", f"no step could be checked ({', '.join(sorted(map(str, kinds or {kind})))})")
    run = run_of(got, guide.order)
    # a failed request reads as p 0.5 ("cannot tell"), with the reason in `error`
    steps = [{"n": s["n"], "complete": complete(s["p"]), "p": round(s["p"], 3) if s["p"] is not None else 0.5,
              **({"error": s["error"]} if "error" in s else {})} for s in got]
    out = {"next_step": run["next_step"], "action": 0, "done_through": run["done_through"],
           "confidence": run["confidence"], "steps": steps,
           "message": message(lang, guide.order, run["done_through"], run["next_step"]),
           "latency_ms": round((time.perf_counter() - t0) * 1000)}
    log.info("locate %s: done_through=%s next=%s p=%s (%s ms)", guide.dir.name, out["done_through"], out["next_step"],
             [s["p"] for s in steps], out["latency_ms"])
    return out


def simulated(guide: Guide, lang: str = "en") -> dict:
    first = guide.order[0]
    return {"next_step": first, "action": 0, "done_through": 0, "confidence": 0.5,
            "steps": [{"n": n, "complete": False, "p": 0.0} for n in guide.order],
            "message": message(lang, guide.order, 0, first, simulated=True), "simulated": True}
