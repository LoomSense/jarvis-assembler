# Blender 5.3 headless: render mockup background plates (real-looking parts on a table + 3D guidance)
# usage: Blender -b -P scene.py -- <scene> <out.png> <out.json> [samples]
import bpy, bmesh, sys, json, math
from mathutils import Vector, Matrix, Euler
from bpy_extras.object_utils import world_to_camera_view

argv = sys.argv[sys.argv.index("--") + 1:]
SCENE, OUT, OUTJ = argv[0], argv[1], argv[2]
SAMPLES = int(argv[3]) if len(argv) > 3 else 96
W, H = 1600, 1000
GLB = "/Users/foivospro/Projects/hackathon/jobs/malm-drawer/walkthrough.glb"
LABEL = __file__.rsplit("/", 1)[0] + "/label.png"

AZURE = (0.62, 0.81, 1.0)       # #9ecfff
GREEN = (0.435, 0.863, 0.608)   # #6fdc9b
RED = (1.0, 0.541, 0.478)       # #ff8a7a
AMBER = (0.91, 0.757, 0.294)    # #e8c14b

bpy.ops.wm.read_factory_settings(use_empty=True)
sc = bpy.context.scene

# ---------------------------------------------------------------- import + bake final transforms
bpy.ops.import_scene.gltf(filepath=GLB)
end = max(a.frame_range[1] for a in bpy.data.actions)
sc.frame_set(int(end))
bpy.context.view_layer.update()
parts = {}
mats = {}
for o in list(bpy.data.objects):
    if o.type == "MESH":
        mats[o.name] = o.matrix_world.copy()
for o in list(bpy.data.objects):
    o.animation_data_clear()
for o in list(bpy.data.objects):
    if o.type == "MESH":
        o.parent = None
        o.matrix_world = mats[o.name]
        parts[o.name] = o
    else:
        bpy.data.objects.remove(o)
for a in list(bpy.data.actions):
    bpy.data.actions.remove(a)
if "marker" in parts:
    bpy.data.objects.remove(parts.pop("marker"))

def P(name):
    for k, o in parts.items():
        if k.startswith(name):
            return o
    raise KeyError(name)

# ---------------------------------------------------------------- materials
def node_mat(name):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    return m, nt, out

def principled(name, color, rough=0.5, metal=0.0, grain=None, scale=30.0, coat=0.0):
    m, nt, out = node_mat(name)
    b = nt.nodes.new("ShaderNodeBsdfPrincipled")
    b.inputs["Base Color"].default_value = (*color, 1)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    if coat:
        b.inputs["Coat Weight"].default_value = coat
    if grain:
        tc = nt.nodes.new("ShaderNodeTexCoord")
        mp = nt.nodes.new("ShaderNodeMapping")
        mp.inputs["Scale"].default_value = (grain[2], 1.0, 1.0)
        nz = nt.nodes.new("ShaderNodeTexNoise")
        nz.inputs["Scale"].default_value = 2.5
        nz.inputs["Detail"].default_value = 8.0
        wv = nt.nodes.new("ShaderNodeTexWave")
        wv.inputs["Scale"].default_value = scale
        wv.bands_direction = "Y"
        wv.inputs["Distortion"].default_value = 1.6
        wv.inputs["Detail"].default_value = 3.0
        ramp = nt.nodes.new("ShaderNodeValToRGB")
        ramp.color_ramp.elements[0].color = (*grain[0], 1)
        ramp.color_ramp.elements[1].color = (*grain[1], 1)
        nt.links.new(tc.outputs["Object"], mp.inputs["Vector"])
        nt.links.new(mp.outputs["Vector"], nz.inputs["Vector"])
        nt.links.new(nz.outputs["Color"], wv.inputs["Vector"])
        nt.links.new(wv.outputs["Fac"], ramp.inputs["Fac"])
        nt.links.new(ramp.outputs["Color"], b.inputs["Base Color"])
    nt.links.new(b.outputs["BSDF"], out.inputs["Surface"])
    return m

