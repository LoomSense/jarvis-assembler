"""From per-frame Decision 1 answers to events (LIV-03, PLAN §6.2). No network, no clock: pure and replayable.

    w = Watcher(pack)                      # pack = refs/step-NN-questions.json, one Watcher per step
    for answers, ts in stream:             # answers as the Decisions API returns them, ts in seconds
        for event in w.feed(answers, ts): ...
    w.state                                # WAITING_FOR_PART | PART_IN_HAND | PLACING | STEP_COMPLETE

An event fires when N of the last M usable frames vote for it, then not again until the window has
emptied of votes (hysteresis) and its cooldown has passed. `step_complete` ignores cooldowns.
`part_in_hand`, `right_part` and `hardware_in_hand` are read only when hands are in view: with no hands
the model still names a part (measured, PROGRESS Log Data Sun 00:45).
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field

SPECIAL = {"hardware_only", "nothing_held", "no_hardware", "other"}


@dataclass
class Config:
    yes: float = 0.85                 # a yes/no answer counts as yes at or above this, as no at or below `no`
    no: float = 0.15
    choice: float = 0.75              # least confidence for a choice answer to count
    hands: float = 0.5
    unusable: float = 0.3             # frame skipped when image_poor or view_blocked reaches this
    windows: dict = field(default_factory=lambda: {   # event: (N, M)
        "wrong_part": (4, 5), "flip_needed": (5, 6), "wrong_hardware": (4, 5), "out_of_order": (6, 8), "step_complete": (8, 10)})
    unsure_frames: int = 8            # this many frames in a row with a part held but not recognised
    hands_away_s: float = 2.0         # step_complete needs hands out of view this long
    cooldown_s: float = 20.0          # same event, same step
    global_cooldown_s: float = 6.0    # any warning after any warning


def p(answers: dict, key: str) -> float | None:
    a = answers.get(key)
    return None if a is None else a.get("noul")


def pick(answers: dict, key: str, least: float) -> str | None:
    a = answers.get(key)
    return a["choice"] if a and a.get("choice") is not None and a.get("confidence", 0) >= least else None


class Watcher:
    def __init__(self, pack: dict, guide_page: int | None = None, cfg: Config | None = None):
        self.cfg, self.step, self.page = cfg or Config(), pack["step"], guide_page
        self.parts, self.hardware = set(pack["expects"]["parts"]), set(pack["expects"]["hardware"])
        self.votes = {e: deque(maxlen=m) for e, (n, m) in self.cfg.windows.items()}
        self.armed = {e: True for e in self.votes} | {"unsure_show_part": True}
        self.last = {}                # event -> ts it last fired
        self.last_warning = None
        self.unsure = 0
        self.hands_seen = None        # ts of the last frame with hands
        self.first_ts = None
        self.state = "WAITING_FOR_PART"
        self.skipped = 0

    def feed(self, answers: dict, ts: float) -> list[dict]:
        c = self.cfg
        if self.first_ts is None:
            self.first_ts = ts
        if (p(answers, "image_poor") or 0) >= c.unusable or (p(answers, "view_blocked") or 0) >= c.unusable:
            self.skipped += 1
            return []
        hands = (p(answers, "hands_present") or 0) >= c.hands
        if hands:
            self.hands_seen = ts
        held = pick(answers, "part_in_hand", c.choice) if hands else None
        hw = pick(answers, "hardware_in_hand", c.choice) if hands else None
        right, orient = p(answers, "right_part"), p(answers, "orientation_ok")
        done, ahead = p(answers, "step_complete"), p(answers, "next_step_part_used")
        wrong_part = held is not None and held not in SPECIAL and held not in self.parts and (right is None or right <= c.no)
        wrong_hw = hw is not None and hw not in SPECIAL and hw not in self.hardware
        seen = {"wrong_part": (wrong_part, held), "wrong_hardware": (wrong_hw, hw),
                "flip_needed": (orient is not None and orient <= c.no, None),
                "out_of_order": (hands and ahead is not None and ahead >= c.yes, None),
                "step_complete": (done is not None and done >= c.yes, None)}
        out = []
        for event, (vote, observed) in seen.items():
            if event not in self.votes:
                continue
            w = self.votes[event]
            w.append((bool(vote), observed))
            n, m = c.windows[event]
            count = sum(1 for v, _ in w if v)
            if count == 0:
                self.armed[event] = True          # opposite evidence filled the window: may fire again
            if count < n or not self.armed[event]:
                continue
            if event == "step_complete":
                away = ts - (self.hands_seen if self.hands_seen is not None else self.first_ts)
                if hands or away < c.hands_away_s:
                    continue
            elif not self._free(event, ts):
                continue
            names = [o for v, o in w if v and o]
            out.append(self._event(event, ts, max(set(names), key=names.count) if names else None, f"{count} of last {len(w)} frames"))
        holding_unknown = hands and (answers.get("part_in_hand") or {}).get("choice") not in (None, "nothing_held", "hardware_only") and held in (None, "other")
        self.unsure = self.unsure + 1 if holding_unknown else 0
        if not holding_unknown:
            self.armed["unsure_show_part"] = True
        if self.unsure >= c.unsure_frames and self.armed["unsure_show_part"] and self._free("unsure_show_part", ts):
            out.append(self._event("unsure_show_part", ts, None, f"{self.unsure} frames in a row"))
        if self.state != "STEP_COMPLETE":
            placing = hands and held in self.parts and right is not None and right >= c.yes and done is not None and done >= 0.5
            self.state = ("PLACING" if placing else "PART_IN_HAND" if hands and held and held not in ("nothing_held",) else
                          "WAITING_FOR_PART" if not hands else self.state)
        if any(e["type"] == "step_complete" for e in out):
            self.state = "STEP_COMPLETE"
        return out

    def _free(self, event: str, ts: float) -> bool:
        c = self.cfg
        return ((event not in self.last or ts - self.last[event] >= c.cooldown_s)
                and (self.last_warning is None or ts - self.last_warning >= c.global_cooldown_s))

    def _event(self, event: str, ts: float, observed, evidence: str) -> dict:
        self.armed[event] = False
        self.last[event] = ts
        if event != "step_complete":
            self.last_warning = ts
        expected = sorted(self.hardware) if event == "wrong_hardware" else sorted(self.parts) if event in ("wrong_part", "unsure_show_part") else None
        return {"type": event, "step": self.step, "guide_page": self.page, "part": observed if event == "wrong_part" else None,
                "expected": expected, "observed": observed, "evidence": evidence, "ts": ts}
