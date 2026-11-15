"""From one frame's answers to guidance events. Pure: no I/O, no clock.

The plan's events (wrong_part, wrong_hardware, flip_needed, out_of_order, step_complete, unsure_show_part)
are decided by `pipeline.perception.logic.Watcher`, with the thresholds, N-of-M windows, hands gating and
cooldowns that were measured on real Decisions API answers and are tuned there. None of that is repeated
here. `Coach` keeps one Watcher per step and camera source, feeds it every frame's answers as the API
returned them, and adds only what the Watcher has no notion of:

    the current action   the display says which `place` or `fasten` is current, so the hardware that is right
                         now is that action's code, and step_complete waits for the last action
    confirmations        right_part / right_hardware, said once
    right_hardware       a yes/no question added to the request; its clear answer overrules the choice answer
    the camera source    a head-mounted camera keeps the hand answers of a frame whose view of the assembly
                         is blocked, and uses wider windows for the questions about the assembly
    the hand summary     "a hand was at the target and has left it" supports step_complete
    mute                 "I know": no more of that event on this step
    more_light           bad frames for 5 s
    status               what is believed to be in the hand, for a UI

For a fixed camera with no action reported and confirmations off, `Coach.on_frame` returns exactly the
Watcher's events (`tests/test_perception.py` holds it to that), so thresholds tuned offline with
`pipeline.perception.replay --cached` are the ones that run live.

Every method takes its time in seconds from any monotonic clock, so a test can replay synthetic answers.
"""
from __future__ import annotations

import re
from collections import Counter, deque
from dataclasses import dataclass, field

from pipeline.perception.logic import SPECIAL, Watcher, p as yes_p, pick
from server.perception.config import NofM, Tuning

WAITING, IN_HAND, PLACING, COMPLETE = "WAITING_FOR_PART", "PART_IN_HAND", "PLACING", "STEP_COMPLETE"
NOTHING = "none"                       # latch value: confidently nothing held

# Events. The first seven are PLAN §4.5; the rest are additions.
PLAN_EVENTS = ("wrong_part", "flip_needed", "wrong_hardware", "out_of_order", "step_complete",
               "unsure_show_part", "vision_paused")
ADDED_EVENTS = ("right_part", "right_hardware", "more_light", "realign_camera", "vision_resumed",
                "show_part_result", "right_way")
WARNINGS = ("wrong_hardware", "wrong_part", "flip_needed", "out_of_order")
NEVER_SUPPRESSED = ("step_complete", "vision_paused", "vision_resumed", "show_part_result")
SILENT = ("vision_paused", "vision_resumed")     # a badge on the screen (§6.5), not speech
RIGHT_HARDWARE = "right_hardware"                # the question this layer adds to the request
SCENE_QUESTIONS = ("step_complete", "next_step_part_used", "orientation_ok")   # about the assembly, not the hand
WRONG_WAY = "wrong_way"                          # optional choice question of a step: WHICH way round is wrong
HARDWARE_COLOUR = "hardware_colour"              # optional question (`Tuning.hardware_colour`): colour before code
WAY_FINE = ("ok", "cannot_tell", "other")        # its answers that name no mistake
REFUSALS = ("auth", "billing")                   # the API will not answer at all: do not keep asking


_SIDE = re.compile(r"(?:^|(?<=[_\-\s]))(?:left|right|l|r)(?=$|[_\-\s])", re.I)


def _unsided(part: str) -> str:
    """A part id without its left/right word: mirror twins give the same string."""
    return _SIDE.sub("", str(part))


def other_steps_sum(probs: dict, parts) -> float:
    """The probability that a part of another step is held: every part choice that is neither one of this
    step's `parts` nor the mirror twin of one (a twin is as likely the right part, read wrongly)."""
    twins = {_unsided(part) for part in parts}
    return min(1.0, sum(float(v) for k, v in probs.items() if isinstance(v, (int, float))
                        and k not in SPECIAL and k not in parts and _unsided(k) not in twins))


@dataclass(frozen=True)
class Expect:
    """What the current step and action ask for. Built from the manifest by `guide.Guide.expect`."""
    step: int
    page: int | None = None
    parts: frozenset = frozenset()          # part ids this step adds (pickParts)
    hardware: frozenset = frozenset()       # hardware codes this step uses
    action: int | None = None               # index in the step's actions; None: the display did not say
    action_kind: str | None = None          # place | fasten | turn
    action_id: str | None = None            # the part id (place) or the hardware code (fasten)
    last_action: bool = True
    last_step: bool = False
    next_step: int | None = None
    next_page: int | None = None
    times: tuple = ()                       # (id, n) for what this step uses more than once: 4 cam bolts, 2 dowels
    colours: tuple = ()                     # (code, "dark" | "grey" | "light") from the manifest, for `hardware_colour`

    def uses(self, item: str) -> int:
        """How many of this part or hardware code the step uses."""
        return dict(self.times).get(item, 1)

    @property
    def now_kind(self) -> str | None:
        """What should be in the hand right now: `part`, `hardware`, or None when the display did not say."""
        return {"place": "part", "fasten": "hardware"}.get(self.action_kind or "")

    @property
    def ok_hardware(self) -> frozenset:
        """Hardware that is fine to hold now. Any other code on the sheet gets a correction."""
        if self.action_kind == "fasten" and self.action_id:
            return frozenset({self.action_id})
        return self.hardware

    @property
    def confirm_hardware(self) -> frozenset:
        """Hardware whose pick-up is confirmed aloud."""
        if self.action is None:
            return self.hardware
        return frozenset({self.action_id}) if self.action_kind == "fasten" and self.action_id else frozenset()

    @property
    def confirm_parts(self) -> frozenset:
        if self.action is None:
            return self.parts
        return frozenset({self.action_id}) if self.action_kind == "place" and self.action_id else frozenset()

    def needed(self, kind: str) -> str | None:
        """The one id to name as "needed" in a correction. None when the step needs none of that kind."""
        if kind == "hardware":
            pool = self.ok_hardware
        else:
            pool = frozenset({self.action_id}) if self.action_kind == "place" and self.action_id else self.parts
        return sorted(pool)[0] if pool else None


