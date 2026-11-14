"""Coarse (0.5 mm) STL export on a fresh solid (OCC caches triangulation per shape). Usage: python bench_cq_coarse.py OUTDIR"""
import importlib, json, os, statistics as st, sys, time
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, os.path.join(HERE, "parts"))
import cadquery as cq, trimesh
OUT = sys.argv[1]; res = {}
for n in ["end_frame", "side_slat", "bottom_slat", "screw_long_108363", "screw_short_108560"]:
    m = importlib.import_module(n); ts = []
    for _ in range(3):
        wp, _h = m.build(m.PARAMS); t = time.perf_counter()
        cq.exporters.export(wp, f"{OUT}/{n}_coarse.stl", tolerance=0.5, angularTolerance=0.5); ts.append(time.perf_counter() - t)
    res[n] = {"stl_0.5_med_ms": round(st.median(ts) * 1000, 2), "tris_0.5": len(trimesh.load(f"{OUT}/{n}_coarse.stl").faces),
              "stl_kb_0.5": os.path.getsize(f"{OUT}/{n}_coarse.stl") // 1024}
print(json.dumps(res, indent=1)); json.dump(res, open(os.path.join(HERE, "results_cq_coarse.json"), "w"), indent=1)
