"""Per step: the outline of the assembly as it stands on the work surface, from the guide data alone.

Reference for the Kotlin side (quest-android vision/StepFootprint.kt, same definition, pinned by StepFootprintTest
against the JSON this writes). Pure Python: json, struct, numpy. No Blender.

  python footprint.py MANIFEST.json WALKTHROUGH.glb [OUT.json]
  python footprint.py ../../quest-android/app/src/test/resources/malm-drawer/manifest.json \
      ../../quest-android/app/src/test/resources/malm-drawer/walkthrough.glb \
      ../../quest-android/app/src/test/resources/step_footprints_ref.json

Definition (step N, two variants):
 - parts: the manifest step's `visible` nodes named `part_*` that have a mesh (hardware `hw_*` does not shape the
   outline). `base` = visible minus `moving`: what stands on the surface when the step starts (when that is empty,
   all visible parts). `done` = all visible parts: the end of the step.
 - geometry: every vertex of those parts at its FINAL place in the ASSEMBLY frame (glTF Y-up, relative to the root
   node, end of the `walkthrough` clip), mapped by the step's TABLE <- ASSEMBLY: the root node's local pose at the
   step's `walkthrough.end_s`. The guide's step TABLE is Y-up.
 - the axis-aligned box of those vertices in the step TABLE frame: x0..x1, y0..y1 (up), z0..z1. Every resting pose of
   a guide is a quarter or half turn (`axis_aligned`), so this box is the oriented bounding rectangle of the outline.
 - length_m = the longer of (x1 - x0, z1 - z0), width_m = the other, height_m = y1 - y0.
 - FOOTPRINT frame (the detector's object frame; for the drawer front alone it is PanelPose.kt's PANEL): origin = the
   centre of the rectangle ON the resting plane (y = y0), +Z = up (TABLE +Y), +X along the long side:
       long side along TABLE X (or square):  +X_fp = +X_table, +Y_fp = -Z_table
       long side along TABLE Z:              +X_fp = +Z_table, +Y_fp = +X_table
   Both are right-handed (+Y_fp = Z_fp x X_fp). assembly_from_footprint = inv(TABLE <- ASSEMBLY) . TABLE <- FOOTPRINT.

What the markerless detector is given (`target`, per step; None = leave the tracker off for the step):
 - the detector (server/perception/panel.py `detect_footprint`) finds a flat white rectangle seen from above. That is
   only measured for a panel lying flat (FRONT_PANEL.md "All steps": the turned-over box is not a rectangle of one
   height from above and is NOT tracked). So the target is the `base` outline's SLAB: a part that rests on the plane
   (within SLAB_REST_M), spans the whole `base` rectangle (both sides within SLAB_SPAN_M) and is at most SLAB_MAX_M
   thick. length/width = the `base` rectangle, top_m = the slab's top face above the plane, same FOOTPRINT frame.
 - fill_hull = parts stand on the slab at some time in the step (`done` height > top_m + SLAB_REST_M): they hide
   parts of its top face, so the detector measures the convex hull of the blob.

The GLB reader mirrors quest-android guide/Glb.kt and Guide.build line by line (rest TRS, clip sampling with
LINEAR / STEP / CUBICSPLINE, T.R.S composition up to the root, triangle primitives only).
"""
from __future__ import annotations

import json
import struct
import sys

import numpy as np

_COMPS = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}
_DTYPE = {5120: "i1", 5121: "u1", 5122: "<i2", 5123: "<u2", 5125: "<u4", 5126: "<f4"}
AXIS_EPS = 1e-3       # a rotation entry this close to -1, 0 or 1 counts as an axis-aligned turn
SLAB_REST_M = 0.001   # a slab's underside is this close to the resting plane
SLAB_SPAN_M = 0.002   # ... and each of its sides this close to the whole outline's
SLAB_MAX_M = 0.030    # ... and it is at most this thick (measured: the 16 mm drawer front)


# ---- quaternions (x, y, z, w) and T.R.S, as in core/Geom.kt and guide/Glb.kt ----

def q_mul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return np.array([aw * bx + ax * bw + ay * bz - az * by,
                     aw * by - ax * bz + ay * bw + az * bx,
                     aw * bz + ax * by - ay * bx + az * bw,
                     aw * bw - ax * bx - ay * by - az * bz])


def q_norm(q):
    n = float(np.linalg.norm(q))
    return np.array([0.0, 0.0, 0.0, 1.0]) if n < 1e-9 else np.asarray(q, float) / n


def q_mat(q):
    x, y, z, w = q_norm(q)
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def q_nlerp(a, b, k):
    s = -1.0 if float(np.dot(a, b)) < 0 else 1.0
    return q_norm(a + (s * b - a) * k)