@dataclass
class Emitted:
    """One event for the session to phrase and broadcast."""
    event: str
    step: int | None
    page: int | None = None
    expected_id: str | None = None
    observed_id: str | None = None
    evidence: dict = field(default_factory=dict)
    p: float | None = None
    repeat: int = 0                         # how many times this event was already said on this step
    reason: str | None = None
    votes: str | None = None                # the Watcher's own words, e.g. "4 of last 5 frames"


class Window:
    """The last usable frames' votes for one event of this layer. A vote is None (does not agree) or a
    payload (agrees; the payload is what was seen, so "agreeing" also means agreeing on the same thing)."""

    def __init__(self, size: int = 16):
        self.votes: deque = deque(maxlen=size)

    def push(self, now: float, payload) -> None:
        self.votes.append((now, payload))

    def clear(self) -> None:
        self.votes.clear()

    def agreed(self, need: NofM, now: float, max_age: float):
        """The payload that at least `n` of the last `m` fresh votes share, or None."""
        recent = [p for ts, p in list(self.votes)[-need.m:] if now - ts <= max_age]
        counts = Counter(p for p in recent if p is not None)
        if not counts:
            return None
        payload, n = counts.most_common(1)[0]
        return payload if n >= need.n else None


class Latch:
    """A belief, for the status message, that changes only when enough recent frames confidently say
    something else. Unknown frames never change it: a lack of evidence is not opposite evidence."""

    def __init__(self, enter: NofM, release: NofM):
        self.enter, self.release = enter, release
        self.value: str | None = None       # None: no belief yet
        self.confidence = 0.0
        self.window = Window()

    def observe(self, now: float, value: str | None, confidence: float, max_age: float) -> bool:
        """Feed one usable frame. Returns True when the belief changed."""
        self.window.push(now, value)
        if value is None:
            return False
        if value == self.value:
            self.confidence = confidence
            return False
        need = self.release if value == NOTHING else self.enter      # putting down needs more evidence
        if self.window.agreed(need, now, max_age) != value:
            return False
        self.value, self.confidence = value, confidence
        self.window.clear()
        self.window.push(now, value)
        return True


class Hands:
    """The headset's hand summary (PLAN §6.0): `{type:"hands", seq, hand_at_target, still, raised, ts}`,
    plus the optional `present` (false when no hand is tracked)."""

    def __init__(self, tuning: Tuning):
        self.t = tuning
        self.last_at: float | None = None
        self.at_target = self.still = self.raised = False
        self.present = True
        self.absent_since: float | None = None
        self.seen_present_at: float | None = None
        self.target_reached_at: float | None = None     # in this action
        self.target_left_at: float | None = None
        self._arrivals: deque = deque(maxlen=30)

    def update(self, msg: dict, now: float) -> None:
        self.last_at = now
        self._arrivals.append(now)
        present = msg.get("present", True) is not False
        at_target = bool(msg.get("hand_at_target")) and present
        if at_target:
            self.target_reached_at, self.target_left_at = now, None
        elif self.at_target:
            self.target_left_at = now
        if present:
            self.seen_present_at, self.absent_since = now, None
        elif self.absent_since is None:
            self.absent_since = now
        self.present, self.at_target = present, at_target
        self.still, self.raised = bool(msg.get("still")) and present, bool(msg.get("raised")) and present

    def fresh(self, now: float) -> bool:
        return self.last_at is not None and now - self.last_at <= self.t.hands_fresh_s

    def new_action(self) -> None:
        self.target_reached_at = self.target_left_at = None

    def placed_then_away(self, now: float) -> bool:
        """A hand was at the target in this action and has been away from it for a moment."""
        return (self.fresh(now) and self.target_reached_at is not None and not self.at_target
                and self.target_left_at is not None and now - self.target_left_at >= self.t.hands_away_s)

    def wanted_hz(self, now: float) -> float:
        """How many frames a second are worth a decision call right now (PLAN §6.3). 0: send nothing."""
        t = self.t
        if not self.fresh(now):
            return t.max_hz                              # no hand signal: nothing to gate on
        if self.raised:
            return t.max_hz                              # holding something up: look now
        if not self.present:
            left_recently = (self.absent_since is not None and self.seen_present_at is not None
                             and now - self.absent_since <= t.after_hands_burst_s)
            return t.max_hz if left_recently else t.idle_hz   # just left: the view is clear, "step done?"
        return t.still_hz if self.still else t.moving_hz  # moving fast: a blurred frame (0 = none, see config)

    def rate_hz(self, now: float) -> float:
        recent = [a for a in self._arrivals if now - a <= 2.0]
        return round(len(recent) / 2.0, 1)

    def view(self, now: float) -> dict | None:
        if not self.fresh(now):
            return None
        return {"present": self.present, "at_target": self.at_target, "still": self.still,
                "raised": self.raised, "hz": self.rate_hz(now)}


