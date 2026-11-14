#!/usr/bin/env python3
"""Phase-2 ground truth and scoring (PLAN §6, §9; tasks LIV-04, LIV-05). Standard library + ffmpeg.

  python eval/phase2/p2.py frames clips/a_correct.mp4      # 4 Hz, 768 px JPEGs named by time, for labelling and replay
  python eval/phase2/p2.py check  labels.json              # validate the labels
  python eval/phase2/p2.py expand labels.json              # per-frame ground truth as JSONL on stdout
  python eval/phase2/p2.py score  labels.json runs/x/frames.jsonl runs/x/events.jsonl [--json]

Labels are written as time segments, not per frame: a segment only lists the fields that change.
Formats are described in eval/phase2/README.md.
"""
import json, os, statistics as st, subprocess, sys

BOOL_Q = ["right_part", "orientation_ok", "panel_on_base", "step_complete", "next_step_part_used",
          "hands_present", "view_blocked", "image_poor", "frame_matches_setup"]
CHOICE_Q = {"part_in_hand": ["hardware_only", "nothing_held", "other"], "hardware_in_hand": ["no_hardware", "other"]}
STATES = ["WAITING_FOR_PART", "PART_IN_HAND", "PLACING", "STEP_COMPLETE"]
EVENTS = ["wrong_part", "flip_needed", "wrong_hardware", "out_of_order", "step_complete", "unsure_show_part", "vision_paused"]
SCORED_EVENTS = EVENTS[:5]          # the last two are status, not claims about the build
POS, NEG, CHOICE_MIN = 0.85, 0.15, 0.75   # PLAN §6.2 starting thresholds
MATCH_BEFORE, MATCH_AFTER = 0.5, 5.0      # a predicted event counts if it lands in [t-0.5 s, t+5 s]
GATES = {"event_accuracy": (">=", 0.70), "false_alarms_on_correct_clips": ("<=", 0), "detection_delay_p50_s": ("<=", 2.0),
         "decision_latency_p50_ms": ("<=", 250), "decision_latency_p95_ms": ("<=", 600), "event_latency_p50_ms": ("<=", 500)}


def die(msg):
    sys.exit("error: " + msg)


def duration(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path], capture_output=True, text=True).stdout
    return float(out.strip() or 0)


def cmd_frames(args):
    src = args[0]; fps = float(args[1]) if len(args) > 1 else 4.0
    out = os.path.splitext(src)[0]; os.makedirs(out, exist_ok=True)
    scale = "scale='if(gt(iw,ih),768,-2)':'if(gt(iw,ih),-2,768)'"  # 768 px long edge (PLAN D6)
    tmp = os.path.join(out, "tmp_%05d.jpg")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src, "-vf", f"fps={fps},{scale}", "-q:v", "5", "-start_number", "0", tmp], check=True)
    n = 0
    for f in sorted(x for x in os.listdir(out) if x.startswith("tmp_")):
        k = int(f[4:9]); os.replace(os.path.join(out, f), os.path.join(out, f"f{k:05d}_t{k / fps:06.2f}.jpg")); n += 1
    print(f"{n} frames at {fps:g} Hz in {out}/ (file names carry the time in seconds)")


def load_labels(path):
    d = json.load(open(path)); base = os.path.dirname(os.path.abspath(path))
    return d, base


def choices(d, q):
    extra = d.get("parts", []) if q == "part_in_hand" else [str(c) for c in d.get("hardware", [])]
    return extra + CHOICE_Q[q]


