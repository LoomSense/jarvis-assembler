"""Phase-2 perception server: what it adds around `pipeline.perception`, and the WebSocket route. No network.

The request, the scheduling and the logic for the plan's events are the pipeline's (tested in
test_perception_logic.py and test_perception_stream.py). Here: the additions, the seams between the two
(the tests named `..._drift_...` fail when the pipeline side changes under the server), and the route
over a real WebSocket with the simulated decider or with the real client on a fake connection.
"""
from __future__ import annotations

import base64
import contextlib
import importlib.util
import io
import json
import logging
import queue
import threading
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image
from starlette.websockets import WebSocketDisconnect

from pipeline.check import decisions
from pipeline.perception import logic as pipeline_logic
from pipeline.perception import questions as writer
from pipeline.perception import request as bench
from pipeline.perception.logic import Watcher
from pipeline.perception.replay import flat
from pipeline.perception.stream import Stream
from server.app import create_app
from server.perception.config import Tuning
from server.perception.decider import DecisionError, GeminiDecider, SimulatedDecider, classify
from server.perception.guide import Guide
from server.perception.logic import COMPLETE, IN_HAND, PLACING, WAITING, Coach, Emitted, Expect, Hands, Health
from server.perception.phrases import perception_line, sentences
from server.perception.questions import EXTRA, MODEL, body_of, build_ask, fallback_pack, load_pack, normalise
from server.perception.wire import FrameError, pack_frame, parse_frame
from server.settings import ROOT, Settings

KEY = "test-key"
JOB = "kn-test"
T = Tuning()
CFG = T.watcher_config()
LONG, SHORT = "108363", "108560"       # the two KNAGGLIG screws: 4 long, 24 short


# ---- a small built job: 4 steps of the KNAGGLIG box ---------------------------------------------

def _fasten(code, name, i, n):
    return {"type": "fasten", "node": f"hw_{code}_{i}", "partId": code, "code": code,
            "text": {"en": f"Take a {name} ({code}) and screw it in here. Screw {i} of {n}.", "el": "Βίδωσέ την εδώ."}}


def _place(part, i=1):
    return {"type": "place", "node": f"part_{part}_{i}", "partId": part, "text": {"en": f"Put the {part} in place.", "el": "Βάλε το."}}


def _step(n, title, parts, hardware, page, actions, pose="upright"):
    return {"n": n, "title": title, "pickParts": parts, "hardware": hardware, "cite": {"page": page, "label": str(n)},
            "refSheet": f"refs/step-{n:02d}-sheet.png", "pose": pose, "actions": actions,
            "moving": [a["node"] for a in actions if "node" in a]}


MANIFEST = {
    "title": "KNAGGLIG box", "glb": "walkthrough.glb", "units": "m",
    "parts": [{"id": "bottom_panel", "name": "Bottom panel (pre-assembled)", "short": "bottom panel"},
              {"id": "side_panel", "name": "Long side panel (pre-assembled)", "short": "side panel"},
              {"id": "end_slat", "name": "End slat", "short": "plain slat"},
              {"id": "end_slat_handle", "name": "End slat with handle cut-out", "short": "slat with the handle cut-out"}],
    "hardware": [{"code": LONG, "name": "Long screw", "short": "long screw", "kind": "screw", "count": 4},
                 {"code": SHORT, "name": "Short screw", "short": "short screw", "kind": "screw", "count": 24}],
    "steps": [
        _step(1, "Stand a side panel against the end of the bottom panel", ["bottom_panel", "side_panel"], [], 3,
              [_place("bottom_panel"), _place("side_panel")]),
        _step(2, "Fix it with two long screws", [], [{"code": LONG, "qty": 2}], 3,
              [_fasten(LONG, "long screw", 1, 2), _fasten(LONG, "long screw", 2, 2)]),
        _step(3, "Stand the box on end and screw on two end slats", ["end_slat"], [{"code": SHORT, "qty": 8}], 5,
              [{"type": "turn", "text": {"en": "Stand the assembly on its end, open end up.",
                                         "el": "Στήσε την κατασκευή όρθια, με την ανοιχτή άκρη προς τα πάνω."}},
               _place("end_slat", 1), _place("end_slat", 2)] + [_fasten(SHORT, "short screw", i, 8) for i in range(1, 9)],
              pose="on_end_a"),
        _step(4, "Screw on the handle slat", ["end_slat_handle"], [{"code": SHORT, "qty": 4}], 6,
              [_place("end_slat_handle")] + [_fasten(SHORT, "short screw", i, 4) for i in range(1, 5)], pose="on_end_a"),
    ],
}
SHEETS = {       # what pipeline/refs.py writes beside each sheet: the printed labels and why each is there
    1: [("bottom_panel", "part", "this_step"), ("side_panel", "part", "this_step"), (LONG, "hardware", "next_step"),
        (SHORT, "hardware", "other_hardware")],
    2: [(LONG, "hardware", "this_step"), ("end_slat", "part", "next_step"), (SHORT, "hardware", "next_step")],
    3: [("end_slat", "part", "this_step"), (SHORT, "hardware", "this_step"), ("end_slat_handle", "part", "look_alike"),
        (LONG, "hardware", "other_hardware")],
    4: [("end_slat_handle", "part", "this_step"), (SHORT, "hardware", "this_step"), ("end_slat", "part", "look_alike"),
        (LONG, "hardware", "other_hardware")],
}
PLAN = {"parts": [{"id": "bottom_panel", "el": "το κάτω πάνελ"}, {"id": "side_panel", "el": "το πλαϊνό πάνελ"},
                  {"id": "end_slat", "el": "μια απλή σανίδα"}, {"id": "end_slat_handle", "el": "τη σανίδα με τη λαβή"}],
        "hardware": [{"code": LONG, "kind": "screw", "el": "μακριά βίδα", "params": {"length": 60}},
                     {"code": SHORT, "kind": "screw", "el": "κοντή βίδα", "params": {"length": 30}}]}


def jpeg(grey: int = 120, size=(96, 72)) -> bytes:
    out = io.BytesIO()
    Image.new("RGB", size, (grey, grey, grey)).save(out, "JPEG")
    return out.getvalue()


def make_job(jobs: Path, job_id: str = JOB, plan: bool = True, questions: bool = True, manifest: dict | None = None) -> Path:
    """A built job folder. `questions`: as the refs stage leaves it today, with `refs/step-NN-questions.json`
    written by the pipeline's own writer (template wording, no network) and the orange sheet. Without: a job
    built before that, with the older sheet and no question files."""
    d = jobs / job_id
    (d / "refs").mkdir(parents=True)
    (d / "guide.pdf").write_bytes(b"%PDF-1.4\n")
    (d / "manifest.json").write_text(json.dumps(manifest or MANIFEST))
    if plan:
        (d / "plan.approved.json").write_text(json.dumps(PLAN, ensure_ascii=False))
    for n, cands in SHEETS.items():
        Image.new("RGB", (128, 64), (255, 255, 255)).save(d / "refs" / f"step-{n:02d}-sheet.png")
        sheet = {"step": n, "sheet": f"refs/step-{n:02d}-sheet.png",
                 "left": "the correct result of this step, seen from the front, about 35 degrees above the table",
                 "right": "labelled candidate parts and hardware",
                 "candidates": [{"label": l, "kind": k, "why": w} for l, k, w in cands]}
        if questions:
            sheet.update(added=f"refs/step-{n:02d}-added.png", before=f"refs/step-{n:02d}-before.png" if n > 1 else None)
        (d / "refs" / f"step-{n:02d}-sheet.json").write_text(json.dumps(sheet))
    if questions:
        assert writer.build(d, use_model=False)["written_by"] == {"model": 0, "template": 4}
    return d


@pytest.fixture()
def guide(tmp_path) -> Guide:
    return Guide(make_job(tmp_path))


@pytest.fixture(autouse=True)
def _fresh_pipeline_caches():
    """`pipeline.perception.request` remembers files by job path; test folders reuse paths across runs."""
    bench.step_pack.cache_clear()
    bench.sheet_jpeg.cache_clear()
    yield


# ---- synthetic answers, in the shape the Decisions API returns them ------------------------------

def noul(v: float) -> dict:
    return {"type": "noul", "noul": v, "confidence": max(v, round(1 - v, 3))}


def choice(label: str, conf: float) -> dict:
    rest = round(1 - conf, 3)
    return {"type": "choice", "choice": label, "confidence": conf,
            "probabilities": {label: conf, **({"other": rest} if label != "other" else {"nothing_held": rest})}}


def frame(part: str = "nothing_held", hw: str = "no_hardware", p: float = 0.93, hands: float | None = None, **yes_no) -> dict:
    """One frame's answers. By default hands are in view exactly when something is held."""
    empty = part == "nothing_held" and hw == "no_hardware"
    answers = {"image_poor": noul(0.03), "view_blocked": noul(0.04), "frame_matches_setup": noul(0.95),
               "orientation_ok": noul(0.9), "step_complete": noul(0.05), "next_step_part_used": noul(0.05),
               "hands_present": noul((0.05 if empty else 0.95) if hands is None else hands),
               "part_in_hand": choice(part, p), "hardware_in_hand": choice(hw, p)}
    answers.update({k: noul(v) for k, v in yes_no.items()})
    return answers


def feed(coach: Coach, frames: list[dict], t0: float = 0.0, dt: float = 0.25, source: str = "fixed", hands=None):
    """Feed frames at 4 Hz from `t0`. Returns `[(time, event name)]` and the events themselves."""
    said, events = [], []
    for i, answers in enumerate(frames):
        for e in coach.on_frame(answers, t0 + i * dt, source, hands):
            said.append((t0 + i * dt, e.event))
            events.append(e)
    return said, events


STEP2 = Expect(step=2, page=3, hardware=frozenset({LONG}), action=0, action_kind="fasten", action_id=LONG,
               last_action=False, next_step=3, next_page=5)
STEP3 = Expect(step=3, page=5, parts=frozenset({"end_slat"}), hardware=frozenset({SHORT}), action=1,
               action_kind="place", action_id="end_slat", last_action=False, next_step=4, next_page=6)
STEP1_LAST = Expect(step=1, page=3, parts=frozenset({"bottom_panel", "side_panel"}), action=1, action_kind="place",
                    action_id="side_panel", last_action=True, next_step=2, next_page=3)
WRONG_SCREW = frame("hardware_only", SHORT, right_hardware=0.04)
RIGHT_SCREW = frame("hardware_only", LONG, right_hardware=0.95)
EMPTY = frame()
DONE = frame(step_complete=0.6, orientation_ok=0.8)       # 0.6: far below the 0.85 of other answers, above the measured 0.45


def coach(expect: Expect, **overrides) -> Coach:
    c = Coach(T.with_overrides(overrides))
    c.start_step(expect)
    return c


# ---- the additions around the Watcher ------------------------------------------------------------

def test_wrong_screw_fires_once_names_both_codes_and_again_only_after_it_was_put_down():
    c = coach(STEP2)
    assert feed(c, [WRONG_SCREW] * 3)[0] == []                        # 3 frames are not 4 of 5
    said, events = feed(c, [WRONG_SCREW], t0=0.75)
    assert said == [(0.75, "wrong_hardware")]
    e = events[0]
    assert (e.observed_id, e.expected_id, e.step, e.page, e.repeat) == (SHORT, LONG, 2, 3, 0)
    assert e.evidence == {f"hardware_in_hand.{SHORT}": 0.93, "right_hardware": 0.04} and e.p == 0.93
    assert e.votes == "4 of last 4 frames"                            # the Watcher's own words
    assert c.belief()["verdict"] == "wrong" and c.belief()["holding"]["id"] == SHORT
    assert feed(c, [WRONG_SCREW] * 100, t0=1.0)[0] == []              # still held, 25 s: said once (the Watcher's rule)
    said, events = feed(c, [EMPTY] * 5 + [WRONG_SCREW] * 4, t0=26.0)  # put down, then the same one again
    assert said == [(28.0, "wrong_hardware")] and events[0].repeat == 1


