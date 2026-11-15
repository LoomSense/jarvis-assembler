"""Per-step questions for the Decisions API, written once per guide (AGT-07, PLAN §6.1).

    .venv/bin/python -m pipeline.perception.questions <job_dir> [--no-model]

Reads manifest.json and refs/step-NN-sheet.json, writes refs/step-NN-questions.json and sets
`questions` on every step of the manifest. Each file holds the step header (text that goes in front of
the images) and the `questions` object, ready to send.

Answer keys of the choice questions are the labels printed on the reference sheet (part ids and hardware
codes), so "which part is this" needs no translation. Structure and wording come from a fixed template.
What a part looks like, how the step must be oriented and what "done" looks like are written by
`gemini-4-hydrogen`, which is shown the sheet and the result of the step before (so it can describe what the step
visibly adds); with --no-model, or if its answer is not usable, plain
template wording is used and the file says so (`written_by`).
"""
from __future__ import annotations

import json
import re
import sys
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
from pathlib import Path

from PIL import Image, ImageChops

from pipeline.check import gemini

MODEL = "gemini-4-hydrogen"
# Version of the `step_complete` wording. A question file without it (or with an older one) was written before
# Sun 15 Nov 15:00; `current` brings its step_complete question up to date in memory (the server does this on load).
STEP_COMPLETE_V = 2
# A step whose additions cover fewer pixels than this in the 768 px reference shows nothing the model can compare.
# Measured on the MALM drawer: the cam locks of step 3 and the runner screws of step 7 cover 0 px (hidden from the
# reference camera: `added` and `ref` are the same picture), the cam bolts of step 1 cover 552 px and score 0.94.
MIN_ADDED_PX = 64
KEY = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
FIXED = {   # the same for every step and every guide; never generated
    "hands_present": {"type": "noul", "instructions": "Are one or both of the user's hands visible in Image 1?"},
    "view_blocked": {"type": "noul", "instructions": "Is the main assembly area blocked from view in Image 1 (by the body, a large "
                     "part held close to the camera, or the picture cutting it off)?"},
    "image_poor": {"type": "noul", "instructions": "Is Image 1 too dark, too bright or too blurred to identify parts?"},
    "frame_matches_setup": {"type": "noul", "instructions": "Does Image 1 show the same object as the LEFT half of Image 2, "
                            "seen from roughly the same side?"},
}
SHEET_NOTE = ("Image 2 is a reference sheet. LEFT half: what the work area should look like when step {n} is done{orange}. "
              "RIGHT half: labelled pictures of the parts and hardware that could be in use now.")
ORANGE_NOTE = ("; the parts that step {n} adds are drawn in ORANGE there (in reality they have the same colour as the "
               "other parts)")
# The live camera rarely sees the assembly from the reference's side and height. On stand-in frames from another side,
# finished steps scored as low as 0.17 on step_complete before this note and the wording below (bench, Sun 15 Nov).
VIEW_NOTE = ("Image 1 may show the assembly from another side and height than the LEFT half of Image 2, "
             "so judge which parts are attached, not the viewpoint.")
# Steps are done in order, and later parts often hide what an earlier step added: step 1 of the MALM drawer scored
# 0.23 to 0.46 on frames of steps 2-7 without this note (bench, Sun 15 Nov).
LATER_NOTE = ("Steps are done in order: if Image 1 shows the assembly further along than the LEFT half of Image 2, with "
              "parts attached that are not there yet, step {n} is already done and the answer is yes.")
# For a step whose additions do not show in the reference (small hardware, hidden from the reference camera).
HIDDEN_NOTE = ("; the hardware that step {n} adds is too small or too hidden to show there, so that picture looks the "
               "same before and after step {n}")


# The third image of a request, for a step that has a close-up (`pipeline.perception.details`).
DETAIL_NOTE = ("Image 3 is a close-up of one place where the hardware of step {n} goes. Its LEFT half (BEFORE) shows that "
               "place before step {n}: the hole is still empty. Its RIGHT half (AFTER) shows it when step {n} is done, with "
               "the fitted hardware drawn in ORANGE (in reality it has its own colour: metal, wood or plastic).")


def detail_note(n: int, sheet: dict, shown: int | None = None) -> str | None:
    return DETAIL_NOTE.format(n=n) if sheet.get("detail") and hidden(shown) else None


