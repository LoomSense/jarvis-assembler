#!/usr/bin/env python3
"""Replay recorded clips through the real perception WebSocket and write a run for `p2.py score`
(PLAN §9, task LIV-05, format in eval/phase2/README.md §3).

    # frames first:  python eval/phase2/p2.py frames eval/phase2/clips/a_correct.mp4
    python eval/phase2/replay.py --job JOB --run runs/x --labels eval/phase2/labels.json \
        eval/phase2/clips/a_correct eval/phase2/clips/d_wrong_hardware
    python eval/phase2/replay.py --job JOB --run runs/x --step 2 --action 0 eval/phase2/clips/d_wrong_hardware
    python eval/phase2/p2.py score eval/phase2/labels.json runs/x/frames.jsonl runs/x/events.jsonl

A clip folder holds the frames `p2.py frames` wrote: `f00012_t003.00.jpg` is frame 12 at 3.00 s.
The step of each clip comes from `--labels` (the clip's `step`) or from `--step`.

Frames are sent as a camera client would send them: in real time (`--speed 1`), with at most as many
un-acked as the server's `hello.limits` allows. The time rules of the logic (cooldowns, "hands away for 2 s")
and the latency gates only mean something at `--speed 1`.

This is the end-to-end measurement: socket, scheduling, logic, events. To tune thresholds on answers that
were already paid for, use `python -m pipeline.perception.replay <job> labels.json runs/x --cached`, which
talks to the API directly and runs the same `Watcher`.

    --base http://localhost:8000   use a running server; without it the app runs inside this process
    --key KEY                      demo key (default: DEMO_KEY from the environment or .env)
    --source fixed|headset         which rules apply to the frames (default fixed: a camera on a stand)

Without GEMINI_API_KEY there is nothing to measure and the replay stops. With PERCEPTION_SIMULATE on,
the answers are scripted, not seen: the replay stops unless `--allow-simulated` is given (plumbing
checks only), and every row is then marked `"sim": true`.
"""
from __future__ import annotations

import argparse
import json
import queue
import re
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from server.perception.wire import pack_frame  # noqa: E402

FRAME_NAME = re.compile(r"^f(\d+)_t(\d+(?:\.\d+)?)\.jpe?g$")
MAX_UNACKED, ACK_TIMEOUT_S = 5, 9.0            # used only if the server's `hello.limits` does not say


class Link:
    """A WebSocket, either to a running server or to the app inside this process."""

    def __init__(self, send_text, send_bytes, recv, close):
        self.send_text, self.send_bytes, self.recv, self.close = send_text, send_bytes, recv, close


def remote_link(base: str, path: str) -> Link:
    from websockets.sync.client import connect
    ctx = connect(base.rstrip("/").replace("http", "ws", 1) + path, max_size=None)
    ws = ctx.__enter__()
    return Link(ws.send, ws.send, ws.recv, lambda: ctx.__exit__(None, None, None))


def local_link(client, path: str) -> Link:
    """`client` is a started `fastapi.testclient.TestClient`."""
    ctx = client.websocket_connect(path)
    ws = ctx.__enter__()
    return Link(ws.send_text, ws.send_bytes, ws.receive_text, lambda: ctx.__exit__(None, None, None))


def clip_frames(folder: Path) -> list[tuple[int, float, Path]]:
    frames = []
    for p in folder.iterdir():
        m = FRAME_NAME.match(p.name)
        if m:
            frames.append((int(m.group(1)), float(m.group(2)), p))
    return sorted(frames)


def replay_clip(open_link, job: str, key: str, folder: Path, step: int, run: Path, *, action: int | None = None,
                source: str = "fixed", speed: float = 1.0, allow_simulated: bool = False, hint: str = "medium",
                out=print) -> dict:
    """Send one clip's frames and append its rows to `run/frames.jsonl` and `run/events.jsonl`."""
    clip = folder.name
    frames = clip_frames(folder)
    if not frames:
        raise SystemExit(f"error: no frames like f00012_t003.00.jpg in {folder} (run `p2.py frames` on the clip)")
    link = open_link(f"/ws/perception/{job}?role=camera&source={source}&answers=1&device=replay&key={key}")
    inbox: queue.Queue = queue.Queue()

    def reader():
        try:
            while True:
                msg = json.loads(link.recv())        # the arrival time is read after the message came
                inbox.put((time.perf_counter(), msg))
        except Exception as e:                       # noqa: BLE001 - the socket closed
            inbox.put((time.perf_counter(), {"type": "closed", "error": type(e).__name__}))

    threading.Thread(target=reader, daemon=True).start()
    try:
        return _replay(link, inbox, clip, frames, step, run, action, source, speed, allow_simulated, hint, out)
    finally:
        try:
            link.close()
        except Exception:                            # noqa: BLE001 - already closed
            pass


