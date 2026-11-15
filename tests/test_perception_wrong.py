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
    weak = {"drawer_bottom": 0.6, "drawer_side_left": 0.25, "other": 0.15}           # under wrong_part_sum_min (0.65)
    assert not wrong(coach(LEFT), [held(weak, right_part=0.1)] * 20)
    unsure = {"other": 0.5, "drawer_side_left": 0.3, "drawer_side_right": 0.2}       # the first choice is not a part
    assert not wrong(coach(FRONT), [held(unsure, right_part=0.1)] * 20)
    assert not wrong(coach(FRONT), [held(TWINS, right_part=0.1, hands=0.2)] * 20)    # nobody's hands in view
    bolts = Expect(step=2, page=3, hardware=frozenset({"BOLT-C"}), action=0, action_kind="fasten", action_id="BOLT-C")
    some = {"drawer_side_left": 0.4, "drawer_side_right": 0.3, "other": 0.3}         # no right_part to agree: 0.75 it is
    assert not wrong(coach(bolts), [held(some, right_part=None)] * 20)


def test_the_bottom_held_up_while_the_side_is_needed_as_on_the_real_frames():
    """Six kept frames of 14:14 (Sun 15 Nov), step 3: `drawer_bottom`, the sum, `right_part` as the model gave them."""
    real = [(0.78, 0.09), (0.66, 0.23), (0.73, 0.27), (0.70, 0.20), (0.84, 0.08), (0.73, 0.11)]
    frames = [held({"drawer_bottom": b, "drawer_side_left": round(0.9 - b, 2), "other": 0.06, "drawer_back": 0.02,
                    "drawer_side_right": 0.02}, right_part=r) for b, r in real]
    said, events = feed(coach(LEFT), frames)
    assert said == [(0.75, "wrong_part")]                             # the 4th frame; none of it was said before
    assert (events[0].observed_id, events[0].expected_id) == ("drawer_bottom", "drawer_side_left")
    assert not wrong(coach(LEFT, wrong_part_sum=False), frames)       # the Watcher alone: 2 of the 6 vote


def test_the_twin_of_the_part_needed_is_left_to_the_watchers_own_rule():
    """Left is needed and the model says right: the two are told apart badly, so nothing here is relaxed."""
    lean = {"drawer_side_right": 0.5, "drawer_side_left": 0.45, "other": 0.05}
    assert not wrong(coach(LEFT), [held(lean, right_part=0.2)] * 20)
    surer = {"drawer_side_right": 0.8, "drawer_side_left": 0.15, "other": 0.05}
    assert not wrong(coach(LEFT), [held(surer, right_part=0.2)] * 20)                # 0.15 stays the line for a twin
    assert wrong(coach(LEFT), [held(surer, right_part=0.05)] * 5) == ["wrong_part"]  # as before this change
    with_twin = {"drawer_bottom": 0.5, "drawer_side_right": 0.3, "drawer_side_left": 0.2}    # the twin is not in the sum
    assert not wrong(coach(LEFT), [held(with_twin, right_part=0.1)] * 20)


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


# ---- which way round is wrong: the optional `wrong_way` choice of a step ------------------------------

def way(choice: str, conf: float, **kw) -> dict:
    """A frame with nobody's hands in it and the step's `wrong_way` answer."""
    answers = frame(**kw)
    rest = round(1 - conf, 3)
    answers["wrong_way"] = {"type": "choice", "choice": choice, "confidence": conf,
                            "probabilities": {choice: conf, ("ok" if choice != "ok" else "cannot_tell"): rest}}
    return answers


def flips(c, frames, **kw) -> list:
    said, events = feed(c, frames, **kw)
    return [(t, e.reason) for (t, name), e in zip(said, events) if name == "flip_needed"]


