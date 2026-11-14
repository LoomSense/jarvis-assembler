"""Build the animated assembly scene and export it for the viewer.

    Blender -b --factory-startup -P pipeline/blender/assemble.py -- <plan.json> <parts_dir> <placements.json> <out_dir>

Writes <out>/walkthrough.glb (one animation per guide step, named step_NN),
<out>/manifest.json (what the viewer shows per step), <out>/scene.blend and
<out>/renders/step_NN.png (check renders, new parts tinted).
"""
import json
import math
import sys
from pathlib import Path

import bmesh
import bpy
from mathutils import Matrix, Vector

plan_path, parts_dir, place_path, out = map(Path, sys.argv[sys.argv.index("--") + 1:][:4])
plan = json.loads(plan_path.read_text())
placed = json.loads(place_path.read_text())["instances"]
(out / "renders").mkdir(parents=True, exist_ok=True)
MM = 0.001
FPS = 24
TRAVEL = {"part": 0.20, "hardware": 0.08}          # metres a piece travels into place
SPAN = {"part": (1, 30), "hardware": (24, 52)}      # frames: boards land first, then their screws
MATERIALS = {"pine": ((0.80, 0.62, 0.40, 1), 0.0, 0.65), "steel": ((0.72, 0.74, 0.77, 1), 1.0, 0.35)}
ACCENT = (0.62, 0.81, 1.0, 1)                       # --ag accent, marks the parts a step adds

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.render.fps = FPS


def material(name):
    if name in bpy.data.materials:
        return bpy.data.materials[name]
    col, metal, rough = MATERIALS[name]
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = col
    b.inputs["Metallic"].default_value = metal
    b.inputs["Roughness"].default_value = rough
    m.diffuse_color = col
    return m


# --- unique meshes -----------------------------------------------------------------------------
defs = {p["id"]: dict(p, kind="part") for p in plan["parts"]}
defs.update({h["code"]: dict(h, kind="hardware") for h in plan["hardware"]})
meshes = {}
for pid, d in defs.items():
    bpy.ops.wm.stl_import(filepath=str(parts_dir / f"{pid}.stl"))
    obj = bpy.context.selected_objects[0]
    me = obj.data
    me.name = f"mesh_{pid}"
    me.transform(Matrix.Scale(MM, 4))                            # part scripts are in mm, the scene in metres
    bm = bmesh.new(); bm.from_mesh(me)
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-6)     # STL triangles are unwelded
    bm.to_mesh(me); bm.free()
    if d["kind"] == "hardware":
        for poly in me.polygons:
            poly.use_smooth = True
    me.materials.append(material(d.get("material", "pine")))
    meshes[pid] = me
    bpy.data.objects.remove(obj)

# --- instances at their final pose ---------------------------------------------------------------
objs, nodes = {}, {}
for inst, pl in placed.items():
    pid, n = inst.split("#")
    name = f"{'hw' if pl['kind'] == 'hardware' else 'part'}_{pid}_{n}"
    o = bpy.data.objects.new(name, meshes[pid])
    scene.collection.objects.link(o)
    M = Matrix(pl["matrix_mm"])
    M.translation = M.translation * MM
    o.matrix_world = M
    o["instance"], o["partId"], o["step"] = inst, pid, pl["step"]
    objs[inst], nodes[inst] = o, name
bpy.context.view_layer.update()

# --- one NLA track per step ------------------------------------------------------------------------
for step in plan["steps"]:
    track = f"step_{step['n']:02d}"
    for add in step["add"]:
        if add.get("base"):
            continue
        inst = add["instance"]; pl = placed[inst]; o = objs[inst]
        final = o.location.copy()
        f0, f1 = SPAN[pl["kind"]]
        o.location = final + Vector(pl["approach"]) * TRAVEL[pl["kind"]]
        o.keyframe_insert("location", frame=f0)
        o.location = final
        o.keyframe_insert("location", frame=f1)
        ad = o.animation_data
        act = ad.action; act.name = f"{track}__{o.name}"
        tr = ad.nla_tracks.new(); tr.name = track
        tr.strips.new(act.name, 1, act)
        ad.action = None
        o.location = final


# --- cameras: one per step, orthographic like the guide's drawings ---------------------------------
def bounds(instances):
    pts = [objs[i].matrix_world @ Vector(c) for i in instances for c in objs[i].bound_box]
    lo = Vector([min(p[k] for p in pts) for k in range(3)]); hi = Vector([max(p[k] for p in pts) for k in range(3)])
    return pts, (lo + hi) / 2


def make_camera(name, instances, az, el):
    pts, centre = bounds(instances)
    a, e = math.radians(az), math.radians(el)
    d = Vector((math.cos(a) * math.cos(e), math.sin(a) * math.cos(e), math.sin(e)))
    cam = bpy.data.cameras.new(name); cam.type = "ORTHO"
    o = bpy.data.objects.new(name, cam); scene.collection.objects.link(o)
    o.location = centre + d * 2.0
    o.rotation_euler = (-d).to_track_quat("-Z", "Y").to_euler()
    bpy.context.view_layer.update()
    inv = o.matrix_world.inverted()
    loc = [inv @ p for p in pts]
    w = max(p.x for p in loc) - min(p.x for p in loc); h = max(p.y for p in loc) - min(p.y for p in loc)
    cam.ortho_scale = max(w, h) * 1.25
    cam.clip_start, cam.clip_end = 0.01, 10
    return o, centre