def test_what_is_held_is_read_only_when_hands_are_in_view():
    c = coach(STEP2)                                                  # measured: with no hands the model still names a part
    assert feed(c, [frame("hardware_only", SHORT, hands=0.05, right_hardware=0.04)] * 20)[0] == []
    assert c.belief()["holding"]["kind"] == "none" and c.belief()["verdict"] == "none"
    c = coach(STEP3)
    assert feed(c, [frame("end_slat", hands=0.2, right_part=0.95)] * 20)[0] == []     # no confirmation either
    assert c.belief()["holding"]["kind"] == "unknown"


def test_noisy_or_unknown_answers_fire_nothing():
    c = coach(STEP2)
    unsure = dict(right_hardware=0.5, step_complete=0.3, next_step_part_used=0.7)
    noise = [
        frame("hardware_only", SHORT, p=0.6, **unsure),               # below the 0.75 a choice needs
        frame("hardware_only", "other", p=0.95, **unsure),            # `other` never counts
        WRONG_SCREW, frame("hardware_only", SHORT, p=0.5), frame("hardware_only", "other"),     # 1 of 3 clear
        frame("hardware_only", SHORT, right_hardware=0.95),           # the yes/no contradicts the choice: overruled
        frame("hardware_only", LONG, p=0.7), EMPTY,
    ]
    assert feed(c, noise * 12)[0] == []                               # 24 s of it
    assert c.belief()["verdict"] in ("unknown", "none")
    c = coach(STEP3)                                                  # a part step: the §6.2 cross-check
    assert feed(c, [frame("end_slat_handle", p=0.9, right_part=0.5)] * 20)[0] == []   # right_part is not a clear no


def test_wrong_part_needs_both_the_choice_and_the_yes_no():
    c = coach(STEP3)
    feed(c, [frame("end_slat_handle", p=0.9, right_part=0.5)] * 6)
    said, events = feed(c, [frame("end_slat_handle", p=0.9, right_part=0.05)] * 6, t0=1.5)
    assert said == [(2.25, "wrong_part")]                             # the 4th agreeing frame of the last 5
    assert (events[0].observed_id, events[0].expected_id) == ("end_slat_handle", "end_slat")


def test_correct_pick_confirms_once_and_again_only_after_a_correction():
    c = coach(STEP2)
    said, events = feed(c, [RIGHT_SCREW] * 3)
    assert said == [(0.5, "right_hardware")] and events[0].observed_id == LONG       # 3 of 4
    assert c.belief()["verdict"] == "right" and c.belief()["state"] == IN_HAND
    assert feed(c, [RIGHT_SCREW] * 110, t0=0.75)[0] == []             # 27 s holding it: said once, not again
    assert feed(c, [EMPTY] * 8 + [RIGHT_SCREW] * 8, t0=28.5)[0] == []   # the next screw of the same kind: silent
    assert feed(c, [WRONG_SCREW] * 4, t0=33.0)[0] == [(33.75, "wrong_hardware")]
    assert feed(c, [RIGHT_SCREW] * 32, t0=34.0)[0] == [(39.75, "right_hardware")]   # corrected: once more, 6 s later
    c = coach(STEP2, confirmations=False)
    assert feed(c, [RIGHT_SCREW] * 20)[0] == []
    c = coach(STEP2)                                                  # the yes/no says "not the right one": no praise
    assert feed(c, [frame("hardware_only", LONG, right_hardware=0.05)] * 20)[0] == []


def test_the_current_action_decides_which_hardware_is_right():
    both = Expect(step=9, page=9, hardware=frozenset({LONG, SHORT}), action=0, action_kind="fasten", action_id=LONG,
                  last_action=False)                                  # a step that uses both screws
    c = coach(both)
    said, events = feed(c, [frame("hardware_only", SHORT)] * 4)
    assert said == [(0.75, "wrong_hardware")] and events[0].expected_id == LONG      # this action wants the long one
    c.set_action(Expect(step=9, page=9, hardware=frozenset({LONG, SHORT}), action=1, action_kind="fasten",
                        action_id=SHORT, last_action=True))
    assert feed(c, [frame("hardware_only", SHORT)] * 40, t0=1.0)[0] == [(6.75, "right_hardware")]   # now it is right
    c = coach(both)                                                   # votes against the last action's screw do not
    feed(c, [frame("hardware_only", LONG)] * 3)                       # outlive it: here the warning was held back by
    assert feed(c, [frame("hardware_only", SHORT)] * 23, t0=0.75)[0] == []     # the 6 s gap after "Good ..." at 0.5 s
    c.set_action(Expect(step=9, page=9, hardware=frozenset({LONG, SHORT}), action=1, action_kind="fasten",
                        action_id=SHORT, last_action=True))           # ... and at 6.5 s the short screw is the right one
    assert feed(c, [frame("hardware_only", SHORT)] * 60, t0=6.5)[0] == [(20.5, "right_hardware")]   # 20 s after the last
    c = coach(Expect(step=9, page=9, hardware=frozenset({LONG, SHORT})))            # the display did not say: both pass
    assert [n for _, n in feed(c, [frame("hardware_only", SHORT)] * 8)[0]] == ["right_hardware"]
    c = coach(STEP1_LAST)                                             # a step with no hardware at all
    said, events = feed(c, [frame("hardware_only", LONG)] * 4)
    assert said == [(0.75, "wrong_hardware")] and events[0].expected_id is None


def test_step_complete_follows_the_measured_rule_and_waits_for_the_last_action():
    c = coach(STEP1_LAST)
    assert feed(c, [DONE] * 7)[0] == []                               # 7 of 10 is not 8 of 10
    assert feed(c, [dict(DONE, view_blocked=noul(0.8))] * 40, t0=1.75)[0] == []   # behind a hand: never (fixed camera)
    said, events = feed(c, [DONE], t0=11.75)
    assert said == [(11.75, "step_complete")] and c.done and c.belief()["state"] == COMPLETE
    assert events[0].evidence == {"step_complete": 0.6, "orientation_ok": 0.8, "hands_present": 0.05}
    assert feed(c, [DONE] * 8, t0=12.0)[0] == []                      # once

    c = coach(STEP1_LAST)                                             # not turned the way the step needs: no vote
    assert feed(c, [frame(step_complete=0.9, orientation_ok=0.3)] * 20)[0] == []
    c = coach(STEP1_LAST)                                             # below the measured line
    assert feed(c, [frame(step_complete=0.4)] * 20)[0] == []
    c = coach(STEP1_LAST)                                             # hands in the picture: wait until they are gone 2 s
    working = frame("side_panel", right_part=0.95, step_complete=0.6)
    said, _ = feed(c, [working] * 12 + [DONE] * 12)
    assert [n for _, n in said] == ["right_part", "step_complete"] and said[1][0] == 2.75 + 2.0

    not_last = Expect(step=3, page=5, parts=frozenset({"end_slat"}), action=1, action_kind="place",
                      action_id="end_slat", last_action=False)
    c = coach(not_last)                                               # the display says more is still to be done
    assert feed(c, [DONE] * 40)[0] == []
    c.set_action(Expect(step=3, page=5, parts=frozenset({"end_slat"}), action=10, action_kind="fasten",
                        action_id=SHORT, last_action=True))
    assert feed(c, [DONE] * 10, t0=10.0)[0] == [(11.75, "step_complete")]          # 8 fresh frames after it began
    c = coach(not_last, step_complete_only_on_last_action=False)
    assert feed(c, [DONE] * 10)[0] == [(2.0, "step_complete")]

    c = coach(STEP3)                                                  # a warning does not hold it back
    late = Expect(step=3, page=5, parts=frozenset({"end_slat"}), hardware=frozenset({SHORT}))
    c.start_step(late)
    said, _ = feed(c, [frame("end_slat_handle", right_part=0.05)] * 4 + [DONE] * 12)
    assert said == [(0.75, "wrong_part"), (2.75, "step_complete")]    # 2 s after the warning, not 6


def test_status_belief_changes_only_on_opposite_evidence():
    c = coach(STEP2)
    feed(c, [RIGHT_SCREW] * 3)
    assert c.belief()["holding"] == {"kind": "hardware", "id": LONG, "confidence": 0.93}
    torn = frame("hardware_only", LONG, p=0.4, hands=0.5)
    assert feed(c, [torn] * 40, t0=0.75)[0] == []
    assert c.belief()["holding"]["id"] == LONG                        # 10 s without evidence changes nothing
    feed(c, [EMPTY] * 5, t0=10.75)
    assert c.belief()["holding"]["id"] == LONG                        # 5 of 8 "empty" is not 6 of 8
    feed(c, [EMPTY], t0=12.0)
    assert c.belief()["holding"]["kind"] == "none" and c.belief()["state"] == WAITING
    feed(c, [WRONG_SCREW] * 2, t0=12.25)
    assert c.belief()["holding"]["kind"] == "none"                    # 2 frames do not make a new belief
    feed(c, [WRONG_SCREW], t0=12.75)
    assert c.belief()["holding"]["id"] == SHORT and c.belief()["verdict"] == "wrong"
    assert c.belief(at_target=True)["state"] == PLACING               # the hand summary: a hand is where the part goes


def test_i_know_mutes_that_event_for_the_step_and_starts_no_cooldown():
    c = coach(Expect(step=3, page=5, parts=frozenset({"end_slat"}), hardware=frozenset({SHORT})))
    wrong_screw, wrong_part = frame("hardware_only", LONG), frame("end_slat_handle", right_part=0.05)
    assert feed(c, [wrong_screw] * 4)[0] == [(0.75, "wrong_hardware")]
    assert c.mute() == "wrong_hardware" and c.belief()["muted"] == ["wrong_hardware"]      # "I know": the last warning
    assert c.mute("step_complete") is None                            # cannot be muted
    said, _ = feed(c, [EMPTY] * 5 + [wrong_screw] * 4 + [wrong_part] * 5, t0=25.0)
    assert said == [(28.0, "wrong_part")]                             # the muted one at 27.0 was not said, so it
    #                                                                   does not push this one 6 s back
    c.start_step(STEP2)                                               # the next step starts unmuted
    assert feed(c, [WRONG_SCREW] * 4, t0=40.0)[0] == [(40.75, "wrong_hardware")]


def test_two_utterances_keep_six_seconds_apart():
    c = coach(STEP2)
    assert feed(c, [RIGHT_SCREW] * 3)[0] == [(0.5, "right_hardware")]
    assert feed(c, [WRONG_SCREW] * 30, t0=0.75)[0] == [(6.5, "wrong_hardware")]    # 4 of 5 at 1.5 s, held back to 6.5
    c = coach(STEP2)
    feed(c, [RIGHT_SCREW] * 3)
    assert feed(c, [WRONG_SCREW] * 6 + [EMPTY] * 30, t0=0.75)[0] == []             # put down before the gap ended
    c = coach(STEP2)                                                  # the gap is kept across a step change
    feed(c, [WRONG_SCREW] * 4)
    c.start_step(STEP3)
    assert feed(c, [frame("end_slat_handle", right_part=0.05)] * 30, t0=1.0)[0] == [(6.75, "wrong_part")]


def test_bad_light_is_said_once_after_five_seconds():
    c = coach(STEP2)
    dark = dict(WRONG_SCREW, image_poor=noul(0.6))                    # discarded: its answers must not count
    said, _ = feed(c, [dark] * 40)
    assert said == [(5.0, "more_light")] and c.belief()["view"] == "poor" and c.belief()["holding"]["kind"] == "unknown"
    assert feed(c, [EMPTY] + [dark] * 19, t0=10.0)[0] == []           # a new dark stretch, under 5 s
    c = coach(STEP2)                                                  # frames too dark to send count the same way
    assert [c.on_bad_frame(t / 4, "dark", "headset") for t in range(20)] == [[]] * 20
    assert [e.event for e in c.on_bad_frame(5.0, "dark", "headset")] == ["more_light"]