def added_px(job: Path, sheet: dict) -> int | None:
    """How many pixels of the reference picture the pieces this step adds cover: where `added` (those pieces in
    orange) differs from the plain render next to it. None when the sheet has no such pair (older jobs)."""
    rel = sheet.get("added")
    if not isinstance(rel, str) or "-added." not in rel:
        return None
    added, plain = Path(job) / rel, Path(job) / rel.replace("-added.", "-ref.")
    try:
        return _differing_px(str(added), added.stat().st_mtime_ns, str(plain), plain.stat().st_mtime_ns)
    except OSError:
        return None


@lru_cache(maxsize=256)
def _differing_px(added: str, added_mtime: int, plain: str, plain_mtime: int) -> int | None:
    """Kept per file version: the server asks for it on every one-picture `locate` of a job with older question files."""
    a, b = Image.open(added).convert("RGB"), Image.open(plain).convert("RGB")
    if a.size != b.size:
        return None
    return ImageChops.difference(a, b).convert("L").point(lambda v: 255 if v > 24 else 0).histogram()[255]


def hidden(shown: int | None) -> bool:
    """Do the step's additions fail to show in the reference? Unknown (None) counts as shown: the old behaviour."""
    return shown is not None and shown < MIN_ADDED_PX


def sheet_note(n: int, sheet: dict, shown: int | None = None) -> str:
    orange = "" if not sheet.get("added") else HIDDEN_NOTE.format(n=n) if hidden(shown) else ORANGE_NOTE.format(n=n)
    return SHEET_NOTE.format(n=n, orange=orange)


def header(manifest: dict, k: int) -> str:
    s = manifest["steps"][k]
    parts = {p["id"]: p for p in manifest["parts"]}
    hw = {h["code"]: h for h in manifest["hardware"]}
    picks = [a["partId"] for a in s["actions"] if a["type"] == "place" and a.get("node") in s["moving"]]
    lines = [f"Flat-pack assembly of the {manifest['title']}. STEP {s['n']} of {len(manifest['steps'])}, guide page {s['cite']['page']}: "
             f"{s['title']}."]
    if picks:
        lines.append("Parts added in this step: " + ", ".join(
            f"{picks.count(p)} x {parts[p]['name']} (label {p})" for p in dict.fromkeys(picks)) + ".")
    if s["hardware"]:
        lines.append("Hardware used in this step: " + ", ".join(
            f"{h['qty']} x {hw[h['code']]['name']} (label {h['code']})" for h in s["hardware"]) + ".")
    turn = next((a["text"]["en"] for a in s["actions"] if a["type"] == "turn"), None)
    lines.append(f"The assembly is worked on in this position: {s.get('pose', 'upright')}." + (f" ({turn})" if turn else ""))
    lines.append("Image 1 is the live camera view of the user's work area.")
    return " ".join(lines)


def added(manifest: dict, k: int) -> str:
    """What step k adds, in words: "2 End slats and 8 Short screws"."""
    s = manifest["steps"][k]
    name = {p["id"]: p["name"] for p in manifest["parts"]} | {h["code"]: h["name"] for h in manifest["hardware"]}
    picks = [a["partId"] for a in s["actions"] if a["type"] == "place" and a.get("node") in s["moving"]]
    items = [f"{picks.count(p)} {name[p]}" + ("s" if picks.count(p) > 1 else "") for p in dict.fromkeys(picks)]
    items += [f"{h['qty']} {name[h['code']]}" + ("s" if h["qty"] > 1 else "") for h in s["hardware"]]
    return " and ".join(items) or "nothing new"


