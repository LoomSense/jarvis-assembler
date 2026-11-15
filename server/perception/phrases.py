"""What is said for each event: a ready sentence in English and Greek for a client that speaks with the
browser's speech synthesis, and the `[PERCEPTION ...]` line for a client that hands the event to the
voice model (PLAN §6.4, `prompts/live_voice.md`).

Voice rules (`docs/design/BRAND.md` §6): short, lead with the fact or the action, name the step and the
page on a correction, calm, describe the fix and never say "wrong", no exclamation marks, no em dashes.
Greek uses the informal singular.
"""
from __future__ import annotations

from server.perception.guide import Guide
from server.perception.logic import SILENT, WARNINGS, Emitted

UNSURE = {"en": "I can't see it clearly. Show me the part.", "el": "Δεν το βλέπω καθαρά. Δείξε μου το κομμάτι."}
FIXED = {
    "unsure_show_part": UNSURE,
    "more_light": {"en": "I can't see well. More light, please.",
                   "el": "Δεν βλέπω καλά. Λίγο περισσότερο φως, σε παρακαλώ."},
    "realign_camera": {"en": "The camera moved. Point it at the work area again.",
                       "el": "Η κάμερα μετακινήθηκε. Στρέψε την ξανά στον χώρο εργασίας."},
    "vision_paused": {"en": "I can't see right now. I'll tell you when I can again.",
                      "el": "Δεν βλέπω αυτή τη στιγμή. Θα σου πω μόλις ξαναδώ."},
    "vision_unavailable": {"en": "Vision is not available. I can still take you through the steps.",
                           "el": "Δεν έχω εικόνα. Μπορώ όμως να σου λέω τα βήματα."},
    "vision_resumed": {"en": "I can see again.", "el": "Βλέπω ξανά."},
}
# What the voice model is asked to do with the line. No request: the line is background and it stays silent.
ASK = {
    "step_complete": "Tell the user step {step} is done.",
    "unsure_show_part": "Ask the user to show the part.",
    "right_part": "Confirm briefly.",
    "right_hardware": "Confirm briefly.",
    "more_light": "Ask for more light, briefly.",
    "realign_camera": "Ask the user to point the camera at the work area again.",
    "show_part_result": "Tell the user briefly what it is.",
}


def _page(page, lang: str, sep: str = ". ") -> str:
    """`. Page 3.` or, with `sep=", "`, `, page 3.`; only the full stop when the page is not known."""
    if page is None:
        return "."
    word = {"en": "page", "el": "σελίδα"}[lang]
    return f"{sep}{word.capitalize() if sep == '. ' else word} {page}."


def _mismatch(guide: Guide, kind: str | None, observed: str, expected: str | None, step, page, repeat: int) -> dict:
    """`That's X. Step N needs Y. Page P.` The fix, not the mistake."""
    obs_en = guide.spoken_en(observed)
    exp_en = guide.spoken_en(expected) if expected else None
    obs_el, exp_el = guide.spoken_el(observed), guide.spoken_el(expected) if expected else None
    if exp_en is None:                                   # the step needs nothing of this kind
        en = f"That's {obs_en}. Step {step} does not need it{_page(page, 'en')}"
    elif repeat:
        en = f"That's {obs_en} again. Step {step} needs {exp_en}{_page(page, 'en', ', ')}"
    else:
        en = f"That's {obs_en}. Step {step} needs {exp_en}{_page(page, 'en')}"
    if obs_el and exp_el:
        el = (f"Πάλι κρατάς {obs_el}. Το βήμα {step} θέλει {exp_el}{_page(page, 'el', ', ')}" if repeat else
              f"Κρατάς {obs_el}. Το βήμα {step} θέλει {exp_el}{_page(page, 'el')}")
    elif obs_el and expected is None:
        el = f"Κρατάς {obs_el}. Δεν χρειάζεται στο βήμα {step}{_page(page, 'el')}"
    else:
        thing = "άλλο εξάρτημα" if kind == "hardware" else "άλλο κομμάτι"
        where = f", στη σελίδα {page}." if page is not None else "."
        el = f"Κρατάς {thing}. Δες ποιο θέλει το βήμα {step}{where}"
    return {"en": en, "el": el}


