"""The Decisions API request for one frame (PLAN §6.1): the one `pipeline.perception` wrote and benchmarked.

    pack = load_pack(guide, step)          refs/step-NN-questions.json, as the refs stage writes it
    ask = build_ask(guide, pack, jpeg)     inputs and questions, ready for `pipeline.check.decisions.ask`

For a job with question files the input blocks come straight from `pipeline.perception.request.build`:
step header, live frame, sheet note, reference sheet (JPEG), and that file's questions. Option keys of the
choice questions are the labels on the sheet (part ids and hardware codes), so answers need no translation.

Three things are added here:
- A question file written before the current `step_complete` wording (`pipeline.perception.questions.STEP_COMPLETE_V`)
  gets that question and its sheet note in the current wording, in memory (`writer.current`): the job folder is
  not touched and the log says so once per step. This is what makes a step whose hardware does not show on the
  reference sheet (MALM drawer steps 3 and 7) answer about the hardware instead of about a picture that cannot
  show it. Rebuilding the job, or `python -m pipeline.perception.questions <job> --no-model`, writes it to the file.
- One question the files do not have, `right_hardware`: a yes/no about the hardware needed right now (the
  current action's code when the display reports it). It cross-checks the `hardware_in_hand` choice.
- A job built before the refs stage wrote question files has none. Its questions are then made in memory
  with the same template (`pipeline.perception.questions.template`), from the sheet it has, and the log
  says so. Nothing is written into the job folder. Such a job has the older sheet (no orange parts), which
  was not benchmarked.
"""
from __future__ import annotations

import base64
import logging
from dataclasses import dataclass, field

from pipeline.perception import questions as writer
from pipeline.perception import request as bench
from server.perception.guide import Guide

log = logging.getLogger("agj.perception")
MODEL = "gemini-decision-1"            # what pipeline.check.decisions sends. PLAN §3: do not substitute another model
RESOLUTIONS = ("low", "medium", "high", "ultra_high")
EXTRA = "right_hardware"               # the one question added to the benchmarked set
NOT_A_CODE = ("no_hardware", "other")


@dataclass(frozen=True)
class StepPack:
    """One step's questions and fixed input text: the content of refs/step-NN-questions.json."""
    step: int
    origin: str                        # "file": written by the refs stage. "fallback": made in memory for an older job
    pack: dict

    @property
    def written_by(self) -> str:
        return str(self.pack.get("written_by") or "unknown")

    @property
    def parts(self) -> tuple:
        return tuple(self.pack["expects"]["parts"])

    @property
    def hardware(self) -> tuple:
        return tuple(self.pack["expects"]["hardware"])


@dataclass(frozen=True)
class Ask:
    """One request, as `pipeline.check.decisions.ask(inputs, questions, resolution)` takes it."""
    step: int
    inputs: list                       # strings and ("image/jpeg", bytes) pairs, in the order the model sees them
    questions: dict
    resolution: str = "medium"
    parts: tuple = ()                  # what the step expects: used by the simulated decider only
    hardware: tuple = ()
    codes: tuple = field(default=())   # the hardware needed right now (what `right_hardware` asks about)


def _usable(pack, job) -> str | None:
    """Why a question file cannot be used, or None."""
    if not isinstance(pack, dict):
        return "not a JSON object"
    for key in ("header", "sheet_note", "sheet"):
        if not isinstance(pack.get(key), str) or not pack[key]:
            return f"`{key}` is missing"
    expects = pack.get("expects")
    if not isinstance(expects, dict) or not isinstance(expects.get("parts"), list) or not isinstance(expects.get("hardware"), list):
        return "`expects` is missing"
    if not isinstance(pack.get("questions"), dict) or not pack["questions"]:
        return "`questions` is missing"
    problems = writer.validate(pack["questions"])
    if problems:
        return "; ".join(problems[:3])
    if not (job / pack["sheet"]).is_file():
        return f"the sheet {pack['sheet']} is missing"
    return None


