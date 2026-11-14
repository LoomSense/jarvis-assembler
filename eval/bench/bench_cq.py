"""CadQuery benchmark. Usage: python bench_cq.py OUTDIR  (run with the venv python)"""
import importlib, json, os, statistics as st, subprocess, sys, time
HERE = os.path.dirname(os.path.abspath(__file__)); PARTS = os.path.join(HERE, "parts")
OUT = os.path.abspath(sys.argv[1]); PY = sys.executable
NAMES = ["end_frame", "side_slat", "bottom_slat", "screw_long_108363", "screw_short_108560"]
med = lambda xs: st.median(xs)
res = {}
# 1. cold import (fresh interpreter each time)
imp = [float(subprocess.run([PY, "-c", "import time;t=time.perf_counter();import cadquery;print(time.perf_counter()-t)"],
        capture_output=True, text=True).stdout) for _ in range(3)]
res["import_cold_s"] = imp
py_bare = []
for _ in range(3):
    t = time.perf_counter(); subprocess.run([PY, "-c", "pass"]); py_bare.append(time.perf_counter() - t)
res["python_bare_spawn_s"] = py_bare
sys.path.insert(0, PARTS)
import cadquery as cq, trimesh, harness
parts = {}
for n in NAMES:
    m = importlib.import_module(n); r = {"build": [], "stl05": [], "stl50": [], "step": []}
    for i in range(3):
        t0 = time.perf_counter(); wp, holes = m.build(m.PARAMS); t1 = time.perf_counter()
        cq.exporters.export(wp, f"{OUT}/{n}.stl", tolerance=0.05, angularTolerance=0.1); t2 = time.perf_counter()
        cq.exporters.export(wp, f"{OUT}/{n}_coarse.stl", tolerance=0.5, angularTolerance=0.5); t3 = time.perf_counter()
        cq.exporters.export(wp, f"{OUT}/{n}.step"); t4 = time.perf_counter()
        r["build"].append(t1 - t0); r["stl05"].append(t2 - t1); r["stl50"].append(t3 - t2); r["step"].append(t4 - t3)
    for k in list(r): r[k + "_med_ms"] = round(med(r.pop(k)) * 1000, 2)
    r["tris_0.05"] = len(trimesh.load(f"{OUT}/{n}.stl").faces)
    r["tris_0.5"] = len(trimesh.load(f"{OUT}/{n}_coarse.stl").faces)
    r["stl_kb_0.05"] = os.path.getsize(f"{OUT}/{n}.stl") // 1024
    r["step_kb"] = os.path.getsize(f"{OUT}/{n}.step") // 1024
    r["holes"] = len(holes)
    parts[n] = r
res["parts"] = parts
# 2. subprocess per part vs one process for all
sub = []
for i in range(3):
    t = time.perf_counter()
    for n in NAMES:
        subprocess.run([PY, f"{n}.py", OUT], cwd=PARTS, capture_output=True, check=True)
    sub.append(time.perf_counter() - t)
one_code = ("import sys,time;t=time.perf_counter();sys.path.insert(0,'.');import harness,importlib\n"
            f"for n in {NAMES!r}:\n m=importlib.import_module(n); harness.run(n,m.build,m.PARAMS,[{OUT!r}])\n"
            "print('TOTAL',time.perf_counter()-t)")
one = []
for i in range(3):
    t = time.perf_counter(); subprocess.run([PY, "-c", one_code], cwd=PARTS, capture_output=True, check=True); one.append(time.perf_counter() - t)
res["all_parts_subprocess_each_s"] = sub
res["all_parts_one_process_s"] = one
print(json.dumps(res, indent=1))
json.dump(res, open(os.path.join(HERE, "results_cq.json"), "w"), indent=1)
