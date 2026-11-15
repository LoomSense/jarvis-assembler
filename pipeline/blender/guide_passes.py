"""Render the pictures a printed, wordless guide is drawn from (pipeline/paper_guide.py composes the pages).

    Blender -b <job>/scene.blend -P pipeline/blender/guide_passes.py -- <job_dir> <out_dir> [size_px]

For the cover and for every step it writes two renders of the same view, from which black line art is made:
    <out>/<name>.normals.png   surface normals as colours: a jump is a fold or an edge
    <out>/<name>.ids.png       one flat colour per piece: a jump is the outline of a piece
A step is drawn exploded: the pieces it adds stand off where they come from, as at the start of the step's
clip. <out>/passes.json lists, per step, each such piece with where it is and where it ends up in the picture
(0-1, origin top left), so the composer can draw the arrows.
"""
import json
import sys
from pathlib import Path

import bpy
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Vector

args = sys.argv[sys.argv.index("--") + 1:]
job, out = Path(args[0]), Path(args[1])
SIZE = int(args[2]) if len(args) > 2 else 1800
out.mkdir(parents=True, exist_ok=True)
m = json.loads((job / "manifest.json").read_text())
scene = bpy.context.scene
fps = m.get("fps", 24)
root = bpy.data.objects.get(m.get("rootNode", "assembly"))
marker = bpy.data.objects.get(m.get("markerNode", "marker"))
pieces = [o for o in bpy.data.objects if o.type == "MESH" and o is not marker]

scene.render.engine = "BLENDER_WORKBENCH"
scene.render.resolution_x = scene.render.resolution_y = SIZE
scene.render.film_transparent = False
scene.display.render_aa = "8"
scene.view_settings.view_transform = "Standard"
sh = scene.display.shading
sh.background_type = "VIEWPORT"; sh.background_color = (1, 1, 1)
sh.show_object_outline = False; sh.show_cavity = False; sh.show_shadows = False


def play(clip):
    for o in bpy.data.objects:
        if o.animation_data:
            for tr in o.animation_data.nla_tracks:
                tr.mute = tr.name != clip


# where every piece ends up: only the end of `walkthrough` has it (see export_usdz.py)
play("walkthrough")
scene.frame_set(max(2, int(round(m.get("walkthrough", {}).get("duration_s", 0) * fps))))
bpy.context.view_layer.update()
final = {o.name: (o.location.copy(), o.rotation_quaternion.copy(), o.rotation_euler.copy(), o.scale.copy())
         for o in bpy.data.objects if o is not root}


def rest_assembled():
    for o in bpy.data.objects:
        if o.name in final:
            o.location, o.rotation_quaternion, o.rotation_euler, o.scale = final[o.name]


def show(names):
    for o in bpy.data.objects:
        o.hide_render = not (o.name in names) or o is marker


def corners(objs):
    return [o.matrix_world @ Vector(c) for o in objs for c in o.bound_box]


def fit(cam, objs, margin=1.18):
    """Slide the orthographic camera in its own plane and set its scale so that these objects fill the picture."""
    bpy.context.view_layer.update()
    inv = cam.matrix_world.inverted()
    pts = [inv @ p for p in corners(objs)]
    lo = Vector((min(p.x for p in pts), min(p.y for p in pts))); hi = Vector((max(p.x for p in pts), max(p.y for p in pts)))
    shift = cam.matrix_world.to_3x3() @ Vector(((lo.x + hi.x) / 2, (lo.y + hi.y) / 2, 0))
    cam.location += shift
    cam.data.ortho_scale = max(hi.x - lo.x, hi.y - lo.y) * margin
    bpy.context.view_layer.update()


def px(cam, p):
    v = world_to_camera_view(scene, cam, p)
    return [round(v.x, 4), round(1 - v.y, 4)]


def centre(o):
    cs = [o.matrix_world @ Vector(c) for c in o.bound_box]
    return sum(cs, Vector()) / 8


def passes(name):
    sh.light, sh.studio_light, sh.color_type, sh.single_color = "MATCAP", "check_normal+y.exr", "SINGLE", (1, 1, 1)
    scene.render.filepath = str(out / f"{name}.normals.png"); bpy.ops.render.render(write_still=True)
    sh.light, sh.color_type = "FLAT", "RANDOM"
    scene.render.filepath = str(out / f"{name}.ids.png"); bpy.ops.render.render(write_still=True)


result = {"size": SIZE, "steps": []}
for st in m["steps"]:
    n, clip = st["n"], st.get("clip")
    visible = set(st.get("visible", []))
    moving = [a for a in st.get("actions", []) if a.get("node") and a["type"] in ("place", "fasten", "press")]
    play(clip)
    rest_assembled()
    show(visible)
    shown = [o for o in pieces if o.name in visible]
    cam = bpy.data.objects[st["camera"]["node"]]
    scene.camera = cam
    cam_home = (cam.location.copy(), cam.data.ortho_scale)
    # the end of the step: where everything sits
    scene.frame_set(max(2, int(round(st.get("duration_s", 2) * fps))))
    bpy.context.view_layer.update()
    ends = {a["node"]: centre(bpy.data.objects[a["node"]]) for a in moving if a["node"] in bpy.data.objects}
    # the start of its first move: the assembly already turned as the step draws it, the new pieces standing off
    first = min((a["t0_s"] for a in moving), default=0.0)
    scene.frame_set(max(1, int(round(first * fps)) + 1) if clip else scene.frame_end)
    bpy.context.view_layer.update()
    starts = {k: centre(bpy.data.objects[k]) for k in ends}
    if shown:
        fit(cam, shown)
    passes(f"step_{n:02d}")
    items = []
    for a in moving:
        if a["node"] not in ends:
            continue
        items.append({"node": a["node"], "type": a["type"], "id": a.get("code") or a.get("partId"), "hardware": bool(a.get("code")),
                      "from": px(cam, starts[a["node"]]), "to": px(cam, ends[a["node"]])})
    result["steps"].append({"n": n, "title": st.get("title"), "moving": items,
                            "turn": any(a["type"] == "turn" for a in st.get("actions", [])),
                            "hardware": st.get("hardware", [])})
    cam.location, cam.data.ortho_scale = cam_home

# the cover: the finished product, standing as it is used
play("walkthrough")
scene.frame_set(max(2, int(round(m.get("walkthrough", {}).get("duration_s", 0) * fps))))
show({o.name for o in pieces})
cam = bpy.data.objects[m["steps"][-1]["camera"]["node"]]
scene.camera = cam
fit(cam, pieces)
passes("cover")
(out / "passes.json").write_text(json.dumps(result, indent=1))
print("RESULT " + json.dumps({"steps": len(result["steps"]), "size": SIZE}))