class Trs:
    """Translation, rotation, scale of one node (glTF order T . R . S)."""

    def __init__(self, t=(0, 0, 0), r=(0, 0, 0, 1), s=(1, 1, 1)):
        self.t, self.r, self.s = np.asarray(t, float), np.asarray(r, float), np.asarray(s, float)

    def apply(self, p):
        """parent <- child for points; `p` is (..., 3)."""
        return (np.asarray(p, float) * self.s) @ q_mat(self.r).T + self.t

    def __mul__(self, o):
        """self . o (apply `o` first); exact for uniform scale, like Glb.kt."""
        return Trs(self.apply(o.t), q_norm(q_mul(self.r, o.r)), self.s * o.s)

    def matrix(self):
        """The rigid part (rotation, translation) as 4 x 4."""
        m = np.eye(4)
        m[:3, :3] = q_mat(self.r)
        m[:3, 3] = self.t
        return m


# ---- GLB ----

class Glb:
    def __init__(self, path):
        data = open(path, "rb").read()
        magic, version, _ = struct.unpack_from("<III", data, 0)
        assert magic == 0x46546C67 and version == 2, "not a glTF 2 binary"
        self.json, self.bin = None, b""
        off = 12
        while off + 8 <= len(data):
            n, kind = struct.unpack_from("<II", data, off)
            if kind == 0x4E4F534A:
                self.json = json.loads(data[off + 8:off + 8 + n].decode("utf-8"))
            elif kind == 0x004E4942:
                self.bin = data[off + 8:off + 8 + n]
            off += 8 + n
        assert self.json is not None, "GLB has no JSON chunk"
        assert not self.json.get("extensionsRequired"), "use the unpacked walkthrough.glb"
        self.nodes = self.json.get("nodes", [])
        self.names = [n.get("name", f"node_{i}") for i, n in enumerate(self.nodes)]
        self.parent = [-1] * len(self.nodes)
        for i, n in enumerate(self.nodes):
            assert "matrix" not in n, "node matrices are not supported (the pipeline writes TRS)"
            for c in n.get("children", []):
                self.parent[c] = i
        self.rest = [Trs(n.get("translation", (0, 0, 0)), q_norm(n.get("rotation", (0, 0, 0, 1))), n.get("scale", (1, 1, 1)))
                     for n in self.nodes]

    def node(self, name):
        return self.names.index(name) if name in self.names else None

    def accessor(self, i):
        """Accessor `i` as a (count, components) float array (normalized integers scaled, like Glb.readFloats)."""
        a = self.json["accessors"][i]
        assert "sparse" not in a, "sparse accessors are not supported"
        bv = self.json["bufferViews"][a["bufferView"]]
        assert bv.get("buffer", 0) == 0, "only the GLB BIN buffer is supported"
        n, ct, count = _COMPS[a["type"]], a["componentType"], a["count"]
        dt = np.dtype(_DTYPE[ct])
        offset = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = bv.get("byteStride", 0) or n * dt.itemsize
        raw = np.frombuffer(self.bin, np.uint8, count=(count - 1) * stride + n * dt.itemsize, offset=offset) if count else np.zeros(0, np.uint8)
        idx = (np.arange(count)[:, None] * stride + np.arange(n * dt.itemsize)[None]).reshape(-1)
        out = raw[idx].view(dt).reshape(count, n).astype(np.float64)
        if a.get("normalized"):
            out = {5120: lambda v: np.maximum(v / 127.0, -1), 5121: lambda v: v / 255.0,
                   5122: lambda v: np.maximum(v / 32767.0, -1), 5123: lambda v: v / 65535.0}.get(ct, lambda v: v)(out)
        return out

    def positions(self, mesh):
        """Every POSITION of the mesh's triangle primitives, (n, 3), in the mesh's own frame."""
        prims = [p for p in self.json["meshes"][mesh].get("primitives", []) if p.get("mode", 4) == 4]
        return np.vstack([self.accessor(p["attributes"]["POSITION"]) for p in prims]) if prims else np.zeros((0, 3))

    def animation(self, name):
        for a in self.json.get("animations", []):
            if a.get("name", "") == name:
                chans = []
                for c in a.get("channels", []):
                    tgt = c.get("target") or {}
                    if tgt.get("node") is None or tgt.get("path") in (None, "weights"):
                        continue
                    s = a["samplers"][c["sampler"]]
                    chans.append({"node": tgt["node"], "path": tgt["path"], "interp": s.get("interpolation", "LINEAR"),
                                  "times": self.accessor(s["input"]).reshape(-1).astype(np.float32),
                                  "values": self.accessor(s["output"]).reshape(-1),
                                  "width": 4 if tgt["path"] == "rotation" else 3})
                return chans
        return None

    @staticmethod
    def duration(clip):
        return max((float(c["times"][-1]) for c in clip if len(c["times"])), default=0.0) if clip else 0.0

    @staticmethod
    def sample(c, t):
        """Channel value at time `t`, clamped to the keys (GlbChannel.sample; times compared as 32-bit floats)."""
        times, w, n = c["times"], c["width"], len(c["times"])
        if n == 0:
            return np.zeros(w)
        cubic = c["interp"] == "CUBICSPLINE"

        def key(i):
            off = i * (w * 3 if cubic else w) + (w if cubic else 0)
            return c["values"][off:off + w]

        t = np.float32(t)
        if t <= times[0]:
            return key(0)
        if t >= times[n - 1]:
            return key(n - 1)
        i = 0
        while i < n - 2 and times[i + 1] <= t:
            i += 1
        a = key(i)
        if c["interp"] == "STEP":
            return a
        b = key(i + 1)
        k = float(np.clip((t - times[i]) / (times[i + 1] - times[i]), 0, 1))
        return q_nlerp(a, b, k) if c["path"] == "rotation" else a + (b - a) * k

    def locals_at(self, clip, t):
        """Every node's local TRS with `clip` applied at time `t` (no clip = rest pose)."""
        out = [Trs(r.t, r.r, r.s) for r in self.rest]
        for c in clip or []:
            v = self.sample(c, t)
            if c["path"] == "translation":
                out[c["node"]].t = np.array(v, float)
            elif c["path"] == "rotation":
                out[c["node"]].r = q_norm(v)
            elif c["path"] == "scale":
                out[c["node"]].s = np.array(v, float)
        return out

    def relative_to(self, root, node, locals_):
        """root <- node; None when `node` is not under `root`."""
        acc, cur = Trs(), node
        while cur != root:
            acc = locals_[cur] * acc
            cur = self.parent[cur]
            if cur < 0:
                return None
        return acc

    def descendants(self, root):
        out = []
        for c in self.nodes[root].get("children", []):
            out.append(c)
            out.extend(self.descendants(c))
        return out


