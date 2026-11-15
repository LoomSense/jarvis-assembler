#!/usr/bin/env python3
"""One real Decisions API request for one image, exactly as the perception server sends it.

    python eval/phase2/try_decision.py <job_dir> <step> <image> [--action 0] [--repeat 5] [--high]
                                       [--only part_in_hand,hardware_in_hand] [--dry-run] [--show]

The request is the benchmarked one (`pipeline.perception.request`: step header, image, sheet note, reference
sheet, the questions of `refs/step-NN-questions.json`) plus the server's one extra question, `right_hardware`,
about the hardware the given action needs. It goes out through `pipeline.check.decisions`, like the server's.
Printed: each answer with the verdict the logic takes from it (the thresholds of
`pipeline.perception.logic.Config`), the round-trip time and the token usage. `--repeat` sends the same request
again on the open connection, which is the latency the live loop sees. `--dry-run` prints the request without
sending it and needs no key.

`python -m pipeline.perception.request <job_dir> <step> <image>` sends the same request without the extra question.
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from pipeline.check.decisions import URL  # noqa: E402
from pipeline.perception.logic import pick  # noqa: E402
from server.perception.config import Tuning  # noqa: E402
from server.perception.decider import DecisionError, GeminiDecider  # noqa: E402
from server.perception.guide import Guide  # noqa: E402
from server.perception.questions import MODEL, body_of, build_ask, load_pack  # noqa: E402


def as_jpeg(path: Path, long_edge: int = 768, quality: int = 70) -> bytes:
    """The image as the client would send it: JPEG, 768 px on the long edge, quality 0.7 (PLAN D6)."""
    from PIL import Image
    data = path.read_bytes()
    im = Image.open(io.BytesIO(data))
    if data.startswith(b"\xff\xd8\xff") and max(im.size) <= long_edge:
        return data
    im = im.convert("RGB")
    im.thumbnail((long_edge, long_edge))
    out = io.BytesIO()
    im.save(out, "JPEG", quality=quality)
    return out.getvalue()


def elided(body: dict) -> dict:
    """The request with the image bytes replaced by their size, for printing."""
    blocks = [{**b, "data": f"<{len(b['data']) * 3 // 4 // 1024} KB base64>"} if b.get("type") == "image" else b
              for b in body["input"]]
    return {**body, "input": blocks}


def show_answers(answers: dict, cfg) -> None:
    for key, a in answers.items():
        if a.get("choice") is not None:
            ranked = ", ".join(f"{k} {v:.2f}" for k, v in sorted((a.get("probabilities") or {}).items(), key=lambda kv: -kv[1]))
            verdict = pick(answers, key, cfg.choice) or f"not counted (below {cfg.choice})"
            print(f"  {key:22s} -> {verdict:28s} [{ranked or a['choice']}]")
        else:
            p = a.get("noul")
            if key == "step_complete":
                verdict = f"votes done (>= {cfg.done_yes})" if p >= cfg.done_yes else "no vote"
            elif key == "hands_present":
                verdict = f"hands in view (>= {cfg.hands})" if p >= cfg.hands else "no hands: what is held is not read"
            else:
                verdict = "yes" if p >= cfg.yes else "no" if p <= cfg.no else "unknown"
            print(f"  {key:22s} -> {verdict:28s} p(yes) = {p:.2f}")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("job_dir", help="a built job folder: manifest.json and refs/")
    ap.add_argument("step", type=int)
    ap.add_argument("image", help="a photo of the work area or of a hand holding a part")
    ap.add_argument("--action", type=int, help="index of the current action in the step (which screw is needed now)")
    ap.add_argument("--high", action="store_true", help="send both images at `high` (as a show_part look does)")
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--only", help="comma-separated question keys")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--show", action="store_true", help="print the request too")
    args = ap.parse_args(argv)

    tuning = Tuning()
    cfg = tuning.watcher_config()
    guide = Guide(Path(args.job_dir))
    if guide.step(args.step) is None:
        raise SystemExit(f"error: the guide has steps {guide.order[0]} to {guide.order[-1]}")
    pack = load_pack(guide, args.step)
    expect = guide.expect(args.step, args.action)
    only = tuple(k.strip() for k in args.only.split(",")) if args.only else None
    ask = build_ask(guide, pack, as_jpeg(Path(args.image)), codes=expect.ok_hardware,
                    resolution="high" if args.high else tuning.resolution, only=only, extra=tuning.ask_right_hardware)
    body = body_of(ask)
    print(f"POST {URL}  model {MODEL}  step {args.step}  {len(ask.questions)} questions  body {len(json.dumps(body)) // 1024} KB")
    print(f"questions: {pack.origin} ({pack.written_by})"
          + ("" if pack.origin == "file" else "  <- NOT the benchmarked files: this job was built before the refs stage wrote them"))
    if args.show or args.dry_run:
        print(json.dumps(elided(body), indent=1, ensure_ascii=False))
    if args.dry_run:
        print("dry run: nothing was sent")
        return 0

    from server.settings import Settings
    key = Settings().gemini_api_key
    if not key:
        raise SystemExit("error: GEMINI_API_KEY is not set (put it in .env). Use --dry-run to see the request.")
    decider = GeminiDecider(key, Tuning(decision_timeout_s=20.0))      # patient: this is a first contact
    times, code = [], 0
    for i in range(max(1, args.repeat)):
        t0 = time.perf_counter()
        try:
            raw = decider.decide(ask)
        except DecisionError as e:
            print(f"call {i + 1}: FAILED after {round((time.perf_counter() - t0) * 1000)} ms: {e.kind}: {e}")
            code = 2
            continue
        ms = round((time.perf_counter() - t0) * 1000)
        times.append(ms)
        print(f"call {i + 1}: {ms} ms" + ("  (includes the TLS handshake)" if i == 0 else ""))
        if i == 0:
            show_answers(raw["answers"], cfg)
            missing = sorted(set(ask.questions) - set(raw["answers"]))
            if missing:
                print("  NOT ANSWERED:", ", ".join(missing))
                code = 3
            print("  usage:", json.dumps(raw.get("usage")))
    if times:
        warm = sorted(times[1:]) or times
        print(f"latency: first {times[0]} ms, warm p50 {warm[len(warm) // 2]} ms over {len(warm)} call(s); "
              f"the server gives up on a call after {round(tuning.decision_timeout_s * 1000)} ms")
    return code


if __name__ == "__main__":
    sys.exit(main())
