#!/usr/bin/env python3
"""Summarise one Quest run: agq.jsonl -> summary.json + summary.md.

Usage:
  summarize.py <run_dir>                 reads <run_dir>/agq.jsonl (+ meta.json, logcat.txt if present)
  summarize.py <file.jsonl> [--out DIR]  a bare log; default output tools/quest/runs/<stem>/
  --quiet                                do not print the markdown

Fields read per event (extra fields are ignored, missing ones are tolerated):
  start                 first one marks t=0
  job_loaded            job, steps
  identify / locate     ms | latency_ms | dt_ms ; ok ; step | next_step
  track                 fps, tracked, pos_m [x,y,z], yaw_deg, reproj_px, source ; optional frame, q
  perception            kind | type | name
  step                  n | step ; via | by | cause | trigger  (auto, button, voice, ...)
  frame                 bytes ; ms | rtt_ms
  error                 msg | message | error
"""
from __future__ import annotations

import argparse
import json
import math
import statistics as st
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from agq import first, load, num, vec3  # noqa: E402

QUEST_DIR = Path(__file__).resolve().parent
JITTER_WINDOW_S = 2.0
STILL_RANGE_M = 0.02          # a window whose positions span more than 2 cm is "moving", not jitter
GAP_S = 3.0                   # track samples further apart than this count as untracked time

# Gates: (metric path, comparison, threshold, label). Tune here; the judge reads pass/fail.
GATES = [
    ("time_to_job_loaded_s", "<=", 10.0, "job loads within 10 s"),
    ("identify.latency_ms_p50", "<=", 4000.0, "identify answers within 4 s"),
    ("locate.latency_ms_p50", "<=", 6000.0, "locate answers within 6 s"),
    ("tracking.tracked_frac", ">=", 0.80, "tracked at least 80% of the time"),
    ("tracking.reproj_px_p50", "<=", 3.0, "median reprojection error at most 3 px"),
    ("tracking.jitter_mm_p50", "<=", 3.0, "still jitter (median) at most 3 mm"),
    ("fps.p5", ">=", 60.0, "p5 frame rate at least 60 fps"),
    ("errors.count", "<=", 0, "no errors"),
    ("crashes.count", "<=", 0, "no crashes"),
]


def pct(xs: list[float], p: float) -> float | None:
    if not xs:
        return None
    xs = sorted(xs)
    k = (len(xs) - 1) * p / 100.0
    lo, hi = math.floor(k), math.ceil(k)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def rnd(v, n=3):
    return None if v is None else round(v, n)


def latency_block(evs: list[dict]) -> dict:
    lat = [x for x in (num(first(e, "ms", "latency_ms", "dt_ms", "rtt_ms")) for e in evs) if x is not None]
    oks = [e.get("ok") for e in evs if "ok" in e]
    results = [first(e, "next_step", "step", "result") for e in evs]
    return {
        "count": len(evs),
        "ok": sum(1 for o in oks if o is True) if oks else None,
        "failed": sum(1 for o in oks if o is False) if oks else None,
        "latency_ms_p50": rnd(pct(lat, 50), 0),
        "latency_ms_max": rnd(max(lat), 0) if lat else None,
        "results": [r for r in results if r is not None][:10],
    }


def wrap_deg(a: float) -> float:
    return (a + 180.0) % 360.0 - 180.0


def jitter(track: list[dict]) -> dict:
    """Pose noise while the object is still: RMS distance from the window mean over 2 s windows."""
    pts = [(e["_t"], vec3(e.get("pos_m")), num(e.get("yaw_deg")), e.get("source")) for e in track
           if e.get("tracked") is True and vec3(e.get("pos_m")) is not None]
    pos_j, yaw_j, moving = [], [], 0
    for i, (t0, _, _, src) in enumerate(pts):
        win = [p for p in pts[i:] if p[0] - t0 <= JITTER_WINDOW_S + 1e-6 and p[3] == src]
        if len(win) < 2 or win[-1][0] - t0 < JITTER_WINDOW_S * 0.45:
            continue
        P = [w[1] for w in win]
        span = max(math.dist(a, b) for a in P for b in P)
        if span > STILL_RANGE_M:
            moving += 1
            continue
        mean = [sum(c) / len(P) for c in zip(*P)]
        pos_j.append(1000.0 * math.sqrt(sum(math.dist(p, mean) ** 2 for p in P) / len(P)))
        yaws = [w[2] for w in win if w[2] is not None]
        if len(yaws) >= 2:
            ref = yaws[0]
            d = [wrap_deg(y - ref) for y in yaws]
            yaw_j.append(st.pstdev(d))
    return {
        "jitter_windows": len(pos_j),
        "moving_windows": moving,
        "jitter_mm_p50": rnd(pct(pos_j, 50), 2),
        "jitter_mm_p90": rnd(pct(pos_j, 90), 2),
        "yaw_jitter_deg_p50": rnd(pct(yaw_j, 50), 3),
    }


