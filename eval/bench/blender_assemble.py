"""Blender -b --factory-startup --python blender_assemble.py -- STLDIR OUT.glb OUT.blend
Imports part STLs (mm -> m), builds the KNAGGLIG assembled pose, makes one action per step on NLA tracks
step_01..step_08, exports a GLB with one animation per NLA track."""
import bpy, json, math, sys, time
from mathutils import Vector
args = sys.argv[sys.argv.index("--") + 1:]
STL, GLB, BLEND = args
T = {}; t0 = time.perf_counter()
bpy.ops.wm.read_factory_settings(use_empty=True)
src = {}
for n in ["end_frame", "side_slat", "bottom_slat", "screw_long_108363", "screw_short_108560"]:
    bpy.ops.wm.stl_import(filepath=f"{STL}/{n}.stl", global_scale=0.001)
    o = bpy.context.selected_objects[0]; o.name = n; src[n] = o
T["import_s"] = time.perf_counter() - t0
t1 = time.perf_counter()
mm = 0.001
def inst(part, name, loc, rot=(0, 0, 0)):
    o = src[part] if src[part].get("used") is None else src[part].copy()  # linked mesh data for instances
    if o is not src[part]: bpy.context.collection.objects.link(o)
    src[part]["used"] = 1
    o.name = name; o.location = Vector(loc) * mm; o.rotation_euler = [math.radians(a) for a in rot]
    return o
steps = {i: [] for i in range(1, 9)}
# box: X length 460, Y depth 310, Z height 250 (bottom slats under z=0)
for k, x in enumerate((-220, 220)):
    steps[1 + k].append((inst("end_frame", f"end_frame_{k+1}", (x, 0, 0), (0, 0, 90)), Vector((0, 0, 150))))
zs = (30, 125, 220)
for side, (y, st_slat, st_screw) in enumerate(((-160, 3, 4), (160, 5, 6))):
    for j, z in enumerate(zs):
        steps[st_slat].append((inst("side_slat", f"side_slat_{side}{j}", (0, y, z), (90, 0, 0)), Vector((0, math.copysign(150, y), 0))))
        for sx in (-1, 1):
            for dz in (-15, 15):
                steps[st_screw].append((inst("screw_short_108560", f"screw_s_{side}{j}{sx}{dz}", (sx * 220, y + math.copysign(5, y), z + dz),
                                             (0, -90 if y < 0 else 90, 0) if False else (90 if y < 0 else -90, 0, 0)), Vector((0, math.copysign(60, y), 0))))
for j, y in enumerate((-110, 0, 110)):
    steps[7].append((inst("bottom_slat", f"bottom_slat_{j}", (0, y, -5)), Vector((0, 0, -150))))
for sx in (-1, 1):
    for y in (-110, 110):
        steps[8].append((inst("screw_long_108363", f"screw_l_{sx}{y}", (sx * 220, y, -10), (180, 0, 0)), Vector((0, 0, -80))))
F = 30
for s, items in steps.items():
    for o, off in items:
        final = o.location.copy()
        o.location = final + off * mm; o.keyframe_insert("location", frame=1)
        o.location = final; o.keyframe_insert("location", frame=F)
        ad = o.animation_data; act = ad.action; act.name = f"step_{s:02d}_{o.name}"
        tr = ad.nla_tracks.new(); tr.name = f"step_{s:02d}"
        strip = tr.strips.new(act.name, 1, act)
        ad.action = None
        o.location = final
T["anim_setup_s"] = time.perf_counter() - t1
T["objects"] = len(bpy.data.objects)
T["tris"] = sum(len(o.data.polygons) for o in bpy.data.objects if o.type == "MESH")
t2 = time.perf_counter()
bpy.ops.export_scene.gltf(filepath=GLB, export_format="GLB", export_animation_mode="NLA_TRACKS",
                          export_merge_animation="NLA_TRACK", export_apply=False)
T["glb_export_s"] = time.perf_counter() - t2
bpy.ops.wm.save_as_mainfile(filepath=BLEND)
T["total_in_blender_s"] = time.perf_counter() - t0
print("ASSEMBLE " + json.dumps(T))
