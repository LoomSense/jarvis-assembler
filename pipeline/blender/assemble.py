"""Build the animated assembly scene and export it for the viewer.

    Blender -b --factory-startup -P pipeline/blender/assemble.py -- <plan.json> <parts_dir> <placements.json> <out_dir>

The animation follows the guide the way a person follows it: one action at a
time, in the guide's order. A step becomes a list of actions:

    turn     the whole assembly is turned the way the guide draws it for this step
    place    one part is picked up and moved into position
    fasten   one screw goes to its hole, then turns as it is driven in

Writes <out>/walkthrough.glb, manifest.json, scene.blend and renders/*.png.
The GLB's first animation, `walkthrough`, plays every action of every step in
order. Then one animation per step, `step_NN`, for viewers that step manually.
"""
import json
import math
import struct
import sys
from pathlib import Path

import bmesh
import bpy
from mathutils import Euler, Matrix, Quaternion, Vector

plan_path, parts_dir, place_path, out = map(Path, sys.argv[sys.argv.index("--") + 1:][:4])
plan = json.loads(plan_path.read_text())
placed = json.loads(place_path.read_text())["instances"]
(out / "renders").mkdir(parents=True, exist_ok=True)
MM = 0.001
FPS = 24
# frames per action
TURN_F, PLACE_F, GAP_F = 34, 30, 6
APPROACH_F, DRIVE_F, TURNS = 10, 18, 2.5           # a screw: fly to the hole, then drive in while turning
STEP_HOLD_F = 16
PLACE_TRAVEL, SCREW_CLEAR = 0.20, 0.07             # metres
HIDDEN = (1e-4, 1e-4, 1e-4)                        # glTF cannot animate visibility
MATERIALS = {"pine": ((0.80, 0.62, 0.40, 1), 0.0, 0.65), "steel": ((0.72, 0.74, 0.77, 1), 1.0, 0.35)}
ACCENT = (0.62, 0.81, 1.0, 1)
# each hardware code gets its own colour, so it is clear which screw goes where (steel, brass, black, copper, green zinc)
HW_COLOURS = [(0.72, 0.74, 0.77, 1), (0.86, 0.64, 0.18, 1), (0.16, 0.16, 0.18, 1), (0.76, 0.40, 0.24, 1), (0.45, 0.60, 0.45, 1)]

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.render.fps = FPS


def material(name):
    if name in bpy.data.materials:
        return bpy.data.materials[name]
    col, metal, rough = MATERIALS.get(name, MATERIALS["pine"])
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = col
    b.inputs["Metallic"].default_value = metal
    b.inputs["Roughness"].default_value = rough
    m.diffuse_color = col
    return m


# --- unique meshes ---------------------------------------------------------------------------------
defs = {p["id"]: dict(p, kind="part") for p in plan["parts"]}
defs.update({h["code"]: dict(h, kind="hardware") for h in plan["hardware"]})
info = {pid: json.loads((parts_dir / f"{pid}.mates.json").read_text()) for pid in defs}
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
    if d["kind"] == "hardware":
        MATERIALS[f"hw_{pid}"] = (HW_COLOURS[list(h["code"] for h in plan["hardware"]).index(pid) % len(HW_COLOURS)], 1.0, 0.35)
        me.materials.append(material(f"hw_{pid}"))
    else:
        me.materials.append(material(d.get("material", "pine")))
    meshes[pid] = me
    bpy.data.objects.remove(obj)

# --- instances at their final pose, all children of one root so the whole assembly can be turned --------
root = bpy.data.objects.new("assembly", None)
root.rotation_mode = "XYZ"
scene.collection.objects.link(root)
objs, nodes = {}, {}
for inst, pl in placed.items():
    pid, n = inst.split("#")
    name = f"{'hw' if pl['kind'] == 'hardware' else 'part'}_{pid}_{n}"
    o = bpy.data.objects.new(name, meshes[pid])
    scene.collection.objects.link(o)
    o.parent = root
    o.rotation_mode = "QUATERNION"                               # set before the matrix, or the rotation is lost
    M = Matrix(pl["matrix_mm"])
    M.translation = M.translation * MM
    o.matrix_local = M
    o["instance"], o["partId"], o["step"] = inst, pid, pl["step"]
    objs[inst], nodes[inst] = o, name
