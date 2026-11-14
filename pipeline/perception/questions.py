"""Per-step questions for the Decisions API, written once per guide (AGT-07, PLAN §6.1).

    .venv/bin/python -m pipeline.perception.questions <job_dir> [--no-model]

Reads manifest.json and refs/step-NN-sheet.json, writes refs/step-NN-questions.json and sets
`questions` on every step of the manifest. Each file holds the step header (text that goes in front of
the images) and the `questions` object, ready to send.

Answer keys of the choice questions are the labels printed on the reference sheet (part ids and hardware
codes), so "which part is this" needs no translation. Structure and wording come from a fixed template.
What a part looks like, how the step must be oriented and what "done" looks like are written by
`gemini-4-hydrogen`, which is shown the sheet; with --no-model, or if its answer is not usable, plain
template wording is used and the file says so (`written_by`).
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from pipeline.check import gemini

MODEL = "gemini-4-hydrogen"
KEY = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
FIXED = {   # the same for every step and every guide; never generated
    "hands_present": {"type": "noul", "instructions": "Are one or both of the user's hands visible in Image 1?"},
    "view_blocked": {"type": "noul", "instructions": "Is the main assembly area blocked from view in Image 1 (by the body, a large "
                     "part held close to the camera, or the picture cutting it off)?"},
    "image_poor": {"type": "noul", "instructions": "Is Image 1 too dark, too bright or too blurred to identify parts?"},
    "frame_matches_setup": {"type": "noul", "instructions": "Does Image 1 show the same object as the LEFT half of Image 2, "
                            "seen from roughly the same side?"},
}
SHEET_NOTE = ("Image 2 is a reference sheet. LEFT half: what the work area should look like when step {n} is done. "
              "RIGHT half: labelled pictures of the parts and hardware that could be in use now.")


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


def template(manifest: dict, sheet: dict, k: int) -> dict:
    """The questions with plain wording. `words` (from the model) is merged in afterwards."""
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
    q["step_complete"] = {"type": "noul", "instructions": f"Compare Image 1 with the LEFT half of Image 2. Is step {n} finished?",
                          "criteria": {"true_description": "Every part and screw of this step is in place, as in the reference",
                                       "false_description": "A part or screw of this step is missing or loose, or the scene differs from the reference"}}
    ahead = [p for p in (nxt["pickParts"] if nxt else []) if p not in s["pickParts"]]
    if ahead:
        q["next_step_part_used"] = {"type": "noul", "instructions": "Is the user already fitting a part of the NEXT step ("
                                    + ", ".join(f"{name[p]} ({p})" for p in ahead) + f") before step {n} is finished?"}
    q.update(FIXED)
    return q


ASK = """You write visual criteria for an automatic checker that watches a person assemble flat-pack furniture.
{header}
The attached image is the reference sheet for this step. LEFT half: the correct result of this step. RIGHT half:
labelled pictures of candidate parts and hardware. The labels are: {labels}.

Return JSON with exactly these keys:
- "looks": an object with one entry per label. Each value says in at most 25 words how to recognise that item in a
  camera picture and how to tell it from the other labelled items (size, shape, cut-outs, colour, holes).
- "orientation_true": at most 30 words. What a camera sees when the assembly is positioned correctly for this step.
- "orientation_false": at most 30 words. The wrong positions most likely here.
- "complete_true": at most 30 words. What is visibly different once this step is finished.
- "complete_false": at most 30 words. What the scene looks like when this step is not finished yet.
Describe only what a camera can see. Do not mention the sheet, labels or step numbers inside the values."""


def model_words(job: Path, manifest: dict, sheet: dict, k: int) -> dict | None:
    labels = [c["label"] for c in sheet["candidates"]]
    try:
        text = gemini.generate(MODEL, [ASK.format(header=header(manifest, k), labels=", ".join(labels)),
                                       job / sheet["sheet"]], json_out=True, timeout=120, thinking_budget="medium")
        w = json.loads(text)
    except (RuntimeError, json.JSONDecodeError, OSError):
        return None
    need = ("orientation_true", "orientation_false", "complete_true", "complete_false")
    if not isinstance(w, dict) or not isinstance(w.get("looks"), dict) or any(not isinstance(w.get(x), str) for x in need):
        return None
    if set(w["looks"]) != set(labels) or any(not isinstance(v, str) or not v.strip() for v in w["looks"].values()):
        return None
    return w


def merge(q: dict, w: dict) -> dict:
    for key in ("part_in_hand", "hardware_in_hand"):
        for label, text in w["looks"].items():
            if key in q and label in q[key]["criteria"]:
                q[key]["criteria"][label] = f"{q[key]['criteria'][label]}: {text.strip()}"
    q["orientation_ok"]["criteria"] = {"true_description": w["orientation_true"].strip(), "false_description": w["orientation_false"].strip()}
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
    for k, s in enumerate(manifest["steps"]):
        n = s["n"]
        sheet = json.loads((job / "refs" / f"step-{n:02d}-sheet.json").read_text())
        q = template(manifest, sheet, k)
        w = model_words(job, manifest, sheet, k) if use_model else None
        if w:
            q = merge(q, w)
        problems = validate(q)
        if problems:
            raise ValueError(f"step {n}: " + "; ".join(problems))
        who = MODEL if w else "template"
        by["model" if w else "template"] += 1
        out = {"step": n, "written_by": who, "header": header(manifest, k), "sheet_note": SHEET_NOTE.format(n=n),
               "sheet": sheet["sheet"], "expects": {"parts": s["pickParts"], "hardware": [h["code"] for h in s["hardware"]]},
               "questions": q}
        (job / "refs" / f"step-{n:02d}-questions.json").write_text(json.dumps(out, indent=1, ensure_ascii=False))
        s["questions"] = f"refs/step-{n:02d}-questions.json"
    (job / "manifest.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False))
    return {"steps": len(manifest["steps"]), "written_by": by}


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--no-model"]
    print("RESULT " + json.dumps(build(Path(args[0]), "--no-model" not in sys.argv)))