def fallback_pack(guide: Guide, n: int) -> dict:
    """What `pipeline.perception.questions.build(job, use_model=False)` would write for step `n`, without writing it."""
    manifest = guide.manifest
    k = next(i for i, s in enumerate(manifest["steps"]) if s["n"] == n)
    s = manifest["steps"][k]
    sheet = guide.sheet(n)
    shown = writer.added_px(guide.dir, sheet)
    questions = writer.template(manifest, sheet, k, shown)
    problems = writer.validate(questions)
    if problems:
        raise ValueError("; ".join(problems[:3]))
    image = guide.sheet_image(n)
    note = writer.detail_note(n, sheet, shown)
    return {"step": n, "written_by": "template", "step_complete_v": writer.STEP_COMPLETE_V, "added_px": shown,
            "detail": sheet.get("detail") if note else None, "detail_note": note,
            "header": writer.header(manifest, k), "sheet_note": writer.sheet_note(n, sheet, shown),
            "sheet": str(image.relative_to(guide.dir.resolve())) if image else None,
            "expects": {"parts": s["pickParts"], "hardware": [h["code"] for h in s["hardware"]]},
            "questions": questions}


_told: set = set()                     # (job folder, step) whose older question file was already logged


def _current(guide: Guide, n: int, pack: dict) -> dict:
    """The file's pack with `step_complete` in the current wording (see the module text). The file's own dict is
    cached by `pipeline.perception.request` and is never changed."""
    try:
        k = next(i for i, s in enumerate(guide.manifest["steps"]) if s["n"] == n)
        sheet = guide.sheet(n)
        shown = pack["added_px"] if "added_px" in pack else writer.added_px(guide.dir, sheet)
        new = writer.current(pack, guide.manifest, sheet, k, shown)
        if new is pack or writer.validate(new["questions"]):
            return pack
    except Exception as e:                                           # noqa: BLE001 - an older or odd manifest: keep the file
        log.warning("perception: step %s of %s keeps its older step_complete question (%s: %s)", n, guide.dir.name,
                    type(e).__name__, e)
        return pack
    if (str(guide.dir), n) not in _told:
        _told.add((str(guide.dir), n))
        log.info("perception: %s step %s: step_complete brought to wording v%s in memory (the step's additions cover "
                 "%s px of the reference%s)", guide.dir.name, n, writer.STEP_COMPLETE_V, shown,
                 "" if not writer.hidden(shown) else ": hidden, so the question compares with the close-up "
                 + new["detail"] if new.get("detail") else ": hidden and no close-up, run pipeline.perception.details")
    return new


def load_pack(guide: Guide, n: int) -> StepPack:
    """The step's questions. Raises ValueError when there are none and none can be made."""
    path = guide.dir / "refs" / f"step-{n:02d}-questions.json"       # where pipeline.perception.request reads it
    if path.is_file():
        try:
            pack = bench.step_pack(str(guide.dir), n)
            problem = _usable(pack, guide.dir)
        except (OSError, ValueError) as e:
            pack, problem = None, f"{type(e).__name__}: {e}"
        if problem is None:
            return StepPack(n, "file", _current(guide, n, pack))
        log.error("perception: %s cannot be used (%s); questions for step %s are made from the template instead",
                  path, problem, n)
    try:
        pack = fallback_pack(guide, n)
    except Exception as e:                                           # noqa: BLE001 - an older or odd manifest
        raise ValueError(f"no questions for step {n} and the template cannot make them: {type(e).__name__}: {e}") from None
    log.warning("perception: job %s has no refs/step-%02d-questions.json (built before the refs stage wrote them). "
                "Using template questions made in memory on its old sheet: this combination was not benchmarked. "
                "Rebuild the job, or run `python -m pipeline.refs`, to get the benchmarked sheet and questions.",
                guide.dir.name, n)
    return StepPack(n, "fallback", pack)


def right_hardware_question(guide: Guide, sp: StepPack, codes) -> dict | None:
    """`Is the user holding the hardware needed now?` None when nothing is needed or the step has no hardware question."""
    options = (sp.pack["questions"].get("hardware_in_hand") or {}).get("criteria") or {}
    codes = [c for c in sorted(codes) if c in options]
    if not codes:
        return None
    label = lambda c: f"{guide.name(c)} ({c})"                       # noqa: E731
    want = " or ".join(label(c) for c in codes)
    others = [c for c in options if c not in codes and c not in NOT_A_CODE]
    other = f" ({' or '.join(label(c) for c in others)})" if others else ""
    return {"type": "noul",
            "instructions": f"Is the user holding or driving in the hardware that step {sp.step} needs now: {want}?",
            "criteria": {"true_description": f"The hardware in the user's hands matches {want} on the reference sheet",
                         "false_description": f"The hardware in the user's hands is a different item{other}, or no hardware is held"}}