def glow(name, color, base=0.10, rim=0.55, strength=0.75):
    """Ghost: soft fill + fresnel rim, emissive, transparent, no shadows."""
    m, nt, out = node_mat(name)
    tr = nt.nodes.new("ShaderNodeBsdfTransparent")
    em = nt.nodes.new("ShaderNodeEmission")
    em.inputs["Color"].default_value = (*color, 1)
    em.inputs["Strength"].default_value = strength
    lw = nt.nodes.new("ShaderNodeLayerWeight")
    lw.inputs["Blend"].default_value = 0.35
    mul = nt.nodes.new("ShaderNodeMath"); mul.operation = "MULTIPLY_ADD"
    mul.inputs[1].default_value = rim
    mul.inputs[2].default_value = base
    mix = nt.nodes.new("ShaderNodeMixShader")
    nt.links.new(lw.outputs["Facing"], mul.inputs[0])
    nt.links.new(mul.outputs[0], mix.inputs["Fac"])
    nt.links.new(tr.outputs[0], mix.inputs[1])
    nt.links.new(em.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], out.inputs["Surface"])
    return m

def flat(name, color, alpha=1.0, strength=1.0):
    m, nt, out = node_mat(name)
    tr = nt.nodes.new("ShaderNodeBsdfTransparent")
    em = nt.nodes.new("ShaderNodeEmission")
    em.inputs["Color"].default_value = (*color, 1)
    em.inputs["Strength"].default_value = strength
    mix = nt.nodes.new("ShaderNodeMixShader")
    mix.inputs["Fac"].default_value = alpha
    nt.links.new(tr.outputs[0], mix.inputs[1])
    nt.links.new(em.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], out.inputs["Surface"])
    return m

OAK = principled("oak", (0.72, 0.55, 0.36), 0.45, grain=((0.58, 0.42, 0.26), (0.74, 0.58, 0.39), 0.15), scale=60, coat=0.2)
WHITE = principled("white_foil", (0.86, 0.85, 0.82), 0.42)
FIBRE = principled("fibre", (0.90, 0.89, 0.86), 0.7)
PLASTIC = principled("plastic", (0.93, 0.93, 0.92), 0.35)
ZINC = principled("zinc", (0.75, 0.75, 0.76), 0.28, metal=1.0)
DOWEL = principled("dowel", (0.78, 0.63, 0.43), 0.7)
WALNUT = principled("walnut", (0.12, 0.07, 0.04), 0.42, grain=((0.085, 0.05, 0.03), (0.15, 0.09, 0.052), 0.05), scale=9, coat=0.25)
WALL = principled("wall", (0.55, 0.53, 0.50), 0.9)
CARD = principled("cardboard", (0.50, 0.36, 0.22), 0.85)
PAPER = principled("paper", (0.93, 0.92, 0.89), 0.8)
FLOOR = principled("floor", (0.30, 0.27, 0.24), 0.6)

def assign(o, m):
    o.data.materials.clear()
    o.data.materials.append(m)

for k, o in parts.items():
    if "front" in k: assign(o, OAK)
    elif "bottom" in k: assign(o, FIBRE)
    elif "side" in k or "back" in k: assign(o, WHITE)
    elif "runner" in k: assign(o, PLASTIC)
    elif "118331" in k or "120076" in k or "100365" in k: assign(o, ZINC)
    elif "101345" in k: assign(o, DOWEL)
    else: assign(o, PLASTIC)

# ---------------------------------------------------------------- room
def box(name, size, loc, mat, bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc)
    o = bpy.context.object
    o.name = name
    o.scale = size
    bpy.ops.object.transform_apply(scale=True)
    if bevel:
        bm = o.modifiers.new("b", "BEVEL"); bm.width = bevel; bm.segments = 3
    assign(o, mat)
    return o

box("table", (1.7, 0.95, 0.04), (0, 0.05, -0.02), WALNUT, 0.004)
box("wall", (6, 0.05, 3), (0, 1.25, 0.7), WALL)
box("floor", (6, 6, 0.02), (0, 0, -0.75), FLOOR)
# guide booklet on the table
box("booklet", (0.21, 0.297, 0.004), ((-0.52 if SCENE != "a" else 0.62), 0.12 if SCENE != "a" else 0.30, 0.002), PAPER)
bpy.context.object.rotation_euler.z = math.radians(12)

# ---------------------------------------------------------------- rig poses
rig = bpy.data.objects.new("rig", None)
sc.collection.objects.link(rig)
for o in parts.values():
    mw = o.matrix_world.copy()
    o.parent = rig
    o.matrix_world = mw