def test_headset_frames_follow_other_rules_than_a_fixed_camera():
    # Looking at the screw in the hand hides the assembly. Fixed: frame discarded. Headset: the hand still counts,
    # the questions about the assembly do not.
    looking_at_hand = dict(WRONG_SCREW, view_blocked=noul(0.9), step_complete=noul(0.95))
    c = coach(STEP2)
    assert feed(c, [looking_at_hand] * 20, source="fixed")[0] == [] and c.belief()["view"] == "blocked"
    c = coach(STEP2)
    assert feed(c, [looking_at_hand] * 20, source="headset")[0] == [(0.75, "wrong_hardware")]
    c = coach(STEP1_LAST)
    assert feed(c, [dict(DONE, view_blocked=noul(0.9))] * 40, source="headset")[0] == []   # never done behind a hand

    # step_complete from a head viewpoint is weak evidence: 10 of 12 instead of 8 of 10.
    for source, at in (("fixed", 2.0), ("headset", 2.25)):
        c = coach(STEP1_LAST)
        assert feed(c, [DONE] * 16, source=source)[0] == [(at, "step_complete")], source
    assert (T.watcher_config("fixed").windows["step_complete"], T.watcher_config("headset").windows["step_complete"]) \
        == ((8, 10), (10, 12))
    # Hands stay in a headset's picture. The hand summary "was at the target, has left it" is accepted instead of
    # "hands out of the picture for 2 s", on the same votes.
    in_view = dict(DONE, hands_present=noul(0.95))
    c = coach(STEP1_LAST)
    assert feed(c, [in_view] * 24, source="headset")[0] == []
    c, hands = coach(STEP1_LAST), Hands(T)
    hands.update({"hand_at_target": True, "still": True, "raised": False}, 0.5)
    said = []
    for i in range(24):
        now = 1.0 + i * 0.25
        hands.update({"hand_at_target": False, "still": True, "raised": False}, now)
        said += [(now, e.event, e.reason) for e in c.on_frame(in_view, now, "headset", hands)]
    assert said == [(3.25, "step_complete", "hand_summary")] and c.done           # the 10th yes of 12

    # A camera on a stand is rarely where the reference render was taken from, so "the camera moved" is off.
    moved = dict(WRONG_SCREW, frame_matches_setup=noul(0.05))
    c = coach(STEP2)
    assert feed(c, [moved] * 60)[0] == [(0.75, "wrong_hardware")] and c.belief()["view"] == "ok"
    c = coach(STEP2, fixed={"check_setup": True})                     # it can be turned on
    assert feed(c, [moved] * 60)[0] == [(0.75, "wrong_hardware"), (10.0, "realign_camera")]
    assert c.belief()["view"] == "moved"
    assert feed(c, [EMPTY] * 5 + [WRONG_SCREW] * 5, t0=30.0)[0] == [(32.0, "wrong_hardware")]   # a clear yes ends the pause


def test_hand_summary_decides_which_headset_frames_are_worth_a_call():
    h = Hands(T)
    assert h.wanted_hz(0.0) == T.max_hz and h.view(0.0) is None       # no summary: nothing to gate on
    h.update({"hand_at_target": False, "still": False, "raised": False}, 1.0)
    assert h.wanted_hz(1.1) == 0.0                                    # hands moving fast: send nothing
    h.update({"hand_at_target": False, "still": True, "raised": False}, 1.2)
    assert h.wanted_hz(1.3) == T.still_hz                             # present and still
    h.update({"hand_at_target": False, "still": False, "raised": True}, 1.4)
    assert h.wanted_hz(1.5) == T.max_hz                               # holding something up: look now
    h.update({"present": False}, 2.0)
    assert h.wanted_hz(2.5) == T.max_hz                               # hands just left: "is the step done?"
    h.update({"present": False}, 6.0)
    assert h.wanted_hz(6.1) == T.idle_hz                              # idle keep-alive
    assert h.wanted_hz(9.0) == T.max_hz                               # the summary went stale: stop gating
    h.update({"hand_at_target": True, "still": True}, 10.0)
    h.update({"hand_at_target": False, "still": True}, 10.2)
    assert not h.placed_then_away(10.4) and h.placed_then_away(10.75)   # away for half a second
    h.new_action()
    assert not h.placed_then_away(10.75)


def test_decision_failures_back_off_pause_and_recover():
    h = Health(T)
    assert h.fail("timeout", 0.0) == [] and h.backing_off(0.2) and not h.backing_off(0.25)   # from 250 ms
    assert h.fail("server_error", 0.3, started=0.26) == [] and h.backoff_s == 0.5            # doubling
    assert h.fail("network", 0.35, started=0.1) == [] and h.backoff_s == 0.5   # was already on its way: says nothing new
    assert h.fail("network", 1.0, started=0.9) == [] and h.backoff_s == 1.0
    assert h.tick(2.9) == [] and h.tick(3.0) == ["vision_paused"] and h.tick(3.5) == []      # 3 s without success, once
    assert h.ok(4.0) == ["vision_resumed"] and not h.backing_off(4.0) and h.ok(4.1) == []
    for i in range(8):
        h.fail("timeout", 5.0 + i)
    assert h.backoff_s == T.backoff_max_s
    h = Health(T)                                                     # 429: 1 Hz for 30 s
    assert h.fail("rate_limit", 0.0) == [] and h.cap_hz(29.9) == T.rate_limit_hz and h.cap_hz(30.0) is None
    assert not h.backing_off(0.01)
    h.ok(1.0)
    assert h.cap_hz(10.0) == T.rate_limit_hz                          # a success does not end the slow-down
    h = Health(T)                                                     # safety: unusable frames, not an outage
    assert h.fail("safety", 0.0) == [] and h.fail("safety", 0.3) == [] and not h.tighten(0.4)
    h.fail("safety", 0.6)
    assert h.tighten(0.7) and h.tighten(30.0) and not h.tighten(30.7) and h.fail_since is None
    h = Health(T)
    h.fail("safety", 0.0), h.fail("safety", 0.1), h.ok(0.2), h.fail("safety", 0.3)
    assert not h.tighten(0.4)                                         # three in a row, not three in total
    h = Health(T)                                                     # the key or the credit is refused: stop asking,
    assert h.fail("billing", 0.0) == [] and h.refused == "billing" and h.blocked(29.0) and not h.blocked(30.0)
    assert h.fail_since is None and not h.backing_off(0.1)            # try once every 30 s, and it is over at a success
    h.ok(31.0)
    assert h.refused is None


def test_tuning_overrides_reach_both_the_servers_numbers_and_the_watchers():
    t = Tuning().with_overrides('{"max_in_flight": 8, "watcher": {"done_yes": 0.5, "windows": {"wrong_part": [3, 5]}}}')
    assert t.max_in_flight == 8 and Tuning().max_in_flight == 4
    cfg = t.watcher_config("headset")
    assert (cfg.done_yes, cfg.windows["wrong_part"], cfg.windows["step_complete"]) == (0.5, (3, 5), (10, 12))
    assert cfg.windows["wrong_hardware"] == CFG.windows["wrong_hardware"] and CFG.done_yes == 0.45
    t = Tuning().with_overrides({"headset": {"step_complete": [9, 10], "check_setup": True, "flip_needed": None}})
    assert t.watcher_config("headset").windows["step_complete"] == (9, 10) and t.headset.check_setup
    assert t.watcher_config("headset").windows["flip_needed"] == CFG.windows["flip_needed"] and t.fixed == Tuning().fixed
    for bad in ('{"nope": 1}', "[1]", '{"fixed": {"confirm": [5, 2]}}', '{"watcher": {"nope": 1}}',
                '{"watcher": {"windows": {"nope": [1, 2]}}}', '{"watcher": {"yes": "high"}}', '{"max_in_flight": 0}'):
        with pytest.raises(ValueError):
            Tuning().with_overrides(bad)


# ---- the seams with pipeline.perception: these fail when that side changes under the server ----

SCENARIOS = {
    "wrong part, recovered, wrong again": [frame("side_panel", right_part=0.05)] * 10 + [frame("end_slat", right_part=0.95)] * 90
                                          + [frame("side_panel", right_part=0.05)] * 10,
    "wrong screw, then a wrong part": [frame("hardware_only", LONG)] * 6 + [frame("side_panel", right_part=0.05)] * 40,
    "not turned": [frame(orientation_ok=0.05)] * 12,
    "next step's part": [frame("end_slat_handle", right_part=0.5, next_step_part_used=0.95)] * 12,
    "done, hands leave late": [frame("end_slat", right_part=0.95, step_complete=0.9)] * 14 + [DONE] * 12,
    "done, never seen hands": [DONE] * 12,
    "torn": [frame("end_slat", p=0.5, right_part=0.5)] * 12,
    "a tool": [frame("other", p=0.9)] * 12,
    "poor and blocked": [dict(frame("side_panel", right_part=0.05), image_poor=noul(0.6))] * 6
                        + [dict(frame("side_panel", right_part=0.05), view_blocked=noul(0.5))] * 6
                        + [frame("side_panel", right_part=0.05)] * 6,
    "no hands": [frame("side_panel", hands=0.1, right_part=0.05)] * 12,
    "noise": [frame("side_panel", right_part=0.05), frame("end_slat", right_part=0.95), frame("hardware_only", LONG), EMPTY] * 8,
}


@pytest.mark.parametrize("name", SCENARIOS)
def test_no_drift_the_coach_gives_exactly_the_watchers_events(name):
    """Fixed camera, no action reported, confirmations off: nothing of this server's own applies, so every
    event, its time and what it names must be the Watcher's. This is what makes thresholds tuned offline with
    `pipeline.perception.replay --cached` the ones that run live."""
    pack = {"step": 3, "expects": {"parts": ["end_slat"], "hardware": [SHORT]}}
    bare = Watcher(pack, guide_page=5)                                # the pipeline's own defaults
    c = coach(Expect(step=3, page=5, parts=frozenset({"end_slat"}), hardware=frozenset({SHORT})), confirmations=False)
    for i, answers in enumerate(SCENARIOS[name]):
        ts = i * 0.5
        theirs = [(e["type"], e["ts"], e["observed"], e["evidence"]) for e in bare.feed(answers, ts)]
        ours = [(e.event, ts, e.observed_id, e.votes) for e in c.on_frame(answers, ts, "fixed")]
        assert ours == theirs, (name, ts)
        assert c.watcher.state == bare.state and c.watcher.skipped == bare.skipped
    assert c.watcher.cfg == pipeline_logic.Config()                   # and the numbers are theirs, not a copy


def test_no_drift_the_scenarios_cover_every_watcher_event():
    pack = {"step": 3, "expects": {"parts": ["end_slat"], "hardware": [SHORT]}}
    seen = set()
    for frames in SCENARIOS.values():
        w = Watcher(pack, guide_page=5)
        seen |= {e["type"] for i, a in enumerate(frames) for e in w.feed(a, i * 0.5)}
    assert seen == {"wrong_part", "wrong_hardware", "flip_needed", "out_of_order", "step_complete", "unsure_show_part"}


def test_no_drift_what_the_server_reaches_into():
    """The server uses a few things of the pipeline that are not arguments or return values. Named here, so a
    rename there fails here and not in front of a user."""
    w = Watcher({"step": 1, "expects": {"parts": ["a"], "hardware": ["1"]}}, guide_page=2)
    assert (w.parts, w.hardware, w.state, w.last, w.last_warning) == ({"a"}, {"1"}, WAITING, {}, None)
    assert set(w.votes) == set(w.cfg.windows) == {"wrong_part", "flip_needed", "wrong_hardware", "out_of_order", "step_complete"}
    assert w.armed["step_complete"] is True and w._free("anything", 0.0) is True
    w.feed(DONE, 0.0)
    assert list(w.votes["step_complete"]) == [(True, None)]           # (vote, what was seen), newest last
    w.last_warning = 1.0
    assert w._free("anything", 6.9) is False and w._free("anything", 7.0) is True     # the 6 s gap

    done = threading.Event()

    def reentrant():                                                  # the session looks at `waiting` around `submit`
        s = Stream(lambda payload: {"answers": None}, w, k=1)
        with s.cv:
            s.submit(b"x", 0.0)
            assert s.waiting[0][0] == b"x"                            # (payload, ts, wall)
        s.close()
        assert (s.answered, s.errors, s.in_flight) == (0, 0, 0)       # `answers: None` is neither fed nor an error
        done.set()

    threading.Thread(target=reentrant, daemon=True).start()
    assert done.wait(3), "Stream.cv is no longer re-entrant, or a frame without answers is no longer skipped"
    assert callable(bench.step_pack.cache_clear) and callable(bench.sheet_jpeg.cache_clear)
    assert "{n}" in writer.SHEET_NOTE and "{orange}" in writer.SHEET_NOTE and "{n}" in writer.ORANGE_NOTE
    assert pipeline_logic.SPECIAL == {"hardware_only", "nothing_held", "no_hardware", "other"}