# a ring that appears at the hole where the current screw goes
bpy.ops.mesh.primitive_torus_add(major_radius=0.011, minor_radius=0.002, major_segments=32, minor_segments=8)
marker = bpy.context.active_object
marker.name = "marker"
MATERIALS["marker"] = (ACCENT, 0.0, 0.5)
marker.data.materials.append(material("marker"))
marker.data.materials[0].node_tree.nodes["Principled BSDF"].inputs["Emission Color"].default_value = ACCENT
marker.data.materials[0].node_tree.nodes["Principled BSDF"].inputs["Emission Strength"].default_value = 1.0
marker.parent = root
marker.rotation_mode = "QUATERNION"
marker.scale = HIDDEN
bpy.context.view_layer.update()
REST = {i: (o.location.copy(), o.rotation_quaternion.copy()) for i, o in objs.items()}


def local_points(instances):
    return [objs[i].matrix_local @ Vector(c) for i in instances for c in objs[i].bound_box]


ALL = local_points(objs)
CENTRE = sum(ALL, Vector()) / len(ALL)


def root_pose(rot_deg, instances):
    """Root transform that turns the assembly and rests the given pieces on the floor (z = 0)."""
    e = Euler([math.radians(a) for a in rot_deg], "XYZ")
    R = e.to_matrix()
    pts = [R @ p for p in local_points(instances)]
    c = R @ CENTRE
    return e, Vector((CENTRE.x - c.x, CENTRE.y - c.y, -min(p.z for p in pts)))


# --- actions: what a person does, one thing at a time, in the guide's order -----------------------------
def text_for(kind, d, i=0, n=0, first=False):
    name, el = d.get("short", d["name"]).lower(), d.get("el", d["name"])
    if kind == "fasten":
        code = d["code"]
        return {"en": f"Take a {name} ({code}) and screw it in here. Screw {i} of {n}.",
                "el": f"Πάρε μια {el} ({code}) και βίδωσέ την εδώ. Βίδα {i} από {n}."}
    if first:
        return {"en": f"Lay the {name} flat in front of you.", "el": f"Ακούμπησε {el} μπροστά σου."}
    return {"en": f"Take the {name} and put it in place here.",
            "el": f"Πάρε {el} και βάλε {d.get('el_pron', 'το')} στη θέση που φαίνεται."}


steps_actions, shown, prev_rot = [], [], None
for step in plan["steps"]:
    rot = step.get("assembly_rotation_deg", [0, 0, 0])
    acts, f = [], 1
    adds = step["add"]
    part_adds = [a for a in adds if placed[a["instance"]]["kind"] == "part"]
    hw_adds = [a for a in adds if placed[a["instance"]]["kind"] == "hardware"]
    if prev_rot is not None and rot != prev_rot and shown:
        acts.append({"type": "turn", "f0": f, "f1": f + TURN_F, "from": prev_rot, "to": rot, "on": list(shown),
                     "text": step.get("turn_text", {"en": "Turn the assembly as the guide shows.",
                                                    "el": "Γύρισε την κατασκευή όπως δείχνει ο οδηγός."})})
        f += TURN_F + GAP_F
    for a in part_adds:
        inst = a["instance"]; d = defs[inst.split("#")[0]]
        if a.get("base"):
            acts.append({"type": "place", "inst": inst, "f0": f, "f1": f + 8, "base": True, "text": text_for("place", d, first=True)})
            f += 8 + GAP_F
        else:
            acts.append({"type": "place", "inst": inst, "f0": f, "f1": f + PLACE_F, "text": text_for("place", d)})
            f += PLACE_F + GAP_F
    counts = {}
    for a in hw_adds:
        code = a["instance"].split("#")[0]; counts[code] = counts.get(code, 0) + 1
    seen = {}
    for a in hw_adds:
        inst = a["instance"]; code = inst.split("#")[0]; seen[code] = seen.get(code, 0) + 1
        acts.append({"type": "fasten", "inst": inst, "f0": f, "f1": f + APPROACH_F + DRIVE_F,
                     "text": text_for("fasten", defs[code], seen[code], counts[code])})
        f += APPROACH_F + DRIVE_F + GAP_F
    shown += [a["instance"] for a in adds]
    steps_actions.append({"step": step, "rot": rot, "acts": acts, "frames": f + STEP_HOLD_F, "shown": list(shown)})
    prev_rot = rot
