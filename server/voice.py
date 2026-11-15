"""Hands-free control and spoken questions for the native headset apps: one utterance in, one intent and reply out.

    understand(job_dir, step, action, lang, audio=(mime, bytes) | text=str)   one `gemini-4-hydrogen` call
    keywords(text)                                                           the intent from a transcript, no model
    reply(guide, intent, step, action, lang)                                 the spoken confirmation for a command

The model hears the clip (WAV or OGG inline) and returns strict JSON: what was said, which of the seven intents
it is, and, for a question, a short answer grounded in the job's own facts (the same facts `pipeline/ask.py`
gives POST /api/jobs/{id}/ask). Commands are not answered by the model: their confirmation ("Πάμε στο βήμα 3.")
and the step and action to show next are worked out here from the manifest, the way the headset walks it
(`note` actions are not guided one by one). A typed `text` is matched by keywords first; only a question goes
to the model. Without a model (no key, or PERCEPTION_SIMULATE) keywords decide and a question gets the step's
hardware from the manifest.

Grounding: an answer may name only hardware codes the job has. A sentence with any other code is dropped.
"""
from __future__ import annotations

import json
import logging
import re
import time
from pathlib import Path

from pipeline import ask
from pipeline.check import gemini
from server.catalog import fold

log = logging.getLogger("agj.voice")
MODEL = ask.MODEL                  # gemini-4-hydrogen (PLAN §3). Do not substitute an older model
THINKING = ask.THINKING            # "low", the lowest level the model accepts
TIMEOUT_S = 20
INTENTS = ("next", "back", "repeat", "where_am_i", "identify", "ask", "none")
COMMANDS = ("next", "back", "repeat", "where_am_i", "identify")
MAX_ANSWER_WORDS = 35
CODE_RE = re.compile(r"\b\d{5,}\b")

SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "transcript": {"type": "STRING"},
        "intent": {"type": "STRING", "enum": list(INTENTS)},
        "answer": {"type": "STRING"},
        "actions": ask.SCHEMA["properties"]["actions"],
    },
    "required": ["transcript", "intent", "answer", "actions"],
}

PROMPT = """You are the voice control of an assembly guide on a mixed-reality headset. A person is building the
{title} hands-free and says something to the guide, in Greek or English. They are at STEP {step} of {total}
(guide page {page}). The current instruction is: "{current}".

Return JSON:
- transcript: exactly what the person said, in the language and script they used (Greek in Greek letters).
  "" when there is no speech.
- intent, one of:
  - "next": they finished or want to go on. "επόμενο", "επόμενο βήμα", "πάμε", "πάμε παρακάτω", "έγινε",
    "το έκανα", "τελείωσα", "έτοιμο", "προχώρα", "συνέχισε", "next", "done", "ok next", "continue", "finished".
  - "back": they want the previous instruction. "πίσω", "πήγαινε πίσω", "προηγούμενο", "back", "go back", "previous".
  - "repeat": they want to hear the instruction again. "ξανά", "επανάλαβε", "πες το ξανά", "τι είπες", "repeat",
    "again", "say that again".
  - "where_am_i": "πού είμαι", "σε ποιο βήμα είμαι", "where am I", "which step is this".
  - "identify": they hold up or point at a thing and ask what it is, so the camera has to look. "τι είναι αυτό",
    "ποια βίδα είναι αυτή", "αυτό είναι το σωστό;", "what is this", "is this the right screw".
  - "ask": any other question or remark about this build: which screw or part they need now, how many, which way
    round, why, something does not fit. "ποια βίδα χρειάζομαι τώρα;", "πόσες καβίλιες;", "which screw do I need?".
  - "none": silence, noise, or speech not meant for the guide (talking to someone else, unrelated chatter).
- answer: only for "ask", else "". In {language}, 1 or 2 short sentences, under 30 words in all. Lead with the
  action. Use only the FACTS below; never invent parts, hardware, sizes or steps. Name a screw by its name and its
  code from HARDWARE (for example "{hw_example}"); never say a code that is not in HARDWARE. If the facts do not
  answer it, say so and point to the drawing of the step and its page. Calm and neutral, no exclamation marks, no
  dashes, never the words "wrong", "mistake", "λάθος". Greek in the informal singular (εσύ). Never show ids with
  underscores. If the question has nothing to do with this build, say in one sentence that you help with this
  build.
- actions: only for "ask", else []. At most 3 screen actions that help the answer:
  {{"type": "highlight_hardware", "code": CODE}} with a code from HARDWARE, {{"type": "highlight_part", "part_id": ID}}
  with an id from PARTS, {{"type": "go_to_step", "step": N}}. Exact ids only.

FACTS
{facts}"""