def problems(d, base):
    out = []
    if not d.get("clips"): out.append("no clips")
    for c in d.get("clips", []):
        cid = c.get("id", "?"); p = lambda m: out.append(f"clip {cid}: {m}")
        if c.get("kind") not in ("correct", "mistake", "hard"): p("kind must be correct | mistake | hard")
        if not isinstance(c.get("step"), int): p("step must be the guide step number")
        segs = c.get("segments", [])
        if not segs or segs[0].get("t0") != 0: p("first segment must start at t0 = 0")
        for a, b in zip(segs, segs[1:]):
            if b.get("t0", -1) <= a.get("t0", 0): p(f"segments must be in time order (t0 {b.get('t0')})")
        first = segs[0] if segs else {}
        for q in BOOL_Q + list(CHOICE_Q) + ["state"]:
            if q not in first: p(f"first segment must set {q}")
        for s in segs:
            for k, v in s.items():
                if k in ("t0", "note"): continue
                if k == "state" and v not in STATES: p(f"bad state {v}")
                elif k in BOOL_Q and v not in (True, False, None): p(f"{k} must be true, false or null")
                elif k in CHOICE_Q and v is not None and v not in choices(d, k): p(f"{k}={v} is not one of {choices(d, k)}")
                elif k not in BOOL_Q and k not in CHOICE_Q and k != "state": p(f"unknown field {k}")
        for e in c.get("events", []):
            if e.get("event") not in SCORED_EVENTS: p(f"bad event {e.get('event')}")
            if not isinstance(e.get("t"), (int, float)): p("event needs t (seconds)")
        if c.get("kind") == "correct" and any(e["event"] != "step_complete" for e in c.get("events", [])): p("a correct clip can only expect step_complete")
        f = os.path.join(base, c.get("file", ""))
        if os.path.isfile(f):
            dur = duration(f); c["_duration"] = dur
            if any(e.get("t", 0) > dur for e in c.get("events", [])) or (segs and segs[-1].get("t0", 0) > dur): p(f"labels run past the clip ({dur:.1f} s)")
        else:
            p(f"video not found: {c.get('file')} (clips/ is git-ignored; copy it there)")
    return out


def frame_truth(d, clip, fps):
    """Yield (frame index, t, {question: truth}) with segment values carried forward."""
    segs = clip["segments"]; dur = clip.get("_duration") or clip.get("duration") or (max([s["t0"] for s in segs] + [e["t"] for e in clip.get("events", [])]) + 2)
    cur, i, k = {}, 0, 0
    while k / fps < dur:
        t = k / fps
        while i < len(segs) and segs[i]["t0"] <= t:
            cur.update({a: b for a, b in segs[i].items() if a not in ("t0", "note")}); i += 1
        yield k, t, dict(cur); k += 1


def cmd_check(args):
    d, base = load_labels(args[0]); errs = problems(d, base)
    for e in errs: print("  -", e)
    n = sum(1 for c in d.get("clips", []) for _ in frame_truth(d, c, d.get("fps", 4))) if not errs else 0
    print(f"{len(d.get('clips', []))} clips, {n} labelled frames" if not errs else f"{len(errs)} problems")
    return 1 if errs else 0


def cmd_expand(args):
    d, base = load_labels(args[0]); problems(d, base)
    for c in d["clips"]:
        for k, t, truth in frame_truth(d, c, d.get("fps", 4)):
            print(json.dumps({"clip": c["id"], "frame": k, "t": round(t, 3), "step": c["step"], "truth": truth}))


def auc(pairs):
    pos = [p for p, y in pairs if y]; neg = [p for p, y in pairs if not y]
    if not pos or not neg: return None
    return sum((a > b) + 0.5 * (a == b) for a in pos for b in neg) / (len(pos) * len(neg))


def tuned_threshold(pairs, target=0.95):
    """Lowest threshold whose 'yes' answers are right at least `target` of the time (PLAN §9)."""
    best = None
    for thr in sorted({p for p, _ in pairs}, reverse=True):
        said = [y for p, y in pairs if p >= thr]
        if sum(said) / len(said) >= target: best = round(thr, 3)
        else: break
    return best


def pct(xs, q):
    xs = sorted(xs)
    return round(xs[min(len(xs) - 1, int(round(q * (len(xs) - 1))))]) if xs else None


def read_jsonl(path):
    return [json.loads(l) for l in open(path) if l.strip()]


