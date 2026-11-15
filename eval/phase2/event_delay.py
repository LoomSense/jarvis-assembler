"""Delay from the first "finished" frame to the step_complete event, on stand-in frames (renders, no hands).

    .venv/bin/python eval/phase2/event_delay.py . jobs/knagglig [path/to/logic.py]     (needs GEMINI_API_KEY)

For every step with a "before" picture: 4 s of the before picture (any step_complete there is a false alarm), then the
finished picture until the event or 15 s. Four settings: frames per second and requests in flight (4, 4 = the
server's defaults; 8, 8), each with the fast lane of logic.Config off and on. The optional third argument loads
the Watcher from that file, to try a changed logic.py against an installed copy of the repo.
"""
import importlib.util, json, statistics, sys, time
from pathlib import Path
root, job = sys.argv[1], Path(sys.argv[2])
sys.path.insert(0, root)
from pipeline.perception import request
from pipeline.perception.stream import Stream
if len(sys.argv) > 3:
    spec = importlib.util.spec_from_file_location("logic_under_test", sys.argv[3])
    logic = importlib.util.module_from_spec(spec); sys.modules["logic_under_test"] = logic; spec.loader.exec_module(logic)
else:
    from pipeline.perception import logic
steps = [n for n in range(2, 30) if (job / f"refs/step-{n:02d}-before.png").exists()]
out = {}
for hz, k, fast in ((4, 4, 0), (4, 4, 4), (8, 8, 0), (8, 8, 4)):
    rows = []
    for n in steps:
        before, done = request.jpeg(job / f"refs/step-{n:02d}-before.png"), request.jpeg(job / f"refs/step-{n:02d}-ref.png")
        hit = []
        s = Stream(lambda j, n=n: request.ask(job, n, j), logic.Watcher(request.step_pack(job, n), cfg=logic.Config(done_sure_frames=fast)),
                   k=k, on_event=lambda e: hit.append(e) if e["type"] == "step_complete" else None)
        t0 = time.time()
        while time.time() - t0 < 4: s.submit(before, time.time()); time.sleep(1 / hz)
        early = len(hit); t1 = time.time()
        while not hit[early:] and time.time() - t1 < 15: s.submit(done, time.time()); time.sleep(1 / hz)
        st = s.stats(); s.close(wait=False)
        rows.append({"step": n, "delay_s": round(hit[early]["wall"] - t1, 2) if hit[early:] else None, "false_before": early,
                     "evidence": hit[early]["evidence"] if hit[early:] else None, "p50_ms": st["latency_ms"]["p50"], "dropped": st["dropped"], "errors": st["errors"]})
    d = [r["delay_s"] for r in rows if r["delay_s"] is not None]
    out[f"{hz}Hz k{k} fast{fast}"] = {"median_s": statistics.median(d) if d else None, "max_s": max(d) if d else None, "missed": len(rows) - len(d),
                                      "false_before": sum(r["false_before"] for r in rows), "errors": sum(r["errors"] for r in rows), "rows": rows}
    print(f"{hz} Hz, {k} in flight, fast lane {'on' if fast else 'off'}: median {out[f'{hz}Hz k{k} fast{fast}']['median_s']} s, "
          f"delays {[r['delay_s'] for r in rows]}, false {out[f'{hz}Hz k{k} fast{fast}']['false_before']}, errors {out[f'{hz}Hz k{k} fast{fast}']['errors']}", flush=True)
print("RESULT " + json.dumps(out))
