"""Every tunable number of the perception server, in one place.

The numbers that were measured against the real Decisions API (answer thresholds, the `step_complete`
line, the N-of-M windows, hands gating, cooldowns) are the defaults of `pipeline.perception.logic.Config`:
that is where they are tuned (`pipeline.perception.replay --cached`), and this file does not repeat them.
`Tuning.watcher` overrides them for the server without a code change.

Everything the pipeline has no number for is here: how many calls run at once, the timeout, the rules
for a head-mounted camera, the hand gate, confirmations, failure handling.

All of it can be overridden through the `PERCEPTION_TUNING` setting, one JSON object:

    PERCEPTION_TUNING='{"max_in_flight": 8, "watcher": {"done_yes": 0.5}, "headset": {"step_complete": [9, 10]}}'
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field, fields, replace

from pipeline.perception.logic import Config as WatcherConfig

SOURCES = ("fixed", "headset")       # a camera on a stand, or the camera on the person's head
ROLES = ("display", "camera")
WATCHER_EVENTS = ("wrong_part", "flip_needed", "wrong_hardware", "out_of_order", "step_complete")


@dataclass(frozen=True)
class NofM:
    """An event needs `n` agreeing usable frames out of the last `m`."""
    n: int
    m: int


@dataclass(frozen=True)
class SourceRules:
    """Rules that depend on where the frames come from. A window left at None is the Watcher's own."""
    wrong_part: NofM | None = None
    flip_needed: NofM | None = None
    wrong_hardware: NofM | None = None
    out_of_order: NofM | None = None
    step_complete: NofM | None = None
    confirm: NofM = NofM(3, 4)             # right_part / right_hardware (the Watcher has no such event)
    check_setup: bool = False              # pause warnings while `frame_matches_setup` says the camera moved.
    #                                        Off: the question asks for "roughly the same side" as the reference
    #                                        render, and a real camera is rarely on that side (bench, Sun 15 Nov)
    blocked_discards_frame: bool = True    # a blocked view discards the whole frame (the Watcher's rule); if False
    #                                        only the questions about the assembly are left out, the hand ones count
    hand_gate: bool = False                # the hand summary decides which frames are worth a decision call


FIXED = SourceRules()
# A head-mounted camera moves all the time and looks where the person looks. What is in the hand is
# strong evidence; a comparison of the whole assembly with a render from a fixed front view is weak.
HEADSET = SourceRules(flip_needed=NofM(6, 7), out_of_order=NofM(7, 8), step_complete=NofM(10, 12),
                      blocked_discards_frame=False, hand_gate=True)


