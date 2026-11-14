"""Live frames to Decision 1 answers to events, with K requests in flight (LIV-03 latency).

    s = Stream(lambda jpeg: request.ask(job, step, jpeg), Watcher(pack), k=4, on_event=print)
    s.submit(jpeg_bytes, ts)       # never blocks; safe to call from an asyncio handler
    q = s.subscribe(loop)          # optional: asyncio.Queue that receives every event
    s.stats(); s.close()

Scheduling. K worker threads, each with its own kept-open HTTPS connection (decisions.py keeps one per thread).
Frames go to idle workers in arrival order. When every worker is busy there is one waiting slot: a new frame
replaces the frame in it and the replaced one counts as `dropped`. So no old frame is ever queued behind a newer one.

Ordering. A frame gets a sequence number when a worker takes it. Answers are fed to `Watcher.feed` in sequence
(= timestamp) order: an answer that returns before an earlier frame's answer is held until that one returns or
fails. Nothing is lost, and the hold is bounded by the request timeout. A failed request releases its slot
without feeding anything (`errors`). `order="drop"` instead feeds an answer at once and drops it (`late`) if a
newer frame was already fed: no waiting, fewer votes.

Each event is the Watcher event plus `wall` (time.time() when produced), `frame_wall` (when the frame was
submitted) and `latency_ms` of the request that triggered it, so `wall - frame_wall` is the delay from frame to event.
"""
from __future__ import annotations

import asyncio
import statistics
import threading
import time
from typing import Callable


class Stream:
    def __init__(self, ask: Callable[[bytes], dict], watcher, k: int = 4, on_event: Callable[[dict], None] | None = None,
                 order: str = "hold"):
        assert order in ("hold", "drop") and k >= 1
        self.ask, self.watcher, self.k, self.on_event, self.order = ask, watcher, k, on_event, order
        self.cv = threading.Condition()
        self.feed_lock = threading.Lock()
        self.waiting = []                 # [(jpeg, ts, wall)]: at most one more than the idle workers
        self.closed = False
        self.next_seq = 0                 # next sequence number to hand out
        self.next_feed = 0                # hold mode: next sequence number to feed
        self.done = {}                    # hold mode: seq -> (answers or None, ts, wall, latency_ms)
        self.last_fed_ts = None
        self.in_flight = self.max_in_flight = 0
        self.submitted = self.dropped = self.answered = self.errors = self.late = 0
        self.latencies, self.events, self.error_texts = [], [], []
        self.answer_walls = []            # wall time of every answer fed
        self._queues = []
        self.threads = [threading.Thread(target=self._work, daemon=True, name=f"decisions-{i}") for i in range(k)]
        for t in self.threads:
            t.start()

    def submit(self, jpeg: bytes, ts: float) -> None:
        with self.cv:
            self.submitted += 1
            self.waiting.append((jpeg, ts, time.time()))
            while len(self.waiting) > max(1, self.k - self.in_flight):   # all workers busy: keep only the newest
                self.waiting.pop(0)
                self.dropped += 1
            self.cv.notify()

    def subscribe(self, loop: asyncio.AbstractEventLoop | None = None) -> asyncio.Queue:
        """An asyncio.Queue on `loop` (default: the running loop) that receives every event from now on."""
        loop = loop or asyncio.get_running_loop()
        q = asyncio.Queue()
        self._queues.append((loop, q))
        return q

    def close(self, wait: bool = True) -> None:
        with self.cv:
            self.closed = True
            self.cv.notify_all()
        if wait:
            for t in self.threads:
                t.join()

    def _work(self) -> None:
        while True:
            with self.cv:
                while not self.waiting and not self.closed:
                    self.cv.wait()
                if not self.waiting:
                    return
                jpeg, ts, wall = self.waiting.pop(0)
                seq, self.next_seq = self.next_seq, self.next_seq + 1
                self.in_flight += 1
                self.max_in_flight = max(self.max_in_flight, self.in_flight)
            answers, ms = None, None
            try:
                out = self.ask(jpeg)
                answers, ms = out["answers"], out.get("latency_ms")
            except Exception as e:          # a failed frame must still release its place in the order
                with self.cv:
                    self.errors += 1
                    self.error_texts.append(f"{type(e).__name__}: {str(e)[:300]}")
            with self.cv:
                self.in_flight -= 1
                if answers is not None:
                    self.answered += 1
                    if ms is not None:
                        self.latencies.append(ms)
            self._deliver(seq, answers, ts, wall, ms)

    def _deliver(self, seq, answers, ts, wall, ms) -> None:
        with self.feed_lock:
            if self.order == "drop":
                ready = [(answers, ts, wall, ms)]
            else:
                self.done[seq] = (answers, ts, wall, ms)
                ready = []
                while self.next_feed in self.done:
                    ready.append(self.done.pop(self.next_feed))
                    self.next_feed += 1
            for answers, ts, wall, ms in ready:
                if answers is None:
                    continue
                if self.last_fed_ts is not None and ts <= self.last_fed_ts:
                    self.late += 1
                    continue
                self.last_fed_ts = ts
                self.answer_walls.append(time.time())
                for e in self.watcher.feed(answers, ts):
                    e = {**e, "wall": time.time(), "frame_wall": wall, "latency_ms": ms}
                    self.events.append(e)
                    if self.on_event:
                        self.on_event(e)
                    for loop, q in self._queues:
                        loop.call_soon_threadsafe(q.put_nowait, e)

    def stats(self) -> dict:
        lat = sorted(self.latencies)
        pct = lambda f: lat[min(len(lat) - 1, int(round(f * (len(lat) - 1))))] if lat else None
        return {"k": self.k, "order": self.order, "submitted": self.submitted, "dropped": self.dropped, "answered": self.answered,
                "errors": self.errors, "late": self.late, "max_in_flight": self.max_in_flight,
                "latency_ms": {"p50": statistics.median(lat) if lat else None, "p95": pct(0.95), "max": lat[-1] if lat else None},
                "events": len(self.events)}
