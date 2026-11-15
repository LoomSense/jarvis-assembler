#!/usr/bin/env python3
"""Write synthetic AGQ logs that exercise every field summarize.py reads (no headset needed).

  python tools/quest/samples/make_samples.py
  -> tools/quest/samples/agq-sample.jsonl          (app "unity": marker tracking, good run)
  -> tools/quest/samples/agq-sample-android.jsonl  (app "android": slower, more dropouts, one error)

The shape of each line is the AGQ contract: {"t","app","ev",...}, `t` in epoch seconds.
"""
from __future__ import annotations

import json
import math
import random
from pathlib import Path

HERE = Path(__file__).resolve().parent
T0 = 1_794_750_000.0          # 2026-11-15, epoch seconds


def make(app: str, seed: int, *, load_s, id_ms, loc_ms, noise_mm, drop_every, fps_base, error) -> list[dict]:
    rnd = random.Random(seed)
    ev: list[dict] = []

    def emit(t, name, **kw):
        ev.append({"t": round(T0 + t, 3), "app": app, "ev": name, **kw})

    emit(0.0, "start", ver="0.1.0", job="malm-drawer", lang="el", replay=False, hud=True)
    emit(load_s, "job_loaded", job="malm-drawer", steps=7, ms=int(load_s * 1000))
    emit(load_s + 4.0 + id_ms / 1000, "identify", ok=True, ms=id_ms, job="malm-drawer", next_step=1, confidence=0.86)
    emit(load_s + 9.0 + loc_ms / 1000, "locate", ok=True, ms=loc_ms, step=1, residual_px=6.1)
    base = [0.12, 0.74, -0.45]                     # drawer on a table, metres
    yaw0 = 31.0
    t_track = load_s + 2.0
    step, last_step_t = 1, None
    for i in range(115):
        t = t_track + i
        moved = 55 <= i < 58                       # the user turns the drawer around (moving windows)
        if moved:
            base = [base[0] + 0.05, base[1], base[2] - 0.03]
            yaw0 += 30
        tracked = i >= 3 and not (drop_every and i % drop_every in (0, 1))
        pos = [round(b + rnd.gauss(0, noise_mm / 1000), 4) for b in base]
        emit(t, "track", fps=round(max(30.0, rnd.gauss(fps_base, 1.5) - (12 if i % 37 == 0 else 0)), 1),
             tracked=tracked, pos_m=pos if tracked else None,
             yaw_deg=round(yaw0 + rnd.gauss(0, 0.3), 2) if tracked else None,
             reproj_px=round(abs(rnd.gauss(1.4 if app == "unity" else 2.6, 0.5)), 2) if tracked else None,
             source="marker" if tracked else "none")
        for k in range(4):                         # frames to the server at 4 Hz
            emit(t + k * 0.25, "frame", bytes=int(rnd.gauss(52000, 4000)), ms=int(abs(rnd.gauss(180, 40))))
        if i % 8 == 4:
            emit(t + 0.5, "perception", kind=rnd.choice(["hands", "step_check", "part_seen"]), ok=True)
        if i in (20, 38, 61, 80, 96, 108) and step < 7:
            via = "auto" if i in (20, 61, 96, 108) else "button"
            emit(t + 0.6, "step", n=step + 1, prev=step, via=via)
            step += 1
            last_step_t = t
    if error:
        emit(t_track + 70.2, "error", msg="perception socket closed (1006), reconnecting")
    return ev


def write(path: Path, events: list[dict]) -> None:
    with path.open("w") as f:
        f.write("--------- beginning of main\n")           # logcat noise the parser must tolerate
        for e in events:
            f.write(json.dumps(e, separators=(",", ":")) + "\n")
    print(f"wrote {path} ({len(events)} events)")


if __name__ == "__main__":
    write(HERE / "agq-sample.jsonl",
          make("unity", 1, load_s=3.2, id_ms=1850, loc_ms=2400, noise_mm=0.8, drop_every=0, fps_base=72, error=False))
    write(HERE / "agq-sample-android.jsonl",
          make("android", 2, load_s=5.6, id_ms=2300, loc_ms=3100, noise_mm=2.2, drop_every=9, fps_base=70, error=True))
