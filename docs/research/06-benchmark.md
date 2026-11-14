# KNAGGLIG 3D pipeline benchmark: CadQuery + Blender on a MacBook Air M4

Measured 2026-11-14 on a MacBook Air M4 (10 cores, 16 GB) running macOS 15.7.1. Every number below was measured. Where a row says "median" it is the median of 3 runs.

## Environment

| Component | Version | How it was installed |
|---|---|---|
| Python | 3.12.12 (Homebrew) | `uv venv -p /opt/homebrew/bin/python3.12` |
| CadQuery | 2.8.0, with cadquery-ocp 7.9.3.1.1, vtk 9.6.2, trimesh 5.2.1, numpy 2.5.4 | `uv pip install cadquery numpy trimesh` took 13 s on a warm network. The installed venv is 1.3 GB. |
| Blender | 5.3.0 (built 2026-11-10), bundled Python 3.13.13 | Official `blender-5.3.0-macos-arm64.dmg` (360 MB), which downloaded in about 7 s. Blender was run directly from the read-only DMG mount (`hdiutil attach -nobrowse -readonly`) and was not copied. |

**The internal disk was nearly full.** The disk had only about 160 MB free (it showed 100 % used), so the CadQuery install failed with `No space left on device`. The fix was a 3 GB RAM disk mounted inside the scratch folder at `bench/ram`, holding the venv, the uv cache and all generated files. Blender ran straight from the DMG, so it needed no extra disk space. The RAM disk makes file I/O faster than SSD, but the files are tiny (under 1 MB), so this barely affects the numbers. Cold `import cadquery` may be somewhat slower from SSD with a cold page cache.

## Test object

The test object is a simplified IKEA KNAGGLIG box, 460 × 310 × 250 mm, split into 5 unique CadQuery parts in `parts/`. Each part script follows the harness: `build(PARAMS) -> (Workplane, holes{name: origin/normal/diameter})`, then exports STL, STEP and `*.mates.json`.

- `end_frame`: 2 uprights and 3 slats, unioned. It has 12 side pilot holes and 2 bottom pilot holes.
- `side_slat`: 460×60×10 mm, with 4 countersunk holes.
- `bottom_slat`: 440×80×10 mm, with 2 holes.
- `screw_long_108363`: ø5×50 mm with a cross-head.
- `screw_short_108560`: ø4×16 mm with a cross-head.

The assembly has 39 objects: 2 end frames, 6 side slats, 3 bottom slats, 24 short screws and 4 long screws.

## 1. CadQuery (median of 3, times in ms)

| Part | build | STL at 0.05 mm | triangles at 0.05 | STL at 0.5 mm (fresh solid) | triangles at 0.5 | STEP | STL / STEP size |
|---|---|---|---|---|---|---|---|
| end_frame | 101.0 | 12.4 | 7 124 | 2.9 | 1 524 | 3.4 | 347 KB / 112 KB |
| side_slat | 40.5 | 5.0 | 5 076 | 1.4 | 860 | 2.0 | 247 KB / 59 KB |
| bottom_slat | 35.0 | 2.6 | 1 540 | 1.1 | 340 | 1.5 | 75 KB / 40 KB |
| screw_long | 50.0 | 3.9 | 1 808 | 1.3 | 330 | 1.7 | 88 KB / 52 KB |
| screw_short | 50.0 | 4.0 | 1 808 | 1.2 | 330 | 1.7 | 88 KB / 52 KB |

The STL exports used angular tolerance 0.1 at 0.05 mm and 0.5 at 0.5 mm.

- **Cold `import cadquery`: 1.36 s** (runs: 1.33, 1.37, 1.36). A bare `python -c pass` takes 0.014 s.
- **Gotcha:** OCC caches the triangulation on the shape. If you export at 0.05 mm and then at 0.5 mm from the same solid, the coarse export silently reuses the fine mesh and gives identical triangle counts. Pick a single tolerance, or rebuild the solid before exporting at a second one.

### 2. A subprocess per part versus one process

| Mode (5 parts, build + STL + STEP + mates) | Wall time (median of 3) |
|---|---|
| `python part.py OUTDIR` spawned once per part | **7.84 s, about 1.57 s per part** |
| All parts in one process | **1.79 s** (1.36 s import plus about 0.43 s of work) |

