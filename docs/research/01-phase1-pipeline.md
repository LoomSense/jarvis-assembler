# Phase 1 pipeline: guide → plan → parts → scene → GLB

Owner: Phase 1 engineer. Docs were checked on 2026-11-14. Anything marked **UNVERIFIED** has not been checked against a doc or tested on our hardware. Every timing and cost figure is an **estimate**.

## 0. Pipeline at a glance

```
PDF ──PyMuPDF──► page PNGs (200 dpi) + native text
        │
        ▼  gemini-4-carbon, structured output
   plan.json (parts, hardware, steps, mates, box_2d crops, citations, flags)
        │
        ▼  HUMAN APPROVAL (viewer shows each crop next to its JSON entry)
   for each unique part:  Hydrogen writes CadQuery ─► run ─► STL + mates.json
                          ─► Blender render (guide angle) ─► compare with crop ─► fix (≤3)
        │
        ▼  deterministic placement (mates → transforms), flip candidates
   for each step:  Blender scene ─► render ─► compare with step crop ─► fix (≤3)
        │
        ▼
   assembly.glb (one animation per step) + manifest.json (crops, cameras, flags)
```

Design choice: a **Python orchestrator owns the control flow**: phases, loops, iteration caps and stop conditions. Gemini 4 Hydrogen is called with a small tool set inside each loop. We do not hand one long autonomous agent the whole job. With 24 hours left we need runs that are predictable and that we can debug.

---

## 1. PDF ingestion and crops

| Option | Pros | Cons |
|---|---|---|
| Send the PDF to Carbon directly | Simple. Native text is extracted automatically. 560 tokens/page by default | The docs do not say whether `box_2d` coordinates on a PDF page are reliable or which raster they refer to |
| **Rasterise with PyMuPDF and send PNGs (recommended)** | Box coordinates map to an image we own, so crops are exact. We control the DPI | We extract the native text ourselves (`page.get_text()`), which takes one line |