def step_complete(manifest: dict, sheet: dict, k: int, shown: int | None = None) -> dict:
    """The `step_complete` question. `shown`: `added_px` of the step's sheet, when known.

    Asked about the visible difference the step makes (what it adds), not about every screw: screws are too small
    to judge in a frame at `medium` resolution, and the answer must not depend on the camera's side. Four cases:
    - the additions show in orange on the sheet: are those parts attached?
    - they do not show at all (hardware hidden from the reference camera) and the step has a close-up
      (`pipeline.perception.details`, Image 3 of the request): do those places look like its AFTER or its BEFORE half?
      Measured on the MALM drawer (Sun 15 Nov, 55 calls): step 7's "before" frame 0.69 -> 0.34, its done frame 0.60;
      step 3's done frame 0.44 -> 0.46 with the frame before it at 0.42, so step 3 is still a thin margin.
    - hidden and no close-up: the sheet has nothing to compare, and a frame that simply looks like the sheet read as
      "done". The question says so, asks for the hardware itself in the live frame, and answers no when it cannot
      be seen: a missed "done" costs a tap, a false one skips a step. Measured: no false "done", but steps 3 and 7
      then never fire (0.40 and 0.31 when done).
    - an older sheet without orange: the comparison wording, unchanged."""
    s = manifest["steps"][k]
    n, a = s["n"], added(manifest, k)
    later = LATER_NOTE.format(n=n)
    picks = [x["partId"] for x in s["actions"] if x["type"] == "place" and x.get("node") in s["moving"]]
    labels = ", ".join(str(h["code"]) for h in s["hardware"]) or ", ".join(dict.fromkeys(map(str, picks))) or "none"
    # the wording measured on the MALM drawer is the hardware-only one; a hidden part is rare and only named plainly
    what = ("small hardware that goes into parts already on the assembly" if s["hardware"] and not picks else
            "pieces that cannot be seen from the side the LEFT half of Image 2 is taken from")
    if sheet.get("added") and hidden(shown) and sheet.get("detail"):     # there is a close-up to compare with
        return {"type": "noul",
                "instructions": f"Step {n} adds {a} (label {labels} in the RIGHT half of Image 2): {what}. "
                                f"It does not show in the LEFT half of Image 2, so looking "
                                f"like that picture is not evidence that the step is done: use Image 3. Find in Image 1 the "
                                f"places that Image 3 shows, from whatever side Image 1 shows the assembly. Do they look like its "
                                f"AFTER half (hardware fitted in the holes) or like its BEFORE half (empty holes)? {later}",
                "criteria": {"true_description": f"The {a} are fitted: those places look like the AFTER half of Image 3",
                             "false_description": "The holes are still empty, as in the BEFORE half of Image 3, or those places "
                                                  "are hidden, too small or too far away in Image 1 to tell"}}
    if sheet.get("added") and hidden(shown):
        return {"type": "noul",
                "instructions": f"Step {n} adds {a} (label {labels} in the RIGHT half of Image 2): {what}. "
                                f"It is too small or too hidden to show in the LEFT half of "
                                f"Image 2, so the LEFT half looks the same before and after step {n}: looking like it is not "
                                f"evidence that the step is done. Look closely at Image 1 itself, from whatever side it shows "
                                f"the assembly: can you see the {a} fitted in the assembly? {later}",
                "criteria": {"true_description": f"The {a} can be seen in Image 1, seated in their places on the assembly",
                             "false_description": f"No fitted hardware of step {n} can be seen in Image 1: its places are still "
                                                  f"empty, or they are hidden, too small or too far away to tell"}}
    if sheet.get("added"):          # sheets from refs.py since Sun 15 Nov draw the added parts in orange
        return {"type": "noul", "instructions": f"Step {n} adds {a}, drawn in ORANGE in the LEFT half of Image 2. "
                f"{VIEW_NOTE} In Image 1, are the parts shown in orange there now attached to the assembly? {later}",
                "criteria": {"true_description": f"The {a} are attached in the places shown in orange",
                             "false_description": f"Those places are still empty: the {a} are not attached yet"}}
    return {"type": "noul", "instructions": f"Compare Image 1 with the LEFT half of Image 2. Is step {n} finished? "
            f"Step {n} adds {a}. {VIEW_NOTE}",
            "criteria": {"true_description": f"The {a} are attached, as in the LEFT half of Image 2",
                         "false_description": f"The {a} are not attached yet: the assembly still looks as it did before step {n}"}}


def current(pack: dict, manifest: dict, sheet: dict, k: int, shown: int | None) -> dict:
    """`pack` (a question file's content) with its step_complete question and sheet note as this version writes
    them; the same object when it is already at this version. Never changes `pack` itself. Criteria that the model
    wrote are kept, except when the step's additions do not show in the reference: those criteria describe a
    difference the reference cannot show."""
    n = manifest["steps"][k]["n"]
    detail = sheet.get("detail") if detail_note(n, sheet, shown) else None
    if "step_complete" not in (pack.get("questions") or {}) or (
            pack.get("step_complete_v") == STEP_COMPLETE_V and pack.get("detail") == detail):
        return pack
    new = step_complete(manifest, sheet, k, shown)
    if pack.get("written_by") not in (None, "template") and not hidden(shown):
        new = {**new, "criteria": pack["questions"]["step_complete"].get("criteria") or new["criteria"]}
    return {**pack, "step_complete_v": STEP_COMPLETE_V, "added_px": shown, "sheet_note": sheet_note(n, sheet, shown),
            "detail": detail, "detail_note": detail_note(n, sheet, shown),
            "questions": {**pack["questions"], "step_complete": new}}