def tracking(track: list[dict], t0: float) -> dict:
    if not track:
        return {"samples": 0}
    flags = [e.get("tracked") is True for e in track]
    tracked_time = total_time = 0.0
    for a, b in zip(track, track[1:]):
        dt = b["_t"] - a["_t"]
        total_time += dt
        if a.get("tracked") is True and dt <= GAP_S:
            tracked_time += dt
    losses = sum(1 for a, b in zip(flags, flags[1:]) if a and not b)
    longest, cur_start = 0.0, None
    for e, f in zip(track, flags):
        if not f and cur_start is None:
            cur_start = e["_t"]
        elif f and cur_start is not None:
            longest = max(longest, e["_t"] - cur_start)
            cur_start = None
    if cur_start is not None:
        longest = max(longest, track[-1]["_t"] - cur_start)
    reproj = [x for x in (num(e.get("reproj_px")) for e in track if e.get("tracked") is True) if x is not None]
    first_tr = next((e["_t"] - t0 for e, f in zip(track, flags) if f), None)
    rate = (len(track) - 1) / (track[-1]["_t"] - track[0]["_t"]) if len(track) > 1 and track[-1]["_t"] > track[0]["_t"] else None
    out = {
        "samples": len(track),
        "rate_hz": rnd(rate, 2),
        "tracked_frac": rnd(tracked_time / total_time if total_time > 0 else sum(flags) / len(flags), 3),
        "tracked_sample_frac": rnd(sum(flags) / len(flags), 3),
        "first_tracked_s": rnd(first_tr, 2),
        "losses": losses,
        "longest_untracked_s": rnd(longest, 1),
        "sources": dict(Counter(str(e.get("source")) for e, f in zip(track, flags) if f)),
        "reproj_px_p50": rnd(pct(reproj, 50), 2),
        "reproj_px_p90": rnd(pct(reproj, 90), 2),
    }
    out.update(jitter(track))
    return out


def crashes(logcat: Path) -> dict:
    if not logcat.exists():
        return {"count": 0, "checked": False, "lines": []}
    hits = [ln.strip() for ln in logcat.read_text(errors="replace").splitlines()
            if "FATAL EXCEPTION" in ln or "Fatal signal" in ln or " ANR in " in ln or "CRASH" in ln]
    return {"count": len(hits), "checked": True, "lines": hits[:5]}


def get(d: dict, path: str):
    for k in path.split("."):
        if not isinstance(d, dict) or k not in d:
            return None
        d = d[k]
    return d