Facts from the docs:
- Each PDF page is treated as an image plus its native text. Limits are 50 MB or 1000 pages, and large pages are scaled to at most 3072×3072 ([document-processing](https://ai.google.dev/gemini-api/docs/document-processing)).
- Object detection returns `box_2d` as `[ymin, xmin, ymax, xmax]` normalised to 0–1000, which you rescale to the image size ([image-understanding](https://ai.google.dev/gemini-api/docs/image-understanding)).
- Media resolution, per input part: image `high` = 1120 tokens. For PDFs, `medium` (560 + native text) is the recommended level ([media-resolution](https://ai.google.dev/gemini-api/docs/media-resolution)).

**Decision:** rasterise every page at 200 dpi. Send the pages as PNG `image` parts at `resolution: "high"`, together with the page's native text. Ask for a `box_2d` for every part drawing, every step drawing and the parts-list table, all inside the same structured plan. Crops are cut at 300 dpi from the PDF itself, so they come out sharp.

```python
import fitz  # PyMuPDF
def crop(pdf, page_idx, box_2d, dpi=300, pad=0.02):
    p = fitz.open(pdf)[page_idx]; W, H = p.rect.width, p.rect.height
    y0, x0, y1, x1 = [v / 1000 for v in box_2d]
    r = fitz.Rect((x0-pad)*W, (y0-pad)*H, (x1+pad)*W, (y1+pad)*H) & p.rect
    return p.get_pixmap(clip=r, dpi=dpi)  # .save("crop.png")
```

Bounding boxes on dense line drawings can be loose. After the plan call, run a cheap second check: one Hydrogen call per page that only re-detects boxes, at `thinking_level: low`. If its IoU with Carbon's box is below 0.7, set `flags.unclear_box`. The human fixes those boxes in the approval UI.

## 2. Plan JSON schema

The schema is enforced with structured output: `response_format={"type":"text","mime_type":"application/json","schema": Plan.model_json_schema()}` ([structured-output](https://ai.google.dev/gemini-api/docs/structured-output)). The API supports `enum`, `minItems`/`maxItems`, `anyOf` and nullable via `["type","null"]`. It does **not** enforce `minLength`, `uniqueItems` and similar keywords, so we also validate in code.

Structured output is verified for Hydrogen. **UNVERIFIED for gemini-4-carbon**: I could not open its model page. If Carbon rejects the schema, use Hydrogen at `thinking_level: xhigh` for extraction.

```python
from pydantic import BaseModel, Field
from typing import Literal, Optional

Box = list[int]  # [ymin, xmin, ymax, xmax], 0-1000, on page image

class Cite(BaseModel):
    page: int                # 1-based PDF page
    label: str               # exactly as printed: "A", "104321", "Step 3"
    box_2d: Box

class Estimate(BaseModel):
    value: float
    estimated: bool          # True unless the number is printed in the guide
    basis: str               # "printed 720 mm" | "ratio to part A width"

class Feature(BaseModel):    # holes/cams/grooves visible on the part drawing
    id: str                  # "h1"
    kind: Literal["through_hole","blind_hole","cam_hole","groove","notch","edge_slot"]
    face: Literal["+X","-X","+Y","-Y","+Z","-Z"]
    pos_uv: list[float]      # normalised 0-1 on that face
    diameter_mm: Optional[Estimate] = None

class Part(BaseModel):
    id: str                  # guide label, e.g. "A"
    name: str
    count: int
    shape: Literal["board","rod","bracket","drawer_front","other"]
    dims_mm: dict[str, Estimate]       # {"length":..., "width":..., "thickness":...}
    features: list[Feature]
    cite: Cite                         # the parts-list entry
    drawing: Optional[Cite] = None     # best standalone drawing of the part

class Hardware(BaseModel):
    code: str                # "101350" as printed
    kind: Literal["dowel","cam_lock","cam_bolt","screw","nail","bracket","shelf_pin","other"]
    count: int
    length_mm: Optional[Estimate] = None
    cite: Cite

class Mate(BaseModel):       # proposal only; code computes transforms
    moving_part: str         # part id (instance "A#2" if count>1)
    moving_feature: str      # feature id or face "+Z"
    fixed_part: str
    fixed_feature: str
    type: Literal["insert","flush","edge_on_face"]
    hardware: Optional[str] = None     # code

class Step(BaseModel):
    n: int
    cite: Cite                          # the step drawing
    parts_added: list[str]
    hardware_used: dict[str, int]      # code -> qty in this step
    mates: list[Mate]
    view: Literal["iso_front_left","iso_front_right","iso_back_left","iso_back_right","front","side","top"]
    view_az_el_deg: list[float]         # estimated azimuth/elevation
    flip_whole_assembly: bool           # guide shows "turn over" icon
    notes: str

class Flag(BaseModel):
    ref: str                 # "part:A" | "step:3" | "hardware:101350"
    kind: Literal["unclear_drawing","count_mismatch","estimated_dims","unclear_box","ambiguous_mate"]
    message: str

class Plan(BaseModel):
    title: str
    parts: list[Part] = Field(min_length=1)
    hardware: list[Hardware]
    steps: list[Step] = Field(min_length=1)
    flags: list[Flag]
```

Post-validation is deterministic. A failure blocks approval or adds a flag:
- Σ `hardware_used[code]` over all steps == `Hardware.count`, and parts used across steps == `Part.count`.
- Every `Mate` references an existing part and feature.
- Every number not printed in the guide has `estimated=true`.

Grounding: the prompt says "only labels that are printed; if unsure, add a Flag rather than guessing". In the approval UI the human sees each crop next to its JSON entry.

## 3. CadQuery

Install facts:
- `cadquery` 2.8.0 was released 2026-06-21 and is a pure-Python wheel. Its PyPI metadata requires Python ≥3.11, while the page text still says "3.9–3.12", which contradicts it ([pypi/cadquery](https://pypi.org/project/cadquery/)).
- The native dependency `cadquery-ocp` 8.0.1.1.0 has **`macosx_11_0_arm64` wheels for cp311–cp314** ([pypi/cadquery-ocp](https://pypi.org/project/cadquery-ocp/)).
- Conda (`mamba install -c conda-forge cadquery`) is described as "better supported".

**Decision:** use pip in a **Python 3.12 venv** (`uv venv -p 3.12 && uv pip install cadquery`), which sits inside both version ranges. Fall back to mamba if the import fails. Not installed or tested yet, so this is **UNVERIFIED on our M4**.

Export: Workplane/Shape exports to STEP, STL, 3MF, VRML and others. **glTF/GLB is only available for `Assembly.export()`**, and there is no OBJ export ([importexport](https://cadquery.readthedocs.io/en/latest/importexport.html)). Pipeline per part:
- **STL** goes to Blender. Blender 5.3 has `bpy.ops.wm.stl_import` ([bpy.ops.wm](https://docs.blender.org/api/current/bpy.ops.wm.html)).
- **STEP** is kept as an archive copy.

The final GLB comes from Blender. CadQuery's GLB export is not used.

Tags do not survive STL. The template therefore writes a **sidecar `mates.json`** of named frames (origin + axis in part-local mm), computed from the CadQuery geometry. The LLM only edits the `PARAMS` and `build()` body. The harness code is fixed:

```python
# part_A.py — generated; harness lines marked FIXED
import cadquery as cq, json, sys
PARAMS = {"L": 720, "W": 300, "T": 18, "dowel_d": 8, "dowel_depth": 12}  # mm; estimated per plan
def build(p):
    b = cq.Workplane("XY").box(p["L"], p["W"], p["T"])
    frames = {}
    for i, x in enumerate([-p["L"]/2 + 32, p["L"]/2 - 32]):          # holes on +Z face
        b = b.faces(">Z").workplane(centerOption="CenterOfBoundBox") \
             .center(x, 0).hole(p["dowel_d"], p["dowel_depth"])
        frames[f"h{i+1}"] = {"origin": [x, 0, p["T"]/2], "axis": [0, 0, 1]}
    frames["+Z"] = {"origin": [0, 0, p["T"]/2], "axis": [0, 0, 1]}
    return b, frames
# ---- FIXED harness ----
if __name__ == "__main__":
    shape, frames = build(PARAMS); out = sys.argv[1]
    shape.val().exportStl(f"{out}.stl", tolerance=0.05, angularTolerance=0.2)
    cq.exporters.export(shape, f"{out}.step")
    json.dump({"frames": frames, "bbox": shape.val().BoundingBox().__dict__}, open(f"{out}.mates.json", "w"), default=str)
```

`cq.exporters.export` is the long-standing API. The current docs page shows `result.export(path)`; both should work, but **UNVERIFIED for 2.8**.

Each script runs in a subprocess with a 30 s timeout, and the traceback goes back to the agent. Frame ids must match the `Feature.id` values in the plan. The harness checks this and returns the missing ids as an error.

For odd non-board shapes (rare), fal image-to-3D is a fallback, with a cheap and an expensive option:
- `fal-ai/hunyuan-3d/v3.1/pro/image-to-3d`, $0.375
- `meshy/v7.1/image-to-3d`, $0.80

Mates for those parts are then placed by hand. Not planned for the demo.

## 4. Blender: headless app vs `bpy` wheel

| Option | Facts | Verdict |
|---|---|---|
| `bpy` from PyPI | 5.3.0 released 2026-11-10. **Requires Python ==3.13.\***. A `macosx_13_0_arm64` wheel exists (254 MB) ([pypi/bpy](https://pypi.org/project/bpy/)) | Forces 3.13 into our venv. GPU/render context in a wheel is **UNVERIFIED** |
| **Blender 5.3 app, `blender -b --python script.py -- args`** | Uses its own bundled Python. Same API (the docs are for "Blender 5.3 Python API") | **Recommended for the pipeline** |
| blender-mcp (`uvx mcp-for-blender`, add-on on port 9876, tools `execute_blender_code`, `look`) ([GitHub](https://github.com/ahujasid/blender-mcp)) | Live GUI Blender | **Debugging and the stage demo only** |

Note that blender-mcp no longer lists a `get_viewport_screenshot` tool; `look` does that job.

Blender takes about 3–5 s to start (**estimate**). We call it **once per batch, not once per part**: `render_parts.py` takes a JSON list of STL paths and cameras and renders them all. Installing Blender (Saturday night) is a DMG from blender.org, about 400 MB.

## 5. Assembly placement (deterministic)

The agent proposes mates in the plan, and code computes the transforms. Algorithm per step, in guide order:

1. The fixed base is the first part in step 1, at identity.
2. For a moving part, gather its mates against parts already placed. The world frames of the fixed features are `T_fixed · frame`.
3. **One `insert` or `flush` mate:** align the moving axis to the opposite of the fixed axis (`flush`) or to the same axis (`insert`), and make the origins coincide. One degree of freedom (spin about the axis) remains. Resolve it with a second mate if there is one; otherwise snap to whichever of the 4 multiples of 90° keeps the bounding boxes from intersecting.
4. **≥2 point mates** (typical: 2–4 dowel holes): use **Kabsch/SVD** on the matched origins, then check that the axes agree (dot product < −0.9 for opposing faces).
5. Each solution gets a `flip` id. Symmetric hole patterns give 2 or 4 valid solutions, and we keep them all as candidates for the step check (§8).
6. Hardware: a dowel is placed at the fixed hole frame, its axis along the hole, with half its length inside.

```python
import numpy as np
def kabsch(P, Q):  # P moving pts (n,3), Q fixed pts -> R,t with R@P+t≈Q
    cp, cq_ = P.mean(0), Q.mean(0); H = (P-cp).T @ (Q-cq_)
    U, _, Vt = np.linalg.svd(H); d = np.sign(np.linalg.det(Vt.T@U.T))
    R = Vt.T @ np.diag([1,1,d]) @ U.T; return R, cq_ - R@cp
```

CadQuery's own `Assembly.constrain(..., "Plane"|"Axis"|"Point")` plus `solve()` does the same job with an optimiser ([assy docs](https://cadquery.readthedocs.io/en/latest/assy.html)). Underconstrained systems depend on the initial position, so we use our closed-form solution and keep CadQuery's solver as the fallback.

Units: CadQuery works in mm, so scale by 0.001 on import into Blender (metres, which is glTF's unit).

## 6. Animation and GLB export

Blender 5.3 `export_scene.gltf` ([API](https://docs.blender.org/api/current/bpy.ops.export_scene.html)):
- `export_animation_mode`: `ACTIONS` exports actions as separate animations, `ACTIVE_ACTIONS` merges them into one, `NLA_TRACKS` exports individual NLA tracks as separate animations, `SCENE` bakes everything into one.
- `export_merge_animation`: `NLA_TRACK` merges by NLA track name, `ACTION` by action name.
- `export_anim_slide_to_zero` makes every animation start at 0 s.

**Decision:** for step *k*, every object that moves gets an action `step_k__<obj>` pushed onto an NLA track named **`step_k`**. Export with `NLA_TRACKS` and `merge=NLA_TRACK`, so all tracks named `step_03` become one glTF animation called `step_03`.

```python
for obj, (start, end) in moves:                     # exploded -> assembled
    obj.animation_data_create(); act = bpy.data.actions.new(f"step_{k:02d}__{obj.name}")
    obj.animation_data.action = act
    obj.location = start; obj.keyframe_insert("location", frame=1)
    obj.location = end;   obj.keyframe_insert("location", frame=48)   # 2 s @24fps
    # rotation_quaternion keyed the same way when flip/turn-over
    tr = obj.animation_data.nla_tracks.new(); tr.name = f"step_{k:02d}"
    tr.strips.new(act.name, 1, act); obj.animation_data.action = None
bpy.ops.export_scene.gltf(filepath="assembly.glb", export_format="GLB",
    export_animations=True, export_animation_mode="NLA_TRACKS",
    export_merge_animation="NLA_TRACK", export_anim_slide_to_zero=True,
    export_force_sampling=True, export_cameras=True, export_yup=True)
```

The exploded start position is the final position plus `−insert_axis × (1.5 × part size)`. Hardware starts 60 mm out along its hole axis.

glTF animation cannot toggle visibility, so the **viewer reads `manifest.json`**:

```json
{"steps":[{"n":3,"anim":"step_03","visible":["A#1","B#1","dowel_101350#1"],
  "camera":{"pos":[..],"target":[..],"ortho_height":0.9},
  "crop":"crops/step_03.png","cite":{"page":4,"label":"3"},"flags":[]}]}
```

The viewer shows the `visible` nodes in their final pose and plays `anim`. Camera objects `cam_step_03` are also exported. Real export behaviour with many objects is **UNVERIFIED** until the first test GLB loads in three.js. Forum reports describe NLA-mode bugs in old 3.6 builds, not 5.x.

## 7. Check renders

| Engine | Speed (M4, 512 px, **estimate**) | Look | Headless on macOS |
|---|---|---|---|
| **Workbench** | <1 s | Flat lighting, white material, object outline + cavity | **UNVERIFIED** in `-b` (needs a GPU context; should work with a logged-in desktop) |
| EEVEE + Freestyle | 2–5 s | Closest to the guide's line art | **UNVERIFIED**. A vendor article claims EEVEE-Next renders black frames when headless ([iRender](https://irendering.net/eevee-next-headless-dilemma-cloud-render-farm-guide/)), and [#114326](https://projects.blender.org/blender/blender/issues/114326) reports a background-mode Metal device issue |
| Cycles CPU, 4 samples + Freestyle | 5–15 s | Line art | Always works headless: our **fallback** |

**First task Saturday night:** run `blender -b --python test_render.py` with each engine and keep whichever produces non-black output.

- Engine id in 5.3: `BLENDER_EEVEE` (the default, per `RenderSettings.engine`). Workbench is `BLENDER_WORKBENCH` (**UNVERIFIED** name, not shown on the docs page I read).
- Workbench settings: `scene.display.shading.light='FLAT'`, `color_type='OBJECT'` (white), `show_object_outline=True`, `show_cavity=True`, white world.
- Freestyle: `scene.render.use_freestyle=True`, `line_thickness_mode='ABSOLUTE'`, 1.5 px ([RenderSettings](https://docs.blender.org/api/current/bpy.types.RenderSettings.html)).
- **Render size:** 512×512 for comparisons, 1024 for the viewer thumbnails.
- **Camera:** orthographic, because flat-pack guides are axonometric. Azimuth/elevation come from `view_az_el_deg` (default iso 45°/30°). `ortho_scale` fits the bounding box of the visible parts with 10% margin, and the view direction matches the crop's aspect ratio.
- **Part renders:** the part alone, posed the way the guide's part drawing shows it. The agent may set `part_view` to a rotation, one of the 24 axis-aligned orientations.

## 8. Verification loop

**Deterministic pre-check** (OpenCV, about 10 ms):
1. Grayscale both images and run Canny.
2. Crop each to the bounding box of its edges, resize to 256 px on the long side and pad to a square.
3. Silhouette: close the edges morphologically (dilate 3) and fill the outer contour.
4. Compute `IoU(sil_render, sil_crop)` and the aspect-ratio difference.

Guide drawings contain arrows, hands and labels, so mask the label text boxes using native-text positions from PyMuPDF, and treat IoU as a soft signal.

- Gate: `iou ≥ 0.75` and `|aspect diff| < 10%` → still ask Decision 1 once to confirm.
- `iou < 0.4` → skip Decision 1 and fix the proportions immediately (cheap).

**Decision 1** ([decisions-api](https://ai.google.dev/gemini-api/docs/decisions-api)): two images at `resolution: "medium"` (560 tokens each) come to about 1.4k tokens, roughly $0.00014 per call.

```python
r = client.decisions.create(model="gemini-decision-1", input=[
  {"type":"text","text":"Image 1: drawing of part A from an assembly guide (page 2).\n"},
  {"type":"image","data":crop_b64,"mime_type":"image/png","resolution":"medium"},
  {"type":"text","text":"\nImage 2: 3D render of our model of part A from the same angle.\n"},
  {"type":"image","data":render_b64,"mime_type":"image/png","resolution":"medium"}],
 questions={
  "same_part": {"type":"noul","instructions":"Do both images show the same part shape, ignoring line style, shading and labels?"},
  "holes_match": {"type":"noul","instructions":"Are the holes/cutouts in the same number and positions?"},
  "proportions": {"type":"score","instructions":"How well do length/width/thickness ratios match?",
     "criteria":["Clearly different","Noticeably off","Close","Match"]},
  "main_issue": {"type":"choice","instructions":"Biggest difference in image 2 vs image 1?",
     "criteria":{"extra_feature":"Has a feature the drawing lacks","missing_feature":"Lacks a hole/notch/groove",
       "none":"No significant difference","orientation":"Same part but rotated or mirrored",
       "proportions":"Wrong size ratios","thickness":"Wrong thickness"}}})
```

Choice keys are written in alphabetical order, as the API requires.

**Step check:** same pattern, with the guide step crop against the step render. Questions:
- `parts_present` (noul)
- `orientation` (choice: `correct`, `flipped_front_back`, `flipped_left_right`, `rotated_90`, `upside_down`, `wrong_part`)
- `position` (score)
- `hardware_visible` (noul)

**Flip disambiguation:** when §5 produced several candidates, render each one and send them **all in one call** as a `choice` question with keys `cand_a…cand_d`, then take the argmax.

**Stop conditions:**
- **Accept** when `same_part.noul ≥ 0.8`, `holes_match ≥ 0.7` and `proportions.score ≥ 2.0`. For steps: `parts_present ≥ 0.8`, `orientation == correct` and `position ≥ 2.0`.
- **Max 3 fix iterations** per part and per step. Also stop early if two iterations in a row make no improvement (IoU change < 0.02 and the Decision answers are unchanged).
- On give-up: keep the best-scoring attempt and add `Flag(kind="unclear_drawing")`. The viewer shows a "needs human check" badge. The pipeline never blocks.
- The fixing agent receives the Decision answers, the IoU and both images, and may only change `PARAMS`/`build()` (parts) or `flip`/`part_view`/mate choice (steps). It may never change counts or labels in the plan.

## 9. Timing and cost per guide (estimates)

Assumptions: 8 pages, 6 unique parts, 8 steps, average 1.8 iterations.

| Stage | Calls | Time | Cost |
|---|---|---|---|
| Rasterise + crops | n/a | 3 s | n/a |
| Plan extraction (Carbon, ~12k in / ~12k out incl. thinking) | 1 + 8 box checks | 60–120 s | ~$0.15 |
| Part scripts (Hydrogen, ~8k in / ~4k out each) | ~11 | 3–5 min | ~$0.45 |
| CadQuery runs | ~11 | 1 min | n/a |
| Part + step renders (batched Blender) | ~6 batches | 1–2 min | n/a |
| Decision 1 checks | ~40 | 10 s | <$0.01 |
| Step fixes (Hydrogen) | ~6 | 1–2 min | ~$0.25 |
| GLB export | 1 | 10 s | n/a |
| **Total** | | **~8–15 min** | **~$1 (budget $3)** |

Accumulated context counts as input on every turn when we chain with `previous_interaction_id`. That is why each part and each step gets a **fresh** interaction, not one long agent conversation.

## 10. Agent loop and tools

API: `client.interactions.create(model="gemini-4-hydrogen", input=..., tools=[...], previous_interaction_id=...)`. Function calls come back as `steps` with `type=="function_call"`, and we return `{"type":"function_result","call_id":...,"result":[...]}` ([function-calling](https://ai.google.dev/gemini-api/docs/function-calling)).

Hydrogen supports function calling, structured output and code execution, has a 1,048,576-token context, and its thinking levels are low/medium/high/xhigh with `high` as the default ([model page](https://ai.google.dev/gemini-api/docs/models/gemini-4-hydrogen)).

Images go back to the model as `image` parts in the next `input`. Whether a `function_result` may itself contain an image is **UNVERIFIED**.

| Tool | Args (JSON) | Returns | Called by |
|---|---|---|---|
| `extract_plan` | `{pdf_id}` | `plan.json` + flags | orchestrator (Carbon) |
| `get_crop` | `{ref:"part:A"\|"step:3"}` | png | agent |
| `write_part_script` | `{part_id, params:{}, build_src:str}` | `{path}` | agent |
| `run_cadquery` | `{part_id}` | `{ok, stl, frames:[ids], bbox_mm, error?}` | agent |
| `render_part` | `{part_id, part_view:[rx,ry,rz], az_el:[a,e]}` | `{png}` | agent |
| `compare_with_guide` | `{ref, render_png}` | `{iou, aspect_diff, decision:{...}, pass}` | agent |
| `place_part` | `{step, instance, mates:[Mate], flip?:int}` | `{transform, candidates:n, collisions:[]}` | agent / orchestrator |
| `render_step` | `{step, candidate?:int}` | `{png}` | agent |
| `compare_step` | `{step, render_pngs:[..]}` | `{decision:{...}, best_candidate, pass}` | agent |
| `flag_for_human` | `{ref, kind, message}` | `{ok}` | agent |
| `export_glb` | `{}` | `{glb, manifest}` | orchestrator |

Thinking levels: `medium` for script writing, `high` for step fixes, `low` for box re-checks.

**Saturday-night order of work:**
1. Install Blender 5.3 and the Python 3.12 venv with CadQuery, then test the headless render.
2. Hand-build one test GLB with 2 steps and load it in three.js.
3. Plan extraction on the real guide.
4. Part loop.
5. Placement and step loop.
6. Polish.
