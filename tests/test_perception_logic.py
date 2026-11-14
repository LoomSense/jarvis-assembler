"""Decision logic on made-up answer streams (LIV-03). No network."""
from pipeline.perception.logic import Config, Watcher

PACK = {"step": 5, "expects": {"parts": ["end_slat"], "hardware": ["108560"]}}


def frame(hands=0.95, held="end_slat", conf=0.9, right=0.95, orient=0.9, done=0.05, hw="no_hardware", ahead=0.05, poor=0.05, blocked=0.05):
    n = lambda v: {"type": "noul", "noul": v}
    return {"hands_present": n(hands), "part_in_hand": {"type": "choice", "choice": held, "confidence": conf}, "right_part": n(right),
            "orientation_ok": n(orient), "step_complete": n(done), "hardware_in_hand": {"type": "choice", "choice": hw, "confidence": 0.9},
            "next_step_part_used": n(ahead), "image_poor": n(poor), "view_blocked": n(blocked)}


def run(frames, dt=0.5, cfg=None):
    w, out = Watcher(PACK, guide_page=5, cfg=cfg), []
    for k, f in enumerate(frames):
        out += w.feed(f, k * dt)
    return w, out


def types(events):
    return [e["type"] for e in events]


def test_right_part_gives_no_warning():
    w, ev = run([frame()] * 20)
    assert ev == [] and w.state == "PART_IN_HAND"


def test_wrong_part_fires_once_after_four_frames():
    wrong = frame(held="side_panel", right=0.05)
    w, ev = run([frame(hands=0.05)] * 3 + [wrong] * 12)
    assert types(ev) == ["wrong_part"]
    assert ev[0]["ts"] == 3.0 and ev[0]["observed"] == "side_panel" and ev[0]["expected"] == ["end_slat"] and ev[0]["guide_page"] == 5


def test_one_bad_frame_is_not_enough():
    _, ev = run([frame(), frame(held="side_panel", right=0.05), frame(), frame(held="side_panel", right=0.05), frame()] * 3)
    assert ev == []


def test_part_named_without_hands_is_ignored():
    _, ev = run([frame(hands=0.05, held="side_panel", right=0.05)] * 20)
    assert ev == []


def test_wrong_part_again_only_after_recovery_and_cooldown():
    wrong, good = frame(held="side_panel", right=0.05), frame()
    _, ev = run([wrong] * 10 + [good] * 6 + [wrong] * 10)             # 13 s: second mistake inside the 20 s cooldown
    assert types(ev) == ["wrong_part"]
    _, ev = run([wrong] * 10 + [good] * 40 + [wrong] * 10)            # second mistake at 25 s
    assert types(ev) == ["wrong_part", "wrong_part"]
    _, ev = run([wrong] * 80)                                         # never recovered: said once
    assert types(ev) == ["wrong_part"]


def test_global_cooldown_delays_a_second_kind_of_warning():
    _, ev = run([frame(held="side_panel", right=0.05)] * 5 + [frame(hw="108363")] * 30)
    assert types(ev) == ["wrong_part", "wrong_hardware"]
    assert ev[1]["ts"] - ev[0]["ts"] >= 6.0 and ev[1]["expected"] == ["108560"]


def test_flip_needed():
    _, ev = run([frame(orient=0.05)] * 8)
    assert types(ev) == ["flip_needed"] and ev[0]["ts"] == 2.0


def test_step_complete_waits_for_hands_to_leave():
    w, ev = run([frame(done=0.95)] * 12)
    assert ev == []
    w, ev = run([frame(done=0.95)] * 12 + [frame(hands=0.05, done=0.95)] * 6)
    assert types(ev) == ["step_complete"] and w.state == "STEP_COMPLETE"
    assert ev[0]["ts"] == 5.5 + 2.0


def test_step_complete_is_not_held_back_by_a_warning():
    _, ev = run([frame(held="side_panel", right=0.05)] * 5 + [frame(done=0.95)] * 4 + [frame(hands=0.05, done=0.95)] * 8)
    assert types(ev) == ["wrong_part", "step_complete"]


def test_poor_frames_are_skipped():
    w, ev = run([frame(held="side_panel", right=0.05, poor=0.6)] * 20)
    assert ev == [] and w.skipped == 20


def test_unsure_asks_to_show_the_part():
    _, ev = run([frame(held="other", conf=0.9, right=0.5)] * 10)
    assert types(ev) == ["unsure_show_part"]


def test_thresholds_can_be_changed():
    _, ev = run([frame(orient=0.3)] * 8, cfg=Config(no=0.35))
    assert types(ev) == ["flip_needed"]


def test_step_complete_uses_its_own_lower_threshold():
    _, ev = run([frame(hands=0.05, done=0.5)] * 14)
    assert types(ev) == ["step_complete"]
    _, ev = run([frame(hands=0.05, done=0.4)] * 14)
    assert ev == []


def test_step_complete_does_not_count_when_the_assembly_is_not_turned():
    _, ev = run([frame(hands=0.05, done=0.7, orient=0.3)] * 14)
    assert ev == []
