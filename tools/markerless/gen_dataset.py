"""Blender (headless) synthetic dataset for markerless drawer tracking.

Scene: textured wooden table (top at z=0, Blender Z-up), floor, walls, a few
distractor objects; the drawer in one assembly state (mesh from export_states.py,
white laminate) at a random (x, y, yaw) on the table; a moving camera with
Quest-passthrough-like intrinsics (1280x960, fx=fy=870, centred principal point)
at 0.4-0.9 m; three lighting setups; a skin-coloured hand occluding the drawer
during some frames.

Each sequence = one (state, lighting) pair, a continuous camera path.
Outputs in OUT_DIR:
  img/sSS_fFFF.jpg       renders (q95)
  frames.jsonl           one line per frame: K, T_world_cam (OpenCV: x right,
                         y down, z forward), T_world_obj, state, light, hand flag
Usage:
  Blender -b --python gen_dataset.py -- MODELS_DIR OUT_DIR [frames_per_seq] [seed]
"""
import json
import math
import os
import random
import sys

import bpy
from mathutils import Matrix, Vector, Euler

argv = sys.argv[sys.argv.index("--") + 1:]
models_dir, out_dir = argv[0], argv[1]
FPS_SEQ = int(argv[2]) if len(argv) > 2 else 30
SEED = int(argv[3]) if len(argv) > 3 else 7
ONLY = argv[4] if len(argv) > 4 else None  # e.g. "0,1" to render a subset of sequences
STATES = [1, 2, 4, 5]
LIGHTS = ["office", "window", "dim"]
W, H, FX = 1280, 960, 870.0
os.makedirs(os.path.join(out_dir, "img"), exist_ok=True)
rng = random.Random(SEED)

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.render.engine = "BLENDER_EEVEE"
scene.render.resolution_x, scene.render.resolution_y = W, H
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = "JPEG"
scene.render.image_settings.quality = 95
try:
    scene.eevee.taa_render_samples = 32
except Exception:
    pass
try:
    scene.view_settings.view_transform = "Standard"
except Exception:
    pass


def mat(name, color, rough=0.5, metallic=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes.get("Principled BSDF")
    b.inputs["Base Color"].default_value = (*color, 1)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metallic
    return m, b


def wood_mat():
    m, b = mat("wood", (0.4, 0.25, 0.12), 0.45)
    nt = m.node_tree
    tc = nt.nodes.new("ShaderNodeTexCoord")
    mp = nt.nodes.new("ShaderNodeMapping")
    mp.inputs["Scale"].default_value = (1.0, 12.0, 1.0)
    wave = nt.nodes.new("ShaderNodeTexWave")
    wave.inputs["Scale"].default_value = 3.0
    wave.inputs["Distortion"].default_value = 8.0
    wave.inputs["Detail"].default_value = 4.0
    noise = nt.nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = 60.0
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].color = (0.22, 0.12, 0.05, 1)
    ramp.color_ramp.elements[1].color = (0.55, 0.36, 0.18, 1)
    mix = nt.nodes.new("ShaderNodeMath")
    mix.operation = "MULTIPLY_ADD"
    mix.inputs[1].default_value = 0.8
    nt.links.new(tc.outputs["Object"], mp.inputs["Vector"])
    nt.links.new(mp.outputs["Vector"], wave.inputs["Vector"])
    nt.links.new(wave.outputs["Fac"], mix.inputs[0])
    nt.links.new(noise.outputs["Fac"], mix.inputs[2])
    nt.links.new(mix.outputs[0], ramp.inputs["Fac"])
    nt.links.new(ramp.outputs["Color"], b.inputs["Base Color"])
    return m


def noisy_mat(name, c0, c1, scale):
    m, b = mat(name, c0, 0.8)
    nt = m.node_tree
    noise = nt.nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = scale
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].color = (*c0, 1)
    ramp.color_ramp.elements[1].color = (*c1, 1)
    nt.links.new(noise.outputs["Fac"], ramp.inputs["Fac"])
    nt.links.new(ramp.outputs["Color"], b.inputs["Base Color"])
    return m


