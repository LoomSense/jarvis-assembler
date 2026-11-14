"""Render the walkthrough as frames for a preview video. Run by pipeline/preview.py.

    Blender -b <job>/scene.blend -P pipeline/blender/preview_frames.py -- <job_dir> <frames_dir> [size] [frame_step]

The piece being placed is tinted, each screw type keeps its own colour, and the
camera moves in on the hole while a screw is driven. Writes frames.json: for
each frame, the step and action it belongs to.
"""
import json
import sys

import bpy
from mathutils import Vector

args = sys.argv[sys.argv.index("--") + 1:]
job, out = args[0], args[1]
size = int(args[2]) if len(args) > 2 else 720
fstep = int(args[3]) if len(args) > 3 else 2
ACCENT = (0.62, 0.81, 1.0, 1)
ZOOM, EASE = 0.42, 0.22                                  # ortho scale while fastening; smoothing per rendered frame

s = bpy.context.scene
m = json.load(open(f"{job}/manifest.json"))
fps, steps = m["fps"], m["steps"]
root = bpy.data.objects[m["rootNode"]]
for o in bpy.data.objects:
    if o.animation_data:
        for tr in o.animation_data.nla_tracks:
            tr.mute = tr.name != "walkthrough"
    o.hide_render = False
s.render.engine = "BLENDER_WORKBENCH"
sh = s.display.shading
sh.light = "STUDIO"; sh.color_type = "OBJECT"; sh.show_object_outline = True; sh.show_cavity = True
sh.background_type = "VIEWPORT"; sh.background_color = (1, 1, 1)
s.view_settings.view_transform = "Standard"
s.render.resolution_x = s.render.resolution_y = size
s.render.image_settings.file_format = "JPEG"; s.render.image_settings.quality = 88
meshes = [o for o in bpy.data.objects if o.type == "MESH"]
base = {o.name: (tuple(o.data.materials[0].diffuse_color) if o.data.materials else (0.8, 0.8, 0.8, 1)) for o in meshes}
cam0 = {st["n"]: (bpy.data.objects[st["camera"]["node"]].location.copy(), bpy.data.objects[st["camera"]["node"]].data.ortho_scale) for st in steps}

index, i, cur, z, shift = [], 0, None, 0.0, Vector((0, 0, 0))
for f in range(1, s.frame_end + 1, fstep):
    t = (f - 1) / fps
    si = next((k for k, x in enumerate(steps) if x["walkthrough"]["start_s"] <= t < x["walkthrough"]["end_s"]), len(steps) - 1)
    st = steps[si]
    tl = t - st["walkthrough"]["start_s"]
    ai = next((k for k, a in enumerate(st["actions"]) if a["t0_s"] <= tl <= a["t1_s"] + 0.25), None)
    act = st["actions"][ai] if ai is not None else None
    s.frame_set(f)
    cam = bpy.data.objects[st["camera"]["node"]]
    if cur != si:
        cur, z, shift = si, 0.0, Vector((0, 0, 0))
    want_z, want_shift = 0.0, Vector((0, 0, 0))
    if act and act["type"] == "fasten":
        want_z = 1.0
        want_shift = (root.matrix_world @ Vector(act["target_m"])) - Vector(st["camera"]["target"])
    z += (want_z - z) * EASE
    shift += (want_shift - shift) * EASE
    loc0, scale0 = cam0[st["n"]]
    cam.location = loc0 + shift
    cam.data.ortho_scale = scale0 * (1 - (1 - ZOOM) * z)
    s.camera = cam
    for o in meshes:
        o.color = ACCENT if act and act["type"] == "place" and o.name == act.get("node") else base[o.name]
    i += 1
    s.render.filepath = f"{out}/f{i:05d}.jpg"
    bpy.ops.render.render(write_still=True)
    index.append({"step": si, "action": ai, "after_last": t >= steps[-1]["walkthrough"]["end_s"]})
json.dump({"fps": fps / fstep, "size": size, "frames": index}, open(f"{out}/frames.json", "w"))
