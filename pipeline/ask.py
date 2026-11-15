"""Ask the guide: a short, grounded answer to a question the person types or says while assembling (phase 2).

    answer(job_dir, step, question, lang="el", frame_jpeg=None, history=None) -> dict
    .venv/bin/python -m pipeline.ask <job_dir> <step> "<question>" [--lang en] [--frame frame.jpg]

The model (`gemini-4-hydrogen`, thinking `low`, JSON schema output) sees only the job's own facts: the
manifest step with its ordered actions, the plan step, every step in short form, the parts and hardware
lists, the flags, the guide's drawing for the step and, if sent, the camera frame. What comes back is checked
in code: UI actions may only name steps, part ids and hardware codes the job has; anything else is dropped.

When the person describes something already done that does not match the guide ("the panel is upside
down"), `undo` lists, in order, what to take off and which step to do again. The model is given, for every
step so far, what undoing it means, worked out here from the manifest's action order (latest first,
screws out before the part they hold comes off), so it does not have to invent the order. If it names
the step but writes no undo, the same facts are turned into sentences here.

Voice (`docs/design/BRAND.md` §6, `server/perception/phrases.py`): calm, lead with the action, describe the
fix and never say "wrong", no exclamation marks, no em dashes, Greek in the informal singular.
"""
from __future__ import annotations

import argparse
import io
import json
import re
import threading
import time
from pathlib import Path

from pipeline.check import gemini

MODEL = "gemini-4-hydrogen"
THINKING = "low"           # the lowest level the model accepts ("minimal" is refused, measured Sun 15 Nov)
IMAGE_EDGE = 768           # long edge of every image sent; the step drawings are about 1500 px
MAX_QUESTION = 500
MAX_HISTORY = 4
MAX_ACTIONS = 3
MAX_UNDO = 7
ACTION_TYPES = ("go_to_step", "highlight_part", "highlight_hardware")

SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "answer": {"type": "STRING"},
        "actions": {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {
            "type": {"type": "STRING", "enum": list(ACTION_TYPES)},
            "step": {"type": "INTEGER", "nullable": True},
            "part_id": {"type": "STRING", "nullable": True},
            "code": {"type": "STRING", "nullable": True}}, "required": ["type"]}},
        "mistake_step": {"type": "INTEGER", "nullable": True},
        "undo": {"type": "ARRAY", "items": {"type": "STRING"}, "nullable": True},
        "cite_step": {"type": "INTEGER", "nullable": True},
    },
    "required": ["answer", "actions", "mistake_step", "undo", "cite_step"],
}

PROMPT = """You are the voice of an assembly guide. A person is building the {title} with the paper guide beside
them and asks you a question. They are at STEP {step} of {total} (guide page {page}). The steps before it are done;
step {step} may be partly done.

Rules:
- Use only the facts below and the attached image(s). Never invent parts, hardware, sizes, holes or steps. If the
  facts do not answer the question, say so briefly and point to the drawing of the step and its page.
- Answer in {language}, in 1 or 2 short sentences, under 30 words in all. Lead with the action. Name a part by its
  name and a screw by its name and code (for example "{hw_example}"). Cite the step and page when you correct
  something. Never show internal ids with underscores.
- Calm and neutral. Describe the fix, not the mistake. Never use the words "wrong", "mistake", "λάθος", "λανθασμένο".
  No exclamation marks, no em dashes or en dashes, no emoji.
- For a "why" question, give the practical reason the drawing and the steps show (what it lines up with, what
  goes into it next, how the assembly stands), without inventing one.
- If the question has nothing to do with building this product, say in one short sentence that you help with this
  build, and steer back to step {step}. Then actions is empty and mistake_step and undo are null.
{frame_rule}
JSON fields:
- answer: the spoken answer.
- mistake_step: only when the person says something is ALREADY done in a way that does not match the guide (a part
  on upside down or the other way round, a screw in another hole, a part or screw missing, steps done out of
  order). The step where it happened: the one they name, else work it out from the steps below, else {step}.
  Otherwise null. A question about what to do next is not a mistake.
- undo: only when mistake_step is set, else null. 2 to 6 short imperative sentences in {language}, in order, that
  get back to a correct state: first take off only what has to come off to reach the problem, latest first, using
  UNDO FACTS (screws out before the part they hold comes off); then the fix itself as the guide's drawing shows it;
  then "do step N again, page P" and every later step that had to be undone. What may already be done at step
  {step} itself, say as "if you already ...". Use the person's own words to work out how far they got.
- actions: at most 3 screen actions that help with this answer: {{"type": "go_to_step", "step": N}} when the answer
  points to another step (always the mistake step when there is one); {{"type": "highlight_part", "part_id": ID}}
  with an id from PARTS when the answer is about a part; {{"type": "highlight_hardware", "code": CODE}} with a code
  from HARDWARE when it is about hardware. Exact ids only.
- cite_step: the step whose drawing and page support the answer.

FACTS
{facts}"""