def test_no_drift_questions_made_for_an_older_job_are_what_the_writer_would_write(guide):
    """`fallback_pack` repeats the few lines of `pipeline.perception.questions.build` that put a pack together
    (that function also writes files). On a job where the writer ran, both must give the same pack and request."""
    shot = jpeg()
    for n in guide.order:
        written = json.loads((guide.dir / "refs" / f"step-{n:02d}-questions.json").read_text())
        made = fallback_pack(guide, n)
        assert made == written, n
        from_file = build_ask(guide, load_pack(guide, n), shot, codes={LONG, SHORT})
        from_memory = build_ask(guide, type(load_pack(guide, n))(n, "fallback", made), shot, codes={LONG, SHORT})
        assert from_file == from_memory and from_file.inputs == bench.build(guide.dir, n, shot)[0]


def test_no_drift_failures_of_the_decisions_client_are_understood(monkeypatch, guide):
    """`pipeline.check.decisions` reports failures as text; `classify` reads the kind from it."""
    ask = build_ask(guide, load_pack(guide, 2), jpeg(), codes={LONG})
    posted = {}

    def fake(reply):
        def _post(body: bytes, timeout: float):
            posted["body"], posted["timeout"] = json.loads(body), timeout
            if isinstance(reply, Exception):
                raise reply
            return reply
        monkeypatch.setattr(decisions, "_post", _post)
        return GeminiDecider("k", T)

    ok = {"answers": {"right_hardware": {"type": "noul", "noul": 0.97, "confidence": 0.97}}, "usage": {"total_input_tokens": 100}}
    out = fake((200, json.dumps(ok).encode())).decide(ask)
    assert out["answers"] == ok["answers"] and out["latency_ms"] >= 0
    assert posted["body"] == body_of(ask) and posted["timeout"] == T.decision_timeout_s == 4.0
    assert posted["body"]["model"] == MODEL == "gemini-decision-1"

    def kind_of(reply) -> str:
        with pytest.raises(DecisionError) as e:
            fake(reply).decide(ask)
        return e.value.kind

    safety = b'{"error": {"code": "image_safety", "message": "Request blocked due to safety violations."}}'
    assert kind_of((400, safety)) == "safety"
    assert kind_of((429, b'{"error": {"code": 429, "status": "RESOURCE_EXHAUSTED"}}')) == "rate_limit"
    assert kind_of((503, b"upstream")) == "server_error"
    assert kind_of((402, b'{"error": {"message": "prepayment credits are depleted"}}')) == "billing"   # seen Sun 00:30
    assert kind_of((400, b'{"error": {"code": 400, "message": "API key not valid."}}')) == "auth"
    assert kind_of((403, b'{"error": {"message": "denied"}}')) == "auth"
    assert kind_of((400, b'{"error": {"code": 400, "message": "questions: too many"}}')) == "bad_request"
    assert kind_of((200, b'{"nothing": 1}')) == "server_error" and kind_of((200, b"<html>")) == "server_error"
    assert kind_of(RuntimeError("Decisions API connection failed: timed out")) == "timeout"
    assert kind_of(RuntimeError("Decisions API connection failed: [Errno 61] Connection refused")) == "network"
    assert classify(RuntimeError("GEMINI_API_KEY is not set (put it in .env)")).kind == "auth"
    # the two texts above are the ones the client raises today
    monkeypatch.setattr(decisions, "_post", lambda body, timeout: (500, b"boom"))
    with pytest.raises(RuntimeError, match=r"^Decisions API 500: boom$"):
        decisions.ask(["x"], {"q": {"type": "noul", "instructions": "?"}})


def test_no_drift_answers_in_the_logs_agree_with_the_pipelines_replay():
    raw = frame("end_slat", SHORT, right_part=0.9)
    ours, theirs = normalise(raw), flat(raw)                          # both write eval/phase2's frames.jsonl shape
    assert set(ours) == set(theirs)
    for key, value in theirs.items():
        if isinstance(value, dict):                                   # theirs keeps the top choice, ours every option
            (label, conf), = value.items()
            assert max(ours[key], key=ours[key].get) == label and ours[key][label] == conf
        else:
            assert ours[key] == value


# ---- sentences -----------------------------------------------------------------------------------

def test_sentences_follow_the_voice_rules(guide, tmp_path):
    plain = Guide(make_job(tmp_path / "plain", plan=False))           # a job without Greek names
    cases = [Emitted("wrong_hardware", 2, 3, expected_id=LONG, observed_id=SHORT, p=0.93),
             Emitted("wrong_hardware", 2, 3, expected_id=LONG, observed_id=SHORT, repeat=1),
             Emitted("wrong_hardware", 1, 3, expected_id=None, observed_id=LONG),
             Emitted("wrong_part", 3, 5, expected_id="end_slat", observed_id="end_slat_handle"),
             Emitted("flip_needed", 4, 6), Emitted("flip_needed", 2, 3),
             Emitted("out_of_order", 2, 3), Emitted("step_complete", 2, 3), Emitted("unsure_show_part", 2, 3),
             Emitted("right_hardware", 2, 3, expected_id=LONG, observed_id=LONG),
             Emitted("right_part", 3, 5, expected_id="end_slat", observed_id="end_slat"),
             Emitted("more_light", 2, 3), Emitted("realign_camera", 2, 3), Emitted("vision_paused", 2, 3),
             Emitted("vision_paused", None, reason="unavailable"), Emitted("vision_resumed", 2, 3)]
    for g in (guide, plain):
        for e in cases:
            say = sentences(e, g, next_step=3, next_page=5)
            for lang in ("en", "el"):
                text = say[lang]
                assert text and text.endswith("."), (e.event, lang, text)
                assert "!" not in text and "—" not in text and "  " not in text, text
                assert "wrong" not in text.lower() and "λάθος" not in text.lower(), text
                assert len(text.split()) <= 20, text
                if e.event in ("wrong_hardware", "wrong_part", "flip_needed", "out_of_order"):   # corrections cite both
                    assert str(e.step) in text and str(e.page) in text, text
    assert sentences(cases[0], guide) == {
        "en": "That's the short screw, 108560. Step 2 needs the long screw, 108363. Page 3.",
        "el": "Κρατάς κοντή βίδα, 108560. Το βήμα 2 θέλει μακριά βίδα, 108363. Σελίδα 3."}
    assert sentences(cases[0], plain)["el"] == "Κρατάς τη βίδα 108560. Το βήμα 2 θέλει τη βίδα 108363. Σελίδα 3."
    assert sentences(cases[1], guide)["en"] == "That's the short screw, 108560 again. Step 2 needs the long screw, 108363, page 3."
    assert sentences(cases[2], guide) == {"en": "That's the long screw, 108363. Step 1 does not need it. Page 3.",
                                          "el": "Κρατάς μακριά βίδα, 108363. Δεν χρειάζεται στο βήμα 1. Σελίδα 3."}
    # flip_needed is about how the assembly stands: the guide's own turn text, from the step that last turned it
    assert sentences(cases[4], guide) == {
        "en": "Stand the assembly on its end, open end up. Step 4, page 6.",
        "el": "Στήσε την κατασκευή όρθια, με την ανοιχτή άκρη προς τα πάνω. Βήμα 4, σελίδα 6."}
    assert sentences(cases[5], guide)["en"] == "Turn the assembly the way step 2 shows, page 3."
    assert sentences(cases[9], guide) == {"en": "Good. That's the long screw, 108363.", "el": "Ωραία. Κρατάς μακριά βίδα, 108363."}
    assert sentences(cases[7], guide, 3, 5)["en"] == "Step 2 done. Next is step 3, page 5."
    assert sentences(cases[7], guide)["el"] == "Το βήμα 2 έγινε. Ήταν το τελευταίο βήμα."
    line = perception_line("wrong_hardware", 2, 3, guide.label(LONG), guide.label(SHORT), 0.93)
    assert line == ('[PERCEPTION event=wrong_hardware step=2 page=3 expected="long screw 108363" '
                    'observed="short screw 108560" p=0.93] Tell the user briefly, cite step 2 page 3.')
    assert perception_line("vision_paused", 2, 3) == "[PERCEPTION event=vision_paused step=2 page=3]"   # background


# ---- the request ---------------------------------------------------------------------------------

def test_request_is_the_benchmarked_one_plus_one_question(guide):
    """Step 2 has the two candidate screws. The blocks and questions are `pipeline.perception.request`'s."""
    shot = jpeg()
    pack = load_pack(guide, 2)
    assert (pack.origin, pack.written_by, pack.parts, pack.hardware) == ("file", "template", (), (LONG,))
    ask = build_ask(guide, pack, shot, codes={LONG})
    inputs, questions = bench.build(guide.dir, 2, shot)
    assert ask.inputs == inputs and [type(i) for i in inputs] == [str, tuple, str, tuple]       # §6.1, in this order
    assert inputs[1] == ("image/jpeg", shot) and inputs[3][0] == "image/jpeg" and inputs[3][1][:3] == b"\xff\xd8\xff"
    assert "STEP 2 of 4" in inputs[0] and "ORANGE" in inputs[2]
    assert {k: v for k, v in ask.questions.items() if k != EXTRA} == questions and list(ask.questions)[-1] == EXTRA
    assert set(ask.questions["hardware_in_hand"]["criteria"]) == {LONG, SHORT, "no_hardware", "other"}   # the sheet's labels
    extra = ask.questions[EXTRA]
    assert extra["type"] == "noul" and "Long screw (108363)" in extra["instructions"] and "step 2" in extra["instructions"]
    assert "Short screw (108560)" in extra["criteria"]["false_description"]
    assert writer.validate(ask.questions) == [] and (ask.resolution, ask.codes) == ("medium", (LONG,))
    body = body_of(ask)
    assert body["model"] == "gemini-decision-1" and [b["type"] for b in body["input"]] == ["text", "image", "text", "image"]
    assert body["input"][1] == {"type": "image", "mime_type": "image/jpeg", "resolution": "medium",
                                "data": base64.b64encode(shot).decode()}

    assert EXTRA not in build_ask(guide, pack, shot, codes={LONG}, extra=False).questions      # it can be turned off
    assert EXTRA not in build_ask(guide, pack, shot, codes=()).questions                       # nothing is needed now
    assert EXTRA not in build_ask(guide, load_pack(guide, 1), shot, codes=()).questions        # a step without hardware
    both = build_ask(guide, load_pack(guide, 3), shot, codes={SHORT})                          # a part step keeps its own
    assert {"part_in_hand", "right_part", "orientation_ok", "step_complete", "next_step_part_used", "hardware_in_hand",
            "hands_present", "view_blocked", "image_poor", "frame_matches_setup", EXTRA} == set(both.questions)
    look = build_ask(guide, pack, shot, codes={LONG}, resolution="high", only=("part_in_hand", "hardware_in_hand", "image_poor"))
    assert set(look.questions) == {"part_in_hand", "hardware_in_hand", "image_poor"} and look.resolution == "high"
    assert build_ask(guide, pack, shot, resolution="nonsense").resolution == "medium"