def cmd_score(args):
    as_json = "--json" in args; args = [a for a in args if a != "--json"]
    d, base = load_labels(args[0]); errs = [e for e in problems(d, base) if "video not found" not in e]
    if errs: die("labels are not valid, run check: " + "; ".join(errs[:3]))
    fps = d.get("fps", 4)
    truth = {(c["id"], k): t for c in d["clips"] for k, _, t in frame_truth(d, c, fps)}
    frames = read_jsonl(args[1]); events = read_jsonl(args[2]) if len(args) > 2 else []

    bool_pairs = {q: [] for q in BOOL_Q}; choice = {q: [0, 0, 0] for q in CHOICE_Q}  # right, wrong, undecided
    unknown_frames = 0
    for f in frames:
        t = truth.get((f["clip"], f["frame"]))
        if t is None: unknown_frames += 1; continue
        for q, a in (f.get("answers") or {}).items():
            if q in BOOL_Q and t.get(q) is not None: bool_pairs[q].append((float(a), bool(t[q])))
            elif q in CHOICE_Q and t.get(q) is not None:
                best, conf = max(a.items(), key=lambda kv: kv[1])
                if conf < CHOICE_MIN or best == "other": choice[q][2] += 1
                else: choice[q][0 if best == t[q] else 1] += 1
    questions = {}
    for q, pairs in bool_pairs.items():
        if not pairs: continue
        dec = [(p >= POS, y) for p, y in pairs if p >= POS or p <= NEG]
        questions[q] = {"n": len(pairs), "accuracy_when_decided": round(sum(a == y for a, y in dec) / len(dec), 3) if dec else None,
                        "undecided_rate": round(1 - len(dec) / len(pairs), 3), "auc": (lambda v: None if v is None else round(v, 3))(auc(pairs)),
                        "threshold_for_0.95_precision": tuned_threshold(pairs)}
    for q, (r, w, u) in choice.items():
        if r + w + u: questions[q] = {"n": r + w + u, "accuracy_when_decided": round(r / (r + w), 3) if r + w else None, "undecided_rate": round(u / (r + w + u), 3)}

    hits, delays, false_alarms, missed, fa_correct = 0, [], [], [], 0
    for c in d["clips"]:
        pred = sorted((e for e in events if e["clip"] == c["id"] and e["event"] in SCORED_EVENTS), key=lambda e: e["t"]); used = set()
        for exp in c.get("events", []):
            m = next((i for i, e in enumerate(pred) if i not in used and e["event"] == exp["event"] and exp["t"] - MATCH_BEFORE <= e["t"] <= exp["t"] + MATCH_AFTER), None)
            if m is None: missed.append({"clip": c["id"], **exp})
            else: used.add(m); hits += 1; delays.append(max(0.0, pred[m]["t"] - exp["t"]))
        extra = [e for i, e in enumerate(pred) if i not in used]
        false_alarms += [{"clip": e["clip"], "t": e["t"], "event": e["event"]} for e in extra]
        if c["kind"] == "correct": fa_correct += len(extra)
    expected = sum(len(c.get("events", [])) for c in d["clips"])
    lat = [f["latency_ms"] for f in frames if f.get("latency_ms") is not None]
    elat = [e["latency_ms"] for e in events if e.get("latency_ms") is not None]
    r = {
        "frames_scored": len(frames) - unknown_frames, "frames_without_label": unknown_frames,
        "event_accuracy": round(hits / (expected + len(false_alarms)), 3) if expected + len(false_alarms) else None,
        "events_expected": expected, "events_hit": hits, "events_missed": missed, "false_alarms": false_alarms,
        "false_alarms_on_correct_clips": fa_correct,
        "detection_delay_p50_s": round(st.median(delays), 2) if delays else None, "detection_delay_max_s": round(max(delays), 2) if delays else None,
        "decision_latency_p50_ms": pct(lat, 0.5), "decision_latency_p95_ms": pct(lat, 0.95), "event_latency_p50_ms": pct(elat, 0.5),
        "questions": questions,
    }
    r["gates"] = {k: ("no data" if r[k] is None else "PASS" if (r[k] >= v if op == ">=" else r[k] <= v) else "FAIL") + f" ({op} {v})" for k, (op, v) in GATES.items()}
    r["pass"] = all(g.startswith("PASS") for g in r["gates"].values())
    if as_json: print(json.dumps(r, indent=2)); return 0 if r["pass"] else 1
    for k, g in r["gates"].items(): print(f"{k:32s} {str(r[k]):>8s}  {g}")
    print(f"events: {hits}/{expected} hit, {len(false_alarms)} false alarms, delay max {r['detection_delay_max_s']} s")
    for m in missed: print("  missed     ", m)
    for m in false_alarms: print("  false alarm", m)
    print(f"{'question':22s} {'n':>5s} {'acc':>6s} {'undecided':>9s} {'auc':>6s}  threshold for 0.95 precision")
    for q, v in questions.items():
        print(f"{q:22s} {v['n']:5d} {str(v['accuracy_when_decided']):>6s} {v['undecided_rate']:9.2f} {str(v.get('auc', '')):>6s}  {v.get('threshold_for_0.95_precision', '')}")
    print("RESULT", "PASS" if r["pass"] else "FAIL")
    return 0 if r["pass"] else 1


if __name__ == "__main__":
    cmds = {"frames": cmd_frames, "check": cmd_check, "expand": cmd_expand, "score": cmd_score}
    if len(sys.argv) < 3 or sys.argv[1] not in cmds: sys.exit(__doc__)
    sys.exit(cmds[sys.argv[1]](sys.argv[2:]) or 0)