def _replay(link, inbox, clip, frames, step, run, action, source, speed, allow_simulated, hint, out) -> dict:
    try:
        _, hello = inbox.get(timeout=10)
    except queue.Empty:
        raise SystemExit("error: the server sent nothing after the socket opened") from None
    if hello.get("type") == "error":
        raise SystemExit(f"error: {hello.get('code')}: {hello.get('message')}")
    vision = hello.get("vision")
    if vision == "unavailable":
        raise SystemExit(f"error: vision is unavailable on the server: {hello.get('reason')}")
    if vision != "live" and not allow_simulated:
        raise SystemExit("error: the server answers from a script (PERCEPTION_SIMULATE), not from Decision 1. "
                         "Nothing is measured. Pass --allow-simulated to check the plumbing only.")
    sim = vision != "live"
    limits = hello.get("limits") or {}             # a client follows what the server says
    max_unacked = int(limits.get("max_unacked") or MAX_UNACKED)
    ack_timeout = (limits.get("ack_timeout_ms") or ACK_TIMEOUT_S * 1000) / 1000

    run.mkdir(parents=True, exist_ok=True)
    sent: dict[int, dict] = {}                       # seq -> {frame, t, at}
    waiting: dict[int, float] = {}                   # un-acked seq -> when it was sent
    summary = {"clip": clip, "step": step, "frames": len(frames), "sent": 0, "skipped": 0, "used": 0,
               "dropped": {}, "events": [], "decision_ms": [], "sim": sim}
    with open(run / "frames.jsonl", "a", encoding="utf-8") as f_frames, \
            open(run / "events.jsonl", "a", encoding="utf-8") as f_events:

        def handle(at: float, msg: dict) -> None:
            kind = msg.get("type")
            if kind == "ack":
                waiting.pop(msg.get("seq"), None)
                info = sent.get(msg.get("seq"))
                if info is None:
                    return
                if msg.get("used"):
                    summary["used"] += 1
                    summary["decision_ms"].append(msg.get("decision_ms"))
                    row = {"clip": clip, "frame": info["frame"], "t": info["t"], "latency_ms": msg.get("decision_ms"),
                           "answers": msg.get("answers") or {}}
                    f_frames.write(json.dumps({**row, **({"sim": True} if sim else {})}, ensure_ascii=False) + "\n")
                else:
                    reason = msg.get("dropped") or "?"
                    summary["dropped"][reason] = summary["dropped"].get(reason, 0) + 1
            elif kind == "event":
                info = sent.get(msg.get("seq"))
                row = {"clip": clip, "t": info["t"] if info else round((at - started) * speed, 3),
                       "event": msg.get("event"), "step": msg.get("step"),
                       # frame capture (here: the moment it was sent) to event delivery
                       "latency_ms": round((at - info["at"]) * 1000) if info else None,
                       "expected_id": msg.get("expected_id"), "observed_id": msg.get("observed_id")}
                f_events.write(json.dumps({**row, **({"sim": True} if sim else {})}, ensure_ascii=False) + "\n")
                summary["events"].append((row["t"], row["event"]))
                out(f"  {row['t']:7.2f} s  {msg.get('event')}: {(msg.get('say') or {}).get('en', '')}")
            elif kind == "error":
                out(f"  server error {msg.get('code')}: {msg.get('message')}")
            elif kind == "closed":
                raise SystemExit(f"error: the socket closed during the replay ({msg.get('error')})")

        def drain(until: float) -> None:
            while True:
                left = until - time.perf_counter()
                try:
                    handle(*inbox.get(timeout=max(0.0, left)))
                except queue.Empty:
                    return

        started = time.perf_counter()
        start = {"type": "start", "step": step}
        if action is not None:
            start["action"] = action
        link.send_text(json.dumps(start))
        first_t = frames[0][1]
        for seq, (index, t, path) in enumerate(frames, 1):
            drain(started + (t - first_t) / speed)   # wait for this frame's moment, reading replies meanwhile
            now = time.perf_counter()
            for old in [s for s, at in waiting.items() if now - at > ack_timeout]:
                del waiting[old]                     # the client's watchdog for a lost reply
            if len(waiting) >= max_unacked:
                summary["skipped"] += 1
                continue
            header = {"seq": seq, "step": step, "ts": int(time.time() * 1000), "hint": hint, "source": source,
                      "clip": clip, "frame": index, "t": t}
            sent[seq] = {"frame": index, "t": t, "at": time.perf_counter()}
            waiting[seq] = sent[seq]["at"]
            link.send_bytes(pack_frame(header, path.read_bytes()))
            summary["sent"] += 1
        deadline = time.perf_counter() + ack_timeout + 0.5
        while waiting and time.perf_counter() < deadline:
            drain(min(deadline, time.perf_counter() + 0.05))
        link.send_text(json.dumps({"type": "stop"}))
        drain(time.perf_counter() + 0.2)
    ms = sorted(m for m in summary.pop("decision_ms") if m is not None)
    summary["decision_ms_p50"] = ms[len(ms) // 2] if ms else None
    summary["decision_ms_p95"] = ms[min(len(ms) - 1, round(0.95 * (len(ms) - 1)))] if ms else None
    return summary


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("clips", nargs="+", type=Path, help="clip folders with frames from `p2.py frames`")
    ap.add_argument("--job", required=True, help="job id on the server (it must be built: manifest and refs)")
    ap.add_argument("--run", required=True, type=Path, help="output folder, e.g. runs/x (rows are appended)")
    ap.add_argument("--labels", type=Path, help="labels.json: gives each clip its step")
    ap.add_argument("--step", type=int, help="guide step for every clip (instead of --labels)")
    ap.add_argument("--action", type=int, help="index of the current action in the step, as the display would report")
    ap.add_argument("--source", choices=("fixed", "headset"), default="fixed")
    ap.add_argument("--speed", type=float, default=1.0)
    ap.add_argument("--base", help="http(s)://host[:port] of a running server")
    ap.add_argument("--key", help="demo key (default: DEMO_KEY from the environment or .env)")
    ap.add_argument("--allow-simulated", action="store_true")
    args = ap.parse_args(argv)

    steps = {}
    if args.labels:
        steps = {c["id"]: c["step"] for c in json.loads(args.labels.read_text())["clips"]}
    from server.settings import Settings
    settings = Settings()
    key = args.key or settings.demo_key
    if not key:
        raise SystemExit("error: no demo key (pass --key or set DEMO_KEY)")
    client = None
    if args.base:
        open_link = lambda path: remote_link(args.base, path)      # noqa: E731
    else:
        from fastapi.testclient import TestClient
        from server.app import create_app
        client = TestClient(create_app(settings))
        client.__enter__()
        open_link = lambda path: local_link(client, path)          # noqa: E731
    results = []
    try:
        for folder in args.clips:
            step = args.step if args.step is not None else steps.get(folder.name)
            if step is None:
                raise SystemExit(f"error: no step for clip {folder.name} (use --labels or --step)")
            print(f"{folder.name}: step {step}, source {args.source}")
            s = replay_clip(open_link, args.job, key, folder, step, args.run, action=args.action, source=args.source,
                            speed=args.speed, allow_simulated=args.allow_simulated)
            results.append(s)
            print(f"  {s['sent']} of {s['frames']} frames sent, {s['used']} answered, dropped {s['dropped'] or 'none'}, "
                  f"skipped {s['skipped']}; decision p50 {s['decision_ms_p50']} ms, p95 {s['decision_ms_p95']} ms; "
                  f"{len(s['events'])} events" + ("  [SIMULATED]" if s["sim"] else ""))
    finally:
        if client is not None:
            client.__exit__(None, None, None)
    (args.run / "replay.json").write_text(json.dumps(
        {"job": args.job, "source": args.source, "speed": args.speed, "at": time.strftime("%Y-%m-%d %H:%M:%S"),
         "clips": results}, indent=1))
    print(f"run written to {args.run}/ (frames.jsonl, events.jsonl, replay.json)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