def _blender_euler_xyz_to_gltf(deg):
    """Guide.blenderEulerXyzToGltf: only used when the GLB has no walkthrough window for a step."""
    r = [np.radians(deg[i]) if i < len(deg) else 0.0 for i in range(3)]

    def aa(axis, a):
        return np.array([*(np.array(axis, float) * np.sin(a / 2)), np.cos(a / 2)])

    qb = q_norm(q_mul(q_mul(aa((0, 0, 1), r[2]), aa((0, 1, 0), r[1])), aa((1, 0, 0), r[0])))
    return q_norm([qb[0], qb[2], -qb[1], qb[3]])


def _guide(manifest, glb):
    """Guide.build: part vertices at their final place (ASSEMBLY frame) and each step's TABLE <- ASSEMBLY."""
    root = glb.node(manifest.get("rootNode", "assembly"))
    assert root is not None, "GLB has no root node"
    clip = glb.animation((manifest.get("walkthrough") or {}).get("clip", "walkthrough"))
    final = glb.locals_at(clip, glb.duration(clip))
    parts = {}
    for i in glb.descendants(root):
        name, mesh = glb.names[i], glb.nodes[i].get("mesh")
        if mesh is None or not (name.startswith("part_") or name.startswith("hw_")):
            continue
        parts[name] = glb.relative_to(root, i, final).apply(glb.positions(mesh))
    poses, last = {}, Trs()
    for s in manifest.get("steps", []):
        w = s.get("walkthrough")
        if clip is not None and w is not None:
            loc = glb.locals_at(clip, w.get("end_s", 0.0))[root]
            last = Trs(loc.t, loc.r)
        elif any(v != 0 for v in s.get("assemblyRotationDeg", [0, 0, 0])):
            last = Trs((0, 0, 0), _blender_euler_xyz_to_gltf(s["assemblyRotationDeg"]))
        poses[s["n"]] = last
    return parts, poses


# TABLE (guide step table, Y-up) <- FOOTPRINT rotation: columns = the footprint axes in the table frame
_R_LONG_X = np.array([[1.0, 0, 0], [0, 0, 1], [0, -1, 0]])     # X_fp = +X, Y_fp = -Z, Z_fp = +Y
_R_LONG_Z = np.array([[0.0, 1, 0], [0, 0, 1], [1, 0, 0]])      # X_fp = +Z, Y_fp = +X, Z_fp = +Y