def summarize(log: Path, run_dir: Path) -> dict:
    events, bad = load(log)
    meta = {}
    if (run_dir / "meta.json").exists():
        meta = json.loads((run_dir / "meta.json").read_text())
    by = {}
    for e in events:
        by.setdefault(e["ev"], []).append(e)
    starts = by.get("start", [])
    t0 = starts[0]["_t"] if starts else (events[0]["_t"] if events else 0.0)
    jl = by.get("job_loaded", [])
    track = by.get("track", [])
    fps = [x for x in (num(e.get("fps")) for e in track) if x is not None]
    steps = by.get("step", [])
    vias = [str(first(e, "via", "by", "cause", "trigger", default="unknown")) for e in steps]
    step_ns = [n for n in (num(first(e, "n", "step")) for e in steps) if n is not None]
    frames = by.get("frame", [])
    frame_ms = [x for x in (num(first(e, "ms", "rtt_ms")) for e in frames) if x is not None]
    frame_bytes = [x for x in (num(e.get("bytes")) for e in frames) if x is not None]
    errors = by.get("error", [])
    perception = by.get("perception", [])
    span = (events[-1]["_t"] - events[0]["_t"]) if len(events) > 1 else 0.0
    apps = Counter(str(e.get("app")) for e in events if e.get("app"))

    s = {
        "run": str(run_dir),
        "app": meta.get("app") or (apps.most_common(1)[0][0] if apps else None),
        "version": meta.get("version") or first(starts[0], "ver", "version") if starts else meta.get("version"),
        "job": meta.get("job") or (first(jl[0], "job") if jl else None),
        "lang": meta.get("lang") or (starts[0].get("lang") if starts else None),
        "replay": meta["replay"] if "replay" in meta else (starts[0].get("replay") if starts else None),
        "events": len(events),
        "bad_lines": bad,
        "by_event": dict(Counter(e["ev"] for e in events)),
        "duration_s": rnd(span, 1),
        "restarts": max(0, len(starts) - 1),
        "time_to_job_loaded_s": rnd(jl[0]["_t"] - t0, 2) if jl else None,
        "job_steps": first(jl[0], "steps", "n_steps") if jl else None,
        "identify": latency_block(by.get("identify", [])),
        "locate": latency_block(by.get("locate", [])),
        "tracking": tracking(track, t0),
        "fps": {"p50": rnd(pct(fps, 50), 1), "p5": rnd(pct(fps, 5), 1), "min": rnd(min(fps), 1) if fps else None},
        "perception": {"count": len(perception),
                       "by_kind": dict(Counter(str(first(e, "kind", "type", "name", default="?")) for e in perception))},
        "steps": {"advances": len(steps), "by_via": dict(Counter(vias)),
                  "auto": sum(1 for v in vias if v.startswith("auto")),
                  "button": sum(1 for v in vias if v in ("button", "tap", "pinch", "ui")),
                  "max_step": int(max(step_ns)) if step_ns else None,
                  "sequence": [f"{int(n) if n is not None else '?'}:{v}" for n, v in
                               zip([num(first(e, "n", "step")) for e in steps], vias)][:30]},
        "frames": {"count": len(frames),
                   "rate_hz": rnd(len(frames) / span, 2) if span > 0 and frames else None,
                   "bytes_p50": rnd(pct(frame_bytes, 50), 0), "ms_p50": rnd(pct(frame_ms, 50), 0)},
        "errors": {"count": len(errors),
                   "messages": [str(first(e, "msg", "message", "error", default=json.dumps(
                       {k: v for k, v in e.items() if not k.startswith("_")})))[:200] for e in errors][:10]},
        "crashes": crashes(run_dir / "logcat.txt"),
    }
    gates = []
    for path, op, thr, label in GATES:
        v = get(s, path)
        if v is None:
            res = None
        else:
            res = v <= thr if op == "<=" else v >= thr
        gates.append({"metric": path, "op": op, "threshold": thr, "value": v, "pass": res, "label": label})
    s["gates"] = gates
    s["gates_passed"] = sum(1 for g in gates if g["pass"] is True)
    s["gates_failed"] = sum(1 for g in gates if g["pass"] is False)
    s["gates_unknown"] = sum(1 for g in gates if g["pass"] is None)
    return s


def fmt(v) -> str:
    if v is None:
        return "–"
    if isinstance(v, float):
        return f"{v:g}"
    if isinstance(v, dict):
        return ", ".join(f"{k} {fmt(x)}" for k, x in v.items()) or "–"
    return str(v)


