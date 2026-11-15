"""Write one USDZ per step for AR on an iPhone or iPad (AR Quick Look, which uses ARKit and the LiDAR scanner).

    Blender -b <job>/scene.blend -P pipeline/blender/export_usdz.py -- <job_dir>

<job>/ar/step_NN.usdz   the assembly as it stands in step NN, life-size, in the pose the guide draws, with that
                        step's pieces moving into place in a loop and drawn in the accent colour
<job>/ar/finished.usdz  the finished product, upright

Opened from Safari with <a rel="ar">, the phone places the model on the real table and tracks it as the person
walks around it. Adds `ar` to every step of manifest.json and `arFinished` to the manifest.
"""
import json
import math
import sys
from pathlib import Path

import bpy

job = Path(sys.argv[sys.argv.index("--") + 1])
manifest_path = job / "manifest.json"
m = json.loads(manifest_path.read_text())
out = job / "ar"
out.mkdir(exist_ok=True)
scene = bpy.context.scene
fps = m.get("fps", 24)
root = bpy.data.objects.get(m.get("rootNode", "assembly"))
pieces = [o for o in bpy.data.objects if o.type == "MESH" and o.name != m.get("markerNode", "marker")]
ACCENT = (0.36, 0.66, 1.0, 1)


def accent_material():
    mat = bpy.data.materials.get("ar_accent") or bpy.data.materials.new("ar_accent")
    mat.use_nodes = True
    b = mat.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = ACCENT
    b.inputs["Roughness"].default_value = 0.45
    mat.diffuse_color = ACCENT
    return mat


def play(clip):
    """Only this clip's NLA tracks drive the scene (none: everything rests at its assembled pose)."""
    for o in bpy.data.objects:
        if o.animation_data:
            for tr in o.animation_data.nla_tracks:
                tr.mute = tr.name != clip


def show(names):
    """Select what goes into the file: these pieces and the root that turns them. Everything else stays out."""
    for o in bpy.data.objects:
        on = o.name in names or o is root
        o.hide_viewport = False
        o.hide_render = not on
        try:
            o.hide_set(False)
            o.select_set(on)
        except RuntimeError:
            pass


def export(path, animated):
    bpy.ops.wm.usd_export(filepath=str(path), selected_objects_only=True, export_animation=animated,
                          export_materials=True, generate_preview_surface=True, export_cameras=False,
                          export_lights=False, export_normals=True, use_instancing=False,
                          convert_orientation=True, export_global_up_selection="Y",
                          export_global_forward_selection="NEGATIVE_Z", root_prim_path="/root")


# A piece rests hidden (scale 1e-4) and away from its place; where it ends up exists only at the end of the
# `walkthrough` clip. Read every piece's final pose there and make it the resting pose, so that in a step's clip
# the pieces of earlier steps stand assembled while this step's pieces move.
play("walkthrough")
scene.frame_set(max(2, int(round(m.get("walkthrough", {}).get("duration_s", 0) * fps))))
bpy.context.view_layer.update()
final = {o.name: (o.location.copy(), o.rotation_quaternion.copy(), o.rotation_euler.copy(), o.scale.copy())
         for o in bpy.data.objects if o is not root}


def rest_assembled():
    """Put every piece at its final pose. A clip that has played leaves its pieces where its last frame put them,
    so this is called again before each export."""
    for o in bpy.data.objects:
        if o.name in final:
            o.location, o.rotation_quaternion, o.rotation_euler, o.scale = final[o.name]


accent = accent_material()
made = []
for st in m["steps"]:
    n = st["n"]
    clip = st.get("clip")
    visible = set(st.get("visible", []))
    moving = set(st.get("moving", []))
    play(clip)
    rest_assembled()
    show(visible)
    swapped = []
    for o in pieces:                                     # this step's pieces in the accent colour (meshes are shared)
        if o.name in moving and o.material_slots:
            slot = o.material_slots[0]
            swapped.append((slot, slot.link, slot.material))
            slot.link = "OBJECT"
            slot.material = accent
    scene.frame_start = 1
    scene.frame_end = max(2, int(round(st.get("duration_s", 2) * fps)))
    scene.frame_set(scene.frame_end if not clip else 1)
    path = out / f"step_{n:02d}.usdz"
    export(path, bool(clip))
    for slot, link, mat in swapped:
        slot.material = mat
        slot.link = link
    st["ar"] = f"ar/{path.name}"
    made.append(path.name)

play(None)
rest_assembled()
show({o.name for o in pieces})
if root:
    root.rotation_euler = (0, 0, 0)
    bpy.context.view_layer.update()
    lowest = min((o.matrix_world @ v.co).z for o in pieces for v in o.data.vertices) if pieces else 0
    root.location.z -= lowest                            # the finished product stands on the table
scene.frame_set(1)
export(out / "finished.usdz", False)
m["arFinished"] = "ar/finished.usdz"
manifest_path.write_text(json.dumps(m, indent=1, ensure_ascii=False))
print("RESULT " + json.dumps({"usdz": made + ["finished.usdz"],
                              "bytes": sum(p.stat().st_size for p in out.glob("*.usdz"))}))
