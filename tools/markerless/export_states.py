"""Blender (headless): bake the MALM drawer at the end of chosen assembly steps.

For each requested step N it samples the `walkthrough` clip at the end of step N
(manifest walkthrough.steps[].end_s), keeps every part/hardware mesh that is
visible (scale > 1e-2, not the `marker` ring), bakes world transforms, and
re-centres the result into an *object frame*: Blender Z-up, the lowest point
on z = 0 (it rests on the table), x/y centred on the footprint bbox centre.

Outputs (in OUT_DIR):
  state_NN.ply        merged triangle mesh in object frame (metres)
  state_NN.json       per-object list, footprint, height, triangle count
Usage:
  Blender -b --python export_states.py -- GLB MANIFEST OUT_DIR 1 2 4 5
"""
import json
import os
import sys

import bpy
import bmesh
from mathutils import Matrix, Vector

argv = sys.argv[sys.argv.index("--") + 1:]
glb, manifest_path, out_dir = argv[:3]
steps = [int(s) for s in argv[3:]] or [1, 2, 4, 5]
os.makedirs(out_dir, exist_ok=True)
manifest = json.load(open(manifest_path))
ends = {s["n"]: s["end_s"] for s in manifest["walkthrough"]["steps"] if isinstance(s["n"], int)}

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=glb)
scene = bpy.context.scene
fps = scene.render.fps

for n in steps:
    frame = int(round(ends[n] * fps)) - 1
    scene.frame_set(frame)
    dg = bpy.context.evaluated_depsgraph_get()
    verts, faces, objs = [], [], []
    for o in bpy.data.objects:
        if o.type != "MESH" or o.name == "marker":
            continue
        s = o.matrix_world.to_scale()
        if min(abs(s.x), abs(s.y), abs(s.z)) < 1e-2:
            continue
        oe = o.evaluated_get(dg)
        me = oe.to_mesh()
        bm = bmesh.new()
        bm.from_mesh(me)
        bmesh.ops.triangulate(bm, faces=bm.faces[:])
        M = o.matrix_world
        base = len(verts)
        for v in bm.verts:
            verts.append(M @ v.co)
        for f in bm.faces:
            faces.append([base + v.index for v in f.verts])
        objs.append({"name": o.name, "tris": len(bm.faces),
                     "material": [m.name for m in o.data.materials if m]})
        bm.free()
        oe.to_mesh_clear()
    xs = [v.x for v in verts]; ys = [v.y for v in verts]; zs = [v.z for v in verts]
    c = Vector(((min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, min(zs)))
    verts = [v - c for v in verts]
    path = os.path.join(out_dir, f"state_{n:02d}.ply")
    with open(path, "w") as fh:
        fh.write("ply\nformat ascii 1.0\n")
        fh.write(f"element vertex {len(verts)}\nproperty float x\nproperty float y\nproperty float z\n")
        fh.write(f"element face {len(faces)}\nproperty list uchar int vertex_indices\nend_header\n")
        for v in verts:
            fh.write(f"{v.x:.6f} {v.y:.6f} {v.z:.6f}\n")
        for f in faces:
            fh.write("3 " + " ".join(map(str, f)) + "\n")
    info = {"step": n, "frame": frame, "objects": objs, "tris": len(faces),
            "size_m": [max(xs) - min(xs), max(ys) - min(ys), max(zs) - min(zs)],
            "offset_world": list(c)}
    json.dump(info, open(os.path.join(out_dir, f"state_{n:02d}.json"), "w"), indent=1)
    print("STATE", n, "frame", frame, "tris", len(faces), "size", [round(x, 3) for x in info["size_m"]],
          [o["name"] for o in objs])