LANGUAGE = ask.LANGUAGE
TEXT = {
    "en": {"to_step": "On to step {n}, page {page}.", "back_to": "Back to step {n}, page {page}.",
           "last": "That was the last step. The {title} is done.", "first": "This is the first step.",
           "where": "You are at step {n} of {total}, page {page}.",
           "identify": "Hold it up close to the camera.",
           "needs": "Step {n} uses {hardware}, page {page}.",
           "drawing": "Check the drawing for step {n}, page {page}."},
    "el": {"to_step": "Πάμε στο βήμα {n}, σελίδα {page}.", "back_to": "Πίσω στο βήμα {n}, σελίδα {page}.",
           "last": "Αυτό ήταν το τελευταίο βήμα. Τελείωσες.", "first": "Είσαι στο πρώτο βήμα.",
           "where": "Είσαι στο βήμα {n} από {total}, σελίδα {page}.",
           "identify": "Δείξε το από κοντά στην κάμερα.",
           "needs": "Στο βήμα {n} θέλεις {hardware}, σελίδα {page}.",
           "drawing": "Δες το σχέδιο του βήματος {n}, σελίδα {page}."},
}


# ---- keywords (typed text, simulate mode, and a fallback for the model) -----------------------------------

def _phrases(*items: str) -> list[re.Pattern]:
    return [re.compile(r"(?<!\w)" + r"\s+".join(map(re.escape, fold(p).split())) + r"(?!\w)") for p in items]


WHERE = _phrases("πού είμαι", "πού βρίσκομαι", "σε ποιο βήμα", "ποιο βήμα είναι αυτό", "where am i", "where are we",
                 "which step", "what step")
IDENTIFY = _phrases("τι είναι αυτό", "τι είναι αυτή", "τι είναι αυτά", "τι είναι τούτο", "είναι αυτό", "είναι αυτή",
                    "είναι αυτά", "αυτό είναι το σωστό", "αυτή είναι η σωστή", "what is this", "what's this",
                    "whats this", "what is that", "what's that", "is this the", "is this it", "is that the",
                    "which part is this", "which screw is this")
REPEAT = _phrases("ξανά", "επανάλαβε", "επανέλαβε", "ξαναπές", "πες το ξανά", "πες ξανά", "τι είπες", "δεν άκουσα",
                  "repeat", "again", "say that again", "once more", "come again", "pardon", "what did you say")
BACK = _phrases("πίσω", "προηγούμενο", "προηγούμενη", "back", "previous", "go back")
NEXT = _phrases("επόμενο", "επόμενη", "επόμενος", "πάμε παρακάτω", "έγινε", "το έκανα", "τελείωσα", "τέλειωσα",
                "προχώρα", "συνέχισε", "παρακάτω", "next", "done", "continue", "go on", "move on", "finished",
                "i did it")
NEXT_WEAK = _phrases("πάμε", "οκ", "οκέι", "εντάξει", "έτοιμο", "έτοιμος", "έτοιμη", "ok", "okay", "ready", "got it")
ONE_WORD_REPEAT = {fold(w) for w in ("τι", "τί", "πώς", "what", "sorry", "huh")}
QUESTION_START = {fold(w) for w in ("ποια", "ποιο", "ποιος", "ποιες", "ποιους", "πόσες", "πόσα", "πόσοι", "πόσο", "πώς",
                                    "γιατί", "τι", "πού", "which", "what", "how", "why", "where", "when", "do", "does",
                                    "should", "can", "is", "are")}