class Health:
    """Decision call failures (PLAN §6.5): back-off, the 429 slow-down, safety refusals, vision_paused."""

    def __init__(self, tuning: Tuning):
        self.t = tuning
        self.last_ok: float | None = None
        self.fail_since: float | None = None
        self.paused = False
        self.backoff_s = 0.0
        self.backoff_set_at = float("-inf")
        self.next_call_at = 0.0
        self.slow_until = 0.0
        self.safety_run = 0
        self.tight_until = 0.0
        self.refused: str | None = None          # auth | billing, until a call succeeds
        self.refused_until = 0.0

    def ok(self, now: float) -> list[str]:
        self.last_ok, self.fail_since, self.backoff_s, self.next_call_at, self.safety_run = now, None, 0.0, 0.0, 0
        self.refused = None
        if self.paused:
            self.paused = False
            return ["vision_resumed"]
        return []

    def fail(self, kind: str, now: float, started: float | None = None) -> list[str]:
        """`kind` as in `decider.KINDS`. `started`: when the failed call began. Several calls run at once, and
        the ones that were already on their way when the back-off was set do not lengthen it."""
        t = self.t
        if kind == "safety":                              # an unusable frame, not an outage; never retried
            self.safety_run += 1
            if self.safety_run >= t.safety_in_row:
                self.tight_until = now + t.safety_tight_for_s
            return []
        if kind in REFUSALS:
            self.refused, self.refused_until = kind, now + t.refused_retry_s
            return []
        if self.fail_since is None:
            self.fail_since = now
        if kind == "rate_limit":
            self.slow_until = now + t.rate_limit_for_s
        elif started is None or started >= self.backoff_set_at:
            self.backoff_s = t.backoff_start_s if not self.backoff_s else min(self.backoff_s * 2, t.backoff_max_s)
            self.next_call_at, self.backoff_set_at = now + self.backoff_s, now
        return self.tick(now)

    def tick(self, now: float) -> list[str]:
        if self.fail_since is not None and not self.paused and now - self.fail_since >= self.t.paused_after_s:
            self.paused = True
            return ["vision_paused"]
        return []

    def backing_off(self, now: float) -> bool:
        return now < self.next_call_at

    def blocked(self, now: float) -> bool:
        """The API refused the key or the credit: no call until the retry time."""
        return self.refused is not None and now < self.refused_until

    def cap_hz(self, now: float) -> float | None:
        return self.t.rate_limit_hz if now < self.slow_until else None

    def tighten(self, now: float) -> bool:
        """Send the frame cropped tighter (after repeated safety refusals: usually a person in the picture)."""
        return now < self.tight_until