NOTE_MAX = 400


def real_look_note(guide: Guide) -> str | None:
    """`perception_note` in the manifest (optional): a sentence or two on how the real parts differ from the
    reference pictures, which are rendered in one plain colour. It becomes the last text block of every request.
    Measured on real headset frames of the MALM drawer (Sun 15 Nov), whose front is wood-coloured while the sheet
    shows it white: holding the front, `drawer_front` rose from 0.63-0.66 to 0.74-0.76 and `right_part` from
    0.75-0.78 to 0.84-0.92; holding the light grey bottom panel, a false `drawer_front` (0.78, `right_part` 0.82)
    fell to 0.31 (`right_part` 0.21)."""
    note = guide.manifest.get("perception_note")
    if not isinstance(note, str) or not note.strip():
        return None
    return " ".join(note.split())[:NOTE_MAX]


def build_ask(guide: Guide, sp: StepPack, jpeg: bytes, codes=(), resolution: str = "medium",
              only: tuple | None = None, extra: bool = True) -> Ask:
    """The request for one frame. `only` keeps a subset of the questions (a `show_part` look)."""
    # the four blocks of `pipeline.perception.request.build`, from the pack in memory (a file's pack may carry a
    # newer step_complete question than the file: `_current`)
    pack = sp.pack
    inputs, questions = [pack["header"], ("image/jpeg", jpeg)], pack["questions"]
    if pack.get("sheet"):
        inputs += [pack["sheet_note"], ("image/jpeg", bench.sheet_jpeg(str(guide.dir), pack["sheet"]))]
        inputs += bench.detail_blocks(guide.dir, pack)               # Image 3: the close-up of hidden hardware, if any
    note = real_look_note(guide)
    if note:
        inputs.append(note)
    questions = dict(questions)
    if only is not None:
        questions = {k: v for k, v in questions.items() if k in only}
        added = right_hardware_question(guide, sp, codes) if extra and EXTRA in only else None
        if added and EXTRA not in questions:
            questions[EXTRA] = added
    elif extra:
        added = right_hardware_question(guide, sp, codes)
        if added and EXTRA not in questions:
            questions[EXTRA] = added
    return Ask(step=sp.step, inputs=inputs, questions=questions,
               resolution=resolution if resolution in RESOLUTIONS else "medium",
               parts=sp.parts, hardware=sp.hardware, codes=tuple(sorted(codes)))


def body_of(ask: Ask) -> dict:
    """The JSON body `pipeline.check.decisions.ask` posts for this request. For printing and for tests."""
    blocks = [{"type": "image", "mime_type": item[0], "resolution": ask.resolution,
               "data": base64.b64encode(item[1]).decode()} if isinstance(item, tuple) else {"type": "text", "text": str(item)}
              for item in ask.inputs]
    return {"model": MODEL, "input": blocks, "questions": ask.questions}


def forget(job_dir) -> None:
    """A job was rebuilt: drop what `pipeline.perception.request` remembers of its files."""
    bench.step_pack.cache_clear()
    bench.sheet_jpeg.cache_clear()


def normalise(answers: dict | None) -> dict:
    """API answers in the shape of the logs (`eval/phase2/README.md` §3): a yes/no as the probability of yes,
    a choice as `{label: probability}`."""
    out: dict = {}
    for key, answer in (answers or {}).items():
        if not isinstance(answer, dict):
            continue
        if isinstance(answer.get("noul"), (int, float)) and "choice" not in answer:
            out[key] = float(answer["noul"])
        elif answer.get("choice") is not None:
            probs = answer.get("probabilities")
            if not isinstance(probs, dict) or not probs:
                probs = {answer["choice"]: answer.get("confidence") or 0.0}
            out[key] = {str(k): float(v) for k, v in probs.items() if isinstance(v, (int, float))}
    return out