FRAME_RULE = ("- The last image is the person's camera view right now. Mention what it shows only when it is clearly "
              "visible; if it is unclear, ask them to show the part.\n")
LANGUAGE = {"el": "Greek, informal singular (εσύ)", "en": "English"}
FALLBACK = {"el": "Δεν έχω απάντηση αυτή τη στιγμή. Δες το σχέδιο του βήματος {step}, σελίδα {page}.",
            "en": "I have no answer right now. Check the drawing for step {step}, page {page}."}
COUNT_RE = re.compile(r"\s*(Screw|Βίδα) \d+ (of|από) \d+\.")


# ---- the job's facts --------------------------------------------------------------------------------

def _read_json(path: Path) -> dict | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return data if isinstance(data, dict) else None


class NoManifest(ValueError):
    """The job has no manifest.json with steps yet (it is still building, or failed)."""


class Job:
    """What one question needs from a job folder. Raises NoManifest when there is no manifest with steps."""

    def __init__(self, job_dir: Path):
        self.dir = Path(job_dir)
        manifest = _read_json(self.dir / "manifest.json")
        if not manifest or not manifest.get("steps"):
            raise NoManifest("the job has no manifest.json with steps yet")
        self.manifest = manifest
        self.plan = _read_json(self.dir / "plan.approved.json") or _read_json(self.dir / "plan.json") or {}
        self.title = str(manifest.get("title") or "furniture")
        self.steps = {int(s["n"]): s for s in manifest["steps"]}
        self.order = sorted(self.steps)
        self.plan_steps = {int(s["n"]): s for s in self.plan.get("steps", []) if isinstance(s.get("n"), int)}
        plan_items = {str(p.get("id")): p for p in self.plan.get("parts", [])}
        plan_items.update({str(h.get("code")): h for h in self.plan.get("hardware", [])})
        self.parts = {str(p["id"]): {**plan_items.get(str(p["id"]), {}), **p} for p in manifest.get("parts", [])}
        self.parts.update({k: v for k, v in plan_items.items() if k not in self.parts and "id" in v})
        self.hardware = {str(h["code"]): {**plan_items.get(str(h["code"]), {}), **h} for h in manifest.get("hardware", [])}
        self.hardware.update({k: v for k, v in plan_items.items() if k not in self.hardware and "code" in v})

    def page(self, n: int) -> int | None:
        page = (self.steps[n].get("cite") or {}).get("page")
        return page if isinstance(page, int) else None

    def short(self, item: str, lang: str = "en") -> str:
        info = self.parts.get(item) or self.hardware.get(item) or {}
        names = info.get("names") or {}
        if lang == "el":
            return str(names.get("el") or info.get("el") or names.get("en") or info.get("short") or item)
        return str(names.get("en") or info.get("short") or info.get("name") or item)

    def actions(self, n: int) -> list[dict]:
        return [a for a in self.steps[n].get("actions") or [] if isinstance(a, dict)]

    def drawing(self, n: int) -> Path | None:
        """The guide's own drawing of the step (its crop); the reference render when there is no crop."""
        base = self.dir.resolve()
        for rel in (self.steps[n].get("crop"), f"crops/step-{n:02d}.png", self.steps[n].get("refRender")):
            if rel:
                path = (base / rel).resolve()
                if path.is_relative_to(base) and path.is_file():
                    return path
        return None


def _groups(actions: list[dict]) -> list[tuple[dict, int]]:
    """Consecutive actions of the same type on the same item, as (first action, how many)."""
    out: list[tuple[dict, int]] = []
    for a in actions:
        key = (a.get("type"), a.get("code") or a.get("partId"))
        if out and (out[-1][0].get("type"), out[-1][0].get("code") or out[-1][0].get("partId")) == key:
            out[-1] = (out[-1][0], out[-1][1] + 1)
        else:
            out.append((a, 1))
    return out


