"""Parts made from the guide's drawing by an image-to-3D model on fal, for shapes the CadQuery helpers cannot draw
(a moulded plastic mechanism, a shaped shell, a decorative foot).

A plan item uses it instead of a `script`:

    {"id": "tilt_mechanism", ..., "generate": {
        "page": 6, "box_2d": [ymin, xmin, ymax, xmax],     # the clearest drawing of the part, 0-1000 on the page
        "size_mm": [x, y, z],                              # its size in the part frame
        "rotate_deg": [rx, ry, rz],                        # optional: turn the model before it is fitted
        "frames": {"socket": {"origin": [...], "axis": [...]}, ...}}}

The part frame is the fitted box: x and y centred on 0, z from 0 to size z. Frames are given in that frame, because
a generated mesh has no holes to find them on. The model is fal-ai/hunyuan-3d/v3.1/pro/image-to-3d (geometry only,
$0.375 per part); AGJ_GEN3D_MODEL=meshy uses fal-ai/meshy/v7.1/image-to-3d ($0.80). Results are cached per crop.

Without FAL_KEY, or if the call fails, the part becomes a rounded box of the same size and frames, and its info
says why, so a build never stops on it. Generated parts are skipped by the overlap check (no exact solid).
"""
from __future__ import annotations

import hashlib
import json
import os
import time
import urllib.request
from pathlib import Path

import numpy as np
import trimesh
from PIL import Image

MODELS = {
    "hunyuan": ("fal-ai/hunyuan-3d/v3.1/pro/image-to-3d", lambda url: {"input_image_url": url, "generate_type": "Geometry"}),
    "meshy": ("fal-ai/meshy/v7.1/image-to-3d", lambda url: {"image_url": url, "should_texture": False, "target_polycount": 30000}),
}
ROOT = Path(__file__).resolve().parents[2]
MAX_FACES = 20000


def _fal_key() -> str:
    key = os.environ.get("FAL_KEY", "")
    if not key:
        env = ROOT / ".env"
        for line in env.read_text().splitlines() if env.exists() else []:
            if line.startswith("FAL_KEY="):
                key = line.split("=", 1)[1].strip()
    return key


def page_image(plan_path: Path, plan: dict, page: int) -> Image.Image:
    """The guide page as an image: from the job's or the agent's page rasters, else rendered from the PDF."""
    d = plan_path.parent
    for c in (d / "pages" / f"page-{page:03d}.png", d.parent / "guide" / f"page-{page}.png", d / "guide" / f"page-{page}.png",
              d.parent / "pages" / f"page-{page:03d}.png"):
        if c.is_file():
            return Image.open(c).convert("RGB")
    for pdf in (d / "guide.pdf", d.parent / "guide.pdf", (ROOT / plan["guide"]) if plan.get("guide") else None):
        if pdf and Path(pdf).is_file():
            import pymupdf
            pix = pymupdf.open(pdf)[page - 1].get_pixmap(dpi=200, alpha=False)
            return Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
    raise FileNotFoundError(f"no image of guide page {page}")