def pose(rot_deg, loc):
    rig.rotation_euler = Euler([math.radians(a) for a in rot_deg])
    rig.location = loc
    bpy.context.view_layer.update()

def hide(*names):
    for k, o in parts.items():
        if any(k.startswith(n) for n in names):
            o.hide_render = True

def ghost_of(o, color, base=0.14, rim=0.6, edge=True, strength=0.95):
    """Duplicate `o` as a ghost: fresnel fill + bevelled glowing edges."""
    g = o.copy(); g.data = o.data.copy(); g.name = o.name + "_ghost"
    sc.collection.objects.link(g)
    g.parent = o.parent; g.matrix_world = o.matrix_world.copy()
    g.data.materials.clear()
    g.data.materials.append(glow("gfill_" + o.name, color, base, rim, strength))
    if edge:
        g.data.materials.append(flat("gedge_" + o.name, color, 0.97, 1.5))
        bv = g.modifiers.new("edge", "BEVEL"); bv.width = 0.0016; bv.segments = 1
        bv.material = 1; bv.limit_method = "ANGLE"
    g.hide_render = False
    g.visible_shadow = False
    g.visible_diffuse = False
    g.visible_glossy = False
    o.hide_render = True
    return g

def tube(name, pts, color, radius=0.0022, alpha=0.95, strength=1.1, halo=True):
    cu = bpy.data.curves.new(name, "CURVE"); cu.dimensions = "3D"
    sp = cu.splines.new("POLY"); sp.points.add(len(pts) - 1)
    for i, p in enumerate(pts):
        sp.points[i].co = (*p, 1)
    cu.bevel_depth = radius; cu.bevel_resolution = 4; cu.use_fill_caps = True
    o = bpy.data.objects.new(name, cu); sc.collection.objects.link(o)
    o.data.materials.append(flat("m_" + name, color, alpha, strength))
    o.visible_shadow = False
    if halo:
        cu2 = cu.copy(); cu2.bevel_depth = radius * 3.2
        h = bpy.data.objects.new(name + "_halo", cu2); sc.collection.objects.link(h)
        cu2.materials.clear(); cu2.materials.append(flat("h_" + name, color, 0.16, 2.0))
        h.visible_shadow = False
    return o

def cone(name, tip, direction, color, length=0.014, radius=0.0065, strength=1.1):
    d = Vector(direction).normalized()
    bpy.ops.mesh.primitive_cone_add(vertices=32, radius1=radius, radius2=0, depth=length,
                                    location=Vector(tip) - d * length / 2)
    o = bpy.context.object; o.name = name
    o.rotation_mode = "QUATERNION"
    o.rotation_quaternion = Vector((0, 0, 1)).rotation_difference(d)
    o.data.materials.append(flat("m_" + name, color, 0.97, strength))
    o.visible_shadow = False
    return o

def dot(name, p, color, r=0.0035, alpha=0.95, strength=1.0):
    bpy.ops.mesh.primitive_uv_sphere_add(radius=r, location=p, segments=24, ring_count=12)
    o = bpy.context.object; o.name = name
    o.data.materials.append(flat("m_" + name, color, alpha, strength))
    o.visible_shadow = False
    return o

def ring(name, center, normal, r, width, color, alpha, strength=3.0):
    n = Vector(normal).normalized()
    q = Vector((0, 0, 1)).rotation_difference(n)
    pts = []
    for i in range(97):
        a = 2 * math.pi * i / 96
        pts.append(tuple(Vector(center) + q @ Vector((r * math.cos(a), r * math.sin(a), 0))))
    return tube(name, pts, color, width, alpha, strength, halo=False)

def bezier(p0, p1, p2, p3, n=48):
    out = []
    for i in range(n + 1):
        t = i / n
        a = (1 - t) ** 3; b = 3 * (1 - t) ** 2 * t; c = 3 * (1 - t) * t * t; d = t ** 3
        out.append(tuple(Vector(p0) * a + Vector(p1) * b + Vector(p2) * c + Vector(p3) * d))
    return out

def bbox(o):
    bb = [o.matrix_world @ Vector(c) for c in o.bound_box]
    mn = Vector([min(v[i] for v in bb) for i in range(3)])
    mx = Vector([max(v[i] for v in bb) for i in range(3)])
    return mn, mx

ANCH = {}
def anchor(name, p):
    ANCH[name] = tuple(p)

