"""Blender -b FILE.blend --python blender_render.py -- ENGINE SAMPLES FREESTYLE(0/1) OUT.png
Renders one 512x512 still of the assembled box (frame 30 = end of every step strip)."""
import bpy, json, sys, time
from mathutils import Vector
eng, samples, fs, out = sys.argv[sys.argv.index("--") + 1:]
samples, fs = int(samples), fs == "1"
t0 = time.perf_counter()
sc = bpy.context.scene
sc.frame_set(30)
cam_d = bpy.data.cameras.new("cam"); cam = bpy.data.objects.new("cam", cam_d); sc.collection.objects.link(cam)
cam.location = (0.75, -0.85, 0.65)
cam.rotation_euler = (Vector((0, 0, 0.1)) - cam.location).to_track_quat("-Z", "Y").to_euler(); sc.camera = cam
sun_d = bpy.data.lights.new("sun", "SUN"); sun_d.energy = 4.0; sun = bpy.data.objects.new("sun", sun_d)
sun.rotation_euler = (0.7, 0.2, 0.6); sc.collection.objects.link(sun)
world = bpy.data.worlds.new("w"); sc.world = world; world.use_nodes = True
bg = world.node_tree.nodes.get("Background")
if bg: bg.inputs[0].default_value = (0.8, 0.8, 0.8, 1); bg.inputs[1].default_value = 0.35
mat = bpy.data.materials.new("pine"); mat.diffuse_color = (0.85, 0.65, 0.4, 1)
try:
    mat.use_nodes = True; mat.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.85, 0.65, 0.4, 1)
except Exception as e: print("mat", e)
for o in sc.objects:
    if o.type == "MESH": o.data.materials.clear(); o.data.materials.append(mat)
r = sc.render; r.engine = eng; r.resolution_x = r.resolution_y = 512; r.resolution_percentage = 100
r.filepath = out; r.image_settings.file_format = "PNG"
if eng == "CYCLES":
    sc.cycles.device = "CPU"; sc.cycles.samples = samples; sc.cycles.use_denoising = False
elif eng == "BLENDER_EEVEE":
    sc.eevee.taa_render_samples = samples
elif eng == "BLENDER_WORKBENCH":
    sc.display.shading.light = "STUDIO"; sc.display.shading.color_type = "MATERIAL"
r.use_freestyle = fs
if fs:
    r.line_thickness = 1.0; vl = bpy.context.view_layer; vl.use_freestyle = True
    fss = vl.freestyle_settings
    if not fss.linesets: fss.linesets.new("LineSet")
    ls = fss.linesets[0]; ls.select_by_visibility = True; ls.select_silhouette = ls.select_border = ls.select_crease = True
t1 = time.perf_counter()
bpy.ops.render.render(write_still=True)
t2 = time.perf_counter()
print("RENDER " + json.dumps({"engine": r.engine, "samples": samples, "freestyle": fs, "setup_s": t1 - t0, "render_s": t2 - t1}))
