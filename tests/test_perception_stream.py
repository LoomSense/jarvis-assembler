"""Stream scheduling with a fake ask (no network): latest frame only, at most K in flight, in-order feeding."""
import asyncio
import threading
import time

from pipeline.perception.stream import Stream


class Recorder:
    """Stands in for logic.Watcher: records the timestamps it is fed, emits one event per frame."""
    def __init__(self):
        self.fed = []

    def feed(self, answers, ts):
        self.fed.append(ts)
        return [{"type": "seen", "ts": ts}]


class GatedAsk:
    """ask() blocks until the test releases that frame; counts concurrency."""
    def __init__(self):
        self.lock = threading.Lock()
        self.gates, self.started, self.now, self.peak = {}, [], 0, 0

    def __call__(self, jpeg):
        with self.lock:
            self.now += 1
            self.peak = max(self.peak, self.now)
            gate = self.gates.setdefault(jpeg, threading.Event())
            self.started.append(jpeg)
        gate.wait(5)
        with self.lock:
            self.now -= 1
        if jpeg.startswith(b"bad"):
            raise RuntimeError("Decisions API 500: boom")
        return {"answers": {"x": jpeg.decode()}, "latency_ms": 1}

    def release(self, jpeg):
        with self.lock:
            self.gates.setdefault(jpeg, threading.Event()).set()


def wait_for(cond, t=2.0):
    end = time.time() + t
    while not cond():
        assert time.time() < end, "timed out"
        time.sleep(0.005)


def test_latest_frame_only_and_k_in_flight():
    ask, w = GatedAsk(), Recorder()
    s = Stream(ask, w, k=2)
    s.submit(b"f0", 0.0)
    s.submit(b"f1", 0.1)
    wait_for(lambda: len(ask.started) == 2)
    for i in range(2, 6):                       # both workers busy: f2..f4 replaced in turn, f5 waits
        s.submit(f"f{i}".encode(), i / 10)
    time.sleep(0.05)
    assert ask.started == [b"f0", b"f1"] and s.dropped == 3
    ask.release(b"f0")
    wait_for(lambda: len(ask.started) == 3)
    assert ask.started[2] == b"f5"              # the newest frame, not the oldest waiting one
    for f in (b"f1", b"f5"):
        ask.release(f)
    s.close()
    assert ask.peak == 2 and s.max_in_flight == 2
    assert w.fed == [0.0, 0.1, 0.5] and s.stats()["submitted"] == 6


def test_out_of_order_answers_are_fed_in_order():
    ask, w = GatedAsk(), Recorder()
    s = Stream(ask, w, k=3)
    for i in range(3):
        s.submit(f"f{i}".encode(), float(i))
        wait_for(lambda: len(ask.started) == i + 1)
    ask.release(b"f2")
    ask.release(b"f1")
    time.sleep(0.05)
    assert w.fed == []                          # held until f0 answers
    ask.release(b"f0")
    s.close()
    assert w.fed == [0.0, 1.0, 2.0] and s.late == 0
    assert [e["ts"] for e in s.events] == [0.0, 1.0, 2.0] and all("wall" in e and "frame_wall" in e for e in s.events)


def test_failed_request_releases_its_place():
    ask, w = GatedAsk(), Recorder()
    s = Stream(ask, w, k=2)
    s.submit(b"bad0", 0.0)
    wait_for(lambda: len(ask.started) == 1)
    s.submit(b"f1", 1.0)
    wait_for(lambda: len(ask.started) == 2)
    ask.release(b"f1")
    ask.release(b"bad0")
    s.close()
    assert w.fed == [1.0] and s.errors == 1 and "500" in s.error_texts[0]


def test_drop_mode_drops_late_answers():
    ask, w = GatedAsk(), Recorder()
    s = Stream(ask, w, k=2, order="drop")
    s.submit(b"f0", 0.0)
    wait_for(lambda: len(ask.started) == 1)
    s.submit(b"f1", 1.0)
    wait_for(lambda: len(ask.started) == 2)
    ask.release(b"f1")
    wait_for(lambda: w.fed == [1.0])
    ask.release(b"f0")
    s.close()
    assert w.fed == [1.0] and s.late == 1


def test_events_reach_asyncio_queue():
    async def main():
        ask, w = GatedAsk(), Recorder()
        s = Stream(ask, w, k=1)
        q = s.subscribe()
        s.submit(b"f0", 0.0)
        ask.release(b"f0")
        e = await asyncio.wait_for(q.get(), 2)
        s.close()
        return e
    assert asyncio.run(main())["ts"] == 0.0
