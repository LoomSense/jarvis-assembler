"""Is this step finished? One careful look at one photo, with the reasons.

    verify(job_dir, step_n, jpeg, lang)   one `gemini-4-hydrogen` vision call: the person's photo next to the
                                          render of the finished step (and of the step before it)

The live checks (decider.py) answer many small questions per frame in half a second and are good at "which part
is in the hand". On real phone pictures their `step_complete` answer stayed low (Sun 15 Nov, the drawer: above
0.5 on 5 % of 1,231 frames while every step was built), so nothing ever told the person a step was done. This is
the slower, deliberate check for that one question: asked when the person, or the voice coach, thinks the step
is finished. It answers what is done, what is still missing, and one sentence to say.
"""
from __future__ import annotations

import json
import logging
import tempfile
import time
from pathlib import Path

from pipeline.check import gemini

log = logging.getLogger("agj.perception")
MODEL = "gemini-4-hydrogen"          # PLAN §3. Do not substitute an older model
THINKING = "low"

PROMPT = """You check one step of a flat-pack furniture assembly from a phone photo.

Image 1 is the person's photo, taken just now.
Image 2 is a 3D render of the assembly when step {n} is finished.{before}
The renders are drawn from another viewpoint, in plain colours, without hands or a floor. Compare which pieces are joined and where, not how the picture looks.

Product: {product}
Its pieces, with their sizes. Pieces of the same colour are told apart by size and thickness:
{pieces}

Step {n} of {total}: {title}
What the person does in this step, in order:
{actions}
Pieces this step adds: {adds}

Decide whether the photo shows step {n} finished. Be strict and concrete:
- "done" is true only if every piece and every fastener this step adds is visibly in its place.
- A piece held in the hand, lying beside the assembly, or only partly pushed in is not done.
- If the photo does not show the part of the assembly this step works on, or is too dark or blurred, "visible" is false and "done" is false.
- Count fasteners when the step has several: say how many you see in place.

Reply with one JSON object:
{{"visible": true or false,
 "done": true or false,
 "confidence": number from 0 to 1,
 "have": "what you can see is already in place, at most 12 words",
 "missing": ["each thing still missing or wrong, at most 8 words each"],
 "say_en": "one short sentence for the person: the result, then what to do next",
 "say_el": "the same sentence in Greek, informal singular (εσύ)"}}"""


def part_sizes(job_dir: Path) -> dict[str, str]:
    """How large each piece is, in words a person can check by eye: "380 x 456 mm, 4 mm thin sheet".
    From the built parts' bounding boxes (parts/<id>.mates.json). Two grey panels differ only by this."""
    out = {}
    for f in sorted((Path(job_dir) / "parts").glob("*.mates.json")):
        try:
            d = json.loads(f.read_text())
            box = d["bbox_mm"]
            a, b, c = sorted((box["max"][i] - box["min"][i] for i in range(3)), reverse=True)
        except (OSError, KeyError, ValueError, TypeError):
            continue
        if d.get("kind") == "hardware":
            out[d["id"]] = f"{round(a)} mm long, {round(b)} mm across"
        else:
            shape = "thin sheet" if c <= 6 else "board"
            out[d["id"]] = f"{round(a)} x {round(b)} mm, {round(c)} mm thick {shape}"
    return out


def pieces_text(job_dir: Path, manifest: dict) -> str:
    """One line per piece: its name, how many, and its size, largest first, so look-alikes can be told apart."""
    sizes = part_sizes(job_dir)
    rows = [(p["id"], p.get("name", p["id"]), p.get("count", 1)) for p in manifest.get("parts", [])]
    rows += [(h["code"], h.get("name", h["code"]), h.get("count", 1)) for h in manifest.get("hardware", [])]
    return "\n".join(f"- {name} (x{count}): {sizes.get(pid, 'size unknown')}" for pid, name, count in rows)


def _step(manifest: dict, n: int) -> dict:
    for s in manifest.get("steps", []):
        if s.get("n") == n:
            return s
    raise ValueError(f"the guide has no step {n}")


def verify(job_dir: Path, n: int, jpeg: bytes, lang: str = "en") -> dict:
    job_dir = Path(job_dir)
    manifest = json.loads((job_dir / "manifest.json").read_text())
    step = _step(manifest, n)
    names = {p["id"]: p.get("name", p["id"]) for p in manifest.get("parts", [])}
    names.update({h["code"]: h.get("name", h["code"]) for h in manifest.get("hardware", [])})
    after = job_dir / "refs" / f"step-{n:02d}-ref.png"
    before = job_dir / "refs" / f"step-{n - 1:02d}-ref.png"
    if not after.is_file():
        raise ValueError(f"step {n} has no reference render")
    actions = [(a.get("text") or {}).get("en") for a in step.get("actions", [])]
    adds = [f"{names.get(p, p)}" for p in step.get("pickParts", [])]
    adds += [f"{h.get('qty', '?')} x {names.get(h.get('code'), h.get('code'))}" for h in step.get("hardware", [])]
    prompt = PROMPT.format(
        n=n, total=len(manifest.get("steps", [])), product=manifest.get("title", "furniture"), title=step.get("title", ""),
        pieces=pieces_text(job_dir, manifest),
        before="\nImage 3 is a render of the assembly before this step." if before.is_file() else "",
        actions="\n".join(f"- {t}" for t in actions if t) or "- (see the title)", adds=", ".join(adds) or "nothing new")
    t0 = time.perf_counter()
    with tempfile.NamedTemporaryFile(suffix=".jpg") as photo:
        photo.write(jpeg)
        photo.flush()
        parts = [prompt, Path(photo.name), after] + ([before] if before.is_file() else [])
        text = gemini.generate(MODEL, parts, json_out=True, timeout=40, thinking_budget=THINKING)
    try:
        out = json.loads(text)
    except json.JSONDecodeError:
        raise RuntimeError(f"the vision model's answer was not JSON: {text[:160]}") from None
    done = bool(out.get("done")) and out.get("visible") is not False
    missing = [str(x)[:80] for x in (out.get("missing") or []) if x][:4]
    say = {"en": str(out.get("say_en") or "")[:200], "el": str(out.get("say_el") or out.get("say_en") or "")[:200]}
    result = {"step": n, "done": done, "visible": out.get("visible") is not False,
            "confidence": round(float(out.get("confidence") or 0), 2), "have": str(out.get("have") or "")[:120],
            "missing": [] if done else missing, "say": say, "message": say.get(lang) or say["en"],
            "latency_ms": round((time.perf_counter() - t0) * 1000), "model": MODEL}
    try:                                               # every check is kept, so a confusing answer can be looked at later
        log_dir = job_dir / "perception"
        log_dir.mkdir(exist_ok=True)
        with (log_dir / "verify.jsonl").open("a", encoding="utf-8") as f:
            f.write(json.dumps({"t": round(time.time(), 1), **result}, ensure_ascii=False) + "\n")
    except OSError:
        pass
    return result
