# Brief for the build agent: assembly guide → plan and part scripts

You are given the pages of a flat-pack assembly guide as images (`guide/page-N.png`). Produce the files that let the pipeline build every part in 3D and animate the assembly exactly as the guide shows it, in the guide's order.

Write:

- `job/plan.json`: the plan (schema below).
- `job/parts/<name>.py`: one CadQuery script per unique part and per fastener shape.

Then the pipeline must pass:

    ./py -m pipeline.run job/plan.json job/out

It exits 0 and prints `RESULT {...}` when every part builds, every mate fits (within 0.5 mm and 2°), no two parts overlap, and the counts match. Otherwise it prints `FIT PROBLEMS:` with one line per problem. Fix the scripts or the plan and run again until it passes. Then look at `job/out/renders/step_NN.png` and `job/out/renders/cover.png` and compare them with the guide's drawings. If a step looks different from the guide (a part on the wrong side, upside down, wrong screws), fix it.

## Rules

1. Stay loyal to the guide. Same parts, same counts, same step order, same screw at the same place. Never invent a step or a part. Never change a count to make a check pass.
2. Hardware counts come from the guide's parts list (e.g. "4x 108363"). The number of screws used across all steps must equal that count.
3. Pre-assembled sub-assemblies that come out of the box as one piece (for example slats already stapled to battens) are ONE part.
4. The guide has no dimensions. Use the overall size you are given and the drawings' proportions. Put shared dimensions in the plan's `dims` so mating parts agree.
5. If a drawing is unclear, choose the most likely reading and add an entry to `flags`.
6. Use only `cadquery` and `pipeline.cad.lib` in part scripts. No file or network access.

## Part script

```python
"""What the part is, guide page, and its local frame."""
from pipeline.cad.lib import board, drill, frame, union, screw

PARAMS = {"L": 460, "t": 12}          # mm. A key that also appears in the plan's `dims` is overridden by it.

def build(p):
    solid = board(0, 0, 0, p["L"], p["t"], 70)     # cq.Workplane
    frames = {"hole_1": frame((20, 0, 35), (0, -1, 0))}
    solid = drill(solid, (20, 0, 35), (0, -1, 0), 4.2, p["t"] + 1)
    return solid, frames
```

Library (`pipeline/cad/lib.py`, read it):

- `board(x0, y0, z0, dx, dy, dz)`: axis-aligned board with its min corner at (x0, y0, z0).
- `drill(solid, origin, axis, diameter, depth)`: round hole starting at `origin` on the surface, going into the material (along −axis).
- `union(*solids)`.
- `screw(length, shank_d, head_d, head_h)`: seat (underside of the head) at the origin, shank along +Z.
- `frame(origin, axis)`: a mate frame.
- `rounded_box(x0, y0, z0, dx, dy, dz, r_side, r_edge)`: a box with rounded corners and soft edges (moulded plastic, padded parts).
- `cushion(cx, cy, z0, w, d, h, corner, crown)`: an upholstered pad (seat, back cushion, headrest), rounded in plan with a soft top.
- `cylinder(origin, axis, diameter, length)` and `tube(origin, axis, outer_d, inner_d, length)`: round bars and hollow tubes.
- `bent_rod(points, diameter)`: a round bar through 3D points with rounded joints (tube frames, handles, armrest loops).
- `turned(profile, origin, axis)`: a lathe part from (radius, height) points (wheels, knobs, feet, collars).
- `curved_panel(width, height, thickness, depth, z0)`: a shell curved in plan (chair backs, seat shells).
- `radial(solid, n, centre, axis)`: n copies around an axis, joined (star bases, spokes).

**Shapes.** Model what a person would recognise from the drawing: the outline, the curves, where the holes are. A chair seat is a `cushion`, not a box; a star base is `radial` arms around a hub; a caster is `turned` wheels in a fork. Exact curvature cannot be read from a drawing, so keep it simple, but never use a plain box for something that is clearly round or padded. If you still have to simplify a part a lot, add a flag `{"kind": "approximate_shape", "ref": "part:<id>", "message": "..."}` so the person approving the plan knows.

**Mate frames.** A frame is a point on the part's surface where another part or a fastener attaches. `origin` lies on the surface. `axis` is a unit vector pointing OUT of the material there. Two frames mate when their origins coincide and their axes are opposite.

- Two boards touching face to face: put a frame on each touching face at the same spot (usually at each screw line).
- A part needs at least two mates to be fixed (one mate leaves it free to spin).
- A screw has one frame, `seat`, with axis (0, 0, 1). It mates to a frame on the OUTER face of the piece it passes through first, at the hole, with the axis pointing out of that face. Drill that hole through, and a pilot hole in the piece behind.
- Parts must not overlap (more than 5 mm³ of shared volume fails). Touching is fine. Fasteners are not checked for overlap.

## plan.json

