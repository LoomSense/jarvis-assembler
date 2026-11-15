# Markerless 6-DoF tracking of the MALM drawer: offline study

**Question.** Can the native Quest apps drop the printed marker, using only the
RGB passthrough camera, its intrinsics and pose, the table plane, and the exact
mesh of the parts placed so far?

**Short answer.** Not for the demo. Edge-based model tracking works once it is
initialised (p50 about 4.6 mm and 0.5° with realistic noise, about 43 ms per
frame on an M4 core in Python), but it is about 3× less accurate than the
marker (p50 1.35 mm). Its weak points are initialisation and near-symmetric
states: one sequence in 12 locked onto the 180°-flipped drawer and stayed there.
Keypoint methods fail outright on the white panels (0% success). Keep the
marker for the demo. If we do markerless later, use the table plane and the
known step to initialise, track with edges, and ask the user to confirm the
orientation.

All numbers below come from synthetic renders. Real passthrough images (lower
dynamic range, rolling shutter, a real MALM with chipboard edges, model
dimensions that are only estimated) will be worse. Treat them as an upper bound.

## 1. Dataset (`gen_dataset.py`, Blender 5.3 headless, EEVEE)

- **Models**: `export_states.py` samples the `walkthrough` clip of
  `jobs/malm-drawer/walkthrough.glb` at the end of steps 1, 2, 4 and 5. It
  keeps the visible parts and hardware (not the `marker` ring), bakes them, and
  sets the object frame with its origin at the footprint centre on the table
  (Blender Z-up) → `models/state_NN.ply`. Sizes: step 1 is
  0.38 × 0.13 × 0.04 m (the front lying flat), step 2 is
  0.38 × 0.13 × 0.42 m (the sides standing up), and steps 4 and 5 are
  0.38 × 0.42 × 0.13 m (the box turned over). Triangle counts: 1.4k to 6.6k.
- **Scene**: a 1.6 × 1.0 m table with a high-contrast procedural wood texture,
  walls, a floor, and distractors (a mug, a book, an A4 leaflet). The drawer is
  **white laminate, with no texture** (the worst case, and realistic).
- **Lighting** (3 setups): `office` (area light plus ambient), `window` (a low
  sun with hard shadows), `dim` (one warm lamp, low ambient; degraded frames get
  double noise).
- **Camera**: Quest-like intrinsics: 1280 × 960, fx = fy = 870, principal point
  at the centre. The camera moves smoothly round the drawer with a 40–70° sweep,
  0.45–0.85 m from the drawer centre, 30–65° elevation, ±3° roll and 3 mm head
  jitter. That gives about 2° per frame, which is fast for 30 fps head motion.
- **Hand**: a skin-coloured hand and sleeved forearm hovers over the drawer for
  6 frames in every other sequence (36 frames). It covers 10–50% of the drawer.
- **Size**: 4 states × 3 lights = 12 sequences × 30 frames = **360 frames** with
  ground truth (`frames.jsonl`: K, `T_world_cam` in OpenCV axes,
  `T_world_obj`). Render time about 5 min.
- **Degradation, applied at evaluation time** (`common.degrade`, seeded per
  frame): motion blur of 0–9 px, Gaussian noise σ 2–6 (doubled in `dim`), and
  JPEG quality 60–90.
- **Prior noise** (`common.table_prior`, `camera_pose_noisy`): table height
  σ 3 mm and tilt σ 0.4° per sequence, as scene understanding would give it;
  camera pose σ 1.5 mm and 0.15° per frame, for head tracking plus
  camera-extrinsic error.
- Committed: `sample/` (6 frames, the full `frames.jsonl`, and
  `gt_overlay.jpg`). The full renders go to `data/` and are git-ignored.

## 2. Method A: edge-based model tracking (`edge_tracker.py`)

- **Pose model**: `T_world_obj = T_world_table · Trans(x, y, 0) · Rz(yaw)`. A
  6-DoF stage then adds (z, roll, pitch) with a soft prior towards the table
  (σ 1 cm and 3°).
- **Image side**: Canny edges with an adaptive threshold (85th percentile of the
  gradient). The edges are split into 8 orientation bins. Each bin gets a
  truncated distance transform plus its gradient, at 640 × 480 (τ = 10 px) and
  320 × 240 (τ = 12 px). This is an oriented chamfer: a model edge only matches
  image edges with the same orientation (±1 bin), which removes most of the
  wood-grain and clutter edges.