def add_box(name, size, loc, material, rot_z=0.0):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc, rotation=(0, 0, rot_z))
    o = bpy.context.active_object
    o.name = name
    o.scale = size
    o.data.materials.append(material)
    return o


# --- static scene -----------------------------------------------------------
M_WOOD = wood_mat()
M_FLOOR = noisy_mat("floor", (0.18, 0.18, 0.2), (0.3, 0.3, 0.32), 8)
M_WALL = noisy_mat("wall", (0.7, 0.68, 0.62), (0.8, 0.78, 0.72), 3)
M_WHITE, _ = mat("laminate", (0.82, 0.82, 0.8), 0.35)
M_SKIN, _ = mat("skin", (0.75, 0.5, 0.38), 0.55)
M_SLEEVE, _ = mat("sleeve", (0.1, 0.15, 0.3), 0.8)
M_MUG, _ = mat("mug", (0.7, 0.1, 0.08), 0.3)
M_BOOK, _ = mat("book", (0.1, 0.35, 0.2), 0.6)
M_PAPER, _ = mat("paper", (0.9, 0.9, 0.88), 0.7)

add_box("table", (1.6, 1.0, 0.04), (0, 0, -0.02), M_WOOD)
add_box("floor", (8, 8, 0.02), (0, 0, -0.76), M_FLOOR)
add_box("wall_n", (8, 0.05, 3), (0, 2.2, 0.7), M_WALL)
add_box("wall_e", (0.05, 8, 3), (2.5, 0, 0.7), M_WALL)
add_box("wall_w", (0.05, 8, 3), (-2.5, 0, 0.7), M_WALL)
add_box("wall_s", (8, 0.05, 3), (0, -2.2, 0.7), M_WALL)

bpy.ops.mesh.primitive_cylinder_add(radius=0.04, depth=0.1)
MUG = bpy.context.active_object; MUG.data.materials.append(M_MUG)
BOOK = add_box("book", (0.24, 0.17, 0.03), (0, 0, 0.015), M_BOOK)
PAPER = add_box("paper", (0.21, 0.297, 0.001), (0, 0, 0.0005), M_PAPER)  # IKEA leaflet
DISTRACT = [(MUG, 0.05), (BOOK, 0.015), (PAPER, 0.0005)]

# --- drawer states ------------------------------------------------------------
STATE_OBJ = {}
for n in STATES:
    bpy.ops.wm.ply_import(filepath=os.path.join(models_dir, f"state_{n:02d}.ply"))
    o = bpy.context.selected_objects[0]
    o.name = f"drawer_state_{n:02d}"
    o.data.materials.clear()
    o.data.materials.append(M_WHITE)
    o.hide_render = True
    STATE_OBJ[n] = o


# --- hand ---------------------------------------------------------------------
def build_hand():
    root = bpy.data.objects.new("hand", None)
    scene.collection.objects.link(root)
    parts = []
    bpy.ops.mesh.primitive_uv_sphere_add(radius=1, location=(0, 0, 0))
    palm = bpy.context.active_object; palm.scale = (0.045, 0.05, 0.014); parts.append(palm)
    for i, dx in enumerate([-0.03, -0.01, 0.01, 0.03]):
        ln = [0.07, 0.08, 0.075, 0.06][i]
        bpy.ops.mesh.primitive_cylinder_add(radius=0.009, depth=ln, location=(dx, 0.05 + ln / 2, -0.004),
                                            rotation=(math.pi / 2, 0, 0))
        parts.append(bpy.context.active_object)
    bpy.ops.mesh.primitive_cylinder_add(radius=0.011, depth=0.06, location=(-0.055, 0.01, -0.005),
                                        rotation=(math.pi / 2, 0, 0.8))
    parts.append(bpy.context.active_object)
    for p in parts:
        p.data.materials.append(M_SKIN)
    bpy.ops.mesh.primitive_cylinder_add(radius=0.035, depth=0.45, location=(0, -0.27, 0.0),
                                        rotation=(math.pi / 2, 0, 0))
    arm = bpy.context.active_object; arm.data.materials.append(M_SLEEVE); parts.append(arm)
    for p in parts:
        p.parent = root
    return root, parts


