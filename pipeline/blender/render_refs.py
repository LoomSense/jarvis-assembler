"""Phase-2 reference renders: the correct result of every step, seen like a phone on a stand (CAD-07).

    Blender -b --factory-startup -P pipeline/blender/render_refs.py -- <job_dir> [size_px]

Reads <job>/scene.blend, manifest.json and placements.json (written by pipeline.run), writes <job>/refs/step-NN-ref.png.
Each picture shows the pieces present after the step, in the pose the step leaves them in, in their real
colours, from the front and REF_VIEW[1] degrees above the table, with a perspective lens like a phone's.
"""
import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Euler, Matrix, Vector

args = sys.argv[sys.argv.index("--") + 1:]
job = Path(args[0])
SIZE = int(args[1]) if len(args) > 1 else 768
REF_VIEW = (-90, 35)         # azimuth, elevation in degrees: front of the table, a phone on a stand
FOV_DEG = 60

bpy.ops.wm.open_mainfile(filepath=str(job / "scene.blend"))
manifest = json.loads((job / "manifest.json").read_text())
(job / "refs").mkdir(exist_ok=True)
scene = bpy.context.scene
root = bpy.data.objects[manifest["rootNode"]]
marker = bpy.data.objects.get(manifest.get("markerNode", "marker"))
pieces = [o for o in root.children if o.type == "MESH" and o is not marker]
if marker:
    marker.hide_render = True
for o in pieces + [root] + ([marker] if marker else []):
    if o.animation_data:                                       # stop the walkthrough clips from moving anything
        o.animation_data.action = None
        for tr in o.animation_data.nla_tracks:
            tr.mute = True
root.rotation_mode = "XYZ"
# opening the file plays frame 1 of the walkthrough, where most pieces are still hidden: put each one back
placed = json.loads((job / "placements.json").read_text())["instances"]
for o in pieces:
    M = Matrix(placed[o["instance"]]["matrix_mm"])
    M.translation = M.translation * 0.001
    o.matrix_local = M
bpy.context.view_layer.update()

scene.render.engine = "BLENDER_WORKBENCH"
scene.render.resolution_x = scene.render.resolution_y = SIZE
scene.render.film_transparent = False
scene.display.render_aa = "8"
scene.view_settings.view_transform = "Standard"
sh = scene.display.shading
sh.light = "STUDIO"; sh.color_type = "OBJECT"; sh.show_object_outline = True; sh.object_outline_color = (0.1, 0.1, 0.1)
sh.show_cavity = True; sh.cavity_type = "BOTH"; sh.show_shadows = False
sh.background_type = "VIEWPORT"; sh.background_color = (1, 1, 1)

cam_data = bpy.data.cameras.new("cam_ref"); cam_data.type = "PERSP"
cam_data.sensor_fit = "HORIZONTAL"; cam_data.angle = math.radians(FOV_DEG)
cam = bpy.data.objects.new("cam_ref", cam_data); scene.collection.objects.link(cam); scene.camera = cam


def local_points(objs):
    return [o.matrix_local @ Vector(c) for o in objs for c in o.bound_box]


ALL = local_points(pieces)
CENTRE = sum(ALL, Vector()) / len(ALL)


def root_pose(rot_deg, objs):
    """Same rule as assemble.py: turn the assembly, then rest the given pieces on the floor (z = 0)."""
    e = Euler([math.radians(a) for a in rot_deg], "XYZ")
    R = e.to_matrix()
    c = R @ CENTRE
    return e, Vector((CENTRE.x - c.x, CENTRE.y - c.y, -min((R @ p).z for p in local_points(objs))))


done = []
for s in manifest["steps"]:
    shown = [o for o in pieces if o.name in s["visible"]]
    root.rotation_euler, root.location = root_pose(s["assemblyRotationDeg"], shown)
    for o in pieces:
        o.hide_render = o not in shown
        o.color = o.data.materials[0].diffuse_color if o.data.materials else (0.8, 0.8, 0.8, 1)
    bpy.context.view_layer.update()
    pts = [o.matrix_world @ Vector(c) for o in shown for c in o.bound_box]
    lo = Vector([min(p[k] for p in pts) for k in range(3)]); hi = Vector([max(p[k] for p in pts) for k in range(3)])
    centre, r = (lo + hi) / 2, (hi - lo).length / 2
    az, el = s.get("refViewAzElDeg", REF_VIEW)
    a, e = math.radians(az), math.radians(el)
    d = Vector((math.cos(a) * math.cos(e), math.sin(a) * math.cos(e), math.sin(e)))
    dist = r / math.sin(math.radians(FOV_DEG) / 2) * 1.02
    cam.location = centre + d * dist
    cam.rotation_euler = (-d).to_track_quat("-Z", "Y").to_euler()
    cam_data.clip_start, cam_data.clip_end = 0.01, dist + r * 2 + 1
    path = job / "refs" / f"step-{s['n']:02d}-ref.png"
    scene.render.filepath = str(path)
    bpy.ops.render.render(write_still=True)
    done.append({"n": s["n"], "ref": f"refs/{path.name}", "view_az_el_deg": [az, el], "pieces": len(shown)})

print("RESULT " + json.dumps({"refs": done, "size": SIZE}))