def _item(a: dict) -> str | None:
    return str(a.get("code") or a.get("partId")) if (a.get("code") or a.get("partId")) else None


def undo_facts(job: Job, n: int) -> list[str]:
    """What undoing step `n` means, latest action first: screws come out before the part they hold."""
    out = []
    for a, k in reversed(_groups(job.actions(n))):
        item, kind = _item(a), a.get("type")
        if kind == "fasten" and item:
            out.append(f"unscrew the {k} {job.short(item)}{'s' if k > 1 else ''} {item} put in at step {n}")
        elif kind == "place" and item:
            out.append(f"take off the {job.short(item)}{f' ({k} of them)' if k > 1 else ''} placed at step {n}")
        elif kind == "turn":
            out.append(f"turn the assembly back the way it stood before step {n}")
    return out


def facts(job: Job, step: int) -> str:
    lines = ["PARTS (id: name; spoken name en / el; count; guide page)"]
    for pid, p in job.parts.items():
        cite = (p.get("cite") or {}).get("page")
        lines.append(f"- {pid}: {p.get('name') or pid}; {job.short(pid)} / {job.short(pid, 'el')}; {p.get('count', '?')}; page {cite}")
    lines.append("HARDWARE (code: name; spoken name en / el; kind; length; count in the box; guide page)")
    for code, h in job.hardware.items():
        length = h.get("length_mm") or (h.get("params") or {}).get("length")
        est = " (estimated)" if (h.get("dims") or {}).get("estimated") else ""
        cite = (h.get("cite") or {}).get("page")
        lines.append(f"- {code}: {h.get('name') or code}; {job.short(code)} / {job.short(code, 'el')}; {h.get('kind', '?')}; "
                     f"{f'{length} mm{est}' if length else 'length not printed'}; {h.get('count', '?')}; page {cite}")
    lines.append("STEPS (in guide order; the person's step is marked; actions in the order the person does them)")
    for n in job.order:
        s = job.steps[n]
        mark = "  <== THE PERSON IS HERE" if n == step else ("  (step before)" if n == step - 1 else
                                                             "  (next step)" if n == step + 1 else "")
        hw = ", ".join(f"{h.get('qty')} x {h.get('code')}" for h in s.get("hardware") or [])
        lines.append(f"STEP {n} · page {job.page(n)} · position: {s.get('pose', 'upright')} · {s.get('title', '')}"
                     f"{' · hardware: ' + hw if hw else ''}{mark}")
        if n == step:
            for i, a in enumerate(job.actions(n), 1):
                text = a.get("text") or {}
                lines.append(f"  {i}. {a.get('type')} {_item(a) or 'assembly'}: {text.get('en', '')} | {text.get('el', '')}")
        else:
            for a, k in _groups(job.actions(n)):
                text = COUNT_RE.sub("", (a.get("text") or {}).get("en", ""))
                lines.append(f"  - {a.get('type')} {_item(a) or 'assembly'}{f' x{k}' if k > 1 else ''}: {text}")
    plan_step = job.plan_steps.get(step)
    if plan_step:
        lines.append(f"PLAN FOR STEP {step} (how the parts join)")
        for add in plan_step.get("add") or []:
            mates = "; ".join(f"{m.get('moving')} to {m.get('fixed')}" for m in add.get("mates") or [])
            lines.append(f"- {add.get('instance')}: {mates}")
        for key in ("notes", "view", "flip_whole_assembly"):
            if plan_step.get(key) not in (None, "", [], False):
                lines.append(f"- {key}: {plan_step[key]}")
        if isinstance(plan_step.get("turn_text"), dict):
            lines.append(f"- how the assembly stands: {plan_step['turn_text'].get('en', '')}")
    flags = [f for f in job.manifest.get("flags") or [] if isinstance(f, dict)]
    if flags:
        lines.append("OPEN DOUBTS IN THE 3D MODEL (the guide's drawing wins over the model; say so if one matters)")
        lines += [f"- {f.get('ref')}: {f.get('message')}" for f in flags]
    lines.append("UNDO FACTS (what undoing each step means)")
    for n in [m for m in job.order if m <= step]:
        lines.append(f"- step {n}: " + ("; ".join(undo_facts(job, n)) or "nothing to undo"))
    return "\n".join(lines)


# ---- images -------------------------------------------------------------------------------------------

_cache: dict[tuple[str, int], bytes] = {}
_cache_lock = threading.Lock()