- **Model side**: sample points every 5 mm on the sharp mesh edges (dihedral
  angle > 30°). Visibility comes from a back-face test plus a numba z-buffer.
  Samples on an **occluding contour** (background on one side) get weight 1.
  **Interior creases** get weight 0.3: on white laminate both faces of a crease
  often have the same brightness, so the edge is not in the image.
- **Solver**: Levenberg–Marquardt with Huber weights on the DT residuals, coarse
  to fine (8 + 10 iterations). It runs in 3 DoF first, then 6 DoF (8
  iterations). The Jacobian is ∇DT × ∂uv/∂θ.
- **Initialisation** (global search, only at the start or after a loss):
  - (x, y) candidates come from casting a 9 px pixel grid onto the table
    plane, within 1.2 m (arm's reach), with yaw in 10° steps. That is about
    800 positions × 36 yaws.
  - Candidates are scored with a **metric chamfer**: the pixel residual × depth
    / f, truncated at 3 cm. A plain pixel chamfer prefers far, small hypotheses
    that hide in the wood texture. On the init test frames, fixing that took
    the init from 0/3 correct to 3/3.
  - The top 16 candidates after non-maximum suppression are refined with LM,
    then verified with edge fitness plus a **region term**: the Lab colour
    separation between the projected silhouette and a band round it.
  - **Multi-frame init**: the drawer is static while the head moves, so the
    best 6 hypotheses are kept and re-scored over 3 frames before the tracker
    commits.
- **Tracking**: each frame starts LM from the last pose. Because the object is
  static in the world, the prior is free. A gate on the weighted inlier ratio
  (> 0.35) and cost (< 0.4) decides: a bad frame holds the last pose, and after
  8 bad frames the tracker re-initialises.

## 3. Method B: keypoint templates → PnP (`eval_keypoints.py`)

- Templates are 6 keyframes per sequence, taken from the *other* lighting
  sequences of the same state (18 per state).
- Keypoints inside the projected object mask are lifted to 3D in the object
  frame with the model z-buffer.
- Each query frame is matched against them (ratio test 0.8), then
  `solvePnPRansac` runs, then the pose is projected onto the table (x, y, yaw
  kept).
- ORB (3000 features) and SIFT were tested. AKAZE is not in this OpenCV 5 build.

## 4. Results

Success means the pose is within **10 mm** (object origin) and **5°** (full
rotation) of the truth. Rows 1–5 cover all frames, including the 3
initialisation frames per sequence. In ms/frame, the "tracking" column was
measured with 5 evaluations running in parallel. The **isolated timing** on one
Apple M4 performance core (Python + numpy + numba, 640 × 480 working
resolution) is **42.5 ms p50 / 49.5 ms p90** per tracked frame: 23.6 ms for
pyramid, Canny and 16 distance transforms, plus 19.0 ms for the solve. A global
initialisation takes **1.1–1.3 s**, and the 2 follow-up init frames take about
170 ms each.

| config | n | success (<10 mm & <5°) | trans p50 / p90 (mm) | rot p50 / p90 (°) | success, hand frames | ms/frame p50 (parallel load) |
|---|---|---|---|---|---|---|
| **A edges, tracking, 6-DoF, degraded images + prior noise (main)** | 360 | **84%** | **4.6** / 14.1 | **0.51** / 5.20 | 75% | 54 |
| A edges, tracking, clean images, exact priors | 360 | 91% | 2.2 / 8.4 | 0.41 / 2.19 | 94% | 61 |
| A edges, tracking, 3-DoF only (x, y, yaw) | 360 | 81% | 5.0 / 27.3 | 0.66 / 178 | 64% | 49 |
| A edges, tracking, model 2% too long (dims estimated) | 360 | 72% | 6.2 / 16.0 | 1.08 / 179 | 56% | 60 |
| A edges, every frame from scratch (no temporal prior) | 120 | 60% | 6.1 / 375 | 0.87 / 166 | 25% | 1203 |
| B ORB templates → PnP + table | 120 | **0%** (pose found 2%) | – | – | 0% | 103 |
| B SIFT templates → PnP + table | 120 | **0%** (pose found 0%) | – | – | 0% | 64 |
| *Marker tracker (reference, from the device runs)* | | | *p50 1.35* | | *lost when covered* | |

Main config, split by slice:

| slice | n | success | trans p50 (mm) | rot p50 (°) | yaw p50 (°) | 180° flips |
|---|---|---|---|---|---|---|
| step 1 (front lying flat) | 90 | 100% | 3.5 | 0.38 | 0.12 | 0% |
| step 2 (sides standing) | 90 | 83% | 5.8 | 0.41 | 0.26 | 1% |
| step 4 (box turned over, bottom in) | 90 | 52% | 8.9 | 4.08 | 0.41 | 33% |
| step 5 (back fitted) | 90 | 99% | 3.6 | 0.50 | 0.17 | 0% |
| light office | 120 | 74% | 5.5 | 0.53 | 0.28 | 25% |
| light window | 120 | 82% | 4.7 | 0.68 | 0.22 | 1% |
| light dim | 120 | 94% | 4.1 | 0.39 | 0.15 | 0% |
| hand occluding | 36 | 75% | 5.0 | 1.06 | 0.38 | 17% |
| no hand | 324 | 85% | 4.6 | 0.47 | 0.19 | 8% |

Per-sequence success for frames 3 and later (sequences 0–11 = steps 1, 2, 4, 5 × office, window, dim):

| config | per sequence (%) | init correct |
|---|---|---|
| main | 100 100 100 · 100 48 100 · **0** 78 74 · 96 100 100 | 11/12 |
| clean | 100 100 100 · 100 56 100 · 78 74 70 · 100 100 100 | 12/12 |
| 3-DoF | 100 100 100 · 100 93 100 · **0** 100 **0** · 85 100 96 | 10/12 |
| model +2% | 100 96 70 · 100 56 89 · **0** 63 **0** · 100 100 100 | 10/12 |

Reading the numbers:

1. **Once the init is right, edge tracking is good.** On 11 of 12 sequences
   the error stays at a few millimetres and under a degree, frame after frame.
   It also holds through the hand frames: the gate holds the pose or the
   tracker rides through, and it never re-initialised mid-sequence.
2. **Most of the 4.6 mm is the priors, not the vision.** With exact priors and
   clean images the p50 is 2.2 mm. The table height (σ 3 mm) and the camera
   pose (σ 1.5 mm, 0.15° ≈ 1.8 mm at 0.7 m) go straight into the world pose. The
   marker shares the camera-pose error, but not the table error.
3. **The big failures are discrete, not noise.**
   - (a) **180° yaw flips** on step 4. The turned-over box is nearly
     symmetric front-to-back from most viewpoints. Init committed to the flip
     in 1 of 12 sequences (2 of 12 with 3 DoF or a wrong-size model), and
     tracking never recovers from it because the flipped pose fits well.
   - (b) **Slipping by a panel thickness** (16–23 mm) on steps 2 and 4: there
     are several parallel edges 16–18 mm apart (the panel faces, the groove, the
     bottom panel). That is the main cause of the 48–78% sequences.
4. **6 DoF beats 3 DoF** (84% vs 81%; p90 14 mm vs 27 mm), even though the
   object does rest on the table. The extra freedom absorbs table-tilt and
   camera-pose errors that 3 DoF has to push into x, y and yaw.
5. **The model must be right.** A 2% length error (7.6 mm on the 380 mm front;
   `manifest.overall_mm.estimated` is true for this guide) drops success from
   84% to 72% and doubles the flips.
6. **Without temporal context, single frames fail.** Detection from scratch on
   every frame succeeds 60% of the time, takes 1.2 s, and has 375 mm p90
   outliers. Init has to be multi-frame, or seeded from something else.
7. **Keypoints are hopeless on white laminate.** ORB found about 300 keypoints
   "on" the drawer, but they sit on its contour, where the descriptor mostly
   sees the table behind. SIFT found about 65. Neither gives consistent 2D–3D
   matches, so the pose is found on ≤ 2% of frames and is never correct. Do
   not use a template or keypoint approach for this product.

## 5. What it would take on Quest

- **CPU budget.** About 43 ms per frame on an M4 core in Python, roughly 60%
  image preprocessing and 40% solve. The Quest 3's XR2 Gen 2 cores are about
  3–4× slower than an M4 core for this kind of code. A C++/NEON port (OpenCV
  Canny and distanceTransform, Eigen LM, about 1.5k visible samples) should do
  **about 15–25 ms per frame on one core at 640 × 480**, so 10–15 Hz tracking
  on a background thread is realistic. That is enough because the object is
  static: the render pose comes from head tracking, and vision only corrects
  drift. The global init (1.2 s in Python, about 0.3–0.5 s native) runs once.
  This is an estimate, not a measurement on the headset.
- **GPU (Vulkan compute or a Unity compute shader).** The z-buffer and
  visibility (6.6k triangles) and the 8 distance transforms can move to the
  GPU, which lowers CPU load but adds a readback each frame. Do it only if the
  CPU thread competes with the app.
- **Sentis.** It is not needed for Method A. It would be the route for a
  learned edge or segmentation front end, such as a small U-Net that outputs
  "drawer silhouette" instead of Canny. That is the most promising fix for
  the flip and slip failures (region-based tracking in the RBOT/SRT3D style
  on a learned mask), but it needs training data. The generator here is a
  start.
- **Inputs it needs from the app**: per-frame camera intrinsics and
  `T_world_cam` at the exposure timestamp (the latency must be compensated, or
  the 0.15° error grows fast during head motion); the table plane from MRUK;
  the current step (from the guide state, or `/api/perception/{job}/locate`),
  to pick `state_NN`; and the per-step GLB meshes, which we already have.

## 6. Failure modes

| failure mode | seen here | mitigation |
|---|---|---|
| White, featureless panels: interior creases do not show in the image | yes, so creases are down-weighted to 0.3 | rely on occluding contours; a region/silhouette term |
| Near-symmetric states (step 4 box, step 2 U-shape): 180° flips | 1–2 of 12 sequences, unrecoverable once committed | ask the user to confirm ("is the front facing you?") or use the hardware positions; keep both hypotheses until the view disambiguates |
| Parallel edges a panel thickness apart: 16–23 mm slips | steps 2 and 4, 20–50% of frames in those sequences | region term in tracking; multi-hypothesis tracking; finer model (groove, chamfers) |
| Hands occluding | 75% success on hand frames (main) | gate plus hold; hand masks from Meta hand tracking can mask the edges directly |
| Model dimension errors (`dimsEstimated: true`) | 2% error → −12 points success | measure the real panels once, or let the tracker estimate a scale |
| Textured table or clutter edges | handled by the oriented, metric chamfer | a white desk would break the region term instead |
| Initialisation from scratch | 60% single-frame, 1.2 s | multi-frame init; seed from the scan, two-corner touch or a 2D box from Gemini locate |
| Not tested: real sensor (rolling shutter, auto-exposure, HDR clipping), real chipboard texture, motion faster than 2° per frame | – | needs the replay datasets from `tools/quest/replay` |

## 7. Recommendation

1. **Demo: keep the marker.** It is better than 1.35 mm p50 against 4.6 mm, and
   it has no flip or slip failures.
2. **Next step, if markerless is wanted, as a hybrid:**
   - Init: the marker, the two-corner touch, or the table plane plus the known
     step plus a coarse 2D box, then the multi-frame edge init here.
   - Ask the user to confirm the orientation once per state change (the step
     2 → 4 turn-over is exactly when the pose changes anyway).
   - Then track with edges at 10–15 Hz while the marker is covered (step 5
     onwards).
   - That fixes the marker's real gap: from step 4 the bottom panel covers it
     and today the apps only hold the anchor.
3. **Before trusting any number here**, run `eval_edges.py` on a real
   passthrough replay with the marker pose as ground truth. The replay format is
   already defined in `docs/QUEST_TESTING.md` §5.

## 8. Reproduce

```sh
cd tools/markerless
B=/Applications/Blender.app/Contents/MacOS/Blender
$B -b --python export_states.py -- ../../jobs/malm-drawer/walkthrough.glb ../../jobs/malm-drawer/manifest.json models 1 2 4 5
$B -b --python gen_dataset.py -- models data/synth 30 7       # 360 frames, ~5 min
./run_all.sh                                                  # all experiments, ~10 min
python summarize.py out                                       # tables above
python check_gt.py data/synth sheet.jpg                       # GT overlay contact sheet
python debug_gt.py data/synth dbg.jpg s07_f000                # edge fitness at the GT pose
python diag_init.py data/synth s06_f000                       # init ranking vs GT
```
