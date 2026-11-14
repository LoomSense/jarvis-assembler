# 3D pipeline: plan → parts → fit checks → animated GLB

Turns an approved plan (`plan.json`) into the files the viewer and the headset need. No LLM is involved here; the agent's job is to write the plan and the part scripts that this pipeline consumes.

```
.venv/bin/python -m pipeline.run eval/golden/knagglig/plan.json jobs/knagglig
```

Measured on the MacBook Air M4 for the KNAGGLIG box (6 unique parts, 37 pieces, 8 steps): **about 3 s** end to end.

## Stages

| Stage | Code | Output |
|---|---|---|
| Build parts | `cad/harness.py` runs each part script's `build(PARAMS)` in CadQuery | `parts/<id>.stl`, `.step`, `.mates.json` |
| Place parts | `assemble/placement.py` solves every piece's position from the plan's mates | `placements.json` |
| Check fit | `placement.py` (mated points must coincide within 0.5 mm) and `assemble/collide.py` (no two parts may share volume), plus part and hardware counts against the plan | `problems.json` and exit code 1 if anything fails |
| Scene | `blender/assemble.py`, run headless in Blender 5.3 | `walkthrough.glb`, `manifest.json`, `scene.blend`, `renders/step_NN.png` |

## How parts are made to fit

1. **One dimension table.** `plan.json` has a `dims` block (board thickness, slat height, upright size…). The harness overrides each script's defaults with it, so every part is cut from the same numbers.
2. **Mate frames.** Each part script returns named frames: a point on its surface and an axis pointing out of the material. Holes are drilled at the frames, so the geometry and the mating data cannot drift apart.
3. **Mates in the plan.** A step says which frame of the new piece meets which frame of a placed piece. Two frames mate when their origins coincide and their axes are opposite.
4. **Solver, then proof.** Positions are computed (Kabsch fit over the mated points), never guessed. A mate that misses by more than 0.5 mm or 2°, or two boards that overlap, stops the build with a message naming the part and the frame. That message is what the agent gets back to fix its script.

## Part script contract

```python
from pipeline.cad.lib import board, drill, frame, union

PARAMS = {"W": 310, "t": 12, ...}          # mm; plan dims override matching keys

def build(p):
    solid = board(0, 0, 0, p["W"], p["t"], 70)
    frames = {"in_L_a": frame((26, 0, 17), (0, -1, 0))}   # origin on the surface, axis out of the material
    solid = drill(solid, (26, 0, 17), (0, -1, 0), 4.2, p["t"] + 1)
    return solid, frames
```

Fasteners use one frame, `seat`, under the head with the axis along the shank.

## What the viewer gets

- `walkthrough.glb`: metres, Y-up, one animation per step named `step_NN`, nodes `part_<id>_<n>` and `hw_<code>_<n>`, one camera per step `cam_step_NN`. Pieces rest at their assembled pose; each clip moves only that step's pieces in from the direction they are inserted (boards first, then their screws).
- `manifest.json`: per step the clip, title, visible nodes, moving nodes, parts to pick up, hardware and counts, camera, guide page, and the check render.

A ready-made sample is in `eval/golden/knagglig/out/`.

## Not done yet

- Running agent-written scripts in a sandbox (CAD-09).
- Comparing check renders with the guide's drawings (needs Gemini Decision 1).
- Guide crops and the phase-2 reference sheets in the manifest.
- Mesh compression (gltfpack); the sample is 400 KB, so it is not urgent.
- The guide's "turn the whole thing over" steps are flagged in the manifest but not animated.