def _jpeg(data: bytes, quality: int) -> bytes:
    from PIL import Image

    img = Image.open(io.BytesIO(data))
    img.thumbnail((IMAGE_EDGE, IMAGE_EDGE))
    out = io.BytesIO()
    img.convert("RGB").save(out, "JPEG", quality=quality)
    return out.getvalue()


def drawing_jpeg(path: Path) -> bytes:
    """The step drawing at IMAGE_EDGE, cached per file version."""
    key = (str(path), path.stat().st_mtime_ns)
    with _cache_lock:
        if key in _cache:
            return _cache[key]
    data = _jpeg(path.read_bytes(), 85)
    with _cache_lock:
        _cache[key] = data
    return data


def frame_image(frame_jpeg: bytes) -> bytes:
    """The camera frame, shrunk only when it is larger than IMAGE_EDGE (phase-2 frames are 768 px already)."""
    from PIL import Image

    try:
        size = Image.open(io.BytesIO(frame_jpeg)).size
    except Exception as e:                       # PIL raises several types for a broken file
        raise ValueError(f"the frame is not a readable image: {e}") from None
    return frame_jpeg if max(size) <= IMAGE_EDGE else _jpeg(frame_jpeg, 75)


# ---- the answer -----------------------------------------------------------------------------------------

def clean(text: str, limit: int = 300) -> str:
    """Brand voice in code as well: no em or en dashes, no exclamation marks, one line, bounded."""
    text = re.sub(r"\s*[—–]\s*", ", ", str(text))
    text = text.replace("!", ".").replace("¡", "")
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"\.{2,}", ".", text)
    return text[:limit].rstrip()


def valid_actions(job: Job, raw) -> list[dict]:
    """Keep only actions that name a step, part id or hardware code the job has, without duplicates."""
    out: list[dict] = []
    for a in raw if isinstance(raw, list) else []:
        if not isinstance(a, dict):
            continue
        kind, act = a.get("type"), None
        if kind == "go_to_step" and isinstance(a.get("step"), int) and not isinstance(a["step"], bool) and a["step"] in job.steps:
            act = {"type": kind, "step": a["step"]}
        elif kind == "highlight_part" and str(a.get("part_id")) in job.parts:
            act = {"type": kind, "part_id": str(a["part_id"])}
        elif kind == "highlight_hardware" and str(a.get("code")) in job.hardware:
            act = {"type": kind, "code": str(a["code"])}
        if act and act not in out:
            out.append(act)
    return out[:MAX_ACTIONS]


def fallback_undo(job: Job, mistake: int, step: int, lang: str) -> list[str]:
    """Undo sentences straight from the action order, for when the model names the step but writes none."""
    out = []
    for n in sorted((m for m in job.order if mistake <= m <= max(step, mistake)), reverse=True):
        for a, k in reversed(_groups(job.actions(n))):
            item, kind = _item(a), a.get("type")
            if kind == "fasten" and item:
                out.append(f"Unscrew {k} x {job.short(item)} {item} from step {n}." if lang == "en" else
                           f"Ξεβίδωσε {k} x {job.short(item, 'el')} {item} από το βήμα {n}.")
            elif kind == "place" and item:
                out.append(f"Take off the {job.short(item)} from step {n}." if lang == "en" else
                           f"Βγάλε: {job.short(item, 'el')}, από το βήμα {n}.")
            elif kind == "turn":
                out.append(f"Turn the assembly back the way it stood before step {n}." if lang == "en" else
                           f"Γύρισε την κατασκευή όπως ήταν πριν από το βήμα {n}.")
    page = job.page(mistake)
    out.append(f"Do step {mistake} again as the drawing shows, page {page}." if lang == "en" else
               f"Κάνε ξανά το βήμα {mistake} όπως δείχνει το σχέδιο, σελίδα {page}.")
    if len(out) > MAX_UNDO:                  # keep the order: the first things to take off, then the step to redo
        out = out[:MAX_UNDO - 1] + out[-1:]
    return with_carry_on(job, out, mistake, step, lang)


STEP_RE = re.compile(r"(?:steps?|βήμα|βήματα|βήματος)\s+(\d+)", re.IGNORECASE)