@dataclass(frozen=True)
class Tuning:
    # ---- the measured logic (pipeline.perception.logic.Config): overrides only, e.g. {"done_yes": 0.5} ----
    watcher: dict = field(default_factory=dict)
    fixed: SourceRules = FIXED
    headset: SourceRules = HEADSET

    # ---- additions to that logic ----
    confirmations: bool = True             # say "right part" / "right hardware" once
    reconfirm: bool = True                 # an item the step uses several times (4 cam bolts) is confirmed each time,
    #                                        up to that many times: on a new action index from the display, or
    reconfirm_after_s: float = 4.0         # ...when the confirmed item was not seen in the hand for this long
    reconfirm_frames: int = 4              # ...over at least this many frames
    hardware_colour: bool = False          # ask for the held item's material and colour before its code, and do not
    #                                        believe a code of another colour (the manifest's `color`). Off: on the
    #                                        real frames the item is mostly hidden in the fingers (README)
    hardware_colour_min: float = 0.5       # ...the colour answer counts from this confidence
    ask_right_hardware: bool = True        # one more yes/no question, about the hardware needed now
    hardware_veto: bool = True             # ...whose clear answer overrules a contradicting choice answer
    hardware_no_floor: float = 0.4         # a clear "not the right one" lets a different code count from this confidence
    step_complete_only_on_last_action: bool = True   # when the display reports actions, wait for the last one
    wrong_part_sum: bool = True            # wrong_part: when the first choice is a part of another step, the
    #                                        probabilities of all such parts are added up (mirror twins share one
    #                                        answer: a side panel shown during step 1 scored left 0.47-0.54, right
    #                                        0.36-0.43). False: the Watcher's rule alone (first choice at 0.75)
    wrong_part_sum_min: float = 0.65       # ...the sum that counts, when `right_part` agrees. Real frames, the
    #                                        drawer bottom held up during step 3: 0.68-0.85; the part needed: 0.01-0.04.
    #                                        A step without parts has no `right_part`: the sum must reach `choice`
    wrong_part_no: float = 0.3             # ...and `right_part` at or below this agrees (the Watcher's own `no` is
    #                                        0.15). Real frames: a wrong board held where the right one goes
    #                                        0.08-0.27; the right part, named with confidence, 0.32 at the least
    #                                        (21 logged frames), 0.90-0.93 when held up
    wrong_way: bool = True                 # a step whose questions have the optional `wrong_way` choice: the mistake
    #                                        it names becomes `flip_needed` with `reason` (README, "Wrong way round")
    wrong_way_choice: float = 0.7          # ...named with this confidence. The front lying on its face (a phone
    #                                        photo): 0.74-0.76; lying right (16 real headset frames): 0.58 at most
    wrong_way_window: NofM = NofM(4, 5)    # ...in 4 of the last 5 frames in which the assembly is in view
    all_choices: bool = True               # "which part / which hardware is held" also offers the parts of later
    #                                        steps and every hardware code of the guide (questions.complete_choices).
    #                                        A question file lists only its own step's and the next one's, so any
    #                                        other wrong pick could only be answered `other`: never a wrong_part
    hold_enter: NofM = NofM(3, 4)          # status: believe "holding X" after 3 of 4 frames name X
    hold_release: NofM = NofM(6, 8)        # status: believe "put down" only after 6 of 8 frames say so
    window_max_age_s: float = 15.0         # for the two windows above and `confirm`: older answers do not count

    # ---- calls in flight and rate (measured Sun 15 Nov: one request p50 0.64-1.1 s, p95 1.4-2.6 s) ----
    max_in_flight: int = 4                 # requests at once. step_complete came 3.7-5.5 s after the change with 2,
    #                                        2.6-3.8 s with 4, 1.8 s with 8 (one run); no 429 in about 400 requests.
    #                                        4 uses about half of Tier 1's token budget, 8 nearly all of it
    max_hz: float = 4.0                    # frames accepted per second at most (PLAN §6.3)
    still_hz: float = 4.0                  # hand gate: hands present and still
    idle_hz: float = 1.0                   # hand gate: no hands, keep-alive
    rate_slack: float = 0.8                # a frame is accepted when 0.8 of the interval has passed (client jitter)
    hands_fresh_s: float = 1.0             # a hand summary older than this is ignored
    hands_away_s: float = 0.5              # hand summary "away": not at the target for this long
    after_hands_burst_s: float = 3.0       # after the hands leave, full rate this long ("step done?" frames)
    source_switch_after_s: float = 2.0     # frames from a second source are used once the first is silent this long

    # ---- frames (D6) ----
    resolution: str = "medium"             # both images of a request; what was benchmarked
    show_part_resolution: str = "high"
    max_frame_bytes: int = 1_500_000
    dark_luma: float = 22.0                # mean brightness (0-255) below this: dark, no call is spent; 0 disables
    dark_peak: float = 60.0                # ...but only when the brightest 2 % of the frame stay below this as well:
    #                                        a dark floor with lit parts on it is not a dark frame. 0: the mean alone
    poor_at: float = 0.55                  # `image_poor` at or above this discards the frame (never below the
    #                                        Watcher's `unusable`, 0.3, which was the line until Sun 15 Nov and still
    #                                        is for `view_blocked`). Real frames, headset worn, work in view:
    #                                        0.08-0.19; a desk 0.26-0.43; of 924 logged frames with a hand seen,
    #                                        22 % reached 0.3 and 8 % reach 0.55; imitated dark, defocused or
    #                                        overexposed frames 0.64-0.92
    dim_luma: float = 50.0                 # `more_light` is said only for poor frames whose mean brightness is under
    #                                        this (and for frames too dark to send). A poor frame in good light is
    #                                        blur, a covered lens or a headset put down: real frames of a lit office
    #                                        had a mean of 82-120, and "more light" was said 53 times that day.
    #                                        0: every poor frame counts, as before
    show_nothing_max: float = 0.1          # show_part names a code only if `no_hardware` is below this (an empty
    #                                        pinching hand scored 0.18-0.76, a held bolt 0.01-0.06; real and drawn)
    show_right_min: float = 0.7            # ...and, for a code needed now, only if `right_hardware` reaches this
    #                                        (empty hand 0.14-0.62; the right bolt 0.72-0.94, so the margin is thin)

    # ---- failure handling (§6.5) ----
    decision_timeout_s: float = 4.0        # above the measured p95 (up to 2.6 s). PLAN's 800 ms is below the median
    paused_after_s: float = 3.0            # no successful decision for this long while calls fail: vision_paused
    backoff_start_s: float = 0.25
    backoff_max_s: float = 4.0
    rate_limit_hz: float = 1.0             # after a 429
    rate_limit_for_s: float = 30.0
    refused_retry_s: float = 30.0          # key refused or credit used up: no call for this long, then one try
    bad_light_after_s: float = 5.0         # bad frames for this long: "more light please", once
    setup_lost_after_s: float = 10.0       # with `check_setup`: frame_matches_setup negative for this long
    safety_in_row: int = 3                 # safety refusals in a row before the frame is cropped tighter
    safety_tight_for_s: float = 30.0
    tighten_factor: float = 0.7            # centre crop kept when tightening
    show_part_wait_s: float = 8.0          # a show_part request waits this long for a frame

    # ---- status message ----
    status_interval_s: float = 0.4         # at most 2.5 status messages a second
    status_heartbeat_s: float = 2.0        # and one at least this often, changed or not

    def __post_init__(self) -> None:
        if self.max_in_flight < 1 or self.max_hz <= 0 or self.decision_timeout_s <= 0:
            raise ValueError("max_in_flight must be at least 1, max_hz and decision_timeout_s above 0")

    def rules(self, source: str) -> SourceRules:
        return self.headset if source == "headset" else self.fixed

    def watcher_config(self, source: str = "fixed") -> WatcherConfig:
        """The Watcher's own defaults, then `watcher` overrides, then this source's windows."""
        cfg = WatcherConfig()
        for key, value in self.watcher.items():
            if key == "windows":
                cfg.windows.update({event: (int(n), int(m)) for event, (n, m) in value.items()})
            else:
                setattr(cfg, key, type(getattr(cfg, key))(value))
        rules = self.rules(source)
        for event in WATCHER_EVENTS:
            window = getattr(rules, event)
            if window is not None:
                cfg.windows[event] = (window.n, window.m)
        return cfg

    def with_overrides(self, overrides: dict | str | None) -> "Tuning":
        """A copy with values replaced. `[n, m]` lists become windows; `fixed`, `headset` and `watcher` take objects."""
        if not overrides:
            return self
        if isinstance(overrides, str):
            overrides = json.loads(overrides)
        if not isinstance(overrides, dict):
            raise ValueError("PERCEPTION_TUNING must be a JSON object")
        return _apply(self, overrides)