def crop(im: Image.Image, box) -> Image.Image:
    y0, x0, y1, x1 = box
    W, H = im.size
    pad = 0.02
    c = im.crop((max(0, int((x0 / 1000 - pad) * W)), max(0, int((y0 / 1000 - pad) * H)),
                 min(W, int((x1 / 1000 + pad) * W)), min(H, int((y1 / 1000 + pad) * H))))
    side = max(c.size) + 40                                   # square, white margin: what image-to-3D models expect
    sq = Image.new("RGB", (side, side), "white")
    sq.paste(c, ((side - c.width) // 2, (side - c.height) // 2))
    return sq.resize((1024, 1024), Image.LANCZOS) if side < 1024 else sq


def call_fal(png: Path, model: str) -> bytes:
    os.environ.setdefault("FAL_KEY", _fal_key())             # fal_client reads only the environment
    import fal_client
    endpoint, args = MODELS[model]
    try:
        url = fal_client.SyncClient().upload_file(str(png))
    except Exception:                                         # noqa: BLE001 - fall back to a data URI
        url = fal_client.encode_file(str(png))
    result = fal_client.subscribe(endpoint, arguments=args(url), with_logs=False)
    with urllib.request.urlopen(result["model_glb"]["url"], timeout=120) as r:
        return r.read()


def main_body(mesh: trimesh.Trimesh, keep=0.04) -> trimesh.Trimesh:
    """Drop loose bits (a screw or a callout the model copied from the drawing): keep only pieces with at least
    `keep` of the surface area, so they do not stretch the box the part is fitted into."""
    parts = mesh.split(only_watertight=False)
    if len(parts) < 2:
        return mesh
    total = sum(p.area for p in parts)
    big = [p for p in parts if p.area >= keep * total] or [max(parts, key=lambda p: p.area)]
    return trimesh.util.concatenate(big)


def fit(mesh: trimesh.Trimesh, size, rotate_deg=None) -> trimesh.Trimesh:
    """glTF is Y-up: turn it Z-up, apply the plan's turn, then scale and move it into the fitted box."""
    m = main_body(mesh)
    m.apply_transform(trimesh.transformations.rotation_matrix(np.pi / 2, [1, 0, 0]))
    if rotate_deg:
        for ang, ax in zip(rotate_deg, ([1, 0, 0], [0, 1, 0], [0, 0, 1])):
            if ang:
                m.apply_transform(trimesh.transformations.rotation_matrix(np.radians(ang), ax))
    lo, hi = m.bounds
    m.apply_translation(-lo)
    ext = np.maximum(hi - lo, 1e-6)
    m.vertices = m.vertices * (np.array(size, dtype=float) / ext)
    m.apply_translation([-size[0] / 2, -size[1] / 2, 0])
    return m


def placeholder(size) -> trimesh.Trimesh:
    from pipeline.cad.lib import rounded_box
    x, y, z = size
    s = rounded_box(-x / 2, -y / 2, 0, x, y, z, r_side=min(x, y) * 0.15, r_edge=min(x, y, z) * 0.1).val()
    v, f = s.tessellate(0.5, 0.3)
    return trimesh.Trimesh([(p.x, p.y, p.z) for p in v], f)


def build(plan_path: Path, plan: dict, item: dict, out: Path, kind: str = "part") -> dict:
    t0 = time.perf_counter()
    g, pid = item["generate"], item.get("id") or item["code"]
    size = [float(v) for v in g["size_mm"]]
    for name, f in g.get("frames", {}).items():
        if set(f) != {"origin", "axis"} or len(f["origin"]) != 3 or len(f["axis"]) != 3:
            raise ValueError(f"{pid}: bad frame `{name}`")
    model = os.environ.get("AGJ_GEN3D_MODEL", "hunyuan")
    status, mesh = {}, None
    try:
        png = out / f"{pid}.gen.png"
        crop(page_image(plan_path, plan, int(g["page"])), g["box_2d"]).save(png)
        digest = hashlib.sha256(png.read_bytes() + model.encode()).hexdigest()[:16]
        cached = out / f"{pid}.gen-{digest}.glb"
        if not cached.is_file():
            if not _fal_key():
                raise RuntimeError("FAL_KEY is not set")
            cached.write_bytes(call_fal(png, model))
        mesh = trimesh.load(cached, force="mesh")
        if len(mesh.faces) > MAX_FACES:                       # the model returns ~500k faces; the GLB budget is 150k in all
            try:
                mesh = mesh.simplify_quadric_decimation(face_count=MAX_FACES)
            except Exception:                                 # noqa: BLE001 - fast_simplification missing: keep it heavy
                pass
        status = {"generated": True, "model": MODELS[model][0], "glb": cached.name, "crop": png.name}
    except Exception as e:                                    # noqa: BLE001 - never stop a build on a generated part
        status = {"generated": False, "reason": f"{type(e).__name__}: {e}"[:300]}
    m = fit(mesh, size, g.get("rotate_deg")) if mesh is not None else placeholder(size)
    m.export(out / f"{pid}.stl")
    lo, hi = m.bounds
    info = {"id": pid, "kind": kind, "params": {}, "frames": g.get("frames", {}),
            "bbox_mm": {"min": lo.tolist(), "max": hi.tolist()},
            "volume_mm3": float(abs(m.volume)) if m.is_volume else float(np.prod(hi - lo)),
            "build_ms": round((time.perf_counter() - t0) * 1000, 1), "source": "generate", **status}
    (out / f"{pid}.mates.json").write_text(json.dumps(info, indent=1))
    return info
