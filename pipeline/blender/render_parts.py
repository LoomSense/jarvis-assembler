"""Render every part and hardware item on its own, in one Blender call (CAD-03).

    Blender -b --factory-startup -P pipeline/blender/render_parts.py -- <plan.json> <parts_dir> <out_dir> [size_px]

Writes for each id:
    <out>/renders/part-<id>.png          line art on white, to compare with the guide's drawing of the part
    <out>/renders/part-<id>-shaded.png   same view in the scene's colours, for the phase-2 reference sheet
The view is orthographic, like the guide. Angle: the part's `view_az_el_deg` in the plan, else PART_VIEW.
Each part is turned so its longest edge runs across the picture and its thinnest edge faces up, the way a
parts list draws a board.
"""
import json
import math
import sys
from pathlib import Path

import bmesh
import bpy
import numpy as np
from mathutils import Matrix, Vector

args = sys.argv[sys.argv.index("--") + 1:]
plan_path, parts_dir, out = map(Path, args[:3])
SIZE = int(args[3]) if len(args) > 3 else 512
plan = json.loads(plan_path.read_text())
(out / "renders").mkdir(parents=True, exist_ok=True)
PART_VIEW = (-55, 28)        # azimuth, elevation in degrees
MARGIN = 1.12
EDGE = 0.06                   # colour jump that counts as an edge in the normals picture
PINE = (0.80, 0.62, 0.40, 1)
# same order as assemble.py, so a screw has the same colour here as in the walkthrough
HW_COLOURS = [(0.72, 0.74, 0.77, 1), (0.86, 0.64, 0.18, 1), (0.16, 0.16, 0.18, 1), (0.76, 0.40, 0.24, 1), (0.45, 0.60, 0.45, 1)]

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.render.engine = "BLENDER_WORKBENCH"
scene.render.resolution_x = scene.render.resolution_y = SIZE
scene.render.film_transparent = False
scene.display.render_aa = "8"
scene.view_settings.view_transform = "Standard"
sh = scene.display.shading
sh.background_type = "VIEWPORT"; sh.background_color = (1, 1, 1)
sh.show_object_outline = True; sh.object_outline_color = (0, 0, 0)
sh.show_cavity = True; sh.cavity_type = "BOTH"; sh.show_shadows = False
sh.color_type = "OBJECT"; sh.light = "STUDIO"

cam_data = bpy.data.cameras.new("cam"); cam_data.type = "ORTHO"
cam = bpy.data.objects.new("cam", cam_data); scene.collection.objects.link(cam); scene.camera = cam


def load(pid):
    bpy.ops.wm.stl_import(filepath=str(parts_dir / f"{pid}.stl"))
    o = bpy.context.selected_objects[0]
    me = o.data
    bm = bmesh.new(); bm.from_mesh(me)
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-4)        # STL triangles are unwelded
    bm.to_mesh(me); bm.free()
    # longest bounding-box edge -> X, thinnest -> Z, then centre on the origin
    lo = Vector([min(v.co[k] for v in me.vertices) for k in range(3)])
    hi = Vector([max(v.co[k] for v in me.vertices) for k in range(3)])
    order = sorted(range(3), key=lambda k: hi[k] - lo[k], reverse=True)
    rot = Matrix([[1 if order[r] == c else 0 for c in range(3)] for r in range(3)])
    if rot.determinant() < 0:                                       # keep it a rotation, never a mirror image
        rot[1] = [-x for x in rot[1]]
    me.transform(Matrix.Translation(-(lo + hi) / 2)); me.transform(rot.to_4x4())
    return o


def frame(o, az, el):
    a, e = math.radians(az), math.radians(el)
    d = Vector((math.cos(a) * math.cos(e), math.sin(a) * math.cos(e), math.sin(e)))
    r = max(v.co.length for v in o.data.vertices)
    cam.location = d * (r * 4 + 1)
    cam.rotation_euler = (-d).to_track_quat("-Z", "Y").to_euler()
    cam_data.clip_start, cam_data.clip_end = 0.001, r * 8 + 2
    bpy.context.view_layer.update()
    inv = cam.matrix_world.inverted()
    pts = [inv @ v.co for v in o.data.vertices]
    w = max(abs(p.x) for p in pts) * 2; h = max(abs(p.y) for p in pts) * 2
    cam_data.ortho_scale = max(w, h) * MARGIN


def shot(path, colour, size=SIZE):
    for o in scene.objects:
        if o.type == "MESH":
            o.color = colour
    scene.render.resolution_x = scene.render.resolution_y = size
    scene.render.filepath = str(path)
    bpy.ops.render.render(write_still=True)


def line_art(path):
    """Black lines on white. Workbench has no line renderer, so render the surface normals as colours at
    twice the size, mark every pixel where the colour jumps (an edge or the silhouette), and shrink."""
    sh.light, sh.studio_light, sh.color_type = "MATCAP", "check_normal+y.exr", "SINGLE"
    sh.single_color = (1, 1, 1)
    sh.show_object_outline = False
    tmp = path.with_suffix(".normals.png")
    shot(tmp, (1, 1, 1, 1), SIZE * 2)
    img = bpy.data.images.load(str(tmp)); n = SIZE * 2
    px = np.empty(n * n * 4, dtype=np.float32); img.pixels.foreach_get(px)
    px = px.reshape(n, n, 4)[:, :, :3]
    edge = np.zeros((n, n), dtype=bool)
    dy = np.abs(np.diff(px, axis=0)).max(axis=2) > EDGE; dx = np.abs(np.diff(px, axis=1)).max(axis=2) > EDGE
    edge[:-1] |= dy; edge[1:] |= dy; edge[:, :-1] |= dx; edge[:, 1:] |= dx
    grey = 1 - edge.reshape(SIZE, 2, SIZE, 2).mean(axis=(1, 3)).clip(0, 0.5) * 2      # 2x2 average, then darken
    rgba = np.dstack([grey, grey, grey, np.ones_like(grey)]).astype(np.float32)
    out_img = bpy.data.images.new("line", SIZE, SIZE); out_img.pixels.foreach_set(rgba.ravel())
    out_img.filepath_raw, out_img.file_format = str(path), "PNG"; out_img.save()
    bpy.data.images.remove(img); bpy.data.images.remove(out_img); tmp.unlink()
    sh.light, sh.color_type, sh.show_object_outline = "STUDIO", "OBJECT", True


done = {}
items = [(p["id"], p, "part") for p in plan["parts"]] + [(h["code"], h, "hardware") for h in plan["hardware"]]
for pid, item, kind in items:
    o = load(pid)
    if kind == "hardware":
        for poly in o.data.polygons:
            poly.use_smooth = True
    az, el = item.get("view_az_el_deg", PART_VIEW)
    frame(o, az, el)
    line, shaded = out / "renders" / f"part-{pid}.png", out / "renders" / f"part-{pid}-shaded.png"
    line_art(line)
    codes = [h["code"] for h in plan["hardware"]]
    shot(shaded, HW_COLOURS[codes.index(pid) % len(HW_COLOURS)] if kind == "hardware" else PINE)
    done[pid] = {"kind": kind, "line": f"renders/{line.name}", "shaded": f"renders/{shaded.name}", "view_az_el_deg": [az, el],
                 "px_per_mm": round(SIZE / cam_data.ortho_scale, 4)}
    bpy.data.objects.remove(o, do_unlink=True)

(out / "renders" / "parts.json").write_text(json.dumps(done, indent=1))
print("RESULT " + json.dumps({"parts": len(done), "size": SIZE, "files": 2 * len(done)}))