def keywords(text: str) -> str:
    """The intent of a transcript by keywords: where, identify and repeat phrases first, then a question, then
    the short commands. A longer remark with no command in it is a question; nothing at all is `none`."""
    t = fold(re.sub(r"[.,!;·:«»\"]", " ", text or "")).strip()
    asked = "?" in t or ";" in (text or "")
    t = t.replace("?", " ")
    words = t.split()
    if not words:
        return "none"
    t = " ".join(words)
    hit = lambda pats: any(p.search(t) for p in pats)  # noqa: E731
    if hit(WHERE):
        return "where_am_i"
    if hit(IDENTIFY):
        return "identify"
    if hit(REPEAT) or (len(words) == 1 and words[0] in ONE_WORD_REPEAT):
        return "repeat"
    if asked or words[0] in QUESTION_START:
        return "ask"
    if len(words) <= 6 and hit(BACK):
        return "back"
    if (len(words) <= 6 and hit(NEXT)) or (len(words) <= 3 and hit(NEXT_WEAK)):
        return "next"
    return "ask" if len(words) >= 2 else "none"


# ---- walking the guide the way the headset does -------------------------------------------------------------

def guided(guide: ask.Job) -> list[tuple[int, int]]:
    """Every (step, action index) the headset shows one at a time: all actions except `note`."""
    return [(n, i) for n in guide.order for i, a in enumerate(guide.steps[n].get("actions") or [])
            if isinstance(a, dict) and a.get("type") != "note"]


def neighbour(guide: ask.Job, step: int, action: int, delta: int) -> tuple[int, int] | None:
    flat = guided(guide)
    if (step, action) in flat:
        i = flat.index((step, action)) + delta
        return flat[i] if 0 <= i < len(flat) else None
    if delta > 0:
        return next((p for p in flat if p > (step, action)), None)
    return next((p for p in reversed(flat) if p < (step, action)), None)


def instruction(guide: ask.Job, step: int, action: int, lang: str) -> str:
    """The spoken text of one action; the step's first guided action when `action` is out of range."""
    actions = guide.steps[step].get("actions") or []
    if not 0 <= action < len(actions):
        action = next((i for n, i in guided(guide) if n == step), 0)
    a = actions[action] if 0 <= action < len(actions) and isinstance(actions[action], dict) else {}
    text = a.get("text") or {}
    said = text.get(lang) or text.get("en") or guide.steps[step].get("title") or ""
    return ask.clean(said, 300)


def reply(guide: ask.Job, intent: str, step: int, action: int, lang: str) -> tuple[str, dict | None]:
    """The spoken confirmation for a command and the (step, action) the headset should show, or None."""
    T = TEXT[lang]
    if intent in ("next", "back"):
        target = neighbour(guide, step, action, +1 if intent == "next" else -1)
        if target is None:
            return (T["last"].format(title=guide.title) if intent == "next" else T["first"]), None
        n, i = target
        said = instruction(guide, n, i, lang)
        if n != step:
            said = T["to_step" if intent == "next" else "back_to"].format(n=n, page=guide.page(n)) + " " + said
        return said, {"step": n, "action": i}
    if intent == "repeat":
        return instruction(guide, step, action, lang), {"step": step, "action": action}
    if intent == "where_am_i":
        now = ask.COUNT_RE.sub("", instruction(guide, step, action, lang)).strip()
        where = T["where"].format(n=step, total=len(guide.order), page=guide.page(step))
        return f"{where} {now}".strip(), {"step": step, "action": action}
    if intent == "identify":
        return T["identify"], None
    return "", None


def hardware_answer(guide: ask.Job, step: int, lang: str) -> str:
    """A question answered without a model: the step's hardware with counts and codes, else its drawing."""
    hw = [h for h in guide.steps[step].get("hardware") or [] if isinstance(h, dict) and str(h.get("code")) in guide.hardware]
    if not hw:
        return TEXT[lang]["drawing"].format(n=step, page=guide.page(step))
    listed = ", ".join(f"{h.get('qty', 1)} x {guide.short(str(h['code']), lang)} {h['code']}" for h in hw)
    return TEXT[lang]["needs"].format(n=step, hardware=listed, page=guide.page(step))


def grounded(guide: ask.Job, text: str, step: int, lang: str) -> str:
    """The answer with every sentence that names a code the job does not have taken out, at most 2 sentences."""
    text = ask.clean(text, 300)
    sentences = [s for s in re.split(r"(?<=[.;?])\s+", text) if s]
    kept = [s for s in sentences if all(c in guide.hardware for c in CODE_RE.findall(s))]
    if len(kept) < len(sentences):
        log.info("voice: dropped %d sentence(s) naming a code the job does not have", len(sentences) - len(kept))
    kept = kept[:2]
    words = " ".join(kept).split()
    out = " ".join(words[:MAX_ANSWER_WORDS])
    return out or TEXT[lang]["drawing"].format(n=step, page=guide.page(step))