# --- manifest ----------------------------------------------------------------------------------------
visible, steps_out, cams = [], [], {}
for step in plan["steps"]:
    added = [a["instance"] for a in step["add"]]
    visible += added
    az, el = step.get("view_az_el_deg", (-55, 28))
    cam, centre = make_camera(f"cam_step_{step['n']:02d}", visible, az, el)
    cams[step["n"]] = cam
    steps_out.append({
        "n": step["n"], "clip": f"step_{step['n']:02d}", "title": step["title"],
        "visible": [nodes[i] for i in visible],
        "moving": [nodes[i] for i in added if not next(a for a in step["add"] if a["instance"] == i).get("base")],
        "pickParts": sorted({i.split("#")[0] for i in added if placed[i]["kind"] == "part"}),
        "hardware": [{"code": c, "qty": q} for c, q in step.get("hardware_used", {}).items()],
        "flipWholeAssembly": step.get("flip_whole_assembly", False),
        "camera": {"node": cam.name, "pos": [round(v, 4) for v in cam.location], "target": [round(v, 4) for v in centre],
                   "ortho_height": round(cam.data.ortho_scale, 4), "up": "Z (Blender); the GLB is Y-up"},
        "duration_s": round(SPAN["hardware"][1] / FPS if step.get("hardware_used") else SPAN["part"][1] / FPS, 2),
        "cite": step["cite"], "crop": None, "refRender": f"renders/step_{step['n']:02d}.png", "flags": [],
    })
manifest = {
    "title": plan["title"], "guideId": plan.get("guide_id"), "glb": "walkthrough.glb", "units": "m", "fps": FPS,
    "overall_mm": plan.get("overall_mm"),
    "parts": [{"id": p["id"], "name": p["name"], "count": p["count"], "cite": p["cite"],
               "dimsEstimated": p.get("dims", {}).get("estimated", True),
               "nodes": [n for i, n in nodes.items() if i.split("#")[0] == p["id"]]} for p in plan["parts"]],
    "hardware": [{"code": h["code"], "name": h["name"], "kind": h["kind"], "count": h["count"], "cite": h["cite"],
                  "nodes": [n for i, n in nodes.items() if i.split("#")[0] == h["code"]]} for h in plan["hardware"]],
    "steps": steps_out,
}
(out / "manifest.json").write_text(json.dumps(manifest, indent=1))

# --- export ----------------------------------------------------------------------------------------
bpy.ops.export_scene.gltf(
    filepath=str(out / "walkthrough.glb"), export_format="GLB", export_animations=True,
    export_animation_mode="NLA_TRACKS", export_merge_animation="NLA_TRACK", export_anim_slide_to_zero=True,
    export_force_sampling=True, export_cameras=True, export_yup=True, export_extras=True, export_apply=False)
scene.frame_start, scene.frame_end = 1, SPAN["hardware"][1]
scene.frame_set(scene.frame_end)                              # every step finished: the assembled state
bpy.ops.wm.save_as_mainfile(filepath=str(out / "scene.blend"))

# --- check renders: the state after each step, the step's new parts tinted -------------------------------
scene.render.engine = "BLENDER_WORKBENCH"
sh = scene.display.shading
sh.light = "STUDIO"; sh.color_type = "OBJECT"; sh.show_object_outline = True; sh.object_outline_color = (0.1, 0.1, 0.1)
sh.show_cavity = True; sh.cavity_type = "BOTH"; sh.show_shadows = False
scene.display.render_aa = "8"
scene.render.resolution_x = scene.render.resolution_y = 900
scene.render.film_transparent = False
sh.background_type = "VIEWPORT"; sh.background_color = (1, 1, 1)
scene.view_settings.view_transform = "Standard"
for s in steps_out:
    for inst, o in objs.items():
        o.hide_render = nodes[inst] not in s["visible"]
        base = MATERIALS[defs[inst.split("#")[0]].get("material", "pine")][0]
        o.color = ACCENT if nodes[inst] in s["moving"] else base
    scene.camera = cams[s["n"]]
    scene.render.filepath = str(out / "renders" / f"step_{s['n']:02d}.png")
    bpy.ops.render.render(write_still=True)

# the finished product from the cover's angle, for comparison with the guide's cover drawing
az, el = plan.get("cover_view_az_el_deg", (-125, 25))
cover_cam, _ = make_camera("cam_cover", list(objs), az, el)
for inst, o in objs.items():
    o.hide_render = False
    o.color = MATERIALS[defs[inst.split("#")[0]].get("material", "pine")][0]
scene.camera = cover_cam
scene.render.filepath = str(out / "renders" / "cover.png")
bpy.ops.render.render(write_still=True)

tris = sum(len(o.data.loop_triangles) or len(o.data.polygons) for o in objs.values())
print("RESULT " + json.dumps({"instances": len(objs), "steps": len(steps_out), "triangles": tris,
                              "glb_bytes": (out / "walkthrough.glb").stat().st_size}))
