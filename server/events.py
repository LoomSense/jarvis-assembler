"""Append-only event log per job (PLAN §4.4): `events.jsonl` feeds the SSE stream and the post-mortem.

One writer (this process). An event is on disk, fsynced, before `emit` returns, so a crash never
loses an event that a client has already seen.
"""
from __future__ import annotations

import asyncio
import json
import os
import threading
import time
from pathlib import Path
from typing import AsyncIterator, Callable

TYPES = {"thought", "tool_call", "tool_result", "image", "part_status", "step_ready",
         "approval_required", "progress", "error", "done", "inventory"}
LEVELS = {"info", "warn", "success", "error"}
ARG_LIMIT = 400


def truncate_args(value):
    """Cut long strings (code) inside tool arguments to 400 characters (PLAN §4.4)."""
    if isinstance(value, str):
        return value if len(value) <= ARG_LIMIT else value[:ARG_LIMIT] + "…"
    if isinstance(value, dict):
        return {k: truncate_args(v) for k, v in value.items()}
    if isinstance(value, list):
        return [truncate_args(v) for v in value]
    return value


class EventLog:
    def __init__(self, path: Path, job_id: str):
        self.path = path
        self.job_id = job_id
        self._lock = threading.Lock()
        self._waiters: set[tuple[asyncio.AbstractEventLoop, asyncio.Event]] = set()
        self.closed = False
        if self.path.exists() and self.path.stat().st_size:
            with open(self.path, "rb+") as f:
                f.seek(-1, os.SEEK_END)
                if f.read(1) != b"\n":                # a crash cut the last line: end it, or the next event
                    f.seek(0, os.SEEK_END)            # would be glued to the fragment and lost with it
                    f.write(b"\n")
        events, _ = self._read_from(0)
        self._seq = events[-1]["seq"] if events else 0

    @property
    def last_seq(self) -> int:
        return self._seq

    def emit(self, type: str, text: str = "", *, stage: str | None = None, level: str = "info", **fields) -> dict:
        if type not in TYPES:
            raise ValueError(f"unknown event type `{type}`")
        if level not in LEVELS:
            raise ValueError(f"unknown event level `{level}`")
        if "args" in fields:
            fields["args"] = truncate_args(fields["args"])
        with self._lock:
            self._seq += 1
            event = {"seq": self._seq, "ts": int(time.time() * 1000), "jobId": self.job_id, "stage": stage,
                     "type": type, "level": level, "partId": fields.pop("partId", None),
                     "stepN": fields.pop("stepN", None), "text": text}
            event.update({k: v for k, v in fields.items() if v is not None and k not in event})
            line = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with open(self.path, "a", encoding="utf-8") as f:
                f.write(line + "\n")
                f.flush()
                os.fsync(f.fileno())
        self._wake()
        return event

    def read(self, after: int = 0) -> list[dict]:
        events, _ = self._read_from(0)
        return [e for e in events if e["seq"] > after]

    async def follow(self, after: int = 0, heartbeat: float = 15.0,
                     stop: Callable[[], bool] = lambda: False) -> AsyncIterator[dict | None]:
        """Yield every event with seq > `after`, then new ones as they are written. `None` is a heartbeat."""
        waiter = (asyncio.get_running_loop(), asyncio.Event())
        self._waiters.add(waiter)
        offset = 0
        try:
            while not (stop() or self.closed):
                waiter[1].clear()                      # clear before reading, so no wake-up is lost
                events, offset = self._read_from(offset)
                for e in events:
                    if e["seq"] > after:
                        after = e["seq"]
                        yield e
                try:
                    await asyncio.wait_for(waiter[1].wait(), heartbeat)
                except asyncio.TimeoutError:
                    yield None
        finally:
            self._waiters.discard(waiter)

    def close(self) -> None:
        """Wake every follower so its stream ends (server shutdown)."""
        self.closed = True
        self._wake()

    def _wake(self) -> None:
        for loop, flag in list(self._waiters):
            try:
                loop.call_soon_threadsafe(flag.set)
            except RuntimeError:                       # the follower's loop is gone
                self._waiters.discard((loop, flag))

    def _read_from(self, offset: int) -> tuple[list[dict], int]:
        """Parse complete lines from `offset`; returns the events and the offset after the last complete line."""
        if not self.path.exists():
            return [], 0
        with open(self.path, "rb") as f:
            f.seek(offset)
            data = f.read()
        end = data.rfind(b"\n") + 1
        events = []
        for raw in data[:end].splitlines():
            try:
                events.append(json.loads(raw))
            except json.JSONDecodeError:
                continue                               # a torn line from a crash: skip it
        return events, offset + end