# ---- one utterance -------------------------------------------------------------------------------------------

def load(job_dir: Path, step: int | None) -> tuple[ask.Job, int]:
    """The job's facts and the step to work from (its first step when none is given). NoManifest, ValueError."""
    guide = ask.Job(Path(job_dir))
    if step is None:
        step = guide.order[0]
    if not isinstance(step, int) or isinstance(step, bool) or step not in guide.steps:
        raise ValueError(f"step must be one of {guide.order}")
    return guide, step


def _result(guide: ask.Job, step: int, action: int, lang: str, transcript: str, intent: str, answer: str,
            actions: list, model: str, simulated: bool, t0: float) -> dict:
    if intent in COMMANDS:
        answer, target = reply(guide, intent, step, action, lang)
        actions = []
    else:
        target = None
        if intent == "ask":
            answer = grounded(guide, answer, step, lang) if answer else hardware_answer(guide, step, lang)
        else:
            answer, actions = "", []
    return {"transcript": transcript, "intent": intent, "answer": answer, "target": target, "actions": actions,
            "latency_ms": round((time.perf_counter() - t0) * 1000), "model": model, "simulated": simulated}


def understand(job_dir: Path, step: int | None, action: int, lang: str, *, audio: tuple[str, bytes] | None = None,
               text: str | None = None, use_model: bool = True) -> dict:
    """`{transcript, intent, answer, target, actions, latency_ms, model, simulated}`.

    `audio` goes to the model; `text` is matched by keywords and goes to the model only when it is a question.
    use_model=False (simulate): keywords only, and a question gets the step's hardware. ValueError for a bad step;
    RuntimeError when the model fails or returns something unusable."""
    t0 = time.perf_counter()
    guide, step = load(job_dir, step)
    lang = lang if lang in LANGUAGE else "el"
    action = action if isinstance(action, int) and not isinstance(action, bool) else 0

    if audio is None:
        said = re.sub(r"\s+", " ", text or "").strip()[:ask.MAX_QUESTION]
        intent = keywords(said)
        if intent != "ask" or not use_model:
            return _result(guide, step, action, lang, said, intent, "", [], "keywords" if use_model else "simulated",
                           not use_model, t0)
    elif not use_model:
        return _result(guide, step, action, lang, "", "none", "", [], "simulated", True, t0)

    hw_example = next((f"{guide.short(c, lang)} {c}" for c in guide.hardware), "long screw 108363")
    prompt = PROMPT.format(title=guide.title, step=step, total=len(guide.order), page=guide.page(step),
                           current=instruction(guide, step, action, "en"), language=LANGUAGE[lang],
                           hw_example=hw_example, facts=ask.facts(guide, step))
    parts: list = [prompt]
    if audio is not None:
        parts += ["The person's voice:", audio]
    else:
        parts.append(f"The person typed instead of speaking (use it as the transcript): {said}")
    try:
        out = json.loads(gemini.generate(MODEL, parts, timeout=TIMEOUT_S, thinking_budget=THINKING, schema=SCHEMA))
    except json.JSONDecodeError:
        raise RuntimeError("the model returned no usable JSON") from None
    except OSError as e:                         # timeout or no connection (HTTP errors arrive as RuntimeError)
        raise RuntimeError(f"{MODEL} did not answer: {type(e).__name__}") from None
    if not isinstance(out, dict):
        raise RuntimeError("the model returned no usable JSON")

    transcript = re.sub(r"\s+", " ", str(out.get("transcript") or "")).strip()[:ask.MAX_QUESTION]
    if audio is None:
        transcript = said
    intent = out.get("intent") if out.get("intent") in INTENTS else keywords(transcript)
    answer = str(out.get("answer") or "")
    actions = ask.valid_actions(guide, out.get("actions")) if intent == "ask" else []
    result = _result(guide, step, action, lang, transcript, intent, answer, actions, MODEL, False, t0)
    log.info("voice: step=%s intent=%s transcript=%r (%s ms)", step, intent, transcript[:80], result["latency_ms"])
    return result