final_turn = None
if prev_rot != [0, 0, 0]:
    final_turn = {"type": "turn", "f0": 1, "f1": 1 + TURN_F, "from": prev_rot, "to": [0, 0, 0], "on": list(shown),
                  "text": plan.get("final_turn_text", {"en": "Turn it the right way up.", "el": "Γύρισέ το στην κανονική θέση."})}


def key_root(frame, rot, instances):
    e, loc = root_pose(rot, instances)
    root.rotation_euler, root.location = e, loc
    root.keyframe_insert("rotation_euler", frame=frame)
    root.keyframe_insert("location", frame=frame)


def key_marker(inst, f0, f1, approach):
    """Show the ring at this piece's target point from f0 to f1: it pops in large, then settles."""
    marker.location = REST[inst][0] + approach * 0.0015
    marker.rotation_quaternion = Vector((0, 0, 1)).rotation_difference(approach)
    for f in (f0, f1):
        marker.keyframe_insert("location", frame=f); marker.keyframe_insert("rotation_quaternion", frame=f)
    marker.scale = HIDDEN
    if f0 - 1 >= 1:
        marker.keyframe_insert("scale", frame=f0 - 1)
    marker.keyframe_insert("scale", frame=f1 + 1)
    marker.scale = (1.9, 1.9, 1.9); marker.keyframe_insert("scale", frame=f0)
    marker.scale = (1, 1, 1); marker.keyframe_insert("scale", frame=f0 + APPROACH_F); marker.keyframe_insert("scale", frame=f1)
    marker.scale = HIDDEN


def key_action(a, off):
    """Insert the keyframes of one action, shifted by `off` frames."""
    f0, f1 = a["f0"] + off, a["f1"] + off
    if a["type"] == "turn":
        key_root(f0, a["from"], a["on"]); key_root(f1, a["to"], a["on"])
        return
    o = objs[a["inst"]]; pl = placed[a["inst"]]
    loc, quat = REST[a["inst"]]
    approach = Vector(pl["approach"])
    if a["type"] == "place":
        if not a.get("base"):
            o.location = loc + approach * PLACE_TRAVEL; o.keyframe_insert("location", frame=f0)
            o.location = loc; o.keyframe_insert("location", frame=f1)
        return
    length = info[pl["part"]]["params"].get("length", 30) * MM
    key_marker(a["inst"], f0, f1, approach)
    fd = f0 + APPROACH_F                                     # tip touches the hole, driving starts
    o.location = loc + approach * (length + SCREW_CLEAR); o.keyframe_insert("location", frame=f0)
    o.location = loc + approach * length; o.keyframe_insert("location", frame=fd)
    o.location = loc; o.keyframe_insert("location", frame=f1)
    quarters = int(TURNS * 4)
    for q in range(quarters + 1):                            # clockwise seen from the head = tightening
        o.rotation_quaternion = quat @ Quaternion((0, 0, 1), -math.pi / 2 * (quarters - q))
        o.keyframe_insert("rotation_quaternion", frame=fd + round(q * DRIVE_F / quarters))
    o.rotation_quaternion = quat @ Quaternion((0, 0, 1), -math.pi / 2 * quarters)
    o.keyframe_insert("rotation_quaternion", frame=f0)
    o.location, o.rotation_quaternion = loc, quat


