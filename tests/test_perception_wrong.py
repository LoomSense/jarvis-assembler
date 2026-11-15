"""A wrong part in the hand (Sun 15 Nov, after the real drawer frames): the choices a wrong pick needs,
and the sum over parts of other steps. No model is called."""
from __future__ import annotations

import json

from pipeline.perception.logic import SPECIAL
from server.perception.guide import Guide
from server.perception.logic import Expect
from server.perception.questions import NOT_PICTURED, build_ask, complete_choices, load_pack
from tests.test_perception import coach, feed, frame, jpeg, make_job, noul

FRONT = Expect(step=1, page=2, parts=frozenset({"drawer_front"}), action=0, action_kind="place",
               action_id="drawer_front", last_action=True, next_step=2, next_page=3)
LEFT = Expect(step=3, page=4, parts=frozenset({"drawer_side_left"}), action=0, action_kind="place",
              action_id="drawer_side_left", last_action=False, next_step=4, next_page=5)


def held(probs: dict, right_part: float | None = 0.1, hands: float = 0.95) -> dict:
    """A frame whose `part_in_hand` answer has these probabilities (the first one is the choice)."""
    first = next(iter(probs))
    answers = frame(first, p=probs[first], hands=hands)
    answers["part_in_hand"] = {"type": "choice", "choice": first, "confidence": probs[first], "probabilities": dict(probs)}
    if right_part is None:
        answers.pop("right_part", None)
    else:
        answers["right_part"] = noul(right_part)
    return answers


def wrong(c, frames) -> list:
    """The `wrong_part` events of these frames (a part held but not recognised also gives `unsure_show_part`)."""
    return [name for _, name in feed(c, frames)[0] if name == "wrong_part"]


TWINS = {"drawer_side_left": 0.51, "drawer_side_right": 0.38, "other": 0.06, "drawer_front": 0.05}   # as measured


def test_mirror_twins_share_one_wrong_part():
    said, events = feed(coach(FRONT), [held(TWINS, right_part=0.14)] * 6)
    assert said == [(0.75, "wrong_part")]                             # the 4th frame of the Watcher's 4 of 5
    e = events[0]
    assert (e.observed_id, e.expected_id) == ("drawer_side_left", "drawer_front")
    assert e.evidence["right_part"] == 0.14 and e.evidence["part_in_hand.other_steps"] == 0.89


def test_the_sum_needs_right_part_to_say_no():
    assert not wrong(coach(FRONT), [held(TWINS, right_part=0.35)] * 20)              # above wrong_part_no (0.3)
    assert feed(coach(FRONT), [held(TWINS, right_part=0.27)] * 5)[0] == [(0.75, "wrong_part")]
    # a step without parts has no right_part question: the sum alone decides, as the single choice did
    bolts = Expect(step=2, page=3, hardware=frozenset({"BOLT-C"}), action=0, action_kind="fasten", action_id="BOLT-C")
    assert feed(coach(bolts), [held(TWINS, right_part=None)] * 5)[0] == [(0.75, "wrong_part")]


def test_the_sum_is_not_reached_by_a_weak_or_unsure_answer():
    weak = {"drawer_bottom": 0.66, "drawer_side_left": 0.17, "other": 0.1, "nothing_held": 0.07}   # a real frame: 0.70 in all
    assert not wrong(coach(LEFT), [held(weak, right_part=0.1)] * 20)                 # step 3: the left side is its own part
    unsure = {"other": 0.5, "drawer_side_left": 0.3, "drawer_side_right": 0.2}       # the first choice is not a part
    assert not wrong(coach(FRONT), [held(unsure, right_part=0.1)] * 20)
    assert not wrong(coach(FRONT), [held(TWINS, right_part=0.1, hands=0.2)] * 20)    # nobody's hands in view


def test_the_twin_of_the_part_needed_is_not_called_wrong():
    """Left is needed, the model leans to right: the two are told apart badly, so nothing is said."""
    lean = {"drawer_side_right": 0.5, "drawer_side_left": 0.45, "other": 0.05}
    assert not wrong(coach(LEFT), [held(lean, right_part=0.2)] * 20)
    sure = {"drawer_bottom": 0.73, "other": 0.15, "drawer_side_left": 0.08, "drawer_back": 0.04}   # a real frame
    said, events = feed(coach(LEFT), [held(sure, right_part=0.27)] * 5)
    assert said == [(0.75, "wrong_part")] and events[0].observed_id == "drawer_bottom"


def test_the_sum_can_be_switched_off():
    c = coach(FRONT, wrong_part_sum=False)
    assert not wrong(c, [held(TWINS, right_part=0.05)] * 20)                         # the Watcher's rule alone
    c = coach(FRONT, wrong_part_no=0.15)
    assert not wrong(c, [held(TWINS, right_part=0.2)] * 20)


def test_choices_gain_the_parts_of_later_steps_and_all_hardware(tmp_path):
    guide = Guide(make_job(tmp_path))
    codes = {c for n in guide.order for c, _ in guide.hardware(n)}
    for n in guide.order:
        file = json.loads((guide.dir / "refs" / f"step-{n:02d}-questions.json").read_text())
        plain, full = load_pack(guide, n, complete=False), load_pack(guide, n)
        assert plain.pack["questions"]["part_in_hand"] == file["questions"]["part_in_hand"]      # nothing added
        later = {p for m in guide.order if m > n for p in guide.parts(m)}
        earlier = {p for m in guide.order if m < n for p in guide.parts(m)} - later - set(guide.parts(n))
        for key, want in (("part_in_hand", later), ("hardware_in_hand", codes)):
            before, after = file["questions"][key]["criteria"], full.pack["questions"][key]["criteria"]
            assert want <= set(after) and set(before) <= set(after)
            added = [k for k in after if k not in before]
            assert set(added) <= want and all(NOT_PICTURED in after[k] for k in added)
            assert [k for k in after if k in SPECIAL] == list(after)[-len([k for k in after if k in SPECIAL]):]   # last
            assert all(after[k] == before[k] for k in before)                                     # untouched
            if added:
                assert full.pack["questions"][key]["instructions"].startswith(file["questions"][key]["instructions"])
        assert not earlier & (set(full.pack["questions"]["part_in_hand"]["criteria"]) - set(file["questions"]["part_in_hand"]["criteria"]))
        assert json.loads((guide.dir / "refs" / f"step-{n:02d}-questions.json").read_text()) == file   # file not written
        assert set(build_ask(guide, full, jpeg()).questions["part_in_hand"]["criteria"]) >= later


def test_a_complete_pack_is_left_alone(tmp_path):
    guide = Guide(make_job(tmp_path))
    last = guide.order[-1]
    pack = load_pack(guide, last, complete=False).pack
    crit = pack["questions"]["hardware_in_hand"]["criteria"]
    full = {**pack, "questions": {**pack["questions"], "hardware_in_hand": {
        **pack["questions"]["hardware_in_hand"],
        "criteria": {**{c: "x" for n in guide.order for c, _ in guide.hardware(n)}, **crit}}}}
    assert complete_choices(guide, last, full) is full                # the last step has no later part