def _match(guide: Guide, kind: str | None, observed: str, step) -> dict:
    obs_el = guide.spoken_el(observed)
    el = f"Ωραία. Κρατάς {obs_el}." if obs_el else f"Ωραία. Αυτό θέλει το βήμα {step}."
    return {"en": f"Good. That's {guide.spoken_en(observed)}.", "el": el}


def sentences(e: Emitted, guide: Guide, next_step: int | None = None, next_page: int | None = None) -> dict:
    """`{"en": ..., "el": ...}` for one event."""
    step, page = e.step, e.page
    if e.event in ("wrong_hardware", "wrong_part") and e.observed_id:
        return _mismatch(guide, guide.kind(e.observed_id), e.observed_id, e.expected_id, step, page, e.repeat)
    if e.event in ("right_hardware", "right_part") and e.observed_id:
        return _match(guide, guide.kind(e.observed_id), e.observed_id, step)
    if e.event == "flip_needed":                         # the assembly, not the held part, stands the wrong way
        turn = guide.turn_text(step) if step is not None else None
        if turn:
            return {"en": f"{turn['en']} Step {step}{_page(page, 'en', ', ')}",
                    "el": f"{turn['el']} Βήμα {step}{_page(page, 'el', ', ')}"}
        return {"en": f"Turn the assembly the way step {step} shows{_page(page, 'en', ', ')}",
                "el": f"Γύρισε την κατασκευή όπως δείχνει το βήμα {step}{_page(page, 'el', ', ')}"}
    if e.event == "out_of_order":
        return {"en": f"Hold on. That part comes after step {step}. Finish step {step} first{_page(page, 'en', ', ')}",
                "el": f"Περίμενε. Αυτό το κομμάτι μπαίνει μετά το βήμα {step}. "
                      f"Τελείωσε πρώτα το βήμα {step}{_page(page, 'el', ', ')}"}
    if e.event == "step_complete":
        if next_step is None:
            return {"en": f"Step {step} done. That was the last step.",
                    "el": f"Το βήμα {step} έγινε. Ήταν το τελευταίο βήμα."}
        return {"en": f"Step {step} done. Next is step {next_step}{_page(next_page, 'en', ', ')}",
                "el": f"Το βήμα {step} έγινε. Επόμενο το βήμα {next_step}{_page(next_page, 'el', ', ')}"}
    if e.event == "vision_paused" and e.reason == "unavailable":
        return dict(FIXED["vision_unavailable"])
    return dict(FIXED.get(e.event, UNSURE))


def show_part_sentences(guide: Guide, observed: str | None, expected: str | None, step, page) -> dict:
    """The answer to a `show_part` request: one close look at what the person holds up."""
    if observed is None:
        return dict(UNSURE)
    if expected and observed == expected:
        return _match(guide, guide.kind(observed), observed, step)
    if expected and step is not None:
        return _mismatch(guide, guide.kind(observed), observed, expected, step, page, 0)
    obs_el = guide.spoken_el(observed)
    return {"en": f"That's {guide.spoken_en(observed)}.", "el": f"Κρατάς {obs_el}." if obs_el else f"Είναι το {observed}."}


def perception_line(event: str, step, page, expected: str | None = None, observed: str | None = None,
                    p: float | None = None) -> str:
    """`[PERCEPTION event=wrong_part step=4 page=6 expected="..." observed="..." p=0.93] Tell the user ...`"""
    attrs = [f"event={event}"]
    if step is not None:
        attrs.append(f"step={step}")
    if page is not None:
        attrs.append(f"page={page}")
    for name, value in (("expected", expected), ("observed", observed)):
        if value:
            attrs.append(f'{name}="{str(value).replace(chr(34), chr(39))}"')
    if p is not None:
        attrs.append(f"p={p:.2f}")
    line = "[PERCEPTION " + " ".join(attrs) + "]"
    if event in WARNINGS:
        cite = f"step {step} page {page}" if page is not None else f"step {step}"
        return f"{line} Tell the user briefly, cite {cite}."
    ask = ASK.get(event)
    return f"{line} {ask.format(step=step)}" if ask else line


def spoken(event: str) -> bool:
    """False for events that only change what the screen shows."""
    return event not in SILENT