def test_a_job_built_before_the_question_files_gets_template_questions_in_memory(tmp_path, caplog):
    old = Guide(make_job(tmp_path / "old", questions=False))
    before = sorted(p.name for p in old.dir.rglob("*"))
    with caplog.at_level(logging.WARNING, logger="agj.perception"):
        pack = load_pack(old, 2)
    assert pack.origin == "fallback" and pack.written_by == "template"
    assert "no refs/step-02-questions.json" in caplog.text and "not benchmarked" in caplog.text    # clearly logged
    ask = build_ask(old, pack, jpeg(), codes={LONG})
    assert [type(i) for i in ask.inputs] == [str, tuple, str, tuple] and "ORANGE" not in ask.inputs[2]   # the old sheet
    assert set(ask.questions["hardware_in_hand"]["criteria"]) == {LONG, SHORT, "no_hardware", "other"}
    assert EXTRA in ask.questions and writer.validate(ask.questions) == []
    assert sorted(p.name for p in old.dir.rglob("*")) == before       # nothing is written into the job

    (old.dir / "refs" / "step-02-questions.json").write_text('{"step": 2, "questions": {}}')    # a broken file
    with caplog.at_level(logging.ERROR, logger="agj.perception"):
        assert load_pack(old, 2).origin == "fallback" and "cannot be used" in caplog.text

    bare = Guide(make_job(tmp_path / "bare", questions=False))        # no sheet at all: header and frame only
    for f in (bare.dir / "refs").iterdir():
        f.unlink()
    ask = build_ask(bare, load_pack(bare, 2), jpeg(), codes={LONG})
    assert [type(i) for i in ask.inputs] == [str, tuple] and set(ask.questions["hardware_in_hand"]["criteria"]) >= {LONG, SHORT}

    odd = json.loads(json.dumps(MANIFEST))
    del odd["steps"][2]["moving"]                                     # a manifest the template cannot read
    with pytest.raises(ValueError, match="no questions for step 3"):
        load_pack(Guide(make_job(tmp_path / "odd", questions=False, manifest=odd)), 3)


@pytest.mark.skipif(not (ROOT / "jobs/local-fresh/manifest.json").is_file(), reason="no locally built KNAGGLIG job")
def test_every_step_of_the_built_knagglig_job_makes_a_valid_request():
    g = Guide(ROOT / "jobs/local-fresh")
    for n in g.order:
        ask = build_ask(g, load_pack(g, n), jpeg(), codes=g.expect(n).ok_hardware)
        assert writer.validate(ask.questions) == [] and len(ask.inputs) == 4, n
        assert {LONG, SHORT} <= set(ask.questions["hardware_in_hand"]["criteria"])
        assert (EXTRA in ask.questions) == bool(g.expect(n).ok_hardware)


def test_frame_layout_is_big_endian_and_bad_frames_are_named():
    shot = jpeg()
    data = pack_frame({"seq": 7, "step": 2, "ts": 1, "hint": "low"}, shot)
    n = int.from_bytes(data[:4], "big")
    assert json.loads(data[4:4 + n]) == {"seq": 7, "step": 2, "ts": 1, "hint": "low"} and data[4 + n:] == shot
    assert parse_frame(data) == ({"seq": 7, "step": 2, "ts": 1, "hint": "low"}, shot)
    little = n.to_bytes(4, "little") + data[4:]                       # tolerated, not the contract
    assert parse_frame(little)[0]["seq"] == 7
    for bad, seq in ((b"", None), (b"\x00\x00\x00\x05abc", None), ((9999).to_bytes(4, "big") + b"{}" + shot, None),
                     (pack_frame({"step": 2}, shot), None), (pack_frame({"seq": 3}, b"GIF89a"), 3),
                     (pack_frame({"seq": 4}, shot + b"0" * 2000), 4)):
        with pytest.raises(FrameError) as e:
            parse_frame(bad, max_jpeg_bytes=len(shot) + 100)
        assert e.value.seq == seq


# ---- the simulated decider -----------------------------------------------------------------------

def test_simulated_decider_answers_like_the_api_and_follows_its_script(guide):
    ask = build_ask(guide, load_pack(guide, 3), jpeg(), codes={SHORT})
    sim = SimulatedDecider(T)
    sim.update({"type": "sim", "latency_ms": 0})

    def answers() -> dict:
        raw = sim.decide(ask)
        assert set(raw["answers"]) == set(ask.questions) and raw["simulated"] is True
        for key, a in raw["answers"].items():                        # the API's shapes
            assert a["type"] == ask.questions[key]["type"]
            if a["type"] == "choice":
                assert a["choice"] in ask.questions[key]["criteria"] and abs(sum(a["probabilities"].values()) - 1) < 0.01
        return normalise(raw["answers"])

    a = answers()
    assert max(a["part_in_hand"], key=a["part_in_hand"].get) == "nothing_held" and a["hands_present"] < 0.15
    sim.update({"holding": SHORT})
    a = answers()
    assert a["hardware_in_hand"][SHORT] == 0.93 and a["part_in_hand"]["hardware_only"] == 0.93 and a[EXTRA] == 0.93
    sim.update({"holding": LONG})
    assert answers()[EXTRA] <= 0.15                                   # not the code this action needs
    sim.update({"holding": "end_slat_handle"})
    a = answers()
    assert a["part_in_hand"]["end_slat_handle"] == 0.93 and a["right_part"] <= 0.15 and a["hardware_in_hand"]["no_hardware"] == 0.93
    sim.update({"unsure": True})
    assert max(answers()["part_in_hand"].values()) < CFG.choice
    sim.update({"reset": True, "step_complete": True, "latency_ms": 0, "on_base": True})     # `on_base`: still accepted
    assert answers()["step_complete"] == 0.93 and sim.script["holding"] is None
    for bad in ({"holdng": 1}, {"fail": "explode"}, {"latency_ms": "fast"}):
        with pytest.raises(ValueError):
            sim.update(bad)
    sim.update({"fail": "rate_limit"})
    with pytest.raises(DecisionError) as e:
        sim.decide(ask)
    assert e.value.kind == "rate_limit"
    # the scripted answers drive the real logic: the short screw on a step that wants the long one
    sim.update({"reset": True, "holding": SHORT, "latency_ms": 0})
    c = coach(STEP2)
    ask2 = build_ask(guide, load_pack(guide, 2), jpeg(), codes={LONG})
    assert feed(c, [sim.decide(ask2)["answers"] for _ in range(4)])[0] == [(0.75, "wrong_hardware")]


# ---- the WebSocket route -------------------------------------------------------------------------

FAST = {"max_hz": 1_000_000, "status_interval_s": 0.05}              # tests do not wait a quarter second per frame


def app_client(tmp_path, tuning: dict | None = None, job: dict | None = None, **settings):
    """The app with one built job. Simulated vision unless a test says otherwise."""
    make_job(tmp_path / "jobs", **(job or {}))
    values = dict(demo_key=KEY, jobs_dir=tmp_path / "jobs", gemini_api_key="", client_dist=tmp_path / "no-client",
                  perception_simulate=True, perception_tuning=json.dumps({**FAST, **(tuning or {})}))
    values.update(settings)
    return TestClient(create_app(Settings(_env_file=None, **values)))


@pytest.fixture()
def client(tmp_path):
    with app_client(tmp_path) as c:
        yield c


class Peer:
    """One device on the socket. A thread reads everything; a test waits for the message it wants."""

    def __init__(self, ws):
        self.ws, self.inbox, self.seen = ws, queue.Queue(), []
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        try:
            while True:
                self.inbox.put(self.ws.receive_json())
        except Exception as e:                       # noqa: BLE001 - closed
            self.inbox.put(e)

    def next(self, timeout: float = 5.0) -> dict:
        try:
            msg = self.inbox.get(timeout=timeout)
        except queue.Empty:
            raise AssertionError(f"no message within {timeout} s; seen so far: {[m.get('type') for m in self.seen]}") from None
        if isinstance(msg, Exception):
            raise msg
        self.seen.append(msg)
        return msg

    def until(self, want, timeout: float = 5.0) -> dict:
        """The next message for which `want(msg)` is true (or whose type is `want`)."""
        test = want if callable(want) else (lambda m: m["type"] == want)
        deadline = time.monotonic() + timeout
        while True:
            msg = self.next(max(0.05, deadline - time.monotonic()))
            if test(msg):
                return msg
            assert time.monotonic() < deadline, f"not seen within {timeout} s; last: {msg}"

    def find(self, want, timeout: float = 5.0) -> dict:
        """Like `until`, but a message that was already read while waiting for something else counts too."""
        test = want if callable(want) else (lambda m: m["type"] == want)
        return next((m for m in self.seen if test(m)), None) or self.until(test, timeout)

    def send(self, **msg) -> None:
        self.ws.send_json(msg)

    def frame(self, seq: int, shot: bytes | None = None, **header) -> None:
        self.ws.send_bytes(pack_frame({"seq": seq, "ts": int(time.time() * 1000), **header}, shot or jpeg()))

    def frame_ack(self, seq: int, **header) -> dict:
        self.frame(seq, **header)
        return self.until(lambda m: m["type"] == "ack" and m["seq"] == seq)


def url(role: str = "display", job: str = JOB, key: str = KEY, extra: str = "") -> str:
    return f"/ws/perception/{job}?role={role}&key={key}{extra}"


