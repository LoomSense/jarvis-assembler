#!/usr/bin/env python3
"""Side-by-side table of two (or more) run summaries.

Usage:
  compare.py <run_a> <run_b> [...]   run folders (summary.json is made if missing) or agq .jsonl files
  compare.py --latest                the latest unity run vs the latest android run in tools/quest/runs/
  --out DIR                          default: tools/quest/runs/<timestamp>-compare/
Writes compare.md and compare.json; prints the markdown.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import summarize as S  # noqa: E402

QUEST_DIR = Path(__file__).resolve().parent
RUNS = QUEST_DIR / "runs"

# (label, path in summary.json, better: "low" | "high" | None)
ROWS = [
    ("App version", "version", None),
    ("Job / lang / replay", None, None),
    ("Duration s", "duration_s", None),
    ("Time to job_loaded s", "time_to_job_loaded_s", "low"),
    ("Identify p50 ms", "identify.latency_ms_p50", "low"),
    ("Identify ok / count", None, None),
    ("Locate p50 ms", "locate.latency_ms_p50", "low"),
    ("Tracked fraction", "tracking.tracked_frac", "high"),
    ("First tracked s", "tracking.first_tracked_s", "low"),
    ("Tracking losses", "tracking.losses", "low"),
    ("Longest untracked s", "tracking.longest_untracked_s", "low"),
    ("Reproj p50 px", "tracking.reproj_px_p50", "low"),
    ("Still jitter p50 mm", "tracking.jitter_mm_p50", "low"),
    ("Still jitter p90 mm", "tracking.jitter_mm_p90", "low"),
    ("Yaw jitter p50 deg", "tracking.yaw_jitter_deg_p50", "low"),
    ("FPS p50", "fps.p50", "high"),
    ("FPS p5", "fps.p5", "high"),
    ("Perception events", "perception.count", None),
    ("Steps auto / button", None, None),
    ("Max step", "steps.max_step", "high"),
    ("Frames sent Hz", "frames.rate_hz", None),
    ("Errors", "errors.count", "low"),
    ("Crashes", "crashes.count", "low"),
    ("Gates pass / fail / no data", None, None),
]


def load_summary(target: Path) -> dict:
    if target.is_file():                                    # bare log
        run_dir = RUNS / target.stem
        run_dir.mkdir(parents=True, exist_ok=True)
        (run_dir / "agq.jsonl").write_bytes(target.read_bytes())
        target = run_dir
    sj = target / "summary.json"
    log = target / "agq.jsonl"
    if log.exists() and (not sj.exists() or sj.stat().st_mtime < log.stat().st_mtime):
        s = S.summarize(log, target)
        sj.write_text(json.dumps(s, indent=2, ensure_ascii=False))
        (target / "summary.md").write_text(S.to_markdown(s))
    if not sj.exists():
        raise SystemExit(f"no agq.jsonl or summary.json in {target}")
    return json.loads(sj.read_text())


def cell(s: dict, label: str, path: str | None):
    if path:
        return S.get(s, path)
    if label.startswith("Job"):
        return f"{s.get('job')} / {s.get('lang')} / {s.get('replay')}"
    if label.startswith("Identify ok"):
        return f"{S.fmt(s['identify']['ok'])} / {s['identify']['count']}"
    if label.startswith("Steps auto"):
        return f"{s['steps']['auto']} / {s['steps']['button']}"
    if label.startswith("Gates"):
        return f"{s['gates_passed']} / {s['gates_failed']} / {s['gates_unknown']}"
    return None


def latest(app: str) -> Path | None:
    runs = sorted((p for p in RUNS.glob(f"*-{app}") if p.is_dir() and (p / "agq.jsonl").exists()),
                  key=lambda p: p.stat().st_mtime, reverse=True)
    return runs[0] if runs else None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("runs", nargs="*", type=Path)
    ap.add_argument("--latest", action="store_true")
    ap.add_argument("--out", type=Path)
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args()
    targets = list(a.runs)
    if a.latest:
        targets = [p for p in (latest("unity"), latest("android")) if p]
    if len(targets) < 2:
        ap.error("need two runs (or --latest with one unity and one android run)")
    sums = [load_summary(t) for t in targets]
    names = [f"{s.get('app') or '?'} ({Path(s['run']).name})" for s in sums]
    lines = ["| Metric | " + " | ".join(names) + " |", "|---|" + "---|" * len(sums)]
    table = []
    for label, path, better in ROWS:
        vals = [cell(s, label, path) for s in sums]
        best = None
        nums = [v for v in vals if isinstance(v, (int, float)) and not isinstance(v, bool)]
        if better and len(nums) == len(vals) and len(set(nums)) > 1:
            best = (min if better == "low" else max)(nums)
        shown = [f"**{S.fmt(v)}**" if best is not None and v == best else S.fmt(v) for v in vals]
        lines.append(f"| {label} | " + " | ".join(shown) + " |")
        table.append({"metric": label, "path": path, "better": better, "values": vals})
    gate_rows = ["", "| Gate | " + " | ".join(names) + " |", "|---|" + "---|" * len(sums)]
    for i, g in enumerate(sums[0]["gates"]):
        res = []
        for s in sums:
            gg = s["gates"][i] if i < len(s["gates"]) else {}
            res.append(f"{ {True: 'PASS', False: 'FAIL', None: 'no data'}[gg.get('pass')] } ({S.fmt(gg.get('value'))})")
        gate_rows.append(f"| {g['label']} | " + " | ".join(res) + " |")
    md = "## Quest run comparison\n\n" + "\n".join(lines + gate_rows) + "\n\n**Bold** = better of the runs.\n"
    out = a.out or RUNS / f"{time.strftime('%Y%m%d-%H%M%S')}-compare"
    out.mkdir(parents=True, exist_ok=True)
    (out / "compare.md").write_text(md)
    (out / "compare.json").write_text(json.dumps({"runs": [s["run"] for s in sums], "apps": [s.get("app") for s in sums],
                                                  "table": table}, indent=2, default=str))
    if not a.quiet:
        print(md)
    print(f"wrote {out / 'compare.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
