"""Build and send one Decisions request for a live frame (LIV-02, PLAN §6.1).

    from pipeline.perception import request
    out = request.ask(job_dir, step_n, frame)        # frame: path to a JPEG or PNG
    out["answers"]["step_complete"]["noul"], out["latency_ms"]

    .venv/bin/python -m pipeline.perception.request <job_dir> <step_n> <frame.jpg>

The input is four blocks, in this order: the step header, the live frame, a line explaining the sheet,
the reference sheet. A step whose hardware does not show on the sheet has two more: a line explaining the
close-up, and the close-up (`pipeline.perception.details`). Both images go at `medium` resolution (560 tokens each) and as JPEG: a PNG frame
and sheet are 1.1 MB per request and cost about a second of upload. Run pipeline.perception.questions first.

`frame` may be a path or JPEG bytes (what the camera socket delivers).
"""
from __future__ import annotations

import io
import json
import sys
from functools import lru_cache
from pathlib import Path

from PIL import Image

from pipeline.check import decisions


@lru_cache(maxsize=64)
def step_pack(job: str, n: int) -> dict:
    return json.loads((Path(job) / "refs" / f"step-{n:02d}-questions.json").read_text())


def jpeg(path: Path) -> bytes:
    if path.suffix.lower() in (".jpg", ".jpeg"):
        return path.read_bytes()
    buf = io.BytesIO()
    Image.open(path).convert("RGB").save(buf, "JPEG", quality=80)
    return buf.getvalue()


@lru_cache(maxsize=64)
def sheet_jpeg(job: str, rel: str) -> bytes:
    return jpeg(Path(job) / rel)


def build(job: Path, n: int, frame: Path | bytes) -> tuple[list, dict]:
    pack = step_pack(str(job), n)
    live = frame if isinstance(frame, bytes) else jpeg(Path(frame))
    inputs = [pack["header"], ("image/jpeg", live), pack["sheet_note"], ("image/jpeg", sheet_jpeg(str(job), pack["sheet"]))]
    return inputs + detail_blocks(job, pack), pack["questions"]


def detail_blocks(job: Path, pack: dict) -> list:
    """Image 3, for a step whose hardware does not show on the sheet: the close-up `pipeline.perception.details` made."""
    if not pack.get("detail") or not pack.get("detail_note") or not (Path(job) / pack["detail"]).is_file():
        return []
    return [pack["detail_note"], ("image/jpeg", sheet_jpeg(str(job), pack["detail"]))]


def ask(job: Path, n: int, frame: Path | bytes, timeout: float = 30) -> dict:
    inputs, questions = build(job, n, frame)
    return decisions.ask(inputs, questions, resolution="medium", timeout=timeout)


if __name__ == "__main__":
    out = ask(Path(sys.argv[1]), int(sys.argv[2]), Path(sys.argv[3]))
    for key, a in out.get("answers", {}).items():
        print(f"{key:22s} {a.get('choice', '')!s:18s} {a.get('noul', a.get('confidence')):.2f}")
    print("RESULT " + json.dumps({"latency_ms": out["latency_ms"], "usage": out.get("usage")}))