def _footprint(nodes, parts, T_ta):
    P = np.vstack([parts[n] for n in nodes]) @ T_ta[:3, :3].T + T_ta[:3, 3]      # step TABLE frame
    lo, hi = P.min(0), P.max(0)
    ex, ez = hi[0] - lo[0], hi[2] - lo[2]
    long_x = ex >= ez
    T_tf = np.eye(4)
    T_tf[:3, :3] = _R_LONG_X if long_x else _R_LONG_Z
    T_tf[:3, 3] = [(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2]
    R = T_ta[:3, :3]
    return {
        "length_m": float(max(ex, ez)), "width_m": float(min(ex, ez)), "height_m": float(hi[1] - lo[1]),
        "long_axis": "x" if long_x else "z",
        "nodes": list(nodes),
        "assembly_from_footprint": (np.linalg.inv(T_ta) @ T_tf).tolist(),          # 4 x 4, row-major
        "table_from_footprint": T_tf.tolist(),
        "table_from_assembly": T_ta.tolist(),
        "box_table_m": [float(v) for v in (lo[0], hi[0], lo[1], hi[1], lo[2], hi[2])],
        # a quarter/half turn: every entry of the rotation is -1, 0 or 1 -> the box above is the oriented rectangle
        "axis_aligned": bool(np.all(np.minimum(np.abs(R), np.abs(np.abs(R) - 1)) < AXIS_EPS)),
    }


def _target(base, done, parts, T_ta):
    """The flat base part the detector can find at this step (see the module text), or None."""
    if base is None or not base["axis_aligned"]:
        return None
    x0, x1, y0, _, z0, z1 = base["box_table_m"]
    for n in base["nodes"]:
        P = parts[n] @ T_ta[:3, :3].T + T_ta[:3, 3]
        lo, hi = P.min(0), P.max(0)
        top = float(hi[1] - y0)
        if (abs(lo[1] - y0) <= SLAB_REST_M and top <= SLAB_MAX_M
                and abs((hi[0] - lo[0]) - (x1 - x0)) <= SLAB_SPAN_M and abs((hi[2] - lo[2]) - (z1 - z0)) <= SLAB_SPAN_M):
            # sizes to 0.1 mm: the drawer front comes out as exactly 0.380 x 0.130 x 0.016, today's numbers
            return {"length_m": round(base["length_m"], 4), "width_m": round(base["width_m"], 4), "top_m": round(top, 4), "slab": n,
                    "fill_hull": bool(done is not None and done["height_m"] > top + SLAB_REST_M),
                    "assembly_from_footprint": base["assembly_from_footprint"]}
    return None


def step_footprints(manifest_path, glb_path):
    """{step n: {"base": {...}, "done": {...}, "target": {...}}}; a variant is None when the step shows no part
    with geometry, `target` is None when the step has no flat base part (tracker off)."""
    manifest = json.load(open(manifest_path))
    parts, poses = _guide(manifest, Glb(glb_path))
    out = {}
    for s in manifest.get("steps", []):
        visible = [n for n in s.get("visible", []) if n.startswith("part_") and n in parts and len(parts[n])]
        base = [n for n in visible if n not in s.get("moving", [])] or visible
        T_ta = poses[s["n"]].matrix()
        out[s["n"]] = {"base": _footprint(base, parts, T_ta) if base else None,
                       "done": _footprint(visible, parts, T_ta) if visible else None}
        out[s["n"]]["target"] = _target(out[s["n"]]["base"], out[s["n"]]["done"], parts, T_ta)
    return out


if __name__ == "__main__":
    fp = step_footprints(sys.argv[1], sys.argv[2])
    print("step variant  length  width  height (mm)  long  aligned  parts")
    for n, d in fp.items():
        for v in ("base", "done"):
            f = d[v]
            if f is None:
                print(f"{n:4} {v:7}  no parts")
                continue
            print(f"{n:4} {v:7}  {f['length_m'] * 1000:6.1f} {f['width_m'] * 1000:6.1f} {f['height_m'] * 1000:7.1f}"
                  f"      {f['long_axis']:4}  {str(f['axis_aligned']):7}  {' '.join(x.removeprefix('part_') for x in f['nodes'])}")
    print("step  detector target (mm)        hull   slab")
    for n, d in fp.items():
        t = d["target"]
        print(f"{n:4}  " + ("off (no flat base part)" if t is None else
                            f"{t['length_m'] * 1000:6.1f} x {t['width_m'] * 1000:5.1f} x {t['top_m'] * 1000:4.1f}   {str(t['fill_hull']):5}  {t['slab']}"))
    if len(sys.argv) > 3:
        json.dump({"generator": "tools/markerless/footprint.py", "steps": {str(n): d for n, d in fp.items()}},
                  open(sys.argv[3], "w"), indent=1)
        print("wrote", sys.argv[3])