def template(manifest: dict, sheet: dict, k: int, shown: int | None = None) -> dict:
    """The questions with plain wording. `words` (from the model) is merged in afterwards. `shown`: `added_px`."""
    s = manifest["steps"][k]
    nxt = manifest["steps"][k + 1] if k + 1 < len(manifest["steps"]) else None
    name = {p["id"]: p["name"] for p in manifest["parts"]} | {h["code"]: h["name"] for h in manifest["hardware"]}
    cand_parts = [c["label"] for c in sheet["candidates"] if c["kind"] == "part"]
    cand_hw = [c["label"] for c in sheet["candidates"] if c["kind"] == "hardware"]
    n, q = s["n"], {}
    if cand_parts:
        q["part_in_hand"] = {"type": "choice", "instructions": "In Image 1, which part from the reference sheet is the user "
                             "holding, lifting or putting in place right now?",
                             "criteria": {**{p: name[p] for p in cand_parts},
                                          "hardware_only": "The user holds only small hardware (screws) or a tool",
                                          "nothing_held": "The hands are empty, or no hands are visible",
                                          "other": "The user holds something that is not on the reference sheet, or it cannot be told"}}
    if s["pickParts"]:
        want = " or ".join(f"{name[p]} ({p})" for p in s["pickParts"])
        q["right_part"] = {"type": "noul", "instructions": f"Is the user holding or placing a part that step {n} needs: {want}?",
                           "criteria": {"true_description": f"The part in the user's hands matches {want} on the reference sheet",
                                        "false_description": "The part in the user's hands is a different one, or no part is held"}}
    if cand_hw:
        q["hardware_in_hand"] = {"type": "choice", "instructions": "Which hardware item is the user holding or driving in right now?",
                                 "criteria": {**{h: name[h] for h in cand_hw},
                                              "no_hardware": "No hardware is visible in the hands or at the tool",
                                              "other": "Hardware that is not on the reference sheet, or it cannot be told"}}
    q["orientation_ok"] = {"type": "noul", "instructions": f"Is the assembly in Image 1 positioned the way step {n} needs?",
                           "criteria": {"true_description": "It stands or lies the same way as in the LEFT half of Image 2",
                                        "false_description": "It is turned another way: upside down, on another side, or on its end"}}
    q["step_complete"] = step_complete(manifest, sheet, k, shown)
    ahead = [p for p in (nxt["pickParts"] if nxt else []) if p not in s["pickParts"]]
    if ahead:
        q["next_step_part_used"] = {"type": "noul", "instructions": "Is the user already fitting a part of the NEXT step ("
                                    + ", ".join(f"{name[p]} ({p})" for p in ahead) + f") before step {n} is finished?"}
    q.update(FIXED)
    return q


ASK = """You write visual criteria for an automatic checker that watches a person assemble flat-pack furniture.
{header}
The first attached image is the reference sheet for this step. LEFT half: the correct result of this step. RIGHT half:
labelled pictures of candidate parts and hardware. The labels are: {labels}.{more}
The camera will see the assembly from an unknown side and height, in real light, often with hands in view.

Return JSON with exactly these keys:
- "looks": an object with one entry per label. Each value says in at most 25 words how to recognise that item in a
  camera picture and how to tell it from the other labelled items (size, shape, cut-outs, colour, holes).
- "orientation_true": at most 30 words. What a camera sees when the assembly is positioned correctly for this step.
- "orientation_false": at most 30 words. The wrong positions most likely here.
- "complete_true": at most 30 words. What a camera sees, from any side, that shows the parts of this step are attached.
  Name the visible change (which face or end gets which parts), not exact screw positions.
- "complete_false": at most 30 words. What the assembly looks like just before this step, in the same position: what is
  still missing or bare.
Describe only what a camera can see. Do not mention the sheet, pictures, labels or step numbers inside the values."""