class Coach:
    """Guidance for one session: a `Watcher` per step and source, and the additions listed at the top."""

    def __init__(self, tuning: Tuning):
        self.t = tuning
        self.expect: Expect | None = None
        self.watcher: Watcher | None = None
        self.source: str | None = None
        self.last_utterance: float | None = None    # carried from one step's Watcher to the next
        self._new_step()

    # ---- control --------------------------------------------------------------------------------

    def _new_step(self) -> None:
        t = self.t
        self.done = False
        self.part = Latch(t.hold_enter, t.hold_release)
        self.hw = Latch(t.hold_enter, t.hold_release)
        self.confirm = {"right_part": Window(), "right_hardware": Window()}
        self.confirmed: set[tuple[str, str]] = set()
        self.sure = {"right_part": Window(), "right_hardware": Window()}    # the same votes, only the very sure ones
        self.wrong_sure = Window()                  # frames that vote wrong_part with `wrong_part_fast_min` or more
        self.confirm_count: Counter = Counter()     # (kind, id) -> confirmations said on this step
        self.unseen: dict = {}                      # (kind, id) confirmed -> (when last seen in the hand, frames since)
        self.again: set[tuple[str, str]] = set()    # re-armed for the next one of the same item
        self.way = Window()                         # `wrong_way` answers: the mistake named, or None
        self.way_said: set[str] = set()             # mistakes already said and not yet seen put right
        self.way_ok = Window()                      # `wrong_way` answers: "ok" when it is sure, or None
        self.way_praised = False                    # `right_way` was said for this step (or action)
        self.muted: set[str] = set()
        self.said: Counter = Counter()
        self.last_warning: str | None = None
        self.bad_since: float | None = None
        self.light_said = False
        self.setup_bad_since: float | None = None
        self.setup_lost = self.setup_said = False
        self.view = "none"                          # ok | blocked | poor | dark | moved | none
        self.hands_present: bool | None = None
        self.frames = 0

    def start_step(self, expect: Expect | None) -> None:
        """A new step (or none). Everything per-step is forgotten; the gap between two utterances is kept."""
        if self.watcher is not None:
            self.last_utterance = self.watcher.last_warning
        self.expect, self.watcher, self.source = expect, None, None
        self._new_step()

    def reset_session(self) -> None:
        self.last_utterance = None
        if self.watcher is not None:
            self.watcher.last_warning = None

    def set_action(self, expect: Expect) -> None:
        """Another action of the same step: what is right to hold may have changed."""
        before = self.expect
        self.expect = expect
        if before is not None and expect.action != before.action:
            self.way_praised = False                     # the next action's part may be praised too
            self.way_ok.clear()
        if self.t.reconfirm and before is not None and expect.action is not None and expect.action != before.action:
            for key in [k for k in self.confirmed if k[1] == expect.action_id]:
                self._again(key)                         # the display moved on to the next bolt: confirm that one too
        for window in self.confirm.values():
            window.clear()
        w = self.watcher
        if w is not None:
            if set(expect.ok_hardware) != w.hardware and "wrong_hardware" in w.votes:
                w.votes["wrong_hardware"].clear()        # votes cast against what the last action needed
            w.parts, w.hardware = set(expect.parts), set(expect.ok_hardware)

    def _other_colour(self, raw: dict, code) -> bool:
        """`hardware_colour` is on, the model is sure of the held item's colour, and `code` has another one:
        a dark metal item is not the white plastic peg, however much the step expects a peg."""
        answer = raw.get(HARDWARE_COLOUR)
        if not self.t.hardware_colour or not isinstance(answer, dict) or code is None or self.expect is None:
            return False
        seen, mine = answer.get("choice"), dict(self.expect.colours).get(code)
        return (mine is not None and seen in ("dark", "grey", "light") and seen != mine
                and float(answer.get("confidence") or 0.0) >= self.t.hardware_colour_min)

    def _again(self, key: tuple[str, str]) -> None:
        """A confirmed item may be confirmed once more, while the step still uses more of it than were confirmed."""
        if self.confirm_count[key] < self.expect.uses(key[1]):
            self.confirmed.discard(key)
            self.again.add(key)
        self.unseen.pop(key, None)

    def _rearm(self, kind: str, vote: str | None, ts: float) -> None:
        """Bolt 2 of 4 is confirmed like bolt 1 (Sun 15 Nov: on the headset only the first one was). Without an
        action index from the display, "the next one" is: the confirmed item was not seen in the hand for
        `reconfirm_after_s`, over at least `reconfirm_frames` frames (driven in, put down, or the hand went away)."""
        for key in [k for k in self.confirmed if k[0] == kind]:
            last, frames = self.unseen.get(key, (ts, 0))
            if vote == key[1]:
                self.unseen[key] = (ts, 0)
                continue
            self.unseen[key] = (last, frames + 1)
            if frames + 1 >= self.t.reconfirm_frames and ts - last >= self.t.reconfirm_after_s:
                self._again(key)

    def _watcher(self, source: str) -> Watcher:
        """This step's Watcher. Frames from another camera start a new one: earlier votes describe another view."""
        if self.watcher is None or source != self.source:
            old, e = self.watcher, self.expect
            pack = {"step": e.step, "expects": {"parts": sorted(e.parts), "hardware": sorted(e.ok_hardware)}}
            self.watcher = Watcher(pack, guide_page=e.page, cfg=self.t.watcher_config(source))
            self.watcher.last_warning = old.last_warning if old is not None else self.last_utterance
            if old is not None:
                self.watcher.last = dict(old.last)
            self.source = source
            for window in (*self.confirm.values(), self.part.window, self.hw.window):
                window.clear()
            self.setup_bad_since, self.setup_lost = None, False
        return self.watcher

    def mute(self, event: str | None = None) -> str | None:
        """"I know": no more of this event on this step. Without a name, the last warning said."""
        event = event or self.last_warning
        if not event or event in NEVER_SUPPRESSED:
            return None
        self.muted.add(event)
        return event

    def unmute(self, event: str | None = None) -> None:
        if event:
            self.muted.discard(event)
        else:
            self.muted.clear()

    # ---- saying things --------------------------------------------------------------------------

    def _free(self, event: str, ts: float) -> bool:
        """May this layer's event be said now? The Watcher's own cooldown rule decides."""
        if event in self.muted:
            return False
        return self.watcher._free(event, ts)

    def _emit(self, event: str, **kw) -> Emitted:
        e = self.expect
        kw["evidence"] = {k: round(float(v), 3) for k, v in (kw.get("evidence") or {}).items() if v is not None}
        if kw.get("p") is not None:
            kw["p"] = round(float(kw["p"]), 3)
        out = Emitted(event, e.step if e else None, e.page if e else None, repeat=self.said[event], **kw)
        self.said[event] += 1
        if event in WARNINGS:
            self.last_warning = event
        return out

    def _say(self, event: str, ts: float, **kw) -> Emitted:
        """An event of this layer. It goes into the Watcher's books, so the Watcher keeps its distance from it."""
        self.watcher.last[event] = ts
        self.watcher.last_warning = ts
        return self._emit(event, **kw)

    def system_event(self, event: str, reason: str | None = None) -> Emitted:
        """An event that does not come from a frame: vision_paused, vision_resumed."""
        return self._emit(event, reason=reason)

    def _from_watcher(self, ev: dict, raw: dict) -> Emitted:
        name, e, observed = ev["type"], self.expect, ev.get("observed")
        kw: dict = {"votes": ev.get("evidence")}
        if name in ("wrong_part", "wrong_hardware"):
            kind = "part" if name == "wrong_part" else "hardware"
            answer = raw.get(f"{kind}_in_hand") or {}
            conf = answer.get("confidence") if answer.get("choice") == observed else None
            check = "right_part" if kind == "part" else RIGHT_HARDWARE
            kw.update(expected_id=e.needed(kind), observed_id=observed, p=conf,
                      evidence={f"{kind}_in_hand.{observed}": conf, check: yes_p(raw, check)})
            probs = answer.get("probabilities")
            if kind == "part" and self.t.wrong_part_sum and isinstance(probs, dict):
                kw["evidence"]["part_in_hand.other_steps"] = round(other_steps_sum(probs, e.parts), 3)
            self.confirmed = {c for c in self.confirmed if c[0] != kind}     # the next right pick is confirmed again
        elif name == "flip_needed":
            o = yes_p(raw, "orientation_ok")
            kw.update(p=None if o is None else 1 - o, evidence={"orientation_ok": o})
        elif name == "out_of_order":
            kw.update(p=yes_p(raw, "next_step_part_used"), evidence={"next_step_part_used": yes_p(raw, "next_step_part_used")})
        elif name == "step_complete":
            kw.update(p=yes_p(raw, "step_complete"),
                      evidence={k: yes_p(raw, k) for k in ("step_complete", "orientation_ok", "hands_present")})
        elif name == "unsure_show_part":
            conf = (raw.get("part_in_hand") or {}).get("confidence")
            kw.update(expected_id=e.needed(e.now_kind or "part"), p=conf, evidence={"part_in_hand.top": conf})
        return self._emit(name, **kw)

    def _wrong_part_fast(self, seen: dict, w: Watcher, ts: float, busy: bool) -> dict | None:
        """`wrong_part` one frame sooner when the answers are very sure: `wrong_part_fast` (3 of 4) frames that
        vote for it as the Watcher counts a vote, each with `wrong_part_fast_min` (0.8) or more, instead of the
        Watcher's 4 of 5 at 0.75. The event goes into the Watcher's books, so it is not said a second time."""
        cfg, t = w.cfg, self.t
        hands = (yes_p(seen, "hands_present") or 0) >= cfg.hands
        held = pick(seen, "part_in_hand", t.wrong_part_fast_min) if hands else None
        right = yes_p(seen, "right_part")
        vote = held if (held is not None and held not in SPECIAL and held not in w.parts
                        and (right is None or right <= cfg.no)) else None
        self.wrong_sure.push(ts, vote)
        need = t.wrong_part_fast
        if (vote is None or busy or "wrong_part" in self.muted or not w.armed.get("wrong_part", True)
                or self.wrong_sure.agreed(need, ts, t.window_max_age_s) != vote or not w._free("wrong_part", ts)):
            return None
        w.armed["wrong_part"], w.last["wrong_part"], w.last_warning = False, ts, ts
        recent = [p for _, p in list(self.wrong_sure.votes)[-need.m:]]
        return {"type": "wrong_part", "observed": vote, "evidence": f"{recent.count(vote)} of last {len(recent)} frames"}

    def _wrong_way(self, raw: dict, ts: float, blocked: bool, busy: bool) -> Emitted | None:
        """`flip_needed` with a `reason`: the step's own `wrong_way` question names one mistake (a choice whose
        options are the ways this step goes wrong, plus `ok` and `cannot_tell`) in enough frames. The yes/no
        `orientation_ok` cannot say which mistake it is and separates badly on real frames (the front lying right:
        0.26-0.92; lying on its face: 0.19). Said once per mistake; again only after frames that say `ok`."""
        answer = raw.get(WRONG_WAY)
        if not self.t.wrong_way or not isinstance(answer, dict) or answer.get("choice") is None:
            return None
        choice, conf = str(answer["choice"]), float(answer.get("confidence") or 0.0)
        if blocked:
            return None                                          # the assembly is not in view: not a vote either way
        named = choice not in WAY_FINE and choice not in SPECIAL and conf >= self.t.wrong_way_choice
        self.way.push(ts, choice if named else None)
        self.way_ok.push(ts, "ok" if choice == "ok" and conf >= self.t.right_way_min else None)
        if (self.t.right_way and not self.way_praised and choice == "ok" and conf >= self.t.right_way_min
                and "right_way" not in self.muted
                and self.way_ok.agreed(self.t.right_way_window, ts, self.t.window_max_age_s) == "ok"):
            # The positive answer of the same question: the part is at its place the right way round. Said once per
            # step (again after a flip_needed, or on a new action index); never held back by the gap between
            # utterances, because the headset ends "put it here" on it.
            self.way_praised = True
            sure = [p for _, p in list(self.way_ok.votes)[-self.t.right_way_window.m:]]
            return self._emit("right_way", reason="ok", p=conf, evidence={f"{WRONG_WAY}.ok": conf},
                              votes=f"{sure.count('ok')} of last {len(sure)} frames")
        if choice == "ok" and conf >= self.t.wrong_way_choice:
            fine = [p for _, p in list(self.way.votes)[-self.t.wrong_way_window.m:]]
            if all(p is None for p in fine):
                self.way_said.clear()                            # put right (or never wrong): may be said again
        if not named or busy or choice in self.way_said or "flip_needed" in self.muted:
            return None
        need = self.t.wrong_way_window
        if self.way.agreed(need, ts, self.t.window_max_age_s) != choice or not self._free("flip_needed", ts):
            return None
        self.way_said.add(choice)
        self.way_praised = False                             # once it is put right, that is said
        self.way_ok.clear()
        recent = [p for _, p in list(self.way.votes)[-need.m:]]
        return self._say("flip_needed", ts, reason=choice, p=conf,
                         evidence={f"{WRONG_WAY}.{choice}": conf, "orientation_ok": yes_p(raw, "orientation_ok")},
                         votes=f"{recent.count(choice)} of last {len(recent)} frames")

    # ---- frames ---------------------------------------------------------------------------------

    def on_bad_frame(self, ts: float, view: str = "dark", source: str | None = None,
                     luma: float | None = None) -> list[Emitted]:
        """A frame too dark to send, or one the model called too poor to judge. `luma`: the mean brightness of
        a poor frame, when it was measured; a poor frame in good light does not count towards `more_light`."""
        self.view = view
        if self.expect is None or self.done:
            return []
        if view != "dark" and luma is not None and self.t.dim_luma and luma >= self.t.dim_luma:
            self.bad_since, self.light_said = None, False    # poor, but not for want of light
            return []
        if self.bad_since is None:
            self.bad_since = ts
        if not self.light_said and ts - self.bad_since >= self.t.bad_light_after_s:
            self._watcher(source or self.source or "fixed")
            if self._free("more_light", ts):
                self.light_said = True                   # once per stretch of bad frames
                return [self._say("more_light", ts)]
        return []

    def _prefilter(self, raw: dict, w: Watcher, rules, blocked: bool) -> dict:
        """The answers the Watcher gets: all of them, unless one of this layer's rules takes some out."""
        e, t, drop = self.expect, self.t, set()
        if (yes_p(raw, "image_poor") or 0) >= w.cfg.unusable:
            drop.add("image_poor")                               # under `poor_at`: usable, the Watcher must not skip it
        if blocked and not rules.blocked_discards_frame:
            drop.update(("view_blocked", *SCENE_QUESTIONS))      # the hand is in view, the assembly is not
        if t.step_complete_only_on_last_action and e.action is not None and not e.last_action:
            drop.add("step_complete")                            # the display says more is still to be done
        if t.hardware_veto:
            sure = yes_p(raw, RIGHT_HARDWARE)
            choice = (raw.get("hardware_in_hand") or {}).get("choice")
            if sure is not None and sure >= w.cfg.yes and choice not in SPECIAL and choice not in w.hardware:
                drop.add("hardware_in_hand")                     # the yes/no says it is the right one
            conf = float((raw.get("hardware_in_hand") or {}).get("confidence") or 0.0)
            hands = (yes_p(raw, "hands_present") or 0) >= w.cfg.hands
            if (sure is not None and sure <= w.cfg.no and hands and choice is not None and choice not in SPECIAL
                    and choice not in w.hardware and t.hardware_no_floor <= conf < w.cfg.choice):
                # The yes/no is sure it is NOT the screw needed, and the choice leans to another code without
                # reaching the threshold (seen with a short screw in hand: right_hardware 0.05, 108560 at 0.51).
                # Let that code count as seen, so the measured N-of-M window decides the warning.
                raw = {**raw, "hardware_in_hand": {**raw["hardware_in_hand"], "confidence": w.cfg.choice}}
        if self._other_colour(raw, (raw.get("hardware_in_hand") or {}).get("choice")):
            drop.add("hardware_in_hand")                         # neither right nor wrong: the code is not believed
        if t.wrong_part_sum:
            raw = self._wrong_part_sum(raw, w)
        return {k: v for k, v in raw.items() if k not in drop} if drop else raw

    def _wrong_part_sum(self, raw: dict, w: Watcher) -> dict:
        """A part this step does not use, read from the whole answer instead of its first choice alone.

        Real frames (Sun 15 Nov): the drawer bottom held up during step 3 scored `drawer_bottom` 0.66-0.84 (four
        of six frames under the 0.75 line) with `right_part` at 0.08-0.27 (three above the 0.15 line); a side
        panel held up during step 1 scored left 0.47-0.54 and right 0.36-0.43, because the model cannot tell
        mirror twins apart from a distance. So when the first choice is a part of another step, the
        probabilities of all such parts are added up, and the frame counts as a confident wrong pick for the
        Watcher's window when that sum reaches `wrong_part_sum_min` and `right_part` is at `wrong_part_no` or
        less. A step without parts has no `right_part` question: there the sum must reach the Watcher's own
        `choice` line. The mirror twin of a part this step uses is left to the Watcher's own rule and is not
        in the sum: left and right are told apart too badly to be relaxed."""
        answer = raw.get("part_in_hand") or {}
        probs, choice = answer.get("probabilities"), answer.get("choice")
        if not isinstance(probs, dict) or choice not in probs or choice in SPECIAL or choice in w.parts:
            return raw
        if _unsided(choice) in {_unsided(part) for part in w.parts} or (yes_p(raw, "hands_present") or 0) < w.cfg.hands:
            return raw
        total = other_steps_sum(probs, w.parts)
        right = yes_p(raw, "right_part")
        if right is None:
            if total < w.cfg.choice:
                return raw
        elif total < self.t.wrong_part_sum_min or right > self.t.wrong_part_no:
            return raw
        out = {**raw, "part_in_hand": {**answer, "confidence": max(total, w.cfg.choice, float(answer.get("confidence") or 0.0))}}
        if right is not None and right > w.cfg.no:
            out["right_part"] = {**raw["right_part"], "noul": w.cfg.no}     # a "no" by `wrong_part_no`
        return out

    def _camera_moved(self, raw: dict, ts: float, cfg, out: list) -> bool:
        """With `check_setup`: warnings pause while a fixed camera no longer shows the setup (§6.5)."""
        setup = yes_p(raw, "frame_matches_setup")
        if setup is not None and setup >= cfg.yes:
            self.setup_bad_since, self.setup_lost = None, False
        elif setup is not None and setup <= cfg.no:
            if self.setup_bad_since is None:
                self.setup_bad_since = ts
            if not self.setup_lost and ts - self.setup_bad_since >= self.t.setup_lost_after_s:
                self.setup_lost, self.setup_said = True, False
        elif not self.setup_lost:
            self.setup_bad_since = None                  # entering needs unbroken evidence; leaving needs a yes
        if self.setup_lost and not self.setup_said and self._free("realign_camera", ts):
            self.setup_said = True
            out.append(self._say("realign_camera", ts, p=setup, evidence={"frame_matches_setup": setup}))
        return self.setup_lost

    def on_frame(self, raw: dict, ts: float, source: str = "fixed", hands: Hands | None = None,
                 now: float | None = None, luma: float | None = None) -> list[Emitted]:
        """Feed one frame's answers, as the Decisions API returned them. `ts`: when the frame was taken.
        `now`: the time of the hand summary's clock, when it differs. Returns the events to send."""
        e = self.expect
        if e is None or self.done:
            return []
        now = ts if now is None else now
        w = self._watcher(source)
        cfg, rules, t = w.cfg, self.t.rules(source), self.t
        self.frames += 1
        out: list[Emitted] = []
        poor = (yes_p(raw, "image_poor") or 0) >= max(t.poor_at, cfg.unusable)
        blocked = (yes_p(raw, "view_blocked") or 0) >= cfg.unusable
        if rules.check_setup and not poor and not blocked and self._camera_moved(raw, ts, cfg, out):
            self.view = "moved"
            return out

        # 1. The Watcher: the plan's events, as measured.
        before = w.last_warning
        seen = raw if poor else self._prefilter(raw, w, rules, blocked)
        events = w.feed(seen, ts)
        if poor:
            return self.on_bad_frame(ts, "poor", source, luma)
        self.bad_since, self.light_said = None, False
        self.view = "blocked" if blocked else "ok"
        if blocked and rules.blocked_discards_frame:
            return out
        complete = next((ev for ev in events if ev["type"] == "step_complete"), None)
        for ev in events:
            if ev is complete or (complete is None and ev["type"] not in self.muted):
                out.append(self._from_watcher(ev, raw))

        if complete is None and t.fast:
            sooner = self._wrong_part_fast(seen, w, ts, any(e.event in WARNINGS for e in out))
            if sooner is not None:
                out.append(self._from_watcher(sooner, raw))
        if complete is None:
            flip = self._wrong_way(raw, ts, blocked, any(e.event in WARNINGS for e in out))
            if flip is not None:
                out.append(flip)

        # 2. What is in the hand, read as the Watcher reads it: only when hands are in view.
        hands_p = yes_p(raw, "hands_present")
        in_view = (hands_p or 0) >= cfg.hands
        if hands_p is not None:
            self.hands_present = in_view
        held = pick(raw, "part_in_hand", cfg.choice) if in_view else None
        code = pick(raw, "hardware_in_hand", cfg.choice) if in_view else None
        is_part, is_code = held is not None and held not in SPECIAL, code is not None and code not in SPECIAL
        p_conf = float((raw.get("part_in_hand") or {}).get("confidence") or 0.0)
        h_conf = float((raw.get("hardware_in_hand") or {}).get("confidence") or 0.0)
        empty = hands_p is not None and hands_p <= cfg.no
        age = t.window_max_age_s
        self.part.observe(ts, held if is_part else NOTHING if (empty or held in ("nothing_held", "hardware_only")) else None,
                          p_conf, age)
        self.hw.observe(ts, code if is_code else NOTHING if (empty or code == "no_hardware" or held == "nothing_held") else None,
                        h_conf, age)

        # 3. Confirmations: the right thing in the hand, said once.
        right_part, right_hw = yes_p(raw, "right_part"), yes_p(raw, RIGHT_HARDWARE)
        votes = {
            "right_hardware": code if (is_code and code in e.confirm_hardware and not self._other_colour(raw, code)
                                       and not (t.hardware_veto and right_hw is not None and right_hw <= cfg.no)) else None,
            "right_part": held if (is_part and held in e.confirm_parts
                                   and not (right_part is not None and right_part <= cfg.no)) else None,
        }
        for name, vote in votes.items():
            self.confirm[name].push(ts, vote)
            self.sure[name].push(ts, vote if (h_conf if name == "right_hardware" else p_conf) >= t.confirm_fast_min else None)
        if t.reconfirm:
            self._rearm("hardware", votes["right_hardware"], ts)
            self._rearm("part", votes["right_part"], ts)
        if t.confirmations and not out:
            for kind, name, conf in (("hardware", "right_hardware", h_conf), ("part", "right_part", p_conf)):
                vote = votes[name]
                if vote is None or (kind, vote) in self.confirmed:
                    continue
                if self.confirm[name].agreed(rules.confirm, ts, age) != vote and not (
                        t.fast and self.sure[name].agreed(t.confirm_fast, ts, age) == vote):
                    continue                             # neither 3 of 4, nor 2 of 3 very sure ones
                # the next one of the same item waits for the gap between two utterances only: the 20 s
                # "same event" rule would swallow a bolt picked up right after the last one
                free = (name not in self.muted and (w.last_warning is None or ts - w.last_warning >= cfg.global_cooldown_s)
                        if (kind, vote) in self.again else self._free(name, ts))
                if free:
                    self.confirmed.add((kind, vote))     # once per item, until a correction or the next one re-arms it
                    self.again.discard((kind, vote))
                    self.confirm_count[(kind, vote)] += 1
                    self.unseen[(kind, vote)] = (ts, 0)
                    out.append(self._say(name, ts, expected_id=vote, observed_id=vote, p=conf,
                                         evidence={f"{kind}_in_hand.{vote}": conf}))
                    break

        # 4. step_complete with hands still in view: the Watcher waits for them to leave the picture. A hand that
        #    was at the target and has left it is accepted instead, on the Watcher's own votes.
        window = w.votes.get("step_complete")
        if (complete is None and not blocked and hands is not None and hands.placed_then_away(now)
                and window and window[-1][0] and w.armed.get("step_complete")):
            count = sum(1 for vote, _ in window if vote)
            if count >= cfg.windows["step_complete"][0]:
                w.armed["step_complete"], w.last["step_complete"], w.state = False, ts, COMPLETE
                complete = {"type": "step_complete", "evidence": f"{count} of last {len(window)} frames"}
                out = [self._from_watcher(complete, raw)]
                out[0].reason = "hand_summary"
        if complete is not None:
            self.done = True
            out = [o for o in out if o.event == "step_complete"]
        if not any(o.event not in NEVER_SUPPRESSED for o in out) and w.last_warning != before:
            w.last_warning = before                      # a warning that was not said starts no cooldown
        return out

    # ---- what the system believes, for the status message -------------------------------------

    def belief(self, at_target: bool = False) -> dict:
        e = self.expect
        hw, part = self.hw.value, self.part.value
        if hw not in (None, NOTHING):
            kind, held, conf = "hardware", hw, self.hw.confidence
        elif part not in (None, NOTHING):
            kind, held, conf = "part", part, self.part.confidence
        elif part == NOTHING and hw in (NOTHING, None):
            kind, held, conf = "none", None, self.part.confidence
        else:
            kind, held, conf = "unknown", None, 0.0
        verdict = "none" if kind == "none" else "unknown"
        if e is not None and held is not None:
            verdict = "right" if held in (e.ok_hardware if kind == "hardware" else e.parts) else "wrong"
        state = "IDLE" if e is None else COMPLETE if self.done else self.watcher.state if self.watcher else WAITING
        if at_target and state in (WAITING, IN_HAND):
            state = PLACING                              # the hand summary says a hand is where the part goes
        return {"state": state, "holding": {"kind": kind, "id": held, "confidence": round(conf, 3)},
                "verdict": verdict, "hands_present": self.hands_present, "view": self.view,
                "muted": sorted(self.muted)}