def push(track):
    """Move every object's freshly keyed action onto an NLA track of that name."""
    for o in list(objs.values()) + [root, marker]:
        ad = o.animation_data
        if ad and ad.action:
            ad.action.name = f"{track}__{o.name}"
            tr = ad.nla_tracks.new(); tr.name = track
            tr.strips.new(ad.action.name, 1, ad.action)
            ad.action = None
    for i, o in objs.items():
        o.location, o.rotation_quaternion = REST[i]; o.scale = (1, 1, 1)
    marker.scale = HIDDEN


# one clip per step
for sa in steps_actions:
    if not any(a["type"] == "turn" for a in sa["acts"]):
        key_root(1, sa["rot"], sa["shown"])                  # every clip states the assembly's orientation
    if any(a["type"] == "fasten" for a in sa["acts"]):
        marker.scale = HIDDEN; marker.keyframe_insert("scale", frame=1)
    for a in sa["acts"]:
        key_action(a, 0)
    push(f"step_{sa['step']['n']:02d}")

# the whole guide in one clip
cursor, timeline = 0, []
key_root(1, steps_actions[0]["rot"], steps_actions[0]["shown"])
marker.scale = HIDDEN; marker.keyframe_insert("scale", frame=1)
for sa in steps_actions:
    for a in sa["acts"]:
        key_action(a, cursor)
        if a["type"] != "turn" and not a.get("base"):
            o = objs[a["inst"]]; f0 = a["f0"] + cursor
            o.scale = HIDDEN; o.keyframe_insert("scale", frame=1)
            if f0 - 1 > 1:
                o.keyframe_insert("scale", frame=f0 - 1)
            o.scale = (1, 1, 1); o.keyframe_insert("scale", frame=f0)
    timeline.append({"n": sa["step"]["n"], "start_s": round(cursor / FPS, 3), "end_s": round((cursor + sa["frames"]) / FPS, 3)})
    cursor += sa["frames"]
if final_turn:
    key_action(final_turn, cursor)
    timeline.append({"n": "finish", "start_s": round(cursor / FPS, 3), "end_s": round((cursor + TURN_F + STEP_HOLD_F) / FPS, 3),
                     "text": final_turn["text"]})
    cursor += TURN_F + STEP_HOLD_F
TOTAL_FRAMES = cursor
push("walkthrough")
scene.frame_start, scene.frame_end = 1, TOTAL_FRAMES


# --- cameras: one per step, orthographic like the guide's drawings ---------------------------------------
def make_camera(name, instances, rot, az, el):
    e, loc = root_pose(rot, instances)
    Mr = Matrix.Translation(loc) @ e.to_matrix().to_4x4()
    pts = [Mr @ p for p in local_points(instances)]
    lo = Vector([min(p[k] for p in pts) for k in range(3)]); hi = Vector([max(p[k] for p in pts) for k in range(3)])
    centre = (lo + hi) / 2
    a, e2 = math.radians(az), math.radians(el)
    d = Vector((math.cos(a) * math.cos(e2), math.sin(a) * math.cos(e2), math.sin(e2)))
    cam = bpy.data.cameras.new(name); cam.type = "ORTHO"
    o = bpy.data.objects.new(name, cam); scene.collection.objects.link(o)
    o.location = centre + d * 2.0
    o.rotation_euler = (-d).to_track_quat("-Z", "Y").to_euler()
    bpy.context.view_layer.update()
    inv = o.matrix_world.inverted()
    loc2 = [inv @ p for p in pts]
    w = max(p.x for p in loc2) - min(p.x for p in loc2); h = max(p.y for p in loc2) - min(p.y for p in loc2)
    cam.ortho_scale = max(w, h) * 1.35
    cam.clip_start, cam.clip_end = 0.01, 10
    return o, centre