# ---------------------------------------------------------------- camera + light
cam_data = bpy.data.cameras.new("cam")
cam = bpy.data.objects.new("cam", cam_data); sc.collection.objects.link(cam); sc.camera = cam
def look(eye, target, lens=24, fstop=5.6):
    cam.location = eye
    d = Vector(target) - Vector(eye)
    cam.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    cam_data.lens = lens; cam_data.sensor_width = 36
    cam_data.dof.use_dof = True
    cam_data.dof.focus_distance = d.length
    cam_data.dof.aperture_fstop = fstop

def area(name, loc, target, size, energy, color=(1, 0.96, 0.9)):
    l = bpy.data.lights.new(name, "AREA"); l.size = size; l.energy = energy; l.color = color
    o = bpy.data.objects.new(name, l); sc.collection.objects.link(o)
    o.location = loc
    o.rotation_euler = (Vector(target) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()

area("window", (-1.6, -0.2, 1.4), (0, 0.1, 0), 1.6, 110, (1.0, 0.95, 0.88))
area("ceiling", (0.4, -0.4, 2.0), (0, 0.1, 0), 1.2, 45, (1.0, 0.92, 0.82))
area("fill", (1.2, -1.4, 0.8), (0, 0, 0.1), 1.5, 14, (0.85, 0.9, 1.0))
world = bpy.data.worlds.new("w"); sc.world = world; world.use_nodes = True
world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.32, 0.31, 0.30, 1)
world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.22

# ---------------------------------------------------------------- scenes
S2_ROT, S2_LOC = (90, 0, 0), (0, 0, 0.016)
S6_ROT, S6_LOC = (180, 0, 0), (0, 0.2, 0.13)

if SCENE in ("a", "b"):
    pose(S2_ROT, S2_LOC)
    hide("hw_120076", "hw_110519", "hw_100365", "part_drawer_back", "part_drawer_bottom", "part_drawer_runner")
    right = P("part_drawer_side_right")
    ghost = ghost_of(right, AZURE)
    # real right side panel lying on the table to the right (its start)
    real = right.copy(); real.data = right.data; real.name = "right_real"; sc.collection.objects.link(real)
    real.parent = None; real.hide_render = False
    real.matrix_world = Matrix.Translation((0.52 if SCENE == "b" else -0.50, -0.34 if SCENE == "b" else -0.22, 0.0)) @ Matrix.Rotation(math.radians(-20 if SCENE == "b" else 25), 4, "Z") @ Matrix.Rotation(math.radians(90), 4, "Y")
    bpy.context.view_layer.update()
    mn, mx = bbox(real)
    real.location.z -= mn.z
    real.location.x += 0.0
    bpy.context.view_layer.update()
    rmn, rmx = bbox(real)
    gmn, gmx = bbox(ghost)
    start = Vector(((rmn.x + rmx.x) / 2, (rmn.y + rmx.y) / 2, rmx.z + 0.012))
    endp = Vector(((gmn.x + gmx.x) / 2 + 0.03, (gmn.y + gmx.y) / 2, gmx.z + 0.03))
    side = 1 if SCENE == "b" else -1
    if SCENE == "a":
        endp = Vector(((gmn.x + gmx.x) / 2 - 0.03, (gmn.y + gmx.y) / 2, gmx.z + 0.03))
    arc = bezier(start, start + Vector((0, 0, 0.30)), endp + Vector((0.16 * side, 0, 0.18)), endp, 60)
    # dotted path: dots every few samples, brighter travelling dash near 60 %
    for i, p in enumerate(arc[::3][:-1]):
        tt = i / (len(arc[::3]) - 1)
        hot = 0.52 < tt < 0.66
        dot(f"d{i}", p, AZURE, 0.0060 if hot else 0.0042, 0.98 if hot else 0.45 + 0.45 * tt, 1.5 if hot else 0.95)
    tip = Vector(arc[-1]); pre = Vector(arc[-4])
    cone("arc_head", tip, tip - pre, AZURE, 0.026, 0.010)
    anchor("label", ((gmn.x + gmx.x) / 2 + 0.01, (gmn.y + gmx.y) / 2, gmx.z + 0.01))
    anchor("ghost_top", ((gmn.x + gmx.x) / 2, (gmn.y + gmx.y) / 2, gmx.z))
    anchor("real_part", tuple(start))
    anchor("object_right", (0.19, -0.07, 0.2))
    anchor("object_center", (0, -0.07, 0.2))
    if SCENE == "a":
        look((0.62, -1.30, 0.92), (0.30, -0.02, 0.18), lens=24, fstop=8)
    else:
        look((0.70, -0.80, 0.74), (0.22, -0.08, 0.20), lens=24, fstop=6)

