"""One perception session per job (PLAN §4.5, §6): the devices that joined, the frames on their way,
the failure handling of §6.5, and the logs.

A frame's way through the session:

    on_bytes   parse, then the gates (step, rate, hand gate, back-off, which camera): a refused frame is acked at once
    Stream     `pipeline.perception.stream.Stream`: K requests at once on worker threads, and when all are busy
               only the newest frame waits (the one it replaces is acked `busy`); answers come out in frame order
    _ask       in a worker thread: local checks, the benchmarked request (`questions.build_ask`), the decider
    _on_answered   back on the event loop when the request ends: the ack
    _on_result     in frame order: `logic.Coach` (the pipeline's Watcher plus this server's additions), events out

One device can be everything: a headset that shows the guide, tracks the hands and sends the frames
on the same socket. A phone on a stand joins the same session as the fallback camera.
"""
from __future__ import annotations

import asyncio
import json
import logging
import secrets
import threading
import time
from collections import Counter, deque
from pathlib import Path

from pipeline.perception.logic import SPECIAL, pick
from pipeline.perception.stream import Stream
from server.perception import imaging
from server.perception.config import SOURCES, Tuning
from server.perception.decider import REFUSED, DecisionError, GeminiDecider, SimulatedDecider
from server.perception.guide import Guide
from server.perception.logic import Coach, Emitted, Hands, Health
from server.perception.phrases import perception_line, sentences, show_part_sentences, spoken
from server.perception.questions import EXTRA, StepPack, build_ask, forget, load_pack, normalise
from server.perception.wire import FrameError, parse_frame

log = logging.getLogger("agj.perception")
PROTOCOL = 1
CONTROL = ("start", "step", "action", "show_part", "stop", "hands", "mute", "unmute", "sim")
SHOW_PART_QUESTIONS = ("part_in_hand", "hardware_in_hand", "image_poor", EXTRA)
KEEP_EVERY_S = 1.0                         # kept frames: one a second at most, and every `show_part` look
NO_KEY = ("The server has no GEMINI_API_KEY and PERCEPTION_SIMULATE is off, so no frame is looked at. "
          "Steps, actions and hand summaries still work.")
NOTHING_TO_FEED = {"answers": None}        # what `_ask` returns for a frame without answers: the Stream skips it


class FrameKeeper:
    """Opt-in (`PERCEPTION_KEEP_FRAMES=<count>`): camera frames as they arrived, under `jobs/_host/frames/<session>/`,
    named `<seq>-<what became of it>.jpg` so they join `frames.jsonl` by `seq`. Off by default; it stops at
    `count` frames or `megabytes`, whichever comes first, counted over the server's run and what the folder
    already holds. The frames show the user's room: `jobs/` is git-ignored and they must stay there."""

    def __init__(self, folder: Path | None, count: int = 0, megabytes: int = 200):
        self.folder = folder if folder is not None and count > 0 else None
        self.count, self.bytes = count, megabytes * 1024 * 1024
        self.kept = self.used = 0
        self._lock = threading.Lock()
        if self.folder is not None and self.folder.is_dir():      # what earlier runs left counts too
            for f in self.folder.glob("*/*.jpg"):
                self.kept, self.used = self.kept + 1, self.used + f.stat().st_size

    @property
    def on(self) -> bool:
        return self.folder is not None and self.kept < self.count and self.used < self.bytes

    def keep(self, session: str, seq, tag: str, jpeg: bytes) -> bool:
        with self._lock:
            if not self.on or self.used + len(jpeg) > self.bytes:
                return False
            self.kept, self.used = self.kept + 1, self.used + len(jpeg)
        try:
            folder = self.folder / session
            folder.mkdir(parents=True, exist_ok=True)
            name = f"{seq:06d}" if isinstance(seq, int) and not isinstance(seq, bool) and seq >= 0 else "noseq"
            (folder / f"{name}-{tag}.jpg").write_bytes(bytes(jpeg))
            return True
        except OSError as e:                                     # a full disk must not stop the guidance
            log.warning("kept frame %s: %s", seq, e)
            return False


class Conn:
    """One device on the socket."""

    def __init__(self, ws, role: str, device: str, source: str, want_answers: bool):
        self.ws, self.role, self.device = ws, role, device
        self.source = source                       # where its frames come from unless a frame header says otherwise
        self.want_answers = want_answers
        self.queue: asyncio.Queue = asyncio.Queue(maxsize=512)
        self.writer: asyncio.Task | None = None
        self.open = True


class Frame(bytes):
    """One JPEG on its way through the Stream, which hands it on untouched, with what the session needs
    to know when its answer comes back."""
    conn: Conn
    header: dict
    seq: int
    source: str
    t_recv: float           # when it arrived (the session's clock)
    ts: float               # the same, made strictly increasing: the Stream and the Watcher order frames by it
    epoch: int
    step: int
    show: dict | None       # a `show_part` request this frame answers
    codes: tuple            # the hardware needed when it arrived
    started: float | None = None


