"""AR models for iPhone and iPad: one USDZ per step (pipeline/blender/export_usdz.py).

    python -m pipeline.ar <job_dir>

Needs the job's scene.blend and manifest.json. Adds `ar` to each step of the manifest and `arFinished` to it.
Never fails a build: without Blender, or if the export breaks, the guide simply has no AR models.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

BLENDER = os.environ.get("BLENDER_BIN") or shutil.which("blender") or "/Applications/Blender.app/Contents/MacOS/Blender"


def build(job: Path) -> dict:
    job = Path(job)
    if not (job / "scene.blend").is_file() or not (job / "manifest.json").is_file():
        return {"usdz": [], "reason": "no scene.blend or manifest.json"}
    script = Path(__file__).parent / "blender" / "export_usdz.py"
    try:
        r = subprocess.run([BLENDER, "-b", str(job / "scene.blend"), "-P", str(script), "--", str(job)],
                           capture_output=True, text=True, timeout=300)
    except (OSError, subprocess.TimeoutExpired) as e:
        return {"usdz": [], "reason": str(e)[:300]}
    res = [line for line in r.stdout.splitlines() if line.startswith("RESULT ")]
    if r.returncode or not res:
        return {"usdz": [], "reason": (r.stdout + r.stderr)[-500:].strip()}
    return json.loads(res[-1][7:])


if __name__ == "__main__":
    out = build(Path(sys.argv[1]))
    print("RESULT " + json.dumps(out))
    sys.exit(0 if out.get("usdz") else 1)
