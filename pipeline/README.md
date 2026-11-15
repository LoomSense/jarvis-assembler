# 3D pipeline: plan → parts → fit checks → animated GLB

Turns an approved plan (`plan.json`) into the files the viewer and the headset need. No LLM is involved here; the agent's job is to write the plan and the part scripts that this pipeline consumes.

```
.venv/bin/python -m pipeline.run eval/golden/knagglig/plan.json jobs/knagglig
```

Measured on the MacBook Air M4 for the KNAGGLIG box (6 unique parts, 37 pieces, 8 steps): **about 3 s** for parts, fit checks and the GLB, **about 12 s** with the part pictures, reference sheets, packed GLB and crops (measured on another M-series Mac).

## Stages

| Stage | Code | Output |
|---|---|---|
| Build parts | `cad/harness.py` runs each part script's `build(PARAMS)` in CadQuery | `parts/<id>.stl`, `.step`, `.mates.json` |
| Place parts | `assemble/placement.py` solves every piece's position from the plan's mates | `placements.json` |
| Check fit | `placement.py` (mated points must coincide within 0.5 mm) and `assemble/collide.py` (no two parts may share volume), plus part and hardware counts against the plan | `problems.json` and exit code 1 if anything fails |
| Scene | `blender/assemble.py`, run headless in Blender 5.3 | `walkthrough.glb`, `manifest.json`, `scene.blend`, `renders/step_NN.png` |
| Pictures | `refs.py` runs `blender/render_parts.py` and `blender/render_refs.py`, then composes the sheets with Pillow | `renders/part-<id>.png`, `part-<id>-shaded.png`, `refs/step-NN-ref.png`, `step-NN-sheet.png`, `step-NN-sheet.json` |
| Pack | `pack.py` runs gltfpack if it is installed | `walkthrough.packed.glb` |
| Crops | `crops.py` cuts each step's drawing from the guide PDF with PyMuPDF | `crops/step-NN.png` |

The last three also write their file names into `manifest.json` (`thumb`, `refRender`, `refSheet`, `glbPacked`, `crop`, `cropSource`).

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

- `walkthrough.glb`: metres, Y-up. The first animation, `walkthrough`, plays the whole guide one action at a time, in the guide's order (65 s and 41 actions for KNAGGLIG). After it come one animation per step, `step_NN`, for viewers that step through manually. Nodes: `assembly` (the root, which turns the whole assembly), `part_<id>_<n>`, `hw_<code>_<n>`, and one camera per step `cam_step_NN`.
- A step is a list of **actions**, the things a person does:
  - `turn`: the whole assembly is turned to the orientation the guide draws for that step (upside down, stood on end) and rests on the floor. It animates the `assembly` node.
  - `place`: one part appears, then moves into position.
  - `fasten`: one screw flies to its own hole, then turns clockwise while it is driven in. Screws go in one after another, never together.