elif SCENE == "c":
    pose(S2_ROT, S2_LOC)
    hide("hw_110519", "hw_100365", "part_drawer_back", "part_drawer_bottom", "part_drawer_runner")
    lock = P("hw_120076_1")
    hide("hw_120076_2")
    g = ghost_of(lock, AZURE, 0.22, 0.6, edge=True, strength=3.0)
    mn, mx = bbox(lock)
    c = (mn + mx) / 2
    outer_x = mn.x - 0.003
    ax = Vector((-1, 0, 0))
    R = 0.027
    pts = []
    # clockwise seen from outside (-x looking +x): in the y-z plane
    a0, a1 = math.radians(100), math.radians(100 - 290)
    for i in range(80):
        a = a0 + (a1 - a0) * i / 79
        pts.append((outer_x - 0.006, c.y + R * math.cos(a), c.z + R * math.sin(a)))
    tube("turn", pts, AZURE, 0.0030, 0.99, 2.3)
    pe = Vector(pts[-1]); pp = Vector(pts[-5])
    cone("turn_head", pe + (pe - pp).normalized() * 0.006, pe - pp, AZURE, 0.014, 0.0070, 2.3)
    ring("rip1", (outer_x - 0.0005, c.y, c.z), ax, 0.0105, 0.0009, AZURE, 0.95, 1.6)
    ring("rip2", (outer_x - 0.0005, c.y, c.z), ax, 0.0135, 0.0007, AZURE, 0.55, 1.3)
    ring("rip3", (outer_x - 0.0005, c.y, c.z), ax, 0.0165, 0.0005, AZURE, 0.25, 1.2)
    anchor("label", (outer_x - 0.01, c.y, c.z + 0.035))
    anchor("lock", (outer_x, c.y, c.z))
    anchor("object_right", (0.19, -0.07, 0.25))
    look((-0.42, -0.34, 0.28), (c.x - 0.03, c.y + 0.05, c.z + 0.06), lens=30, fstop=4.5)

elif SCENE == "d":
    pose(S6_ROT, S6_LOC)
    hide("part_drawer_runner_right", "hw_100365")
    left = P("part_drawer_runner_left")
    right = P("part_drawer_runner_right")
    right.hide_render = True
    # the wrong runner (DR) held 2.5 cm above the left side
    left.location.z += 0.0  # left stays as the "wrong" real part; lift it via a copy
    wrong = left.copy(); wrong.data = left.data; wrong.name = "wrong_real"; sc.collection.objects.link(wrong)
    wrong.parent = None
    wrong.matrix_world = Matrix.Translation((0, 0, 0.028)) @ left.matrix_world
    left.hide_render = True
    wrong.hide_render = False
    wmn0, wmx0 = bbox(wrong)
    pad = 0.004
    lo = wmn0 - Vector((pad, pad, pad)); hi = wmx0 + Vector((pad, pad, pad))
    cs = [Vector((x, y, z)) for x in (lo.x, hi.x) for y in (lo.y, hi.y) for z in (lo.z, hi.z)]
    k = 0
    for i in range(8):
        for j in range(i + 1, 8):
            diff = sum(1 for q in range(3) if abs(cs[i][q] - cs[j][q]) > 1e-6)
            if diff != 1: continue
            a, b = cs[i], cs[j]; L = (b - a).length; t = 0.0
            while t < L:
                e = min(L, t + 0.008)
                tube(f"dash{k}", [tuple(a + (b - a) * (t / L)), tuple(a + (b - a) * (e / L))], RED, 0.0017, 0.99, 1.9, halo=False)
                k += 1; t += 0.013
    wrong.hide_render = False
    # correct place for DR: one ghost on the right side
    gr = ghost_of(right, AZURE, 0.2, 0.6, True, 1.1)
    wmn, wmx = bbox(wrong)
    gmn, gmx = bbox(gr)
    anchor("wrong", ((wmn.x + wmx.x) / 2, (wmn.y + wmx.y) / 2, wmx.z + 0.01))
    anchor("ghost", ((gmn.x + gmx.x) / 2, (gmn.y + gmx.y) / 2, gmx.z + 0.01))
    anchor("object_right", (0.2, 0.0, 0.13))
    look((-0.30, -0.66, 0.66), (0.14, -0.06, 0.08), lens=26, fstop=6)

