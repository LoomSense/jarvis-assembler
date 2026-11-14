"""The server's `parts` stage hook. The build agent (pipeline/agent/extract.py) already wrote and checked
every part script together with the plan, so there is nothing left to generate here: this only says so.
If a person changed the plan at approval so that it needs a script that does not exist, the stage after
this one reports it."""
from __future__ import annotations

import json
import os
from pathlib import Path

if os.environ.get("AGJ_BUILD_AGENT") == "claude":
    async def build_parts(job_dir: Path, emit) -> None:
        job_dir = Path(job_dir)
        plan = json.loads((job_dir / "plan.approved.json").read_text())
        scripts = [it["script"] for it in plan["parts"] + plan["hardware"] if it.get("script")]
        have = sum((job_dir / s).is_file() for s in scripts)
        emit("thought", f"{have} of {len(scripts)} part scripts are already written and checked by the build agent.")