def model_words(job: Path, manifest: dict, sheet: dict, k: int) -> dict | None:
    labels = [c["label"] for c in sheet["candidates"]]
    # shown the state before the step as well, the model describes the difference the step makes; with the sheet
    # alone it often described screw positions no camera can see (stand-in bench, Sun 15 Nov). Step 1 has no before.
    before = sheet.get("before") and job / sheet["before"]
    if k and not (before and before.exists()):     # older sheets: the previous step's result, in its own pose
        before = job / "refs" / f"step-{manifest['steps'][k - 1]['n']:02d}-ref.png"
    extra, more = [], ""
    if k and before.exists():
        extra.append(before)
        more = ("\nThe second attached image is the assembly just BEFORE this step, "
                + ("already in the position this step needs." if before.name.endswith("-before.png") else
                   "as the step before left it (this step may first turn it).")
                + " Compare it with the LEFT half of the sheet to see what this step adds.")
    try:
        text = gemini.generate(MODEL, [ASK.format(header=header(manifest, k), labels=", ".join(labels), more=more),
                                       job / sheet["sheet"], *extra], json_out=True, timeout=120, thinking_budget="medium")
        w = json.loads(text)
    except (RuntimeError, json.JSONDecodeError, OSError):
        return None
    need = ("orientation_true", "orientation_false", "complete_true", "complete_false")
    if not isinstance(w, dict) or not isinstance(w.get("looks"), dict) or any(not isinstance(w.get(x), str) for x in need):
        return None
    if set(w["looks"]) != set(labels) or any(not isinstance(v, str) or not v.strip() for v in w["looks"].values()):
        return None
    return w


def merge(q: dict, w: dict, keep_complete: bool = False) -> dict:
    """`keep_complete`: the step's additions do not show on the sheet the model was given, so its words about what
    "done" looks like are not used."""
    for key in ("part_in_hand", "hardware_in_hand"):
        for label, text in w["looks"].items():
            if key in q and label in q[key]["criteria"]:
                q[key]["criteria"][label] = f"{q[key]['criteria'][label]}: {text.strip()}"
    q["orientation_ok"]["criteria"] = {"true_description": w["orientation_true"].strip(), "false_description": w["orientation_false"].strip()}
    if not keep_complete:
        q["step_complete"]["criteria"] = {"true_description": w["complete_true"].strip(), "false_description": w["complete_false"].strip()}
    return q


def validate(q: dict) -> list[str]:
    """The limits the Decisions API states: key pattern and length, 2-255 options for a choice."""
    bad = []
    for key, item in q.items():
        if not KEY.match(key):
            bad.append(f"bad question key {key!r}")
        if item.get("type") not in ("noul", "choice") or not item.get("instructions"):
            bad.append(f"{key}: needs a type and instructions")
        if item.get("type") == "choice":
            opts = item.get("criteria") or {}
            if not 2 <= len(opts) <= 255:
                bad.append(f"{key}: {len(opts)} options")
            bad += [f"{key}: bad option key {o!r}" for o in opts if not KEY.match(o)]
    return bad


def build(job: Path, use_model: bool = True) -> dict:
    manifest = json.loads((job / "manifest.json").read_text())
    by = {"model": 0, "template": 0}
    sheets = [json.loads((job / "refs" / f"step-{s['n']:02d}-sheet.json").read_text()) for s in manifest["steps"]]
    words = [None] * len(sheets)
    if use_model:                                              # one call per step, four at a time
        with ThreadPoolExecutor(4) as ex:
            words = list(ex.map(lambda k: model_words(job, manifest, sheets[k], k), range(len(sheets))))
    for k, s in enumerate(manifest["steps"]):
        n = s["n"]
        sheet = sheets[k]
        shown = added_px(job, sheet)
        q = template(manifest, sheet, k, shown)
        w = words[k]
        if w:
            q = merge(q, w, keep_complete=hidden(shown))
        problems = validate(q)
        if problems:
            raise ValueError(f"step {n}: " + "; ".join(problems))
        who = MODEL if w else "template"
        by["model" if w else "template"] += 1
        out = {"step": n, "written_by": who, "step_complete_v": STEP_COMPLETE_V, "added_px": shown, "header": header(manifest, k),
               "sheet_note": sheet_note(n, sheet, shown), "sheet": sheet["sheet"],
               "detail": sheet.get("detail") if detail_note(n, sheet, shown) else None, "detail_note": detail_note(n, sheet, shown), "expects": {"parts": s["pickParts"], "hardware": [h["code"] for h in s["hardware"]]},
               "questions": q}
        (job / "refs" / f"step-{n:02d}-questions.json").write_text(json.dumps(out, indent=1, ensure_ascii=False))
        s["questions"] = f"refs/step-{n:02d}-questions.json"
    (job / "manifest.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False))
    return {"steps": len(manifest["steps"]), "written_by": by}


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--no-model"]
    print("RESULT " + json.dumps(build(Path(args[0]), "--no-model" not in sys.argv)))