def to_markdown(s: dict) -> str:
    t, idf, loc = s["tracking"], s["identify"], s["locate"]
    rows = [
        ("Events / bad lines / restarts", f"{s['events']} / {s['bad_lines']} / {s['restarts']}"),
        ("Duration", f"{fmt(s['duration_s'])} s"),
        ("Time to job_loaded", f"{fmt(s['time_to_job_loaded_s'])} s ({fmt(s['job_steps'])} steps)"),
        ("Identify", f"{idf['count']}x, p50 {fmt(idf['latency_ms_p50'])} ms, max {fmt(idf['latency_ms_max'])} ms, "
                     f"ok {fmt(idf['ok'])}, results {fmt(idf['results'])}"),
        ("Locate", f"{loc['count']}x, p50 {fmt(loc['latency_ms_p50'])} ms, max {fmt(loc['latency_ms_max'])} ms, ok {fmt(loc['ok'])}"),
        ("Tracked (time / samples)", f"{fmt(t.get('tracked_frac'))} / {fmt(t.get('tracked_sample_frac'))}, "
                                     f"{t.get('samples', 0)} samples at {fmt(t.get('rate_hz'))} Hz"),
        ("First tracked", f"{fmt(t.get('first_tracked_s'))} s after start"),
        ("Losses / longest gap", f"{fmt(t.get('losses'))} / {fmt(t.get('longest_untracked_s'))} s"),
        ("Sources (tracked)", fmt(t.get("sources"))),
        ("Reprojection p50 / p90", f"{fmt(t.get('reproj_px_p50'))} / {fmt(t.get('reproj_px_p90'))} px"),
        ("Still jitter p50 / p90", f"{fmt(t.get('jitter_mm_p50'))} / {fmt(t.get('jitter_mm_p90'))} mm "
                                   f"({fmt(t.get('jitter_windows'))} windows, {fmt(t.get('moving_windows'))} moving), "
                                   f"yaw {fmt(t.get('yaw_jitter_deg_p50'))} deg"),
        ("FPS p50 / p5 / min", f"{fmt(s['fps']['p50'])} / {fmt(s['fps']['p5'])} / {fmt(s['fps']['min'])}"),
        ("Perception events", f"{s['perception']['count']} ({fmt(s['perception']['by_kind'])})"),
        ("Step advances", f"{s['steps']['advances']}: auto {s['steps']['auto']}, button {s['steps']['button']}, "
                          f"by via {fmt(s['steps']['by_via'])}, max step {fmt(s['steps']['max_step'])}"),
        ("Frames sent", f"{s['frames']['count']} at {fmt(s['frames']['rate_hz'])} Hz, "
                        f"p50 {fmt(s['frames']['bytes_p50'])} B, {fmt(s['frames']['ms_p50'])} ms"),
        ("Errors / crashes", f"{s['errors']['count']} / {s['crashes']['count']}"),
    ]
    out = [f"## {s.get('app') or '?'} run {Path(s['run']).name}",
           f"job {fmt(s['job'])}, lang {fmt(s['lang'])}, replay {fmt(s['replay'])}, version {fmt(s['version'])}", "",
           "| Metric | Value |", "|---|---|"]
    out += [f"| {k} | {v} |" for k, v in rows]
    out += ["", f"Gates: {s['gates_passed']} pass, {s['gates_failed']} fail, {s['gates_unknown']} no data", ""]
    out += ["| Gate | Value | Result |", "|---|---|---|"]
    for g in s["gates"]:
        res = {True: "PASS", False: "FAIL", None: "no data"}[g["pass"]]
        out.append(f"| {g['label']} ({g['metric']} {g['op']} {g['threshold']:g}) | {fmt(g['value'])} | {res} |")
    if s["errors"]["messages"]:
        out += ["", "Errors:"] + [f"- {m}" for m in s["errors"]["messages"]]
    if s["crashes"]["lines"]:
        out += ["", "Crash lines (logcat.txt):"] + [f"- `{m[:200]}`" for m in s["crashes"]["lines"]]
    return "\n".join(out) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("target", type=Path, help="run folder or agq .jsonl file")
    ap.add_argument("--out", type=Path, help="output folder (default: the run folder)")
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args()
    if a.target.is_dir():
        log, run_dir = a.target / "agq.jsonl", a.target
        if not log.exists():
            print(f"no agq.jsonl in {a.target}", file=sys.stderr)
            return 1
    elif a.target.is_file():
        log = a.target
        run_dir = a.out or (QUEST_DIR / "runs" / log.stem)
        run_dir.mkdir(parents=True, exist_ok=True)
        if (run_dir / "agq.jsonl").resolve() != log.resolve():
            (run_dir / "agq.jsonl").write_bytes(log.read_bytes())   # makes it a run folder compare.py can read
    else:
        print(f"not found: {a.target}", file=sys.stderr)
        return 1
    out = a.out or run_dir
    out.mkdir(parents=True, exist_ok=True)
    s = summarize(log, run_dir)
    (out / "summary.json").write_text(json.dumps(s, indent=2, ensure_ascii=False))
    md = to_markdown(s)
    (out / "summary.md").write_text(md)
    if not a.quiet:
        print(md)
        print(f"wrote {out / 'summary.json'} and summary.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
