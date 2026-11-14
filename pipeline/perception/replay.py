"""Run recorded clips through the questions and the decision logic, write what eval/phase2/p2.py scores (LIV-05).

    .venv/bin/python -m pipeline.perception.replay <job_dir> eval/phase2/labels.json eval/phase2/runs/<name> [--cached]

Needs the frames from `p2.py frames` (clips/<id>/f00012_t003.00.jpg). Writes frames.jsonl and events.jsonl
in the run folder, in the shape of eval/phase2/README.md §3. Frames go to Decision 1 one after the other, as live.
With --cached the answers already in frames.raw.jsonl are reused, so thresholds can be tuned without paying
for the requests again.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from pipeline.perception import request
from pipeline.perception.logic import Config, Watcher

NAME = re.compile(r"f(\d+)_t([\d.]+)\.jpe?g$")


def flat(answers: dict) -> dict:
    return {k: ({a["choice"]: a.get("confidence")} if a.get("type") == "choice" or "choice" in a else a.get("noul")) for k, a in answers.items()}


def run(job: Path, labels_path: Path, out: Path, cached: bool = False, cfg: Config | None = None) -> dict:
    labels = json.loads(labels_path.read_text())
    manifest = json.loads((job / "manifest.json").read_text())
    page = {s["n"]: s["cite"]["page"] for s in manifest["steps"]}
    out.mkdir(parents=True, exist_ok=True)
    raw_path = out / "frames.raw.jsonl"
    raw = {(r["clip"], r["frame"]): r for r in map(json.loads, raw_path.read_text().splitlines())} if cached and raw_path.exists() else {}
    frames, events, asked = [], [], 0
    with raw_path.open("a") as keep:
        for clip in labels["clips"]:
            folder = labels_path.parent / "clips" / clip["id"]
            files = sorted((int(m[1]), float(m[2]), f) for f in folder.glob("f*.jp*g") if (m := NAME.search(f.name)))
            if not files:
                print(f"  {clip['id']}: no frames in {folder}, skipped", file=sys.stderr)
                continue
            w = Watcher(request.step_pack(str(job), clip["step"]), guide_page=page.get(clip["step"]), cfg=cfg)
            for k, t, f in files:
                r = raw.get((clip["id"], k))
                if r is None:
                    a = request.ask(job, clip["step"], f)
                    r = {"clip": clip["id"], "frame": k, "t": t, "latency_ms": a["latency_ms"], "answers": a["answers"]}
                    keep.write(json.dumps(r) + "\n")
                    asked += 1
                frames.append({**r, "answers": flat(r["answers"])})
                for e in w.feed(r["answers"], t):
                    events.append({"clip": clip["id"], "t": e["ts"], "event": e["type"], "step": e["step"], "latency_ms": r["latency_ms"],
                                   "observed": e["observed"], "evidence": e["evidence"]})
    (out / "frames.jsonl").write_text("".join(json.dumps(r) + "\n" for r in frames))
    (out / "events.jsonl").write_text("".join(json.dumps(r) + "\n" for r in events))
    return {"clips": len({r["clip"] for r in frames}), "frames": len(frames), "requests": asked, "events": [f"{e['clip']} {e['t']} {e['event']}" for e in events]}


if __name__ == "__main__":
    a = [x for x in sys.argv[1:] if x != "--cached"]
    print("RESULT " + json.dumps(run(Path(a[0]), Path(a[1]), Path(a[2]), "--cached" in sys.argv)))