HAND, HAND_PARTS = build_hand()


def show_hand(on):
    for p in HAND_PARTS:
        p.hide_render = not on


# --- lights -------------------------------------------------------------------
def clear_lights():
    for o in list(bpy.data.objects):
        if o.type == "LIGHT":
            bpy.data.objects.remove(o, do_unlink=True)


def world(strength, color):
    w = bpy.data.worlds.get("W") or bpy.data.worlds.new("W")
    scene.world = w
    w.use_nodes = True
    bg = w.node_tree.nodes.get("Background")
    bg.inputs["Color"].default_value = (*color, 1)
    bg.inputs["Strength"].default_value = strength


def add_light(kind, loc, energy, color=(1, 1, 1), size=0.5, rot=None):
    ld = bpy.data.lights.new(kind, kind)
    ld.energy = energy
    ld.color = color
    if kind == "AREA":
        ld.size = size
    if kind in ("POINT", "SPOT"):
        ld.shadow_soft_size = size
    o = bpy.data.objects.new(kind, ld)
    scene.collection.objects.link(o)
    o.location = loc
    if rot is not None:
        o.rotation_euler = rot
    else:
        d = Vector((0, 0, 0)) - Vector(loc)
        o.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    return o


def set_lighting(name, r):
    clear_lights()
    if name == "office":
        world(0.25, (0.9, 0.9, 1.0))
        add_light("AREA", (0.3 * r.uniform(-1, 1), 0.3 * r.uniform(-1, 1), 1.6), 70, size=1.2)
    elif name == "window":
        world(0.25, (0.6, 0.7, 1.0))
        add_light("SUN", (0, 0, 0), 4.0, (1.0, 0.92, 0.8),
                  rot=Euler((math.radians(r.uniform(25, 40)), 0, r.uniform(0, 2 * math.pi))))
    else:  # dim warm lamp, low ambient -> noisy dark frames
        world(0.05, (1.0, 0.85, 0.7))
        add_light("POINT", (r.uniform(-0.6, 0.6), r.uniform(-0.6, 0.6), 0.9), 40, (1.0, 0.8, 0.6), size=0.1)


# --- camera ---------------------------------------------------------------------
cam_data = bpy.data.cameras.new("cam")
cam_data.sensor_fit = "HORIZONTAL"
cam_data.sensor_width = 36.0
cam_data.lens = FX * 36.0 / W
cam_data.clip_start = 0.02
cam = bpy.data.objects.new("cam", cam_data)
scene.collection.objects.link(cam)
scene.camera = cam
K = [[FX, 0, W / 2], [0, FX, H / 2], [0, 0, 1]]
BL2CV = Matrix(((1, 0, 0, 0), (0, -1, 0, 0), (0, 0, -1, 0), (0, 0, 0, 1)))


def mlist(M):
    return [list(r) for r in M]