Almost all of the per-part subprocess cost is the CadQuery/OCP import (about 1.4 s). The geometry itself takes under 0.15 s per part.

## 3. Blender 5.3 assembly and GLB (median of 3)

| Step | Time |
|---|---|
| `Blender -b --factory-startup --python-expr pass` (wall) | **0.51 s** (runs: 0.61, 0.51, 0.49). The very first launch from the DMG took 0.76 s. |
| Import 5 STLs (`wm.stl_import`, `global_scale=0.001`) | 0.027 s |
| Place 39 instances (linked mesh data) and build 8 steps of keyframed actions on NLA tracks `step_01`…`step_08` | 0.001 s |
| GLB export (`NLA_TRACKS`, merged by `NLA_TRACK`) | **0.19 s** (the first, cold run took 0.41 s) |
| Whole `blender_assemble.py` run including startup (wall) | **0.77 s** |
| GLB output | 940 280 bytes, 39 nodes, 5 meshes, about 100 k triangles at 0.05 mm |

**GLB verification** (`check_glb.py` parses the JSON chunk): the file has **8 animations**, `step_01` … `step_08`. Their channel counts are 1, 1, 3, 12, 3, 12, 3 and 4, which matches the objects moved in each step.

In Blender 5.3 the engine enum lists only `BLENDER_EEVEE` at factory startup. `BLENDER_WORKBENCH` and `CYCLES` are still valid values to assign to `scene.render.engine`.

## 4. Headless rendering on macOS (one 512×512 still, median of 3)

Each render runs as a separate `Blender -b knagglig.blend --python blender_render.py -- ENGINE SAMPLES FREESTYLE out.png` process.

| Engine | Render only | Wall time including startup | Non-black? |
|---|---|---|---|
| `BLENDER_WORKBENCH` | **0.11 s** | 0.60 s | **Yes** (mean luminance 68, std 24) |
| `BLENDER_EEVEE` (EEVEE Next; the enum is still `BLENDER_EEVEE`), 16 samples | **0.31 s**. The first run ever took 3.27 s because of shader compilation. | 0.80 s | **Yes** (mean 148, std 13) |
| `CYCLES` on CPU, 4 samples | **0.10 s** | 0.60 s | **Yes** |
| `CYCLES` on CPU, 16 samples | **0.24 s** | 0.73 s | **Yes** |
| + Freestyle (Workbench / EEVEE / Cycles 16) | 0.49 / 0.67 / 0.60 s | about 1.0–1.2 s | The image is identical to the non-Freestyle one: **no lines were drawn** (see Problems). |

**Answer to the open question: yes.** EEVEE and Workbench both render headless (`-b`) on macOS through Metal and produce real, correct images. The renders were also checked visually and show the assembled crate. Copies of the images are in `outputs/`.

## 5. Estimate for a full guide

**Assumptions:**
- About 6 unique parts and about 8 steps per guide.
- Up to 3 attempts per part and per step.
- About 40 check renders and 1 GLB export.
- Gemini calls take about 5–30 s each and run sequentially. LLM time was not measured.

**CPU and render time (measured costs):**

| Item | Best case (1 attempt) | Worst case (3 attempts) |
|---|---|---|
| CadQuery, one subprocess per part (about 1.6 s each) | 6 × 1.6 ≈ 10 s | 18 × 1.6 ≈ 29 s |
| Blender run per step attempt (about 0.8 s each) | 8 × 0.8 ≈ 6 s | 24 × 0.8 ≈ 19 s |
| 40 check renders, one Blender process each (0.6–0.75 s) | about 24 s (Workbench) | about 30 s (Cycles 16) |
| ↳ alternative: the same renders batched in one Blender process (render-only cost) | about 5 s | about 10 s |
| Final GLB export | about 0.8 s | about 0.8 s |
| **CPU total** | **about 20–40 s** | **about 50–80 s** |

**LLM time (assumed, not measured):**
- Calls: 6–18 for part code, 8–24 for steps, about 40 vision checks on the renders, and about 3 for planning or extraction. That is about 57 calls in the best case and about 85 in the worst.
- Run sequentially, that is **about 57 × 5 s ≈ 5 min at best, and up to about 85 × 30 s ≈ 42 min at worst**. A typical estimate is about 80 calls × 10 s ≈ 13 min.