- `manifest.json`: the start and end time of every step inside `walkthrough`, and per step the clip, title, orientation (`pose`, `assemblyRotationDeg`), visible nodes, parts to pick up, hardware and counts, camera, guide page, the check render, and the ordered `actions`. Each action has its type, node, start and end time inside the step clip, the target point (`target_m`, in the assembly's own frame), the direction it comes from, and the instruction text in English and Greek, for example "Take a short screw (108560) and screw it in here. Screw 3 of 8." The verb follows the hardware `kind` (a dowel is pushed into its hole, a cam lock dropped in and turned until it locks, a pin pressed in, a nail tapped in; unknown kinds get "put it in here"), and the Greek article and pronoun follow the noun's gender. The wording lives in `blender/instructions.py` (plain Python, tested in `tests/test_instructions.py`).
- **Which screw goes where:** each hardware code has its own colour in the GLB (the manifest's `hardware[].color`), and a ring (node `marker`, named in `markerNode`) appears at the hole while that screw is driven.
- **Preview video:** `.venv/bin/python -m pipeline.preview <job_dir> [en|el]` writes `preview_<lang>.mp4`: the step title, a "You need" list of that step's parts and screws with their colours, the part being placed tinted, a close-up of each hole while its screw goes in, and the action text underneath. Add `--callout` for an arrow to the hole of the screw being driven, labelled with its code and count ("108560  3/8"). A step with no actions has `clip: null`.
- The plan gives each step a `pose` and `assembly_rotation_deg`. The order of a step's `add` list is the order of its actions.

- **Packed GLB:** `walkthrough.packed.glb` (named in the manifest's `glbPacked`) is the same file after `gltfpack -cc -kn -ke`: 197 kB instead of 2.0 MB for KNAGGLIG. A three.js viewer needs `loader.setMeshoptDecoder(MeshoptDecoder)` to open it. It is written only when gltfpack is installed (`cd eval/glb_check && npm install`).
- **Pictures:** `renders/part-<id>.png` (line art) and `part-<id>-shaded.png` for every part and screw (`thumb` in the manifest), `crops/step-NN.png` cut from the guide at 300 dpi (`crop`), and for phase 2 `refs/step-NN-ref.png`, `step-NN-sheet.png` and `step-NN-sheet.json` (`refRender`, `refSheet`). `pipeline/refs.py` and `pipeline/crops.py` explain each.
- **Load check:** `cd eval/glb_check && npm install && node check.mjs ../../jobs/knagglig [walkthrough.packed.glb]` opens the GLB with the three.js r187 loader and checks sizes, clips, node names and that every action happens at the time the manifest gives it.

A ready-made sample is in `eval/golden/knagglig/out/` (GLB, packed GLB and manifest; the pictures the manifest names are only in a job folder).

## The build agent

`pipeline/agent/extract.py` turns a guide PDF into `plan.json` and `parts/*.py` with the Claude Agent SDK (PLAN D20):

```
.venv/bin/python -m pipeline.agent.extract <guide.pdf> <job_dir> ["facts, e.g. the overall size"]
```

It gives the agent `agent/BRIEF.md`, the page images and `./build` (this pipeline, with part scripts in the sandbox), and lets it repeat until the build passes. KNAGGLIG took 438 s and $2.12. The server uses it when `AGJ_BUILD_AGENT=claude` is in its environment.

## Image-to-3D parts, push-fit, tighten, notes

A plan part with `generate` (schema in `agent/BRIEF.md`) is made by `cad/generated.py`: the part's drawing is cut from the guide page, sent to fal (`fal-ai/hunyuan-3d/v3.1/pro/image-to-3d`, geometry only, $0.375; `AGJ_GEN3D_MODEL=meshy` for Meshy 7.1), cached per crop, turned Z-up and scaled into the plan's box. Without `FAL_KEY` it is a rounded box, and `parts/<id>.mates.json` says why. The overlap check skips generated parts. Steps can also `tighten` screws already in (they turn in place, ring shown), mark an add `press` (pushed in until it clicks), and carry `notes` (text-only actions, type `note`). Manifest action types are now `turn`, `place`, `fasten`, `tighten`, `note`.

## Drawing check

`check/score.py` asks Gemini Decision 1, for every step, whether our check render shows the same thing as the guide's drawing (`crops/step-NN.png`), and writes `check: {score, pass, orientation, answers}` on each manifest step. The server runs it in the `export` stage when it has a Gemini key and shows each result in the feed. On KNAGGLIG it scores 0.61 to 0.84 (all pass at 0.6). Its orientation answer is not used: it said "mirrored" for the camera on either side of the box.

`check/step_check.py` is the build agent's version (`./check [N]` in its workspace): plan + out folder in, one line per step out, `step_check.json` written, exit 0 only if every step passes. Without a crop it cuts the step's drawing from the cited page image. Its questions are same parts, same arrangement, screws placed (yes/no) plus `pose` and `main_difference` (choices); on the agent's KNAGGLIG build it passed 7 of 8 right renders and failed 7 of 7 wrong ones (swapped or flipped renders, Sun 15 Nov), about 2 s per step. Verdicts are cached per render. `AGJ_VERIFIER=iris` selects Oumi's Iris once its adapter is written (`_iris`, needs `OUMI_API_KEY` and `OUMI_IRIS_URL`).

## Sub-assemblies

A plan can build a group of parts on its own and attach it later as one piece, as guides do with a seat unit, a drawer or a door (plan `subassemblies`; schema in `agent/BRIEF.md`). `assemble/placement.py` places those parts in the group's own frame and, at the joining step, solves where the whole group sits; `placements.json` gets `local_mm` per piece and a `subassemblies` block. In the animation the group is built beside the main assembly, turned as the guide draws it, then lifted and set in place in one move. The manifest gains, per step, `subassembly` (the group the step works on, or null), `subassemblyRotationDeg`, `joins` and `stagedPoses` (node → 4×4 matrix in the `assembly` frame, metres, for pieces still lying beside), and a top-level `subassemblies` list with `joinStep`. A joining action has type `place`, `subassembly`, `node` (its first piece) and `nodes`. Viewers that ignore these fields still play the clips correctly.

## Not done yet

- `pipeline.run` still builds parts in its own process. Scripts a model wrote should go through `pipeline/cad/sandbox.py` (`sandbox.run_part`, same result as `harness.build_part`, about 1.9 s per part): the build loop (AGT-05) has to call it. On Linux it limits time, memory and file size but not network or file access.
- Comparing check renders with the guide's drawings (needs Gemini Decision 1).
- Guide crops and the phase-2 reference sheets in the manifest.
- Mesh compression (gltfpack); the sample is 400 KB, so it is not urgent.
- The guide's "turn the whole thing over" steps are flagged in the manifest but not animated.

## Phase-2 perception: questions and the Decision 1 request (`pipeline/perception/`)

```
.venv/bin/python -m pipeline.perception.questions jobs/<id>            # once per guide, after pipeline.run
.venv/bin/python -m pipeline.perception.request  jobs/<id> 5 frame.jpg # one request for step 5
.venv/bin/python -m pipeline.perception.bench    jobs/<id>             # stand-in benchmark, 64 requests
```

- `questions.py` writes `refs/step-NN-questions.json` (`header`, `sheet_note`, `sheet`, `expects`, `questions`) and sets
  `questions` on each manifest step. Option keys of `part_in_hand` and `hardware_in_hand` are the labels on the reference
  sheet, plus `hardware_only`, `nothing_held`, `no_hardware`, `other` (the names `eval/phase2/p2.py` scores).
  `gemini-4-hydrogen` writes what each part looks like and the orientation and completion criteria; `--no-model` uses
  plain template wording.
- `request.py`: `ask(job, step_n, frame)` where `frame` is a path or JPEG bytes. Images are sent as JPEG on a connection
  that stays open (`pipeline/check/decisions.py`); with PNG and a new connection per request the round trip was 2.5 s
  instead of 0.7 s.
- `bench.py` uses renders from another side as stand-in camera frames. It says nothing about hands or real light.
  Read `part_in_hand` only when `hands_present` is high: on frames with no hands it still names a part.
- `logic.py`: `Watcher(pack).feed(answers, ts)` returns events `{type, step, guide_page, part, expected, observed, evidence, ts}`
  (`wrong_part`, `wrong_hardware`, `flip_needed`, `out_of_order`, `step_complete`, `unsure_show_part`) and keeps `state`.
  Thresholds, N-of-M windows and cooldowns are in `Config`. No network and no clock, so it can be replayed. Tests:
  `tests/test_perception_logic.py`.
- `replay.py <job> eval/phase2/labels.json eval/phase2/runs/<name> [--cached]` runs labelled clips through both and writes
  `frames.jsonl` and `events.jsonl` for `eval/phase2/p2.py score`. `--cached` reuses the stored answers to tune thresholds.
- `stream.py`: `Stream(ask, Watcher(pack), k=4)` for live use. `submit(jpeg, ts)` never blocks; K requests run at once, a
  frame that arrives while all are busy replaces the one waiting (old frames are never queued), answers reach the
  watcher in time order. `subscribe()` gives an asyncio queue of events. Tests: `tests/test_perception_stream.py`.
- The refs stage (`pipeline.refs`, also what the server runs) now writes the questions itself. `AGJ_QUESTIONS=template`
  (default) adds no time and no network; `AGJ_QUESTIONS=model` lets `gemini-4-hydrogen` write the criteria (about 25 s
  for 8 steps, four calls at a time); `off` skips it. A failure there is reported in the result and never stops the build.

## Finding a guide from a QR link, barcode or name (`pipeline/find_guide.py`)

```
.venv/bin/python -m pipeline.find_guide "https://maker.example/qr/123" out.pdf
.venv/bin/python -m pipeline.find_guide "Teknik 2-Shelf Bookcase model 5431916" out.pdf
```

A link is followed first: a PDF is taken as it is, a product page gives the PDF link that looks most like assembly
instructions. Otherwise an agent session with web search finds the maker's PDF (about 25 s). Our code downloads and
checks every file: public addresses only, redirects checked one by one, size caps, real PDF. The server uses it in
`POST /api/catalog/open`; `AGJ_FIND_GUIDE` = `links` (default), `agent` or `off`.