# --- sequences ----------------------------------------------------------------
seqs = [(s, l) for s in STATES for l in LIGHTS]
only = None if ONLY is None else {int(x) for x in ONLY.split(",")}
out = open(os.path.join(out_dir, "frames.jsonl"), "a" if only else "w")
for si, (state, light) in enumerate(seqs):
    r = random.Random(SEED * 1000 + si)
    if only is not None and si not in only:
        continue
    for n, o in STATE_OBJ.items():
        o.hide_render = n != state
    obj = STATE_OBJ[state]
    ox, oy, oyaw = r.uniform(-0.25, 0.25), r.uniform(-0.12, 0.12), r.uniform(-math.pi, math.pi)
    obj.location = (ox, oy, 0.0)
    obj.rotation_euler = (0, 0, oyaw)
    bpy.context.view_layer.update()
    T_wo = obj.matrix_world.copy()
    height = obj.dimensions.z
    center = Vector((ox, oy, height * 0.5))
    # distractors: random spots on the table, away from the drawer
    for d, z in DISTRACT:
        for _ in range(50):
            p = Vector((r.uniform(-0.7, 0.7), r.uniform(-0.42, 0.42), 0))
            if (p.xy - Vector((ox, oy))).length > 0.38:
                break
        d.location = (p.x, p.y, z)
        d.rotation_euler = (0, 0, r.uniform(0, math.pi))
    set_lighting(light, r)
    hand_on = si % 2 == 0
    h0 = r.randint(FPS_SEQ // 2, max(FPS_SEQ // 2, FPS_SEQ - 8))
    hand_frames = set(range(h0, h0 + 6)) if hand_on else set()
    # hand target: above a random point of the drawer top, arm coming from the camera side
    hand_pt = Vector((ox, oy, 0)) + Vector((r.uniform(-0.12, 0.12), r.uniform(-0.12, 0.12), 0))
    az0 = r.uniform(0, 2 * math.pi)
    sweep = math.radians(r.uniform(40, 70)) * r.choice([-1, 1])
    el0, el1 = math.radians(r.uniform(30, 45)), math.radians(r.uniform(45, 65))
    d0, d1 = r.uniform(0.45, 0.6), r.uniform(0.7, 0.85)
    ph = r.uniform(0, math.pi)
    for fi in range(FPS_SEQ):
        u = fi / (FPS_SEQ - 1)
        az = az0 + sweep * u
        el = el0 + (el1 - el0) * (0.5 - 0.5 * math.cos(math.pi * u))
        dist = d0 + (d1 - d0) * (0.5 - 0.5 * math.cos(2 * math.pi * u + ph))
        look = center + Vector((0.06 * math.sin(3.1 * u + ph), 0.06 * math.cos(2.3 * u), -0.03))
        pos = center + dist * Vector((math.cos(el) * math.cos(az), math.cos(el) * math.sin(az), math.sin(el)))
        pos += Vector((r.gauss(0, 0.003), r.gauss(0, 0.003), r.gauss(0, 0.003)))  # head jitter
        cam.location = pos
        q = (look - pos).to_track_quat("-Z", "Y")
        roll = math.radians(r.gauss(0, 3))
        cam.rotation_euler = (q @ Euler((0, 0, roll)).to_quaternion()).to_euler()
        on = fi in hand_frames
        show_hand(on)
        if on:
            # hand hovers 4-9 cm above the top, palm down, arm pointing back to the camera
            k = (fi - h0) / 5.0
            hp = hand_pt + Vector((0, 0, height + 0.04 + 0.05 * abs(k - 0.5)))
            to_cam = (pos - hp); to_cam.z = 0; to_cam.normalize()
            yaw = math.atan2(-to_cam.x, to_cam.y)  # hand +y points away from the camera
            HAND.location = hp
            HAND.rotation_euler = (0.15, 0, yaw + math.pi)
        bpy.context.view_layer.update()
        name = f"s{si:02d}_f{fi:03d}"
        scene.render.filepath = os.path.join(out_dir, "img", name + ".jpg")
        bpy.ops.render.render(write_still=True)
        T_wc = cam.matrix_world @ BL2CV
        out.write(json.dumps({"id": name, "seq": si, "frame": fi, "state": state, "light": light,
                              "hand": on, "K": K, "W": W, "H": H,
                              "T_world_cam": mlist(T_wc), "T_world_obj": mlist(T_wo),
                              "obj_xy_yaw": [ox, oy, oyaw]}) + "\n")
        out.flush()
    print("SEQ", si, state, light, "done", flush=True)
out.close()