def _window(key: str, value) -> NofM:
    n, m = value
    if not 1 <= int(n) <= int(m):
        raise ValueError(f"`{key}` must be [n, m] with 1 <= n <= m")
    return NofM(int(n), int(m))


def _watcher_overrides(value) -> dict:
    """Checked against the fields `pipeline.perception.logic.Config` has today."""
    if not isinstance(value, dict):
        raise ValueError("`watcher` must be an object")
    default = WatcherConfig()
    known = {f.name for f in fields(default)}
    for key, v in value.items():
        if key not in known:
            raise ValueError(f"unknown watcher value `{key}` (pipeline.perception.logic.Config has: {', '.join(sorted(known))})")
        if key == "windows":
            if not isinstance(v, dict) or set(v) - set(default.windows):
                raise ValueError(f"`watcher.windows` takes: {', '.join(default.windows)}")
            for event, window in v.items():
                _window(f"watcher.windows.{event}", window)
        elif isinstance(v, bool) or not isinstance(v, (int, float)):
            raise ValueError(f"`watcher.{key}` must be a number")
    return dict(value)


def _apply(obj, overrides: dict):
    known = {f.name: f for f in fields(obj)}
    changes = {}
    for key, value in overrides.items():
        if key not in known:
            raise ValueError(f"unknown tuning value `{key}`")
        current = getattr(obj, key)
        if key == "watcher":
            value = {**current, **_watcher_overrides(value)}
        elif isinstance(current, NofM) or (isinstance(obj, SourceRules) and key in WATCHER_EVENTS):
            value = None if value is None else _window(key, value)
        elif isinstance(current, SourceRules):
            value = _apply(current, value)
        elif isinstance(current, bool):
            value = bool(value)
        elif isinstance(current, (int, float)):
            value = type(current)(value)
        changes[key] = value
    return replace(obj, **changes)


DEFAULT = Tuning()
__all__ = ["DEFAULT", "FIXED", "HEADSET", "NofM", "ROLES", "SOURCES", "SourceRules", "Tuning", "WatcherConfig"]