def target_of(inst):
    return [round(v, 4) for v in REST[inst][0]]


# --- manifest ----------------------------------------------------------------------------------------------
steps_out, cams = [], {}
for sa, tl in zip(steps_actions, timeline):
    step = sa["step"]; n = step["n"]
    az, el = step.get("view_az_el_deg", (-55, 28))
    cam, centre = make_camera(f"cam_step_{n:02d}", sa["shown"], sa["rot"], az, el)
    cams[n] = cam
    added = [a["instance"] for a in step["add"]]
    actions = []
    for a in sa["acts"]:
        item = {"type": a["type"], "text": a["text"], "t0_s": round((a["f0"] - 1) / FPS, 3), "t1_s": round((a["f1"] - 1) / FPS, 3)}
        if a["type"] != "turn":
            pid = a["inst"].split("#")[0]
            item.update({"node": nodes[a["inst"]], "partId": pid, "target_m": target_of(a["inst"]),
                         "from_dir": placed[a["inst"]]["approach"]})
            if a["type"] == "fasten":
                item["code"] = pid
        else:
            item["rotation_deg"] = a["to"]
        actions.append(item)
    steps_out.append({
        "n": n, "clip": f"step_{n:02d}" if sa["acts"] else None, "title": step["title"], "pose": step.get("pose", "upright"),
        "assemblyRotationDeg": sa["rot"], "actions": actions,
        "visible": [nodes[i] for i in sa["shown"]],
        "moving": [nodes[i] for i in added if not next(a for a in step["add"] if a["instance"] == i).get("base")],
        "pickParts": sorted({i.split("#")[0] for i in added if placed[i]["kind"] == "part"}),
        "hardware": [{"code": c, "qty": q} for c, q in step.get("hardware_used", {}).items()],
        "flipWholeAssembly": step.get("flip_whole_assembly", False),
        "camera": {"node": cam.name, "pos": [round(v, 4) for v in cam.location], "target": [round(v, 4) for v in centre],
                   "ortho_height": round(cam.data.ortho_scale, 4), "up": "Z (Blender); the GLB is Y-up"},
        "duration_s": round(sa["frames"] / FPS, 2), "walkthrough": {"start_s": tl["start_s"], "end_s": tl["end_s"]},
        "cite": step["cite"], "crop": None, "refRender": f"renders/step_{n:02d}.png", "flags": [],
    })