def test_wrong_way_names_the_mistake_once_and_again_only_after_it_was_put_right():
    c = coach(FRONT)
    assert flips(c, [way("front_outer_up", 0.76)] * 3) == []                         # 3 frames are not 4 of 5
    said, events = feed(c, [way("front_outer_up", 0.74)] * 30, t0=0.75)
    assert said == [(0.75, "flip_needed")]                                           # once, however long it lies there
    e = events[0]
    assert (e.reason, e.p, e.votes) == ("front_outer_up", 0.74, "4 of last 4 frames")
    assert e.evidence == {"wrong_way.front_outer_up": 0.74, "orientation_ok": 0.9}
    assert flips(c, [way("front_outer_up", 0.9)] * 8, t0=40.0) == []                 # still the same mistake
    assert flips(c, [way("ok", 0.9)] * 5 + [way("front_outer_up", 0.9)] * 4, t0=50.0) == [(52.0, "front_outer_up")]


def test_wrong_way_needs_a_sure_answer_and_the_assembly_in_view():
    assert flips(coach(FRONT), [way("front_outer_up", 0.58)] * 20) == []             # a real frame, the front lying right
    assert flips(coach(FRONT), [way("cannot_tell", 0.95)] * 20) == []
    assert flips(coach(FRONT), [way("ok", 0.93)] * 20) == []
    blocked = way("front_outer_up", 0.9, view_blocked=0.5)
    assert flips(coach(FRONT), [blocked] * 20, source="headset") == []               # a headset keeps the hand answers only
    assert flips(coach(FRONT), [blocked] * 20) == []                                 # a fixed camera drops the frame
    mixed = [way("front_outer_up", 0.9), way("side_rail_in", 0.9)] * 10              # no mistake has 4 of 5
    assert flips(coach(FRONT), mixed) == []
    assert flips(coach(FRONT, wrong_way=False), [way("front_outer_up", 0.9)] * 20) == []
    assert flips(coach(FRONT, wrong_way_choice=0.8), [way("front_outer_up", 0.76)] * 20) == []
    assert flips(coach(FRONT, wrong_way_window=[2, 2]), [way("front_outer_up", 0.76)] * 3) == [(0.25, "front_outer_up")]


def test_a_step_without_the_question_is_as_before():
    said, events = feed(coach(FRONT), [frame(orientation_ok=0.05)] * 12)             # the Watcher's own flip_needed
    assert [name for _, name in said] == ["flip_needed"] and events[0].reason is None


def test_flip_needed_carries_the_reason_and_the_step_files_own_sentence(tmp_path):
    from tests.test_perception import JOB, Peer, app_client, url
    with app_client(tmp_path) as c:
        path = tmp_path / "jobs" / JOB / "refs" / "step-02-questions.json"
        pack = json.loads(path.read_text())
        pack["questions"]["wrong_way"] = {"type": "choice", "instructions": "Which of these describes the base?", "criteria": {
            "base_face_down": "The base lies with its plain face up", "ok": "The base lies with its holes up",
            "cannot_tell": "The base is not in view"}}
        pack["wrong_way_text"] = {"base_face_down": {"en": "Turn the base over.", "el": "Γύρισε τη βάση ανάποδα."}}
        path.write_text(json.dumps(pack, ensure_ascii=False))
        with c.websocket_connect(url("camera")) as ws:
            cam = Peer(ws)
            cam.send(type="start", step=2)
            cam.send(type="sim", orientation_ok=False)                # the simulated model then names the first mistake
            cam.until("sim")
            for seq in range(1, 5):
                assert cam.frame_ack(seq, step=2)["dropped"] is None
            event = cam.find("event")
            assert (event["event"], event["reason"], event["step"]) == ("flip_needed", "base_face_down", 2)
            assert event["say"] == {"en": "Turn the base over.", "el": "Γύρισε τη βάση ανάποδα."}
            assert event["evidence"]["wrong_way.base_face_down"] == 0.93 and event["votes"] == "4 of last 4 frames"
            cam.send(type="sim", orientation_ok=True)
            cam.until("sim")
            for seq in range(5, 12):
                cam.frame_ack(seq, step=2)
            assert [m["event"] for m in cam.seen if m["type"] == "event"] == ["flip_needed"]     # said once