class Answers(dict):
    """The API's answers for one frame, with the frame they belong to."""
    frame: Frame
    ms: int
    resolution: str
    luma: float | None = None       # the frame's mean brightness, when Pillow measured it


class _Handoff:
    """What the Stream takes for its watcher. It only carries each frame's answers, in frame order, back to
    the event loop, where the session's own state lives."""

    def __init__(self, session: "PerceptionSession"):
        self.session = session

    def feed(self, answers: Answers, ts: float) -> list:
        self.session._post(self.session._on_result, answers, ts)
        return []


class SessionLog:
    """`frames.jsonl`, `events.jsonl` (the shapes of eval/phase2/README.md §3) and `hands.jsonl`
    under `jobs/<job>/perception/<session>/`."""

    def __init__(self, folder: Path | None):
        self.folder = folder
        self._files: dict = {}

    def write(self, name: str, row: dict) -> None:
        if self.folder is None:
            return
        try:
            f = self._files.get(name)
            if f is None:
                self.folder.mkdir(parents=True, exist_ok=True)
                f = self._files[name] = open(self.folder / f"{name}.jsonl", "a", encoding="utf-8")
            f.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
            f.flush()
        except OSError as e:                         # a full disk must not stop the guidance
            log.warning("perception log %s: %s", name, e)

    def close(self) -> None:
        for f in self._files.values():
            f.close()
        self._files = {}