manifest = {
    "title": plan["title"], "guideId": plan.get("guide_id"), "glb": "walkthrough.glb", "units": "m", "fps": FPS,
    "rootNode": "assembly", "markerNode": "marker", "overall_mm": plan.get("overall_mm"),
    "walkthrough": {"clip": "walkthrough", "duration_s": round(TOTAL_FRAMES / FPS, 2), "steps": timeline},
    "parts": [{"id": p["id"], "name": p["name"], "short": p.get("short", p["name"]), "count": p["count"], "cite": p["cite"],
               "dimsEstimated": p.get("dims", {}).get("estimated", True),
               "nodes": [n for i, n in nodes.items() if i.split("#")[0] == p["id"]]} for p in plan["parts"]],
    "hardware": [{"code": h["code"], "name": h["name"], "short": h.get("short", h["name"]), "kind": h["kind"], "count": h["count"], "cite": h["cite"],
                  "color": [round(c, 3) for c in MATERIALS[f"hw_{h['code']}"][0][:3]],
                  "nodes": [n for i, n in nodes.items() if i.split("#")[0] == h["code"]]} for h in plan["hardware"]],
    "steps": steps_out, "flags": plan.get("flags", []),
}
(out / "manifest.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False))

# --- export --------------------------------------------------------------------------------------------------
root.rotation_euler, root.location = (0, 0, 0), (0, 0, 0)
bpy.ops.export_scene.gltf(
    filepath=str(out / "walkthrough.glb"), export_format="GLB", export_animations=True,
    export_animation_mode="NLA_TRACKS", export_merge_animation="NLA_TRACK", export_anim_slide_to_zero=True,
    export_force_sampling=True, export_cameras=True, export_yup=True, export_extras=True, export_apply=False)


def order_animations(path):
    """Put `walkthrough` first, then step_01, step_02, ... A plain GLB viewer plays the first animation."""
    raw = path.read_bytes()
    jlen = struct.unpack_from("<I", raw, 12)[0]
    doc = json.loads(raw[20:20 + jlen])
    doc["animations"].sort(key=lambda a: (a["name"] != "walkthrough", a["name"]))
    body = json.dumps(doc, separators=(",", ":")).encode()
    body += b" " * (-len(body) % 4)
    rest = raw[20 + jlen:]
    path.write_bytes(struct.pack("<4sII", b"glTF", 2, 12 + 8 + len(body) + len(rest))
                     + struct.pack("<I4s", len(body), b"JSON") + body + rest)
    return [a["name"] for a in doc["animations"]]


clip_order = order_animations(out / "walkthrough.glb")
for i, o in objs.items():                                      # the exporter leaves objects in a stale pose
    o.location, o.rotation_quaternion = REST[i]; o.scale = (1, 1, 1)
marker.scale = HIDDEN
bpy.ops.wm.save_as_mainfile(filepath=str(out / "scene.blend"))

# --- check renders: the state after each step, in the orientation the guide draws, new parts tinted ---------
marker.hide_render = True
for o in list(objs.values()) + [root, marker]:
    if o.animation_data:
        for tr in o.animation_data.nla_tracks:
            tr.mute = True
scene.render.engine = "BLENDER_WORKBENCH"
sh = scene.display.shading
sh.light = "STUDIO"; sh.color_type = "OBJECT"; sh.show_object_outline = True; sh.object_outline_color = (0.1, 0.1, 0.1)
sh.show_cavity = True; sh.cavity_type = "BOTH"; sh.show_shadows = False
scene.display.render_aa = "8"
scene.render.resolution_x = scene.render.resolution_y = 900
scene.render.film_transparent = False
sh.background_type = "VIEWPORT"; sh.background_color = (1, 1, 1)
scene.view_settings.view_transform = "Standard"


def base_colour(inst):
    return tuple(meshes[inst.split("#")[0]].materials[0].diffuse_color)


for sa, s in zip(steps_actions, steps_out):
    root.rotation_euler, root.location = root_pose(sa["rot"], sa["shown"])
    for inst, o in objs.items():
        o.hide_render = nodes[inst] not in s["visible"]
        o.color = ACCENT if nodes[inst] in s["moving"] else base_colour(inst)
    scene.camera = cams[s["n"]]
    scene.render.filepath = str(out / "renders" / f"step_{s['n']:02d}.png")
    bpy.ops.render.render(write_still=True)

# the finished product from the cover's angle, for comparison with the guide's cover drawing
root.rotation_euler, root.location = root_pose([0, 0, 0], list(objs))
az, el = plan.get("cover_view_az_el_deg", (-125, 25))
cover_cam, _ = make_camera("cam_cover", list(objs), [0, 0, 0], az, el)
for inst, o in objs.items():
    o.hide_render = False
    o.color = base_colour(inst)
scene.camera = cover_cam
scene.render.filepath = str(out / "renders" / "cover.png")
bpy.ops.render.render(write_still=True)

n_actions = sum(len(sa["acts"]) for sa in steps_actions) + (1 if final_turn else 0)
tris = sum(len(o.data.polygons) for o in objs.values())
print("RESULT " + json.dumps({"instances": len(objs), "steps": len(steps_out), "actions": n_actions, "triangles": tris,
                              "glb_bytes": (out / "walkthrough.glb").stat().st_size,
                              "clips": clip_order, "walkthrough_s": round(TOTAL_FRAMES / FPS, 1)}))
