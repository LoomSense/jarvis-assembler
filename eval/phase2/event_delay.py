"""Delay from the first "finished" frame to the step_complete event, with K requests in flight. Frames at 10 Hz.

    .venv/bin/python eval/phase2/event_delay.py . jobs/knagglig     (repo root, job folder; needs GEMINI_API_KEY)
"""
import json, statistics, sys, time
from pathlib import Path
root, job = sys.argv[1], Path(sys.argv[2])
sys.path.insert(0, root)
from pipeline.perception import request
from pipeline.perception.logic import Watcher
from pipeline.perception.stream import Stream
out = {}
for k in (4, 8):
    rows = []
    for n in (2, 4, 6):
        if not (job / f"refs/step-{n:02d}-before.png").exists(): continue
        before, done = request.jpeg(job / f"refs/step-{n:02d}-before.png"), request.jpeg(job / f"refs/step-{n:02d}-ref.png")
        hit = []
        s = Stream(lambda j, n=n: request.ask(job, n, j), Watcher(request.step_pack(job, n)), k=k,
                   on_event=lambda e: hit.append(e) if e["type"] == "step_complete" else None)
        t0 = time.time()
        while time.time() - t0 < 2: s.submit(before, time.time()); time.sleep(0.1)
        early = len(hit); t1 = time.time()
        while not hit[early:] and time.time() - t1 < 15: s.submit(done, time.time()); time.sleep(0.1)
        rows.append({"step": n, "delay_s": round(hit[early]["wall"] - t1, 2) if hit[early:] else None, "false_before": early, "stats": s.stats()})
        s.close(wait=False)
    d = [r["delay_s"] for r in rows if r["delay_s"] is not None]
    out[k] = {"delays": [r["delay_s"] for r in rows], "median": statistics.median(d) if d else None, "false_before": sum(r["false_before"] for r in rows), "stats": [r["stats"] for r in rows]}
print("RESULT " + json.dumps(out))