def with_carry_on(job: Job, undo: list[str], mistake: int, step: int, lang: str) -> list[str]:
    """When the problem is from an earlier step, the undo ends by sending the person on to the steps after it,
    unless its last line already does."""
    later = [m for m in job.order if mistake < m <= step]
    if not later or any(int(n) > mistake for n in STEP_RE.findall(undo[-1] if undo else "")):
        return undo
    page = job.page(later[0])
    line = (f"Then carry on with step {later[0]}, page {page}." if lang == "en" else
            f"Μετά συνέχισε με το βήμα {later[0]}, σελίδα {page}.")
    return (undo[:MAX_UNDO - 1] + [line]) if len(undo) >= MAX_UNDO else undo + [line]


def answer(job_dir: Path, step: int, question: str, lang: str = "el", frame_jpeg: bytes | None = None,
           history: list[dict] | None = None) -> dict:
    """`{answer, actions, undo, cites: {page, step}, latency_ms, model}`. ValueError for a bad step, question or
    frame; RuntimeError when the model fails or returns something unusable."""
    t0 = time.perf_counter()
    job = Job(Path(job_dir))
    if not isinstance(step, int) or isinstance(step, bool) or step not in job.steps:
        raise ValueError(f"step must be one of {job.order}")
    question = re.sub(r"\s+", " ", str(question or "")).strip()[:MAX_QUESTION]
    if not question:
        raise ValueError("the question is empty")
    lang = lang if lang in LANGUAGE else "el"

    hw_example = next((f"{job.short(c, lang)} {c}" for c in job.hardware), "long screw 108363")
    prompt = PROMPT.format(title=job.title, step=step, total=len(job.order), page=job.page(step),
                           language=LANGUAGE[lang], hw_example=hw_example,
                           frame_rule=FRAME_RULE if frame_jpeg else "", facts=facts(job, step))
    parts: list = [prompt]
    drawing = job.drawing(step)
    if drawing:
        parts += [f"The guide's own drawing of step {step} (page {job.page(step)}):", ("image/jpeg", drawing_jpeg(drawing))]
    if frame_jpeg:
        parts += ["The person's camera view right now:", ("image/jpeg", frame_image(frame_jpeg))]
    turns = [h for h in history or [] if isinstance(h, dict) and h.get("q")][-MAX_HISTORY:]
    if turns:
        parts.append("Earlier in this conversation:\n" + "\n".join(
            f"Q: {str(h['q'])[:MAX_QUESTION]}\nA: {str(h.get('a') or '')[:MAX_QUESTION]}" for h in turns))
    parts.append(f"The person asks: {question}")

    try:
        text = gemini.generate(MODEL, parts, timeout=25, thinking_budget=THINKING, schema=SCHEMA)
        out = json.loads(text)
    except json.JSONDecodeError:
        raise RuntimeError("the model returned no usable JSON") from None
    except OSError as e:                         # timeout or no connection (HTTP errors arrive as RuntimeError)
        raise RuntimeError(f"{MODEL} did not answer: {e}") from None
    if not isinstance(out, dict):
        raise RuntimeError("the model returned no usable JSON")

    said = clean(out.get("answer") or "") or FALLBACK[lang].format(step=step, page=job.page(step))
    actions = valid_actions(job, out.get("actions"))
    mistake = out.get("mistake_step")
    mistake = mistake if isinstance(mistake, int) and not isinstance(mistake, bool) and mistake in job.steps else None
    undo = None
    if mistake is not None:
        undo = [clean(u, 200) for u in out.get("undo") or [] if isinstance(u, str) and clean(u, 200)][:MAX_UNDO]
        undo = with_carry_on(job, undo, mistake, step, lang) if undo else fallback_undo(job, mistake, step, lang)
        if mistake != step and {"type": "go_to_step", "step": mistake} not in actions:
            actions = ([{"type": "go_to_step", "step": mistake}] + actions)[:MAX_ACTIONS]
    cite = out.get("cite_step")
    cite = cite if isinstance(cite, int) and not isinstance(cite, bool) and cite in job.steps else (mistake or step)
    return {"answer": said, "actions": actions, "undo": undo, "cites": {"page": job.page(cite), "step": cite},
            "latency_ms": round((time.perf_counter() - t0) * 1000), "model": MODEL}


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("job_dir", type=Path)
    ap.add_argument("step", type=int)
    ap.add_argument("question")
    ap.add_argument("--lang", default="el", choices=sorted(LANGUAGE))
    ap.add_argument("--frame", type=Path)
    a = ap.parse_args()
    print(json.dumps(answer(a.job_dir, a.step, a.question, a.lang, a.frame.read_bytes() if a.frame else None),
                     ensure_ascii=False, indent=1))
