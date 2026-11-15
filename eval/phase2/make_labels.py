#!/usr/bin/env python3
"""Write a labels file for any built guide, with clip scripts that use that guide's own parts (LIV-04).

    python eval/phase2/make_labels.py jobs/<id> [eval/phase2/labels.json]

Reads the job's manifest.json and refs/step-NN-sheet.json. Picks the steps and the "wrong" parts itself:
  a_correct         the first step that adds a part, done right
  b_wrong_part      the same step with a look-alike part from the reference sheet
  c_wrong_position  a step where the assembly must be turned first, done without turning (left out if no step turns)
  d_wrong_hardware  a step that uses hardware, with another hardware code (left out if the guide has one code only)
  e_bad_light       a correct step in dim light with hands over the work: nothing may fire
Every time in the file is a placeholder (3 s of empty hands, then the action). After recording, run
`p2.py frames` on each clip and replace t0 and t with what the frame file names show.
"""
import json
import sys
from pathlib import Path

REST = {"state": "WAITING_FOR_PART", "part_in_hand": "nothing_held", "hardware_in_hand": "no_hardware", "right_part": None,
        "orientation_ok": None, "panel_on_base": None, "step_complete": False, "next_step_part_used": False,
        "hands_present": False, "view_blocked": False, "image_poor": False, "frame_matches_setup": True}


def main(job: Path, out: Path) -> int:
    m = json.loads((job / "manifest.json").read_text())
    name = {p["id"]: p["name"] for p in m["parts"]} | {h["code"]: h["name"] for h in m["hardware"]}
    steps = m["steps"]

    def others(s, kind):
        sheet = job / "refs" / f"step-{s['n']:02d}-sheet.json"
        cands = [c["label"] for c in json.loads(sheet.read_text())["candidates"] if c["kind"] == kind] if sheet.exists() else []
        want = s["pickParts"] if kind == "part" else [h["code"] for h in s["hardware"]]
        every = [p["id"] for p in m["parts"]] if kind == "part" else [h["code"] for h in m["hardware"]]
        return [c for c in cands if c not in want] or [c for c in every if c not in want]   # look-alikes first

    def clip(cid, kind, s, script, segs, events):
        return {"id": cid, "file": f"clips/{cid}.mp4", "kind": kind, "step": s["n"], "guide_page": s["cite"]["page"],
                "script": script, "segments": [{"t0": 0, **REST}, *segs], "events": events}

    def what(s):
        return " and ".join(f"{name[p]} ({p})" for p in s["pickParts"])

    clips = []
    first = next((s for s in steps if s["pickParts"]), steps[0])
    part = first["pickParts"][0] if first["pickParts"] else None
    clips.append(clip("a_correct", "correct", first,
                      f"Step {first['n']}: {first['title']}. Start with empty hands for 3 s. Pick up the {what(first)}, put it in place, take your hands away and wait 4 s.",
                      [{"t0": 3.0, "state": "PART_IN_HAND", "part_in_hand": part, "right_part": True, "orientation_ok": True, "hands_present": True},
                       {"t0": 9.0, "state": "PLACING"},
                       {"t0": 14.0, "state": "STEP_COMPLETE", "part_in_hand": "nothing_held", "right_part": None, "step_complete": True, "hands_present": False}],
                      [{"t": 14.0, "event": "step_complete"}]))
    wrong = others(first, "part")
    if wrong:
        w = wrong[0]
        clips.append(clip("b_wrong_part", "mistake", first,
                          f"Step {first['n']} needs the {what(first)}. Start with empty hands for 3 s. Pick up the {name[w]} ({w}) instead and bring it to where the right part goes. Hold 5 s. Put it back.",
                          [{"t0": 3.0, "state": "PART_IN_HAND", "part_in_hand": w, "right_part": False, "hands_present": True},
                           {"t0": 12.0, "state": "WAITING_FOR_PART", "part_in_hand": "nothing_held", "right_part": None, "hands_present": False}],
                          [{"t": 3.0, "event": "wrong_part"}]))
    turned = next((s for k, s in enumerate(steps) if k and s.get("assemblyRotationDeg") != steps[k - 1].get("assemblyRotationDeg")), None)
    if turned:
        clips.append(clip("c_wrong_position", "mistake", turned,
                          f"Step {turned['n']} needs the assembly in position `{turned.get('pose')}`. Leave it as the step before left it. Start with empty hands for 3 s, then bring the {what(turned) or 'next part'} to it and hold 5 s. Then turn the assembly the right way.",
                          [{"t0": 3.0, "state": "PART_IN_HAND", "part_in_hand": (turned["pickParts"] or [None])[0], "right_part": True, "orientation_ok": False, "hands_present": True},
                           {"t0": 10.0, "orientation_ok": True}],
                          [{"t": 3.0, "event": "flip_needed"}]))
        clips[-1]["segments"][0]["orientation_ok"] = False
    hw_step = next((s for s in steps if s["hardware"] and others(s, "hardware")), None)
    if hw_step:
        w, need = others(hw_step, "hardware")[0], hw_step["hardware"][0]["code"]
        clips.append(clip("d_wrong_hardware", "mistake", hw_step,
                          f"Step {hw_step['n']} needs {name[need]} ({need}). Start with empty hands for 3 s. Pick up a {name[w]} ({w}) and bring it to the hole. Hold 5 s.",
                          [{"t0": 3.0, "state": "PART_IN_HAND", "part_in_hand": "hardware_only", "hardware_in_hand": w, "hands_present": True}],
                          [{"t": 3.0, "event": "wrong_hardware"}]))
    last = next((s for s in reversed(steps) if s["pickParts"] and s is not first), first)
    clips.append(clip("e_bad_light", "hard", last,
                      f"Dim the light. Do step {last['n']} ({last['title']}) correctly, but keep both hands over the work for long stretches. No warning may fire.",
                      [{"t0": 3.0, "state": "PART_IN_HAND", "part_in_hand": None, "hands_present": True, "view_blocked": True,
                        "note": "null = a person cannot tell from this frame either; not scored"}], []))
    clips[-1]["segments"][0]["image_poor"] = True
    out.write_text(json.dumps({
        "_readme": "GENERATED by make_labels.py with placeholder times. Record the clips, run `p2.py frames` on each, then replace every t0 and t.",
        "job": str(job), "title": m.get("title"), "fps": 4, "camera": "fill in: phone on stand | headset",
        "parts": [p["id"] for p in m["parts"]], "hardware": [str(h["code"]) for h in m["hardware"]], "clips": clips}, indent=1, ensure_ascii=False))
    print(f"{out}: {len(clips)} clips for '{m.get('title')}'")
    for c in clips:
        print(f"  {c['id']:17s} step {c['step']:<2d} {c['script']}")
    return 0


if __name__ == "__main__":
    job = Path(sys.argv[1])
    sys.exit(main(job, Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).parent / "labels.json"))
