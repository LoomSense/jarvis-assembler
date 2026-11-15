"""Shared reader for AGQ logs (logcat tag AGQ, one JSON object per line).

Every line is `{"t", "app", "ev", ...}`. `t` may be epoch seconds, epoch milliseconds,
seconds since start, or an ISO-8601 string: `load()` normalises it to float seconds.
Lines that are not JSON objects are counted, not fatal.
"""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path


def _to_seconds(t) -> float | None:
    if isinstance(t, bool) or t is None:
        return None
    if isinstance(t, (int, float)):
        t = float(t)
        return t / 1000.0 if t > 1e11 else t      # epoch ms vs s
    if isinstance(t, str):
        try:
            return float(t)
        except ValueError:
            pass
        try:
            return datetime.fromisoformat(t.replace("Z", "+00:00")).timestamp()
        except ValueError:
            return None
    return None


def load(path: Path) -> tuple[list[dict], int]:
    """Return (events sorted by time, number of unparseable lines)."""
    events, bad = [], 0
    for i, line in enumerate(Path(path).read_text(errors="replace").splitlines()):
        line = line.strip()
        if not line:
            continue
        start = line.find("{")                       # tolerate a logcat prefix before the JSON
        try:
            obj = json.loads(line[start:]) if start >= 0 else None
        except json.JSONDecodeError:
            obj = None
        if not isinstance(obj, dict) or "ev" not in obj:
            bad += 1
            continue
        t = _to_seconds(obj.get("t"))
        obj["_t"] = t if t is not None else float(i)  # keep order even without t
        obj["_line"] = i + 1
        events.append(obj)
    events.sort(key=lambda e: (e["_t"], e["_line"]))
    return events, bad


def first(d: dict, *keys, default=None):
    """First present, non-None value among keys."""
    for k in keys:
        if k in d and d[k] is not None:
            return d[k]
    return default


def num(v) -> float | None:
    if isinstance(v, bool) or v is None:
        return None
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if f == f else None                     # drop NaN


def vec3(v) -> list[float] | None:
    if isinstance(v, dict):
        v = [v.get("x"), v.get("y"), v.get("z")]
    if isinstance(v, (list, tuple)) and len(v) >= 3:
        out = [num(x) for x in v[:3]]
        if all(x is not None for x in out):
            return out
    return None