# ---- the second, third and fourth bolt are confirmed like the first ----------------------------------

BOLTS = Expect(step=2, page=3, hardware=frozenset({"BOLT-C"}), times=(("BOLT-C", 4),), next_step=3, next_page=4)
BOLT = frame("hardware_only", "BOLT-C", right_hardware=0.95)
DRIVING = frame("hardware_only", "no_hardware", hands=0.95)           # the screwdriver, or the hand on the board


def confirmed(c, frames, **kw) -> list:
    return [t for t, name in feed(c, frames, **kw)[0] if name == "right_hardware"]


def test_each_of_the_four_bolts_is_confirmed_and_a_fifth_is_not():
    c = coach(BOLTS)
    assert confirmed(c, [BOLT] * 4) == [0.5]                          # 3 of 4, as before
    assert confirmed(c, [DRIVING] * 20, t0=1.0) == []
    assert confirmed(c, [BOLT] * 4, t0=7.0) == [7.5]                  # the next one, 7 s after the first: not 20
    for n, t0 in ((3, 14.0), (4, 21.0)):
        feed(c, [DRIVING] * 20, t0=t0 - 6.0)
        assert confirmed(c, [BOLT] * 4, t0=t0) == [t0 + 0.5], n
    feed(c, [DRIVING] * 20, t0=22.0)
    assert confirmed(c, [BOLT] * 8, t0=28.0) == []                    # the step uses 4


def test_the_same_bolt_is_not_confirmed_twice():
    c = coach(BOLTS)
    assert confirmed(c, [BOLT] * 4) == [0.5]
    assert confirmed(c, [DRIVING] * 12 + [BOLT] * 40, t0=1.0) == []   # out of sight for 3 s, then held for 10 s
    c = coach(BOLTS)
    feed(c, [BOLT] * 4)
    assert confirmed(c, [DRIVING] * 3 + [BOLT] * 40, t0=6.0) == []    # 5 s later but only 3 frames without it
    c = coach(BOLTS, reconfirm=False)
    feed(c, [BOLT] * 4)
    assert confirmed(c, [DRIVING] * 20 + [BOLT] * 100, t0=1.0) == []  # as it was: once per step


def test_the_next_bolt_waits_for_the_gap_between_two_utterances():
    c = coach(BOLTS)
    feed(c, [BOLT] * 4)                                               # said at 0.5
    assert confirmed(c, [DRIVING] * 17 + [BOLT] * 8, t0=1.0) == [6.5]   # re-armed at 4.75, free 6 s after 0.5


def test_a_new_action_index_re_arms_at_once_and_a_part_used_once_stays_said_once():
    step = lambda k: Expect(step=2, page=3, hardware=frozenset({"BOLT-C"}), times=(("BOLT-C", 4),), action=k,     # noqa: E731
                            action_kind="fasten", action_id="BOLT-C", last_action=k == 3)
    c = coach(step(0))
    assert confirmed(c, [BOLT] * 4) == [0.5]
    c.set_action(step(0))                                             # the same action again: nothing changes
    assert confirmed(c, [BOLT] * 24, t0=1.0) == []
    c.set_action(step(1))                                             # the display moved on to the second bolt
    assert confirmed(c, [BOLT] * 4, t0=7.0) == [7.5]
    c = coach(FRONT)                                                  # one front: its confirmation is not repeated
    front = frame("drawer_front", right_part=0.9)
    said, _ = feed(c, [front] * 4 + [frame()] * 40 + [front] * 20)
    assert [name for _, name in said if name == "right_part"] == ["right_part"]


def test_the_guide_says_how_many_of_each_item_a_step_uses(tmp_path):
    guide = Guide(make_job(tmp_path))
    for n in guide.order:
        e = guide.expect(n)
        for code, qty in guide.hardware(n):
            assert e.uses(code) == (qty if isinstance(qty, int) and qty > 1 else 1)
        assert all(e.uses(part) >= 1 for part in guide.parts(n)) and e.uses("not_in_this_step") == 1