def load_tool(name: str):
    """A script from eval/phase2 as a module (the folder is not a package)."""
    spec = importlib.util.spec_from_file_location(f"phase2_{name}", ROOT / "eval" / "phase2" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_two_devices_share_a_session_frames_are_acked_and_an_event_reaches_both(client):
    with client.websocket_connect(url("display")) as a, client.websocket_connect(url("camera")) as b:
        display, camera = Peer(a), Peer(b)
        hello = display.next()
        assert hello["type"] == "hello" and hello["vision"] == "simulated" and hello["sim"] is True
        assert hello["steps"] == [1, 2, 3, 4] and hello["frame"]["byte_order"] == "big-endian" and hello["source"] == "headset"
        # from the measurements: 4 requests at once and the newest frame waiting; an ack within two 4 s timeouts
        assert hello["limits"] == {"max_unacked": 5, "in_flight": 4, "max_hz": 1_000_000, "ack_timeout_ms": 9000,
                                   "max_frame_bytes": 1_500_000, "long_edge_px": 768, "jpeg_quality": 0.7}
        assert camera.next()["source"] == "fixed"                     # a phone on a stand, by default
        assert display.until(lambda m: m["type"] == "status" and m["devices"] == {"display": 1, "camera": 1})

        display.send(type="start", step=2, action=0)
        status = camera.until(lambda m: m["type"] == "status" and m["step"] == 2)      # the camera learns the step
        assert status["action"] == 0 and status["page"] == 3 and status["voice_state"] == "[STATE step=2]"
        assert status["expected"]["id"] == LONG and status["expected"]["kind"] == "hardware"
        assert status["expected"]["text"]["en"].startswith("Take a long screw")
        display.send(type="sim", holding=SHORT)                       # debug: "now holding the short screw"
        assert display.until("sim")["script"]["holding"] == SHORT

        for seq in range(1, 5):                                       # one ack per frame, to its sender only
            ack = camera.frame_ack(seq, step=2, hint="medium")
            assert ack["used"] is True and ack["dropped"] is None and ack["latency_ms"] >= ack["decision_ms"] >= 0
        for peer in (display, camera):                                # the event goes to every device
            event = peer.find("event")
            assert event["event"] == "wrong_hardware" and (event["step"], event["page"], event["action"]) == (2, 3, 0)
            assert (event["observed_id"], event["expected_id"]) == (SHORT, LONG)
            assert (event["observed"], event["expected"]) == ("short screw 108560", "long screw 108363")
            assert event["say"]["en"] == "That's the short screw, 108560. Step 2 needs the long screw, 108363. Page 3."
            assert "108560" in event["say"]["el"] and "108363" in event["say"]["el"] and "βήμα 2" in event["say"]["el"]
            assert event["perception"].startswith("[PERCEPTION event=wrong_hardware step=2 page=3 expected=")
            assert event["speak"] and event["turn_complete"] and event["seq"] == 4 and event["votes"] == "4 of last 4 frames"
            assert event["evidence"] == {f"hardware_in_hand.{SHORT}": 0.93, "right_hardware": 0.07}
        status = display.find(lambda m: m["type"] == "status" and m["holding"]["id"] == SHORT)
        assert status["verdict"] == "wrong" and status["holding"]["kind"] == "hardware" and status["source"] == "fixed"
        assert status["holding"]["confidence"] == 0.93 and status["hands_present"] is True and status["view"] == "ok"
        assert status["questions"] == {"origin": "file", "written_by": "template"}    # the refs stage's own files
        assert not any(m["type"] == "ack" for m in display.seen)      # acks are not broadcast
        assert all(m.get("sim") is True for m in display.seen + camera.seen)   # nothing simulated passes for real

        display.send(type="mute", event="wrong_hardware")             # "I know"
        assert camera.until(lambda m: m["type"] == "status" and m["muted"] == ["wrong_hardware"])
        display.send(type="action", index=1)
        assert camera.until(lambda m: m["type"] == "status" and m["action"] == 1)["expected"]["id"] == LONG
        display.send(type="step", n=3)                                # the display moves on; the camera's frames are old
        camera.until(lambda m: m["type"] == "status" and m["step"] == 3)
        assert camera.frame_ack(5, step=2)["dropped"] == "stale_step"
        display.send(type="stop")
        camera.until(lambda m: m["type"] == "status" and m["step"] is None)
        assert camera.frame_ack(6)["dropped"] == "not_started"


def test_one_headset_is_display_hands_and_camera_on_one_socket(client):
    with client.websocket_connect(url("display")) as ws:
        headset = Peer(ws)
        headset.until("status")
        headset.send(type="start", step=2, action=0)
        headset.send(type="sim", holding=LONG, latency_ms=5)
        headset.until("sim")
        moving = dict(type="hands", seq=1, hand_at_target=False, still=False, raised=False, ts=0)
        headset.send(**moving)                                        # hands moving: not worth a decision call
        ack = headset.frame_ack(1, step=2)
        assert (ack["used"], ack["dropped"], ack["decision_ms"]) == (False, "gated", None)
        status = headset.find(lambda m: m["type"] == "status" and m["hands"])
        assert status["hands"]["still"] is False and status["rate_hz"] == 0.0
        headset.send(**dict(moving, seq=2, raised=True))              # holding something up: look now
        seq = 2
        while not any(m["type"] == "event" for m in headset.seen):    # its own frames, from a display role
            assert headset.frame_ack(seq, step=2)["dropped"] is None
            seq += 1
            assert seq < 12
        event = headset.find("event")
        assert event["event"] == "right_hardware" and event["say"]["en"] == "Good. That's the long screw, 108363."
        assert event["perception"].endswith("Confirm briefly.")
        headset.send(**dict(moving, seq=3, raised=True))
        status = headset.until(lambda m: m["type"] == "status" and m["verdict"] == "right" and m["hands"])
        assert status["source"] == "headset" and status["state"] == "PART_IN_HAND" and status["devices"] == {"display": 1}
        assert status["rate_hz"] == FAST["max_hz"] and status["hands"]["raised"] is True

        headset.send(type="show_part", expected=SHORT)                # one close look, at `high`
        headset.frame(49, jpeg(grey=4), step=2)
        assert headset.until("ack")["dropped"] == "dark"              # a lost frame does not use up the request
        ack = headset.frame_ack(50, step=2)
        result = headset.find(lambda m: m["type"] == "event" and m["event"] == "show_part_result")
        assert ack["used"] and result["match"] is False and result["observed_id"] == LONG and result["expected_id"] == SHORT

        headset.send(type="step", n=1, action=1)                      # the last action of step 1
        headset.send(type="sim", reset=True, step_complete=True, latency_ms=5)
        headset.until("sim")
        at_target = dict(type="hands", seq=9, hand_at_target=True, still=True, raised=False, ts=0)
        headset.send(**at_target)
        assert headset.until(lambda m: m["type"] == "status" and m["hands"] and m["hands"]["at_target"])["state"] == "PLACING"
        seq, t0 = 60, time.monotonic()
        while not any(m["type"] == "event" and m["event"] == "step_complete" for m in headset.seen):
            headset.send(**dict(at_target, seq=seq, hand_at_target=False))     # the hand has left the target
            headset.frame_ack(seq, step=1)
            seq += 1
            time.sleep(0.05)
            assert time.monotonic() - t0 < 5
        done = headset.find(lambda m: m["type"] == "event" and m["event"] == "step_complete")
        assert done["say"]["en"] == "Step 1 done. Next is step 2, page 3." and done["perception"].endswith("Tell the user step 1 is done.")
        assert headset.frame_ack(seq, step=1)["dropped"] == "step_done"      # until the display sends the next step


def test_a_second_camera_takes_over_only_when_the_first_is_gone(client):
    with client.websocket_connect(url("camera")) as phone_ws:
        phone = Peer(phone_ws)
        with client.websocket_connect(url("display")) as ws:
            headset = Peer(ws)
            headset.send(type="start", step=2)
            headset.until(lambda m: m["type"] == "status" and m["step"] == 2)
            assert headset.frame_ack(1)["dropped"] is None            # the headset is the camera
            assert phone.frame_ack(1)["dropped"] == "other_source"    # two views are not mixed in one window
        phone.until(lambda m: m["type"] == "status" and m["devices"] == {"camera": 1})
        assert phone.frame_ack(2)["dropped"] is None                  # the fallback takes over
        assert phone.until(lambda m: m["type"] == "status" and m["source"] == "fixed")


def test_four_requests_at_once_and_only_the_newest_frame_waits(client):
    with client.websocket_connect(url("camera")) as ws:
        cam = Peer(ws)
        cam.send(type="start", step=2)
        cam.send(type="sim", latency_ms=400)
        cam.until("sim")
        for seq in range(1, 8):                                       # 7 frames at once
            cam.frame(seq, step=2)
        acks = [cam.until("ack") for _ in range(7)]
        busy, used = acks[:2], acks[2:]
        assert all(a["dropped"] == "busy" and not a["used"] and a["latency_ms"] < 200 for a in busy)   # told at once
        assert all(a["used"] and a["decision_ms"] >= 400 for a in used)          # 4 in flight and 1 waiting were kept
        assert 7 in {a["seq"] for a in used}                          # the newest is never the one given up
        stream = client.app.state.perception.sessions[JOB].stream
        assert (stream.k, stream.max_in_flight, stream.dropped) == (4, 4, 2)     # the pipeline's Stream did it
        assert sum(a["latency_ms"] for a in used) / 5 < 1200           # in parallel: one after another would be 2 s
        assert cam.frame_ack(8, step=2)["used"] is True
        assert cam.until(lambda m: m["type"] == "status" and m["frames"]["used"] == 6)["frames"]["in_flight"] == 0


def test_rate_cap_without_a_hand_gate(tmp_path):
    with app_client(tmp_path, {"max_hz": 4}) as c, c.websocket_connect(url("camera")) as ws:
        cam = Peer(ws)
        assert cam.next()["limits"]["max_hz"] == 4
        cam.send(type="start", step=2)
        cam.send(type="sim", latency_ms=1)
        cam.until("sim")
        assert cam.frame_ack(1)["dropped"] is None
        assert cam.frame_ack(2)["dropped"] == "rate"                  # 4 Hz at most (§6.3)
        time.sleep(0.22)
        assert cam.frame_ack(3)["dropped"] is None


def test_refusals_and_bad_input(client, tmp_path):
    with pytest.raises(WebSocketDisconnect) as refused:
        with client.websocket_connect(url(key="wrong")):
            pass
    assert refused.value.code == 4401
    (tmp_path / "jobs" / "no-manifest").mkdir()
    (tmp_path / "jobs" / "no-manifest" / "guide.pdf").write_bytes(b"%PDF-1.4\n")
    for path, code, close in ((url(job="nope123"), "unknown_job", 4404), (url(role="boss"), "unknown_role", 4400),
                              (url(job="no-manifest"), "not_built", 4409)):
        with client.websocket_connect(path) as ws:
            assert ws.receive_json()["code"] == code                  # said in a message a browser can read
            with pytest.raises(WebSocketDisconnect) as closed:
                ws.receive_json()
            assert closed.value.code == close

    with client.websocket_connect(url("camera")) as ws:
        cam = Peer(ws)
        ws.send_text("not json")
        assert cam.until("error")["code"] == "bad_json"
        cam.send(type="dance")
        assert cam.until("error")["code"] == "unknown_type"
        cam.send(type="start", step=99)
        assert cam.until("error")["code"] == "unknown_step"
        cam.send(type="action", index=0)
        assert cam.until("error")["code"] == "not_started"
        cam.send(type="start", step=2, action=7)
        assert cam.until("error")["code"] == "unknown_action"
        assert cam.until(lambda m: m["type"] == "status" and m["step"] == 2)["action"] is None
        cam.send(type="mute")
        assert cam.until("error")["code"] == "nothing_to_mute"
        ws.send_bytes(b"\x00\x00")
        assert cam.until("error")["code"] == "bad_frame"
        ack = cam.until("ack")                                        # still one ack, though it names no frame
        assert (ack["seq"], ack["used"], ack["dropped"], ack["decision_ms"]) == (None, False, "bad_frame", None)
        ws.send_bytes(pack_frame({"seq": 9}, b"GIF89a not a jpeg"))
        assert cam.until("ack")["seq"] == 9 and cam.seen[-1]["dropped"] == "bad_frame"
        ws.send_bytes(pack_frame({"seq": 10}, b"\xff\xd8\xff" + b"broken"))
        assert cam.until("ack")["dropped"] == "bad_frame"             # looks like a JPEG, does not decode
        cam.frame(11, jpeg(grey=4))
        assert cam.until("ack")["dropped"] == "dark"                  # no call is spent on a black frame
        assert cam.frame_ack(12)["dropped"] is None                   # and the session goes on


def test_without_a_key_the_socket_says_vision_is_unavailable(tmp_path):
    with app_client(tmp_path, perception_simulate=False) as c, c.websocket_connect(url("display")) as ws:
        headset = Peer(ws)
        hello = headset.next()
        assert hello["vision"] == "unavailable" and "GEMINI_API_KEY" in hello["reason"] and "sim" not in hello
        event = headset.until("event")
        assert event["event"] == "vision_paused" and event["reason"] == "unavailable" and event["speak"] is False
        assert "GEMINI_API_KEY" in event["message"] and event["say"]["en"].startswith("Vision is not available")
        headset.send(type="start", step=2, action=0)                  # the guide still moves
        status = headset.until(lambda m: m["type"] == "status" and m["step"] == 2)
        assert status["vision"] == "unavailable" and status["rate_hz"] == 0.0 and status["holding"]["kind"] == "unknown"
        ack = headset.frame_ack(1, step=2)
        assert (ack["used"], ack["dropped"], ack["decision_ms"]) == (False, "vision_unavailable", None)
        headset.send(type="sim", holding=LONG)
        assert headset.until("error")["code"] == "not_simulated"      # no pretending
        assert not any(m["type"] == "event" and m["event"] != "vision_paused" for m in headset.seen)
        assert not any("sim" in m for m in headset.seen)
        assert c.app.state.perception.sessions[JOB].stream is None    # and no worker was started


def test_failed_calls_pause_vision_and_it_recovers(tmp_path):
    tuning = {"decision_timeout_s": 0.08, "paused_after_s": 0.4, "backoff_start_s": 0.02, "backoff_max_s": 0.04}
    with app_client(tmp_path, tuning) as c, c.websocket_connect(url("camera")) as ws:
        cam = Peer(ws)
        cam.send(type="start", step=2)
        cam.send(type="sim", holding=SHORT, latency_ms=1, fail="timeout")
        cam.until("sim")
        t0, seq, reasons = time.monotonic(), 0, []
        while not any(m["type"] == "event" for m in cam.seen):
            seq += 1
            reasons.append(cam.frame_ack(seq)["dropped"])
            assert time.monotonic() - t0 < 5
            time.sleep(0.03)
        assert reasons[0] == "timeout" and set(reasons) <= {"timeout", "backoff"}
        paused = cam.find("event")
        assert paused["event"] == "vision_paused" and paused["speak"] is False and time.monotonic() - t0 >= 0.35
        assert cam.find(lambda m: m["type"] == "status" and m["vision"] == "paused")
        cam.send(type="sim", fail="rate_limit")
        cam.until("sim")
        time.sleep(0.1)                                               # let the back-off pass
        assert cam.frame_ack(seq + 1)["dropped"] == "rate_limit"
        cam.send(type="sim", fail=None)
        cam.until("sim")
        assert cam.frame_ack(seq + 2)["dropped"] == "rate_limit_wait"   # 429: 1 Hz for 30 s
        assert cam.until(lambda m: m["type"] == "status" and m["rate_hz"] == 1.0)
        time.sleep(0.85)
        assert cam.frame_ack(seq + 3)["dropped"] is None
        resumed = cam.find(lambda m: m["type"] == "event" and m["event"] == "vision_resumed")
        assert resumed["speak"] is False
        assert cam.find(lambda m: m["type"] == "status" and m["vision"] == "simulated")
        assert not any(m["type"] == "event" and m["event"] == "wrong_hardware" for m in cam.seen)   # no frame counted


def fake_api(monkeypatch, reply):
    """`pipeline.check.decisions` with its one network call replaced. Returns the list of posted bodies."""
    calls = []

    def _post(body: bytes, timeout: float):
        calls.append(json.loads(body))
        return reply(calls[-1])

    monkeypatch.setattr(decisions, "_post", _post)
    monkeypatch.setenv("GEMINI_API_KEY", "k-123")
    return calls


def short_screw_answers(body: dict):
    answers = {}
    for key, q in body["questions"].items():
        if q["type"] == "noul":
            p = {"hands_present": 0.97, "right_hardware": 0.03}.get(key, 0.02)
            answers[key] = {"type": "noul", "noul": p, "confidence": max(p, 1 - p)}
        else:
            pick = SHORT if key == "hardware_in_hand" else "hardware_only"
            rest = [o for o in q["criteria"] if o != pick]
            answers[key] = {"type": "choice", "choice": pick, "confidence": 0.91,
                            "probabilities": {pick: 0.91, **{o: 0.09 / len(rest) for o in rest}}}
    return 200, json.dumps({"answers": answers, "usage": {"total_input_tokens": 2300, "total_output_tokens": 0}}).encode()


def test_live_path_sends_the_benchmarked_request_through_the_pipelines_client(tmp_path, monkeypatch):
    calls = fake_api(monkeypatch, short_screw_answers)
    with app_client(tmp_path, perception_simulate=False, gemini_api_key="k-123") as c, \
            c.websocket_connect(url("display")) as ws:
        headset = Peer(ws)
        assert headset.next()["vision"] == "live"
        headset.send(type="start", step=2, action=0)
        shot = jpeg()
        for seq in range(1, 5):
            headset.frame(seq, shot, step=2, hint="low")
            assert headset.until("ack")["dropped"] is None
        event = headset.find("event")
        assert event["event"] == "wrong_hardware" and event["observed_id"] == SHORT and event["evidence"] == {
            f"hardware_in_hand.{SHORT}": 0.91, "right_hardware": 0.03}
        assert not any("sim" in m for m in headset.seen)
        job = tmp_path / "jobs" / JOB
    inputs, questions = bench.build(job, 2, shot)                     # what `pipeline.perception.request.ask` sends
    theirs = body_of(build_ask(Guide(job), load_pack(Guide(job), 2), shot, extra=False))
    assert theirs["questions"] == questions and len(calls) == 4
    for body in calls:
        assert body["model"] == "gemini-decision-1" and body["input"] == theirs["input"]      # medium, whatever the hint
        assert {k: v for k, v in body["questions"].items() if k != EXTRA} == questions and EXTRA in body["questions"]


def test_a_refused_key_or_used_up_credit_is_said_plainly_and_asked_again_later(tmp_path, monkeypatch):
    state = {"paid": False}
    calls = fake_api(monkeypatch, lambda body: short_screw_answers(body) if state["paid"]
                     else (402, b'{"error": {"message": "prepayment credits are depleted"}}'))
    with app_client(tmp_path, {"refused_retry_s": 0.3}, perception_simulate=False, gemini_api_key="k-123") as c, \
            c.websocket_connect(url("camera")) as ws:
        cam = Peer(ws)
        cam.send(type="start", step=2)
        assert cam.frame_ack(1)["dropped"] == "billing"
        event = cam.find("event")
        assert event["event"] == "vision_paused" and event["reason"] == "unavailable" and "credit" in event["message"]
        assert cam.until(lambda m: m["type"] == "status" and m["vision"] == "unavailable")["reason"] == event["message"]
        assert cam.frame_ack(2)["dropped"] == "vision_unavailable" and len(calls) == 1      # it stops asking
        state["paid"] = True
        time.sleep(0.35)
        assert cam.frame_ack(3)["dropped"] is None                    # one try after the wait: it works again
        assert cam.until(lambda m: m["type"] == "status" and m["vision"] == "live")["reason"] is None


def test_an_older_job_is_watched_with_template_questions_and_a_job_without_any_says_so(tmp_path):
    odd = json.loads(json.dumps(MANIFEST))
    del odd["steps"][2]["moving"]                                     # step 3 of this one cannot get questions
    make_job(tmp_path / "jobs", "kn-odd", questions=False, manifest=odd)
    with app_client(tmp_path, job={"questions": False}) as c:
        with c.websocket_connect(url("camera")) as ws:
            cam = Peer(ws)
            cam.send(type="start", step=2, action=0)
            cam.send(type="sim", holding=SHORT, latency_ms=1)
            cam.until("sim")
            for seq in range(1, 5):
                assert cam.frame_ack(seq)["dropped"] is None
            assert cam.find("event")["event"] == "wrong_hardware"
            assert cam.find(lambda m: m["type"] == "status" and m["questions"])["questions"] == {
                "origin": "fallback", "written_by": "template"}
            assert not list((tmp_path / "jobs" / JOB / "refs").glob("*-questions.json"))     # nothing written into the job
        with c.websocket_connect(url("camera", job="kn-odd")) as ws:
            cam = Peer(ws)
            cam.send(type="start", step=3)
            error = cam.until("error")
            assert error["code"] == "no_questions" and "Step 3 cannot be watched" in error["message"]
            assert cam.frame_ack(1)["dropped"] == "no_questions"
            cam.send(type="step", n=2)                                # the other steps of that job still work
            cam.until(lambda m: m["type"] == "status" and m["step"] == 2)
            assert cam.frame_ack(2)["dropped"] is None


def test_session_logs_have_the_eval_shapes(client, tmp_path):
    with client.websocket_connect(url("display", extra="&answers=1")) as ws:
        headset = Peer(ws)
        session = headset.next()["session"]
        headset.send(type="start", step=2, action=0)
        headset.send(type="sim", holding=SHORT, latency_ms=1)
        headset.send(type="hands", seq=1, hand_at_target=False, still=True, raised=True, ts=0)
        headset.until("sim")
        acks = [headset.frame_ack(seq, step=2, clip="d_wrong_hardware", frame=10 + seq, t=2.5 + seq / 4) for seq in range(1, 6)]
        assert acks[0]["answers"]["hardware_in_hand"][SHORT] == 0.93  # on request (`answers=1`), for the replay tool
        headset.find("event")
    folder = tmp_path / "jobs" / JOB / "perception" / session
    frames = [json.loads(l) for l in (folder / "frames.jsonl").read_text().splitlines()]
    events = [json.loads(l) for l in (folder / "events.jsonl").read_text().splitlines()]
    assert len(frames) == 5 and {"clip", "frame", "t", "latency_ms", "answers"} <= set(frames[0])
    assert [(f["clip"], f["frame"], f["t"]) for f in frames][:2] == [("d_wrong_hardware", 11, 2.75), ("d_wrong_hardware", 12, 3.0)]
    assert frames[0]["answers"]["hardware_in_hand"][SHORT] == 0.93 and frames[0]["answers"]["right_hardware"] == 0.07
    assert 0 <= frames[0]["answers"]["hands_present"] <= 1 and frames[0]["sim"] is True
    assert set(frames[0]["answers"]["part_in_hand"]) <= {"end_slat", "hardware_only", "nothing_held", "other"}
    assert [(e["clip"], e["t"], e["event"], e["step"]) for e in events] == [("d_wrong_hardware", 3.5, "wrong_hardware", 2)]
    assert events[0]["latency_ms"] >= 0 and events[0]["observed_id"] == SHORT
    hands = json.loads((folder / "hands.jsonl").read_text().splitlines()[0])
    assert hands["raised"] is True and hands["hand_at_target"] is False
    assert not (tmp_path / "jobs" / JOB / "events.jsonl").exists()    # the build feed's file is not touched


def test_replay_streams_a_clip_through_the_route_and_writes_a_run(client, tmp_path):
    replay = load_tool("replay")
    local_link, replay_clip = replay.local_link, replay.replay_clip

    clip = tmp_path / "clips" / "d_wrong_hardware"
    clip.mkdir(parents=True)
    for k in range(10):
        (clip / f"f{k:05d}_t{k / 4:06.2f}.jpg").write_bytes(jpeg())
    (clip / "notes.txt").write_text("not a frame")
    with client.websocket_connect(url("display")) as ws:              # someone sets the scene: the short screw
        Peer(ws).send(type="sim", holding=SHORT, latency_ms=20)
        time.sleep(0.1)
    opener = lambda path: local_link(client, path)                    # noqa: E731
    with pytest.raises(SystemExit) as stop:                           # a scripted run is never taken for a real one
        replay_clip(opener, JOB, KEY, clip, 2, tmp_path / "run", speed=5, out=lambda *_: None)
    assert "PERCEPTION_SIMULATE" in str(stop.value)
    summary = replay_clip(opener, JOB, KEY, clip, 2, tmp_path / "run", action=0, speed=5, allow_simulated=True,
                          out=lambda *_: None)
    assert summary["frames"] == 10 and summary["sent"] + summary["skipped"] == 10 and summary["used"] >= 8 and summary["sim"]
    frames = [json.loads(l) for l in (tmp_path / "run" / "frames.jsonl").read_text().splitlines()]
    events = [json.loads(l) for l in (tmp_path / "run" / "events.jsonl").read_text().splitlines()]
    assert len(frames) == summary["used"] and frames[0]["clip"] == "d_wrong_hardware"
    assert all(f["t"] == f["frame"] / 4 for f in frames) and len({f["frame"] for f in frames}) == len(frames)
    assert frames[0]["answers"]["hardware_in_hand"][SHORT] == 0.93 and frames[0]["latency_ms"] >= 20 and frames[0]["sim"]
    assert [(e["clip"], e["event"], e["step"]) for e in events] == [("d_wrong_hardware", "wrong_hardware", 2)]
    assert 0.75 <= events[0]["t"] <= 2.25 and 20 <= events[0]["latency_ms"] < 2000   # frame sent -> event received

    # The scorer reads the run as it is.
    p2 = load_tool("p2")
    labels = {"fps": 4, "parts": ["end_slat"], "hardware": [LONG, SHORT], "clips": [{
        "id": "d_wrong_hardware", "file": "clips/d_wrong_hardware.mp4", "kind": "mistake", "step": 2, "duration": 2.5,
        "segments": [{"t0": 0, "state": "PART_IN_HAND", "part_in_hand": "hardware_only", "hardware_in_hand": SHORT,
                      "right_part": None, "orientation_ok": None, "panel_on_base": False, "step_complete": False,
                      "next_step_part_used": False, "hands_present": True, "view_blocked": False, "image_poor": False,
                      "frame_matches_setup": True}],
        "events": [{"t": 0.0, "event": "wrong_hardware"}]}]}
    (tmp_path / "labels.json").write_text(json.dumps(labels))
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        p2.cmd_score([str(tmp_path / "labels.json"), str(tmp_path / "run" / "frames.jsonl"),
                      str(tmp_path / "run" / "events.jsonl"), "--json"])
    score = json.loads(out.getvalue())
    assert score["events_hit"] == 1 and score["false_alarms"] == [] and score["frames_without_label"] == 0
    assert score["questions"]["hardware_in_hand"]["accuracy_when_decided"] == 1.0
    assert score["questions"]["hands_present"]["accuracy_when_decided"] == 1.0


def test_position_survives_stop_and_the_last_device_leaving_so_another_device_can_continue(client):
    path = f"/api/perception/{JOB}/position"
    assert client.get(path).status_code == 401                        # behind the demo key, like every /api/ route
    assert client.get(path, params={"key": KEY}).json() == {"job_id": JOB, "step": None, "action": None, "at": None}
    assert client.get("/api/perception/no-such-job/position", params={"key": KEY}).json()["step"] is None
    before = int(time.time() * 1000)
    with client.websocket_connect(url("display")) as ws:              # the phone, with live guidance on
        phone = Peer(ws)
        phone.until("status")
        phone.send(type="start", step=2, action=0)
        phone.until(lambda m: m["type"] == "status" and m["step"] == 2)
        phone.send(type="action", index=1)
        phone.until(lambda m: m["type"] == "status" and m["action"] == 1)
        phone.send(type="stop")                                       # live guidance off: the session forgets the step
        phone.until(lambda m: m["type"] == "status" and m["step"] is None)
    pos = client.get(path, params={"key": KEY}).json()                # the headset asks where to continue
    assert (pos["step"], pos["action"]) == (2, 1) and pos["at"] >= before
    with client.websocket_connect(url("display")) as ws:              # it does not make or change a session by reading
        headset = Peer(ws)
        assert headset.next()["step"] is None
        headset.send(type="step", n=3)
        headset.until(lambda m: m["type"] == "status" and m["step"] == 3)
    assert client.get(path, params={"key": KEY}).json()["step"] == 3 and client.get(path, params={"key": KEY}).json()["action"] is None


def test_clear_no_lets_a_leaning_other_code_warn():
    """Seen on a stand-in photo of a short screw in hand at step 2 (needs 108363): right_hardware 0.05 and the
    choice 108560 at only 0.51. A clear "no" plus a lean to another code must still lead to wrong_hardware."""
    from server.perception import logic as L
    from server.perception.config import Tuning
    from pipeline.perception.logic import Watcher
    pack = {"step": 2, "expects": {"parts": [], "hardware": ["108363"]}}
    w = Watcher(pack)
    coach = L.Coach.__new__(L.Coach)
    coach.expect, coach.t = type("E", (), {"action": None, "last_action": True})(), Tuning()
    raw = {"right_hardware": {"type": "noul", "noul": 0.05, "confidence": 0.95},
           "hands_present": {"type": "noul", "noul": 0.93, "confidence": 0.93},
           "hardware_in_hand": {"type": "choice", "choice": "108560", "confidence": 0.51,
                                "probabilities": {"108560": 0.51, "other": 0.32, "108363": 0.16, "no_hardware": 0.01}}}
    rules = coach.t.rules("fixed")
    out = coach._prefilter(raw, w, rules, False)
    assert out["hardware_in_hand"]["choice"] == "108560" and out["hardware_in_hand"]["confidence"] >= w.cfg.choice
    weak = {**raw, "hardware_in_hand": {**raw["hardware_in_hand"], "confidence": 0.3}}
    assert coach._prefilter(weak, w, rules, False)["hardware_in_hand"]["confidence"] == 0.3     # below the floor
    unsure = {**raw, "right_hardware": {"type": "noul", "noul": 0.4, "confidence": 0.6}}
    assert coach._prefilter(unsure, w, rules, False)["hardware_in_hand"]["confidence"] == 0.51  # no clear "no"


# ---- real headset frames (Sun 15 Nov): the poor line, the dark rule, the close look, kept frames --------------

def test_more_light_is_asked_only_when_the_frames_are_dim():
    """A lit office, the headset put down or turned fast: poor frames, but more light would not help."""
    poor = dict(EMPTY, image_poor=noul(0.7))

    def said(c, luma):
        return [e.event for k in range(40) for e in c.on_frame(poor, k * 0.25, "headset", luma=luma)]

    c = coach(STEP2)
    assert said(c, 100.0) == [] and c.belief()["view"] == "poor"      # measured: real frames had a mean of 82 to 120
    assert said(coach(STEP2), 30.0) == ["more_light"]
    assert said(coach(STEP2), None) == ["more_light"]                 # not measured (no Pillow): as before
    assert said(coach(STEP2, dim_luma=0), 100.0) == ["more_light"]    # switched off: every poor frame counts
    c = coach(STEP2)                                                  # too dark to send: always a light problem
    assert [e.event for k in range(21) for e in c.on_bad_frame(k * 0.25, "dark", "headset")] == ["more_light"]


def test_a_frame_the_model_is_only_half_sure_is_poor_still_counts():
    """Of 924 logged headset frames in which the model saw a hand, 22 % scored `image_poor` 0.3 or more."""
    held = dict(frame("hardware_only", LONG, right_hardware=0.95), image_poor=noul(0.45))
    c = coach(STEP2)
    said, _ = feed(c, [held] * 6)
    assert [name for _, name in said] == ["right_hardware"] and c.belief()["view"] == "ok"
    c = coach(STEP2, poor_at=0.3)                                     # the line before Sun 15 Nov
    assert feed(c, [held] * 6)[0] == [] and c.belief()["view"] == "poor"
    c = coach(STEP2)                                                  # the model is sure: still discarded
    assert feed(c, [dict(held, image_poor=noul(0.56))] * 6)[0] == [] and c.belief()["view"] == "poor"
    c = coach(STEP2, poor_at=0.1)                                     # never below the Watcher's `unusable`
    assert [name for _, name in feed(c, [dict(held, image_poor=noul(0.2))] * 6)[0]] == ["right_hardware"]
    c = coach(STEP2)                                                  # a floor or a desk (about 0.5) is not bad light
    assert feed(c, [dict(EMPTY, image_poor=noul(0.5))] * 40)[0] == [] and c.belief()["view"] == "ok"


def test_a_dark_floor_with_lit_parts_on_it_is_not_a_dark_frame():
    from server.perception import imaging

    def shot(floor: int, board: int | None = None) -> bytes:
        im = Image.new("L", (192, 144), floor)
        if board is not None:
            im.paste(board, (60, 40, 96, 68))                         # 3.6 % of the picture
        out = io.BytesIO()
        im.convert("RGB").save(out, "JPEG")
        return out.getvalue()

    black, floor = imaging.probe(shot(4)), imaging.probe(shot(10, 215))
    assert black[2] < T.dark_luma and floor[2] < T.dark_luma          # both are dark on average
    assert black[3] < T.dark_peak < floor[3]
    assert imaging.too_dark(black, T.dark_luma, T.dark_peak) and not imaging.too_dark(floor, T.dark_luma, T.dark_peak)
    assert imaging.too_dark(floor, T.dark_luma, 0)                    # `dark_peak` 0: the mean alone, as before
    assert not imaging.too_dark(black, 0, T.dark_peak)                # `dark_luma` 0: no check
    assert imaging.probe(b"not a picture") is None


def test_a_close_look_names_hardware_only_on_clear_evidence(tmp_path):
    from server.perception.session import SHOW_PART_QUESTIONS, PerceptionSession

    s = PerceptionSession(JOB, make_job(tmp_path / "jobs"), T, None, logs=False)
    s.step = 2
    s.coach.start_step(STEP2)

    def look(code, conf, empty, right, poor=0.15):
        hw = {"type": "choice", "choice": code, "confidence": conf, "probabilities": {code: conf, "no_hardware": empty}}
        return s._show_part_result({"part_in_hand": choice("hardware_only", 0.9), "hardware_in_hand": hw,
                                    "image_poor": noul(poor), EXTRA: noul(right)}, {"expected": LONG}, (LONG,))

    held = look(LONG, 0.91, 0.03, 0.89)                               # measured: the right screw at 70 cm, `high`
    assert (held.observed_id, held.reason) == (LONG, None)
    assert held.evidence["no_hardware"] == 0.03 and held.evidence[EXTRA] == 0.89 and held.evidence["image_poor"] == 0.15
    empty = look(LONG, 0.77, 0.18, 0.57)                              # measured: an empty pinching hand, `high`
    assert (empty.observed_id, empty.reason) == (None, "hand_may_be_empty")
    assert look(LONG, 0.8, 0.05, 0.4).reason == "not_confirmed"       # the yes/no does not say "the right one"
    assert look(SHORT, 0.89, 0.02, 0.05).observed_id == SHORT         # another code, and the yes/no agrees it is wrong
    assert look(SHORT, 0.8, 0.02, 0.9).reason == "not_confirmed"      # another code, but the yes/no says "right"
    assert look("no_hardware", 0.8, 0.8, 0.1).reason == "not_recognised"
    assert look(SHORT, 0.74, 0.02, 0.05).observed_id == SHORT         # measured: leaning to the wrong code, a sure "no"
    assert look(SHORT, 0.74, 0.02, 0.4).reason == "not_recognised"    # ...but not without that "no"
    assert look(SHORT, 0.3, 0.02, 0.05).reason == "not_recognised"    # ...nor on a weak lean
    assert look(SHORT, 0.74, 0.2, 0.05).reason == "not_recognised"    # ...nor when the hand may be empty
    assert look(LONG, 0.95, 0.01, 0.9, poor=0.45).observed_id == LONG  # a half-sure "poor" no longer loses the look
    assert look(LONG, 0.95, 0.01, 0.9, poor=0.7).reason == "image_poor"
    plain = s._show_part_result({"part_in_hand": choice("hardware_only", 0.9), "hardware_in_hand": choice(SHORT, 0.9),
                                 "image_poor": noul(0.1)}, {"expected": LONG})
    assert plain.observed_id == SHORT and EXTRA not in plain.evidence  # answers without the yes/no still work

    guide = Guide(s.dir)
    ask = build_ask(guide, load_pack(guide, 2), jpeg(), codes={LONG}, resolution="high", only=SHOW_PART_QUESTIONS)
    assert set(ask.questions) == {"part_in_hand", "hardware_in_hand", "image_poor", EXTRA}


def test_a_job_can_say_what_its_real_parts_look_like(tmp_path, guide):
    """The reference pictures are rendered in one plain colour; the real MALM front is wood-coloured."""
    plain = build_ask(guide, load_pack(guide, 2), jpeg(), codes={LONG})
    assert not any(isinstance(b, str) and b.startswith("Note:") for b in plain.inputs)
    note = "Note: the real   end slats are oak-coloured;\nthe reference pictures show them white."
    told = Guide(make_job(tmp_path / "told", manifest={**MANIFEST, "perception_note": note}))
    for only in (None, ("part_in_hand", "hardware_in_hand", "image_poor")):
        ask = build_ask(told, load_pack(told, 2), jpeg(), codes={LONG}, only=only)
        assert ask.inputs[-1] == "Note: the real end slats are oak-coloured; the reference pictures show them white."
        assert ask.inputs[:-1] == build_ask(guide, load_pack(guide, 2), jpeg(), codes={LONG}, only=only).inputs
    empty = Guide(make_job(tmp_path / "empty", manifest={**MANIFEST, "perception_note": "  "}))
    assert build_ask(empty, load_pack(empty, 2), jpeg(), codes={LONG}).inputs == plain.inputs


def test_frames_are_kept_only_when_asked_and_only_up_to_the_cap(tmp_path):
    from server.perception.session import FrameKeeper

    folder = tmp_path / "jobs" / "_host" / "frames"
    off = FrameKeeper(folder, 0)
    assert not off.on and not off.keep("s", 1, "used", jpeg()) and not folder.exists()
    assert Settings(_env_file=None).perception_keep_frames == 0       # off unless PERCEPTION_KEEP_FRAMES says so
    k = FrameKeeper(folder, 2)
    assert k.keep("s", 1, "used", jpeg()) and k.keep("s", 2, "poor", jpeg()) and not k.keep("s", 3, "used", jpeg())
    assert sorted(p.name for p in (folder / "s").iterdir()) == ["000001-used.jpg", "000002-poor.jpg"]
    assert not FrameKeeper(folder, 2).on                              # what is on disk counts after a restart
    assert not FrameKeeper(tmp_path / "other", 100, megabytes=0).keep("s", 1, "used", jpeg())

    with app_client(tmp_path / "app", perception_keep_frames=2) as c, c.websocket_connect(url("camera")) as ws:
        cam = Peer(ws)
        cam.until("status")
        cam.send(type="start", step=2, action=0)
        cam.frame(1, jpeg(grey=4))
        assert cam.until("ack")["dropped"] == "dark"
        for seq in (2, 3, 4):                                         # within the same second: not kept
            assert cam.frame_ack(seq)["dropped"] is None
        cam.send(type="show_part", expected=LONG)                     # a close look is always kept
        assert cam.frame_ack(5)["dropped"] is None
        cam.find(lambda m: m["type"] == "event" and m["event"] == "show_part_result")
        cam.send(type="show_part", expected=LONG)                     # the cap is reached: nothing more
        assert cam.frame_ack(6)["dropped"] is None
        cam.until(lambda m: m["type"] == "event" and m["event"] == "show_part_result" and m["seq"] == 6)
        kept = sorted(p.name for p in (tmp_path / "app" / "jobs" / "_host" / "frames").glob("*/*.jpg"))
    assert kept == ["000001-dark.jpg", "000005-show.jpg"]
