"""Compress a job's walkthrough.glb with gltfpack (CAD-06, PLAN §5.4).

    .venv/bin/python -m pipeline.pack jobs/knagglig

Writes <job>/walkthrough.packed.glb (meshopt compression, node names and extras kept) and sets `glbPacked`
in manifest.json. `glb` stays the uncompressed file, which any viewer can open. A three.js viewer needs
`loader.setMeshoptDecoder(MeshoptDecoder)` to open the packed one.

gltfpack is looked for in GLTFPACK_BIN, then on PATH, then in eval/glb_check/node_modules (npm install
there). If it is not found, nothing is written and the job is still good.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

LOCAL = Path(__file__).resolve().parent.parent / "eval" / "glb_check" / "node_modules" / ".bin" / "gltfpack"
FLAGS = ["-cc", "-kn", "-ke"]        # compress; keep named nodes (the manifest refers to them) and extras


def find() -> str | None:
    return os.environ.get("GLTFPACK_BIN") or shutil.which("gltfpack") or (str(LOCAL) if LOCAL.exists() else None)


def build(job: Path) -> dict:
    exe = find()
    if not exe:
        return {"packed": False, "reason": "gltfpack not found"}
    manifest = json.loads((job / "manifest.json").read_text())
    src, dst = job / manifest["glb"], job / "walkthrough.packed.glb"
    r = subprocess.run([exe, "-i", str(src), "-o", str(dst), *FLAGS], capture_output=True, text=True, timeout=120)
    if r.returncode or not dst.exists():
        return {"packed": False, "reason": (r.stdout + r.stderr)[-500:].strip()}
    manifest["glbPacked"] = dst.name
    (job / "manifest.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False))
    return {"packed": True, "bytes": dst.stat().st_size, "from_bytes": src.stat().st_size}


if __name__ == "__main__":
    out = build(Path(sys.argv[1]))
    print("RESULT " + json.dumps(out))
    sys.exit(0 if out["packed"] else 1)