class PerceptionSession:
    def __init__(self, job_id: str, job_dir: Path, tuning: Tuning, decider, no_decider: str | None = None,
                 logs: bool = True, clock=time.monotonic, keeper: FrameKeeper | None = None):
        self.job_id, self.dir, self.t, self.clock = job_id, Path(job_dir), tuning, clock
        self.keeper, self._kept_at = keeper, float("-inf")
        self.decider = decider
        self.simulated = bool(decider is not None and decider.simulated)
        self.no_decider = no_decider if decider is None else None
        self.guide = Guide(self.dir)
        self.id = time.strftime("%m%d-%H%M%S-") + secrets.token_hex(2)
        self.log = SessionLog(self.dir / "perception" / self.id if logs else None)
        self.conns: set[Conn] = set()
        self.coach, self.hands, self.health = Coach(tuning), Hands(tuning), Health(tuning)
        self.step: int | None = None
        self.action: int | None = None
        # Where the guide last was, kept through `stop` and after the last device left, so that another device
        # (the headset after the phone) can continue there: GET /api/perception/{job_id}/position.
        self.position: dict | None = None
        self.epoch = 0                               # changes with every start, step and stop
        self.stream: Stream | None = None
        self.loop: asyncio.AbstractEventLoop | None = None
        self.last_accept_at = float("-inf")
        self.last_ts = float("-inf")
        self.active_source: str | None = None        # where the frames in the windows come from
        self.active_conn: Conn | None = None
        self.source_seen_at = float("-inf")
        self.show_part: dict | None = None
        self.t0 = clock()
        self.stats: Counter = Counter()
        self.decision_ms: deque = deque(maxlen=50)
        self._packs: dict = {}                       # step -> StepPack, or the ValueError that says why there is none
        self._pack_lock = threading.Lock()
        self._told: set = set()                      # steps whose missing questions were reported
        self._ticker: asyncio.Task | None = None
        self._dirty = True
        self._status_at = float("-inf")
        self._event_id = 0

    # ---- devices --------------------------------------------------------------------------------

    @property
    def unavailable(self) -> str | None:
        """Why no frame is looked at, or None."""
        if self.decider is None:
            return self.no_decider
        return REFUSED.get(self.health.refused) if self.health.refused else None

    @property
    def vision(self) -> str:
        if self.unavailable:
            return "unavailable"
        return "paused" if self.health.paused else "simulated" if self.simulated else "live"

    def join(self, ws, role: str, params) -> Conn:
        self.loop = asyncio.get_running_loop()
        source = params.get("source") if params.get("source") in SOURCES else ("fixed" if role == "camera" else "headset")
        device = (params.get("device") or f"{role}-{secrets.token_hex(2)}")[:40]
        conn = Conn(ws, role, device, source, params.get("answers") in ("1", "true"))
        conn.writer = asyncio.create_task(self._writer(conn))
        self.conns.add(conn)
        if self._ticker is None or self._ticker.done():
            self._ticker = asyncio.create_task(self._tick_loop())
        self._send(conn, self._hello(conn))
        if self.unavailable:                         # say it plainly, in the plan's own event
            self._send(conn, self._event_message(Emitted("vision_paused", self.step, reason="unavailable")))
        self._broadcast_status()
        return conn

    async def leave(self, conn: Conn) -> None:
        conn.open = False
        self.conns.discard(conn)
        if conn is self.active_conn:                 # its camera is gone: another one may take over at once
            self.active_conn = self.active_source = None
        try:
            conn.queue.put_nowait(None)
        except asyncio.QueueFull:
            if conn.writer:
                conn.writer.cancel()
        if conn.writer:
            try:
                await asyncio.wait_for(conn.writer, 1.0)
            except (asyncio.TimeoutError, asyncio.CancelledError, Exception):   # noqa: BLE001 - the socket is gone
                pass
        if self.conns:
            self._broadcast_status()
        else:
            await self.close()

    async def close(self) -> None:
        """The last device left. The session object stays for the job; a device that joins later starts afresh."""
        self.epoch += 1                              # frames still on their way are not used
        if self._ticker:
            self._ticker.cancel()
            self._ticker = None
        if self.stream is not None:
            self.stream.close(wait=False)            # its threads end after the request they are in
            self.stream = None
        self.log.close()

    async def _writer(self, conn: Conn) -> None:
        try:
            while True:
                text = await conn.queue.get()
                if text is None:
                    return
                await conn.ws.send_text(text)
        except Exception:                            # noqa: BLE001 - the device went away
            conn.open = False

    def _send(self, conn: Conn, msg: dict) -> None:
        if not conn.open:
            return
        if self.simulated:
            msg = {**msg, "sim": True}               # nothing simulated may pass for the real thing
        try:
            conn.queue.put_nowait(json.dumps(msg, ensure_ascii=False, separators=(",", ":")))
        except asyncio.QueueFull:                    # a device that does not read: let it go
            conn.open = False
            if conn.writer:
                conn.writer.cancel()

    def _broadcast(self, msg: dict) -> None:
        for conn in list(self.conns):
            self._send(conn, msg)

    def error(self, conn: Conn, code: str, message: str) -> None:
        self._send(conn, {"type": "error", "code": code, "message": message})

    def _post(self, fn, *args) -> None:
        """From a worker thread: run `fn(*args)` on the event loop. Calls arrive there in the order posted."""
        loop = self.loop
        try:
            if loop is not None and not loop.is_closed():
                loop.call_soon_threadsafe(fn, *args)
        except RuntimeError:                         # the server is shutting down
            pass

    # ---- messages -------------------------------------------------------------------------------

    def _devices(self) -> dict:
        return dict(Counter(c.role for c in self.conns))

    def _hello(self, conn: Conn) -> dict:
        t = self.t
        return {"type": "hello", "protocol": PROTOCOL, "job_id": self.job_id, "session": self.id,
                "role": conn.role, "device": conn.device, "source": conn.source,
                "vision": "unavailable" if self.unavailable else "simulated" if self.simulated else "live",
                "reason": self.unavailable, "title": self.guide.title, "steps": self.guide.order,
                "step": self.step, "action": self.action, "devices": self._devices(),
                # The client follows these. One more than the calls in flight may be un-acked: the newest frame
                # waits for a free call. An ack can take two timeouts (the API client tries a dead connection twice).
                "limits": {"max_unacked": t.max_in_flight + 1, "max_hz": t.max_hz, "max_frame_bytes": t.max_frame_bytes,
                           "long_edge_px": 768, "jpeg_quality": 0.7,
                           "ack_timeout_ms": round((2 * t.decision_timeout_s + 1) * 1000), "in_flight": t.max_in_flight},
                "frame": {"layout": "[uint32 header_len][JSON header][JPEG]", "byte_order": "big-endian"}}

    def _expected(self) -> dict | None:
        e = self.coach.expect
        if e is None:
            return None
        item = e.action_id if e.now_kind else None
        actions = self.guide.actions(e.step)
        act = actions[e.action] if e.action is not None and e.action < len(actions) else None
        return {"kind": e.now_kind, "id": item, "name": self.guide.label(item),
                "action_type": e.action_kind, "actions": len(actions), "text": (act or {}).get("text")}

    def _rate_hz(self, source: str, now: float) -> tuple[float, str]:
        """How many frames a second are wanted now, and the drop reason for a frame that comes sooner."""
        t = self.t
        hz, reason = t.max_hz, "rate"
        cap = self.health.cap_hz(now)
        if cap is not None and cap < hz:
            hz, reason = cap, "rate_limit_wait"
        if t.rules(source).hand_gate:
            want = self.hands.wanted_hz(now)
            if want < hz:
                hz, reason = want, "gated"
        return hz, reason

    def _status_message(self, now: float) -> dict:
        hands = self.hands.view(now)
        belief = self.coach.belief(at_target=bool(hands and hands["at_target"]))
        source = self.active_source
        likely = source or ("headset" if any(c.source == "headset" for c in self.conns) else "fixed")
        held = belief["holding"]
        held["name"] = self.guide.label(held["id"])
        ms = sorted(self.decision_ms)
        pack = self._packs.get(self.step) if self.step is not None else None
        stream = self.stream
        looking = self.step is not None and not self.unavailable
        return {"type": "status", "ts": int(time.time() * 1000), "step": self.step, "action": self.action,
                "page": self.guide.page(self.step) if self.step is not None else None,
                "state": belief["state"], "expected": self._expected(), "holding": held, "verdict": belief["verdict"],
                "hands_present": belief["hands_present"], "view": belief["view"], "vision": self.vision,
                "reason": self.unavailable, "source": source,
                "rate_hz": self._rate_hz(likely, now)[0] if looking else 0.0,
                "hands": hands, "muted": belief["muted"], "show_part": self.show_part is not None,
                "devices": self._devices(),
                "voice_state": f"[STATE step={self.step}]" if self.step is not None else None,
                "questions": {"origin": pack.origin, "written_by": pack.written_by} if isinstance(pack, StepPack) else None,
                "frames": {"used": self.stats["used"], "dropped": self.stats["dropped"],
                           "decision_ms_p50": ms[len(ms) // 2] if ms else None,
                           "in_flight": stream.in_flight if stream is not None else 0}}

    def _broadcast_status(self) -> None:
        now = self.clock()
        self._dirty, self._status_at = False, now
        self._broadcast(self._status_message(now))

    def _event_message(self, e: Emitted, frame: Frame | None = None, extra: dict | None = None) -> dict:
        g = self.guide
        expected, observed = g.label(e.expected_id), g.label(e.observed_id)
        if e.event == "show_part_result":
            say = show_part_sentences(g, e.observed_id, e.expected_id, e.step, e.page)
        else:
            x = self.coach.expect
            say = sentences(e, g, x.next_step if x else None, x.next_page if x else None)
        speak = spoken(e.event)
        self._event_id += 1
        msg = {"type": "event", "event": e.event, "step": e.step, "page": e.page, "expected": expected,
               "observed": observed, "evidence": e.evidence, "ts": int(time.time() * 1000),
               # additions to PLAN §4.5
               "id": self._event_id, "action": self.action, "expected_id": e.expected_id, "observed_id": e.observed_id,
               "p": e.p, "say": say, "perception": perception_line(e.event, e.step, e.page, expected, observed, e.p),
               "speak": speak, "turn_complete": speak, "repeat": e.repeat, "seq": frame.seq if frame is not None else None,
               "latency_ms": None if frame is None else round((self.clock() - frame.t_recv) * 1000)}
        if e.votes:
            msg["votes"] = e.votes
        if e.reason:
            msg["reason"] = e.reason
        if e.reason == "unavailable":
            msg["message"] = self.unavailable
        if e.event == "show_part_result":
            msg["match"] = e.observed_id == e.expected_id if e.observed_id and e.expected_id else None
        if extra:
            msg.update(extra)
        return msg

    def _emit(self, events: list[Emitted], frame: Frame | None = None) -> None:
        for e in events:
            msg = self._event_message(e, frame)
            self._broadcast(msg)
            clip, _, t = self._clip_time(frame)
            self.log.write("events", {"clip": clip, "t": t, "event": e.event, "step": e.step,
                                      "latency_ms": msg["latency_ms"], "seq": msg["seq"], "expected_id": e.expected_id,
                                      "observed_id": e.observed_id, "p": e.p, "repeat": e.repeat,
                                      **({"sim": True} if self.simulated else {})})
            self._dirty = True

    def _system(self, name: str, reason: str | None = None) -> None:
        self._emit([self.coach.system_event(name, reason)])

    def _clip_time(self, frame: Frame | None) -> tuple[str, int | None, float]:
        """`clip`, `frame` and `t` for a log row: from the frame header when a replay sent them."""
        h = frame.header if frame is not None else {}
        clip = h["clip"] if isinstance(h.get("clip"), str) and h["clip"] else self.id
        index = h["frame"] if isinstance(h.get("frame"), int) else h.get("seq")
        t = h["t"] if isinstance(h.get("t"), (int, float)) and not isinstance(h.get("t"), bool) \
            else round((frame.t_recv if frame is not None else self.clock()) - self.t0, 3)
        return clip, index, t

    # ---- questions ------------------------------------------------------------------------------

    def _pack(self, n: int) -> StepPack:
        """The step's questions, read once. Called from the event loop's helper thread and from the workers."""
        with self._pack_lock:
            got = self._packs.get(n)
            if got is None:
                try:
                    got = load_pack(self.guide, n)
                except ValueError as e:
                    got = e
                self._packs[n] = got
        if isinstance(got, ValueError):
            raise got
        return got

    def _warm(self, n: int) -> None:
        """Read a step's questions and its sheet before the first frame needs them, off the event loop."""
        if self.decider is None or n in self._packs:
            return

        def read() -> None:
            build_ask(self.guide, self._pack(n), b"\xff\xd8\xff")       # also loads the sheet as JPEG

        def done(task: asyncio.Future) -> None:
            if not task.cancelled() and task.exception() is not None:
                self._no_questions(n)
            self._dirty = True

        asyncio.ensure_future(asyncio.to_thread(read)).add_done_callback(done)

    def _no_questions(self, n: int) -> None:
        if n not in self._told:
            self._told.add(n)
            why = self._packs.get(n)
            log.error("perception: job %s step %s: %s", self.job_id, n, why)
            self._broadcast({"type": "error", "code": "no_questions",
                             "message": f"Step {n} cannot be watched: {why}. Frames of this step are not looked at."})

    # ---- control messages -----------------------------------------------------------------------

    def on_text(self, conn: Conn, text: str) -> None:
        try:
            msg = json.loads(text)
        except ValueError:
            return self.error(conn, "bad_json", "a text message must be one JSON object")
        if not isinstance(msg, dict) or msg.get("type") not in CONTROL:
            return self.error(conn, "unknown_type", f"`type` must be one of: {', '.join(CONTROL)}")
        getattr(self, "_on_" + msg["type"])(conn, msg)

    def _set_step(self, conn: Conn, n, action) -> bool:
        if self.guide.stale():                       # the job was rebuilt since the session began
            try:
                self.guide = Guide(self.dir)
                with self._pack_lock:
                    self._packs, self._told = {}, set()
                forget(self.dir)
            except ValueError:
                pass
        if self.guide.step(n) is None:
            self.error(conn, "unknown_step", f"the guide has steps {self.guide.order[0]} to {self.guide.order[-1]}")
            return False
        if action is not None and not self._valid_action(n, action):
            self.error(conn, "unknown_action", f"step {n} has actions 0 to {len(self.guide.actions(n)) - 1}")
            action = None
        self.step, self.action = n, action
        self._remember()
        self.epoch += 1
        self.show_part = None
        self.coach.start_step(self.guide.expect(n, action))
        self.hands.new_action()
        self._warm(n)
        return True

    def _remember(self) -> None:
        self.position = {"step": self.step, "action": self.action, "at": int(time.time() * 1000)}

    def _valid_action(self, n: int, action) -> bool:
        return isinstance(action, int) and not isinstance(action, bool) and 0 <= action < len(self.guide.actions(n))

    def _on_start(self, conn: Conn, msg: dict) -> None:
        self.coach.reset_session()
        self.t0 = self.clock()
        self.active_conn = self.active_source = None
        if self._set_step(conn, msg.get("step"), msg.get("action")):
            self._broadcast_status()

    def _on_step(self, conn: Conn, msg: dict) -> None:
        if self._set_step(conn, msg.get("n", msg.get("step")), msg.get("action")):
            self._broadcast_status()

    def _on_action(self, conn: Conn, msg: dict) -> None:
        step = msg.get("step", self.step)
        if step != self.step and not self._set_step(conn, step, None):
            return
        if self.step is None:
            return self.error(conn, "not_started", "send `start` with a step first")
        index = msg.get("index")
        if not self._valid_action(self.step, index):
            return self.error(conn, "unknown_action",
                              f"step {self.step} has actions 0 to {len(self.guide.actions(self.step)) - 1}")
        self.action = index
        self._remember()
        self.coach.set_action(self.guide.expect(self.step, index))
        self.hands.new_action()
        self._broadcast_status()

    def _on_show_part(self, conn: Conn, msg: dict) -> None:
        if self.step is None:
            return self.error(conn, "not_started", "send `start` with a step first")
        expected = msg.get("expected")
        expected = str(expected) if expected is not None and self.guide.kind(str(expected)) else None
        self.show_part = {"expected": expected, "until": self.clock() + self.t.show_part_wait_s}
        self._broadcast_status()

    def _on_stop(self, conn: Conn, msg: dict) -> None:
        self.step = self.action = self.show_part = None
        self.epoch += 1
        self.coach.start_step(None)
        self._broadcast_status()

    def _on_hands(self, conn: Conn, msg: dict) -> None:
        now = self.clock()
        self.hands.update(msg, now)
        self.log.write("hands", {"t": round(now - self.t0, 3), "seq": msg.get("seq"), "wall_ms": int(time.time() * 1000),
                                 "hand_at_target": self.hands.at_target, "still": self.hands.still,
                                 "raised": self.hands.raised, "present": self.hands.present})
        self._dirty = True

    def _on_mute(self, conn: Conn, msg: dict) -> None:
        if self.coach.mute(msg.get("event") if isinstance(msg.get("event"), str) else None) is None:
            return self.error(conn, "nothing_to_mute", "name an `event`, or mute after a warning was given")
        self._broadcast_status()

    def _on_unmute(self, conn: Conn, msg: dict) -> None:
        self.coach.unmute(msg.get("event") if isinstance(msg.get("event"), str) else None)
        self._broadcast_status()

    def _on_sim(self, conn: Conn, msg: dict) -> None:
        if not isinstance(self.decider, SimulatedDecider):
            return self.error(conn, "not_simulated", "the debug script only exists when PERCEPTION_SIMULATE is on")
        try:
            script = self.decider.update(msg)
        except ValueError as e:
            return self.error(conn, "bad_script", str(e))
        self._broadcast({"type": "sim", "script": script})

    # ---- frames: on the event loop --------------------------------------------------------------

    def _ack(self, conn: Conn, seq, t_recv: float, dropped: str | None = None, decision_ms: int | None = None,
             answers: dict | None = None) -> None:
        self.stats["dropped" if dropped else "used"] += 1
        if dropped:
            self.stats[f"dropped:{dropped}"] += 1
        msg = {"type": "ack", "seq": seq, "latency_ms": round((self.clock() - t_recv) * 1000),
               "used": dropped is None, "dropped": dropped, "decision_ms": decision_ms}
        if answers is not None and conn.want_answers:
            msg["answers"] = answers
        self._send(conn, msg)

    def _show_part_wanted(self, now: float) -> dict | None:
        if self.show_part and now > self.show_part["until"]:
            self.show_part, self._dirty = None, True
        return self.show_part

    def _gate(self, header: dict, source: str, now: float) -> str | None:
        """Why this frame gets no decision call, or None when it gets one."""
        t = self.t
        if self.decider is None or self.health.blocked(now):
            return "vision_unavailable"
        if self.step is None:
            return "not_started"
        if header.get("step") is not None and header.get("step") != self.step:
            return "stale_step"                      # captured for a step that is no longer the current one
        if isinstance(self._packs.get(self.step), ValueError):
            return "no_questions"
        show = self._show_part_wanted(now)
        if self.coach.done and not show:
            return "step_done"
        if self.active_source not in (None, source) and now - self.source_seen_at < t.source_switch_after_s:
            return "other_source"
        if self.health.backing_off(now):
            return "backoff"
        if show:
            return None                              # a requested close look goes ahead of the rate rules
        hz, reason = self._rate_hz(source, now)
        if hz <= 0 or now - self.last_accept_at < t.rate_slack / hz:
            return reason
        return None

    def on_bytes(self, conn: Conn, data: bytes) -> None:
        t_recv = self.clock()
        try:
            header, jpeg = parse_frame(data, self.t.max_frame_bytes)
        except FrameError as e:
            self.error(conn, "bad_frame", str(e))
            return self._ack(conn, e.seq, t_recv, "bad_frame")
        source = header.get("source") if header.get("source") in SOURCES else conn.source
        reason = self._gate(header, source, t_recv)
        if reason:
            return self._ack(conn, header["seq"], t_recv, reason)
        frame = Frame(jpeg)
        frame.conn, frame.header, frame.seq, frame.source = conn, header, header["seq"], source
        frame.t_recv, frame.epoch, frame.step = t_recv, self.epoch, self.step
        frame.ts = self.last_ts = max(t_recv, self.last_ts + 1e-6)
        frame.show, self.show_part = self.show_part, None
        frame.codes = tuple(sorted(self.coach.expect.ok_hardware))
        self.last_accept_at = self.source_seen_at = t_recv
        self.active_source, self.active_conn = source, conn
        for old in self._submit(frame):              # all calls were busy and this frame is newer than the one waiting
            self._restore_show(old)
            self._ack(old.conn, old.seq, old.t_recv, "busy")

    def _submit(self, frame: Frame) -> list[Frame]:
        """Hand a frame to the Stream. Returns the waiting frames it replaced (the Stream keeps only the newest)."""
        if self.stream is None:
            self.stream = Stream(self._ask, _Handoff(self), k=max(1, self.t.max_in_flight))
        stream = self.stream
        with stream.cv:                              # re-entrant: no worker takes a frame while we compare
            before = [entry[0] for entry in stream.waiting]
            stream.submit(frame, frame.ts)
            kept = {id(entry[0]) for entry in stream.waiting}
        return [f for f in before if id(f) not in kept]

    def _restore_show(self, frame: Frame) -> None:
        """A frame that was to answer a `show_part` request was lost: the request waits for the next one."""
        if frame.show and self.show_part is None and frame.epoch == self.epoch:
            self.show_part = frame.show

    def _failed(self, kind: str, now: float, started: float | None = None) -> None:
        self.stats[f"fail:{kind}"] += 1
        self._dirty = True
        was = self.health.refused
        for name in self.health.fail(kind, now, started):
            self._system(name)
        if self.health.refused and self.health.refused != was:      # the key or the credit: say it once
            self._emit([Emitted("vision_paused", self.step, reason="unavailable")])

    def _on_answered(self, frame: Frame, answers: Answers) -> None:
        """The request for this frame ended well: the ack goes now. Its events follow, in frame order."""
        for name in self.health.ok(self.clock()):
            self._system(name)
        self.decision_ms.append(answers.ms)
        if frame.epoch != self.epoch:
            self._restore_show(frame)
            return self._ack(frame.conn, frame.seq, frame.t_recv, "stale", answers.ms)
        self._ack(frame.conn, frame.seq, frame.t_recv, None, answers.ms,
                  normalise(answers) if frame.conn.want_answers else None)

    def _on_failed(self, frame: Frame, kind: str, text: str) -> None:
        log.warning("decision call failed: %s (%s)", kind, text)
        self._failed(kind, self.clock(), frame.started)
        self._restore_show(frame)
        self._ack(frame.conn, frame.seq, frame.t_recv, kind)

    def _keep(self, frame: Frame, tag: str) -> None:
        """With `PERCEPTION_KEEP_FRAMES`: this frame goes to disk, one a second at most (always a `show_part` look)."""
        k = self.keeper
        if k is None or not k.on or (not frame.show and frame.t_recv - self._kept_at < KEEP_EVERY_S):
            return
        if k.keep(self.id, frame.seq, tag, frame):
            self._kept_at = frame.t_recv

    def _on_lost(self, frame: Frame, reason: str) -> None:
        """A frame that never became a request."""
        current = frame.epoch == self.epoch
        if reason in ("dark", "bad_frame"):
            self._keep(frame, reason)
        if reason == "dark" and current:
            self._emit(self.coach.on_bad_frame(frame.ts, "dark", frame.source), frame)
            self._dirty = True
        elif reason == "bad_frame":
            self.error(frame.conn, "bad_frame", "the JPEG cannot be decoded")
        elif reason == "no_questions" and current:
            self._no_questions(frame.step)
        self._restore_show(frame)
        self._ack(frame.conn, frame.seq, frame.t_recv, reason)

    def _on_result(self, answers: Answers, ts: float) -> None:
        """One frame's answers. The Stream releases them in the order the frames arrived."""
        frame = answers.frame
        if frame.epoch != self.epoch:
            return
        now = self.clock()
        clip, index, clip_t = self._clip_time(frame)
        self.log.write("frames", {"clip": clip, "frame": index, "t": clip_t, "latency_ms": answers.ms,
                                  "answers": normalise(answers), "seq": frame.seq, "step": frame.step,
                                  "source": frame.source, "resolution": answers.resolution,
                                  # added Sun 15 Nov: `t` restarts at every `start`, so the logs could not be joined
                                  "wall_ms": round((time.time() - (now - frame.t_recv)) * 1000),
                                  **({"ts": frame.header["ts"]} if isinstance(frame.header.get("ts"), (int, float)) else {}),
                                  **({"luma": round(answers.luma, 1)} if answers.luma is not None else {}),
                                  **({"show_part": True} if frame.show else {}),
                                  **({"sim": True} if self.simulated else {})})
        poor = ((answers.get("image_poor") or {}).get("noul") or 0.0) >= max(self.t.poor_at, self.t.watcher_config().unusable)
        self._keep(frame, "show" if frame.show else "poor" if poor else "used")
        if frame.show:
            self._emit([self._show_part_result(answers, frame.show, frame.codes)], frame)
        else:
            hands = self.hands if self.hands.fresh(now) else None
            events = self.coach.on_frame(answers, ts, frame.source, hands, now, answers.luma)
            self._emit(events, frame)
            following = self.coach.expect.next_step if self.coach.expect else None
            if following is not None and any(e.event == "step_complete" for e in events):
                self._warm(following)                # §6.2: load the next step's questions and sheet
        self._dirty = True

    def _show_part_result(self, answers: dict, show: dict, codes: tuple = ()) -> Emitted:
        """One close look at what the person holds up (PLAN §6.4 `show_part_request`). No smoothing, so a piece
        of hardware is named only on clear evidence: the code itself reaches the choice line, the model gives
        "nothing in the hand" almost no weight (an empty pinching hand was read as the expected screw at 0.77,
        with `no_hardware` at 0.18), and the yes/no about the hardware needed now (`codes`) does not disagree.
        Otherwise `observed_id` is None and `reason` says why; the sentence is "show it closer"."""
        t, cfg, x = self.t, self.t.watcher_config(), self.coach.expect
        part, code = pick(answers, "part_in_hand", cfg.choice), pick(answers, "hardware_in_hand", cfg.choice)
        p_conf = float((answers.get("part_in_hand") or {}).get("confidence") or 0.0)
        h_conf = float((answers.get("hardware_in_hand") or {}).get("confidence") or 0.0)
        poor = float((answers.get("image_poor") or {}).get("noul") or 0.0)
        empty = float(((answers.get("hardware_in_hand") or {}).get("probabilities") or {}).get("no_hardware") or 0.0)
        right = (answers.get(EXTRA) or {}).get("noul")
        observed, conf, why = None, max(p_conf, h_conf), None
        if poor >= max(t.poor_at, cfg.unusable):
            why = "image_poor"
        elif part and part not in SPECIAL:
            observed, conf = part, p_conf
        elif code and code not in SPECIAL:
            if empty >= t.show_nothing_max:
                why = "hand_may_be_empty"
            elif right is not None and code in codes and right < t.show_right_min:
                why = "not_confirmed"                # the choice names the needed code, the yes/no does not
            elif right is not None and codes and code not in codes and right >= cfg.yes:
                why = "not_confirmed"                # the choice names another code, the yes/no says "the right one"
            else:
                observed, conf = code, h_conf
        else:
            # The Coach's measured rule, for one frame: the yes/no is sure it is NOT the hardware needed, and the
            # choice leans to another code without reaching the line (a dowel at 50 cm on a dark floor: 0.74, with
            # `right_hardware` at 0.05). That code is named, so the answer is "wrong", not "show it closer".
            lean = (answers.get("hardware_in_hand") or {}).get("choice")
            if (t.hardware_veto and codes and right is not None and right <= cfg.no and not (part and part not in SPECIAL)
                    and lean is not None and lean not in SPECIAL and lean not in codes
                    and t.hardware_no_floor <= h_conf and empty < t.show_nothing_max):
                observed, conf = lean, h_conf
            else:
                why = "not_recognised"
        expected = show.get("expected") or (x.action_id if x and x.now_kind else None)
        evidence = {"part_in_hand.top": round(p_conf, 3), "hardware_in_hand.top": round(h_conf, 3),
                    "image_poor": round(poor, 3), "no_hardware": round(empty, 3)}
        if right is not None:
            evidence[EXTRA] = round(float(right), 3)
        return Emitted("show_part_result", self.step, x.page if x else None, expected_id=expected,
                       observed_id=observed, p=round(conf, 3), evidence=evidence, reason=why)

    # ---- frames: in a Stream worker thread ------------------------------------------------------

    def _lose(self, frame: Frame, reason: str) -> dict:
        self._post(self._on_lost, frame, reason)
        return NOTHING_TO_FEED

    def _ask(self, frame: Frame) -> dict:
        """One frame in, the API's answers out. Never raises: a frame without answers is reported to the
        event loop and returns `NOTHING_TO_FEED`."""
        t, decider = self.t, self.decider
        try:
            if frame.epoch != self.epoch or decider is None:
                return self._lose(frame, "stale")    # the step changed while it waited
            info = imaging.probe(frame)
            if info is None:
                return self._lose(frame, "bad_frame")
            if imaging.too_dark(info, t.dark_luma, t.dark_peak):     # too dark to be worth a call (§6.5)
                return self._lose(frame, "dark")
            try:
                pack = self._pack(frame.step)
            except ValueError:
                return self._lose(frame, "no_questions")
            jpeg = imaging.tighten(frame, t.tighten_factor) if self.health.tighten(self.clock()) else frame
            resolution = (t.show_part_resolution if frame.show else
                          "high" if frame.header.get("hint") == "high" else t.resolution)
            ask = build_ask(self.guide, pack, jpeg, codes=frame.codes, resolution=resolution,
                            only=SHOW_PART_QUESTIONS if frame.show else None, extra=t.ask_right_hardware)
            frame.started = self.clock()
            t0 = time.perf_counter()
            raw = decider.decide(ask)
            ms = round((time.perf_counter() - t0) * 1000)
        except DecisionError as e:
            self._post(self._on_failed, frame, e.kind, str(e))
            return NOTHING_TO_FEED
        except Exception:                            # noqa: BLE001 - one bad frame must not end the session
            log.exception("perception frame %s failed", frame.seq)
            return self._lose(frame, "error")
        answers = Answers(raw["answers"])
        answers.frame, answers.ms, answers.resolution = frame, ms, resolution
        answers.luma = info[2] if imaging.Image is not None else None
        self._post(self._on_answered, frame, answers)
        return {"answers": answers, "latency_ms": ms}

    # ---- the clock ------------------------------------------------------------------------------

    async def _tick_loop(self) -> None:
        t = self.t
        while self.conns:
            await asyncio.sleep(t.status_interval_s)
            now = self.clock()
            for name in self.health.tick(now):       # 3 s without a successful decision: vision_paused
                self._system(name)
            self._show_part_wanted(now)
            if self._dirty or now - self._status_at >= t.status_heartbeat_s:
                self._broadcast_status()


class Registry:
    """The sessions of this server, one per job, and the decider they share."""

    def __init__(self, settings, store):
        self.settings, self.store = settings, store
        self.tuning_error: str | None = None
        try:
            self.tuning = Tuning().with_overrides(settings.perception_tuning)
            self.tuning.watcher_config()
        except (ValueError, TypeError) as e:
            self.tuning, self.tuning_error = Tuning(), f"PERCEPTION_TUNING was ignored: {e}"
            log.error(self.tuning_error)
        self.sessions: dict[str, PerceptionSession] = {}
        self._gemini: GeminiDecider | None = None
        keep = int(getattr(settings, "perception_keep_frames", 0) or 0)
        self.keeper = FrameKeeper(Path(settings.jobs_dir) / "_host" / "frames" if keep > 0 else None, keep,
                                  int(getattr(settings, "perception_keep_frames_mb", 200) or 200))

    def _decider(self):
        s = self.settings
        if s.perception_simulate:
            return SimulatedDecider(self.tuning), None           # each session has its own script
        if s.gemini_api_key:
            if self._gemini is None:
                self._gemini = GeminiDecider(s.gemini_api_key, self.tuning)
            return self._gemini, None
        return None, NO_KEY

    def session(self, job_id: str, job_dir: Path) -> PerceptionSession:
        """The job's session. Raises ValueError when the job has no manifest yet."""
        session = self.sessions.get(job_id)
        if session is None:
            decider, reason = self._decider()
            session = self.sessions[job_id] = PerceptionSession(job_id, job_dir, self.tuning, decider, reason,
                                                                logs=self.settings.perception_logs,
                                                                keeper=self.keeper)
        return session

    async def close(self) -> None:
        for session in self.sessions.values():
            await session.close()