**Conclusion:** local CPU time per guide is about 1 minute. LLM latency is 10–40 times larger and dominates the total. Optimise by running LLM calls in parallel, for example generating parts concurrently and batching the check renders into fewer vision calls. CPU work is not the bottleneck. On the CPU side, the only cheap win is to keep one CadQuery worker process alive instead of spawning one per part, which saves about 1.4 s per part attempt.

## Problems encountered

1. **The disk was full** (about 160 MB free). The pip install failed with ENOSPC. The workaround was the RAM disk described above, mounted with `hdiutil attach -nomount ram://6291456` (3 GB), then `newfs_hfs`, then `diskutil mount -mountPoint bench/ram`. The RAM disk disappears on detach or reboot. The user's `~/.cache/uv` was not modified. The Blender DMG was deleted after the run to give back 360 MB of disk.
2. **OCC reuses the cached triangulation** across exports with different tolerances (see section 1).
3. **Freestyle produced no strokes headless**, even with `view_layer.use_freestyle=True` and a lineset that selects silhouette, border and crease edges. Blender logs `Error: strokes set empty`, but it still adds about 0.4 s. This was not debugged further because of the time budget. For line-art check renders, a Workbench render with `shading.show_object_outline` / cavity, or a Solidify-inverted-hull outline, is probably more reliable, but these were not tested.
4. The EEVEE render was washed out with a world strength of 1.0 under the default AgX view transform. Lowering the world strength to 0.35 fixed it. This affects lighting only, not correctness.
5. A `/Volumes/Blender` volume was already mounted, so the DMG mounted as `/Volumes/Blender 1`.

## Files in this folder

- `parts/harness.py`
- `parts/end_frame.py`, `parts/side_slat.py`, `parts/bottom_slat.py`, `parts/screw.py`, `parts/screw_long_108363.py`, `parts/screw_short_108560.py`
- `bench_cq.py`: CadQuery timings, triangle counts, and subprocess versus one process. Writes `results_cq.json`.
- `bench_cq_coarse.py`: the 0.5 mm export on a fresh solid. Writes `results_cq_coarse.json`.
- `blender_assemble.py`: STL import, pose, 8 NLA step tracks and GLB export.
- `check_glb.py`: GLB animation check.
- `blender_render.py` and `bench_render.sh`: the engine matrix. Writes `results_render.txt`.
- `check_png.py`: pixel statistics. Writes `results_png.txt`.
- `outputs/`: the GLB, the renders, the mates JSONs and the end_frame STL.

## Commands to reproduce

```bash
B=eval/bench
cd $B
# RAM disk (only needed because the disk is full; otherwise use any folder)
DEV=$(hdiutil attach -nomount ram://6291456 | awk '{print $1}'); newfs_hfs -v benchram $DEV; mkdir -p ram; diskutil mount -mountPoint $B/ram $DEV
export UV_CACHE_DIR=$B/ram/uvcache
uv venv -p /opt/homebrew/bin/python3.12 ram/venv && uv pip install -p ram/venv/bin/python cadquery numpy trimesh
# Blender
mkdir -p dl && curl -L -o dl/blender.dmg https://download.blender.org/release/Blender5.3/blender-5.3.0-macos-arm64.dmg
hdiutil attach -nobrowse -readonly dl/blender.dmg      # note the mount point, e.g. "/Volumes/Blender 1"
BL="/Volumes/Blender 1/Blender.app/Contents/MacOS/Blender"
# 1+2 CadQuery
mkdir -p ram/out && ram/venv/bin/python bench_cq.py ram/out && ram/venv/bin/python bench_cq_coarse.py ram/out
# 3 Blender startup + assembly/GLB
time "$BL" -b --factory-startup --python-expr "pass"
mkdir -p ram/blend && "$BL" -b --factory-startup --python blender_assemble.py -- ram/out ram/blend/knagglig.glb ram/blend/knagglig.blend | grep ASSEMBLE
python3 -I check_glb.py ram/blend/knagglig.glb
# 4 Renders
./bench_render.sh "$BL" $B/ram/blend/knagglig.blend $B/ram/render
ram/venv/bin/python check_png.py ram/render/*.png
# cleanup
hdiutil detach "/Volumes/Blender 1"; hdiutil detach $DEV
```