elif SCENE == "e":
    pose((0, 0, 0), (0, 0, 0))
    for o in parts.values():
        o.hide_render = True
    b = box("mbox", (0.46, 0.62, 0.075), (0.02, 0.0, 0.0375), CARD, 0.002)
    box("tape", (0.05, 0.625, 0.0772), (0.02 + 0.16, 0.0, 0.0375), principled("tape", (0.42, 0.30, 0.18), 0.3)).rotation_euler.z = math.radians(-8)
    b.rotation_euler.z = math.radians(-8)
    bpy.context.view_layer.update()
    # label on top
    bpy.ops.mesh.primitive_plane_add(size=1, location=(0, 0, 0))
    lab = bpy.context.object; lab.scale = (0.30, 0.1875, 1)
    lab.rotation_euler.z = b.rotation_euler.z
    lab.location = b.matrix_world @ Vector((-0.02, -0.15, 0.0756))
    m, nt, outn = node_mat("label")
    bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled"); bsdf.inputs["Roughness"].default_value = 0.6
    tex = nt.nodes.new("ShaderNodeTexImage"); tex.image = bpy.data.images.load(LABEL)
    nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    nt.links.new(bsdf.outputs["BSDF"], outn.inputs["Surface"])
    lab.data.materials.append(m)
    bpy.context.view_layer.update()
    mn, mx = bbox(b)
    for nm, p in (("c0", (mn.x, mn.y, mx.z)), ("c1", (mx.x, mn.y, mx.z)), ("c2", (mx.x, mx.y, mx.z)), ("c3", (mn.x, mx.y, mx.z))):
        anchor(nm, p)
    corners = [b.matrix_world @ Vector((sx * 0.5, sy * 0.5, 0.5)) for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
    for i, p in enumerate(corners):
        anchor(f"k{i}", p)
    anchor("label", b.matrix_world @ Vector((0, -0.21, 0.5)))
    anchor("object_right", b.matrix_world @ Vector((0.5, 0.0, 0.5)))
    look((0.12, -0.78, 0.66), (0.04, 0.02, 0.03), lens=24, fstop=8)

elif SCENE == "f":
    pose((0, 0, 0), (0.0, -0.05, 0.0))
    d = (0, -0.05 + 0.2, 0)
    ring("done1", (0, 0.15, 0.002), (0, 0, 1), 0.33, 0.0016, GREEN, 0.8, 1.1)
    ring("done2", (0, 0.15, 0.002), (0, 0, 1), 0.40, 0.0011, GREEN, 0.4, 1.0)
    ring("done3", (0, 0.15, 0.002), (0, 0, 1), 0.47, 0.0008, GREEN, 0.16, 1.0)
    anchor("top", (0, -0.06, 0.16))
    anchor("object_right", (0.21, -0.06, 0.13))
    look((0.30, -0.86, 0.52), (0.13, 0.06, 0.07), lens=24, fstop=8)

bpy.context.view_layer.update()

# ---------------------------------------------------------------- render
sc.render.engine = "CYCLES"
prefs = bpy.context.preferences.addons["cycles"].preferences
try:
    prefs.compute_device_type = "METAL"
    prefs.get_devices()
    for dv in prefs.devices:
        dv.use = True
    sc.cycles.device = "GPU"
except Exception as e:
    print("GPU fallback", e)
sc.cycles.samples = SAMPLES
sc.cycles.use_denoising = True
sc.render.resolution_x, sc.render.resolution_y = W, H
sc.render.resolution_percentage = 100
sc.view_settings.view_transform = "Khronos PBR Neutral"
sc.render.filepath = OUT
bpy.ops.render.render(write_still=True)

proj = {}
for k, p in ANCH.items():
    v = world_to_camera_view(sc, cam, Vector(p))
    proj[k] = [round(v.x * W, 1), round((1 - v.y) * H, 1), round(v.z, 3)]
json.dump(proj, open(OUTJ, "w"), indent=1)
print("ANCHORS", proj)