```json
{
  "title": "Product name", "guide_id": "AA-000000-0", "units": "mm",
  "overall_mm": {"L": 460, "W": 310, "H": 250},
  "cover_view_az_el_deg": [-128, 24],
  "dims": {"L": 460, "t": 12},
  "parts": [
    {"id": "side_panel", "name": "Long side panel (pre-assembled)", "short": "side panel",
     "el": "το πλαϊνό πάνελ", "el_pron": "το",
     "count": 2, "script": "parts/side_panel.py", "params": {}, "material": "pine",
     "cite": {"page": 3, "label": null, "note": "step 1, standing"},
     "dims": {"estimated": true, "basis": "scaled from the overall size"}}
  ],
  "hardware": [
    {"code": "108363", "kind": "screw", "name": "Long screw", "short": "long screw", "el": "μακριά βίδα",
     "count": 4, "script": "parts/screw.py", "params": {"length": 60, "shank_d": 4.5, "head_d": 9, "head_h": 3.5},
     "material": "steel", "cite": {"page": 2, "label": "108363"}}
  ],
  "steps": [
    {"n": 1, "title": "Stand a side panel against the end of the bottom panel",
     "cite": {"page": 3, "label": "1"},
     "add": [
       {"instance": "bottom_panel#1", "base": true},
       {"instance": "side_panel#1", "mates": [
         {"moving": "bolt_out_1", "fixed": "bottom_panel#1.pilot_A1"},
         {"moving": "bolt_out_2", "fixed": "bottom_panel#1.pilot_A2"}]}
     ],
     "parts_added": ["bottom_panel", "side_panel"], "hardware_used": {},
     "pose": "upright", "assembly_rotation_deg": [0, 0, 0],
     "turn_text": {"en": "…", "el": "…"},
     "flip_whole_assembly": false, "view_az_el_deg": [125, 28]}
  ],
  "final_turn_text": {"en": "Turn the box the right way up.", "el": "Γύρισε το κουτί στην κανονική του θέση."},
  "flags": [{"ref": "step:4", "kind": "unclear_drawing", "message": "…"}]
}
```

- `parts[].id`: lower_snake_case descriptive name (guides often have no part letters). `material`: `pine` or `steel`. `short`, `el`, `el_pron` are the English and Greek words used in spoken instructions (`el` in the accusative with its article, `el_pron` the matching pronoun το/την/τον).
- `steps`: one per numbered step in the guide, in order. If the guide says to repeat steps on the other side (a turn arrow followed by the same drawings), write those as further steps.
- `add`: the ORDER of this list is the order of the animation: parts first, then each screw, one by one, in a sensible order (around the part, as a person would). Exactly one entry in the whole plan has `"base": true`: the first part laid down. Its local frame is the world frame, Z up.
- An instance is `<part id or hardware code>#<n>`, numbered from 1. `mates[].moving` is a frame name on the new piece; `fixed` is `<instance>.<frame>` on a piece already placed.
- `pose` + `assembly_rotation_deg`: how the whole assembly sits during this step, as the guide draws it: XYZ Euler angles in degrees applied to the base part's frame (`[0,0,0]` = base part as modelled; `[180,0,0]` = upside down; `[0,90,0]` = stood on one end). The animation turns the assembly when this changes between steps and shows `turn_text`. Set `flip_whole_assembly` true on a step that starts with such a turn.
- `view_az_el_deg`: camera azimuth and elevation (degrees, Z up, in the turned scene) matching the guide's drawing for that step. `cover_view_az_el_deg`: the same for the finished product on the cover.
- `hardware_used`: `{code: quantity}` for this step.

**Sub-assemblies.** When the guide builds something on its own and only later attaches it as one piece (a seat with its mechanism and back, a drawer, a door, a frame), do not attach its parts to the main assembly early. Declare it and build it in its own frame:

```json
"subassemblies": [{"id": "seat_unit", "name": "Seat with backrest", "short": "seat with backrest",
                   "el": "το κάθισμα με την πλάτη", "el_pron": "το"}],
"steps": [
  {"n": 4, "subassembly": "seat_unit", "assembly_rotation_deg": [180, 0, 0], "add": [
     {"instance": "seat#1", "into": "seat_unit", "base": true},
     {"instance": "tilt_mechanism#1", "into": "seat_unit", "mates": [{"moving": "seat_rear_L", "fixed": "seat#1.mech_rear_L"}, ...]},
     {"instance": "115994#1", "into": "seat_unit", "mates": [{"moving": "seat", "fixed": "tilt_mechanism#1.scr_rear_L"}]}]},
  {"n": 10, "add": [
     {"subassembly": "seat_unit", "mates": [{"moving": "tilt_mechanism#1.socket", "fixed": "gas_lift#1.top"},
                                            {"moving": "tilt_mechanism#1.base_ring", "fixed": "gas_lift#1.shoulder"}]}]}
]
```

- Every add of a piece of the sub-assembly has `"into"`. Its first part has `"base": true` (its frame is the sub-assembly's frame). Its mates may only name pieces of the same sub-assembly.
- A step that works on the sub-assembly has `"subassembly": "<id>"`. Its `assembly_rotation_deg` then turns the sub-assembly (in its base part's frame), as the guide draws it; the main assembly does not move. It is shown beside the main assembly.
- The joining add has `"subassembly"` and no `"instance"`. Its `moving` frames are `<instance>.<frame>` inside the sub-assembly, its `fixed` frames are on the main assembly. Every piece moves with it. After that, its pieces are part of the main assembly and later steps can mate to them.
- The whole plan still has exactly one main `"base": true` without `"into"`.
