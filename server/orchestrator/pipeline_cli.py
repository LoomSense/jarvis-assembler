"""Stage steps that run in their own process, so a crash or a hang cannot take the server down.

    python -m server.orchestrator.pipeline_cli ingest <job_dir>
    python -m server.orchestrator.pipeline_cli place  <job_dir>
    python -m server.orchestrator.pipeline_cli export <job_dir> <job_id>

Prints `EVENT {...}` lines for the feed and one `RESULT {...}` line (see runner.py).
"""
from __future__ import annotations

import json
import os
import struct
import sys
from pathlib import Path

MAX_PAGES = 80
RASTER_DPI = 200          # PLAN §5.1


def event(type: str, text: str, **fields) -> None:
    print("EVENT " + json.dumps(dict(type=type, text=text, **fields), ensure_ascii=False), flush=True)


def result(**fields) -> None:
    print("RESULT " + json.dumps(fields, ensure_ascii=False), flush=True)


def ingest(job: Path) -> int:
    """Rasterise every guide page at 200 dpi and save its native text beside it."""
    import fitz  # PyMuPDF

    pages_dir = job / "pages"
    pages_dir.mkdir(exist_ok=True)
    doc = fitz.open(job / "guide.pdf")
    if doc.page_count == 0:
        print("the PDF has no pages")
        return 1
    if doc.page_count > MAX_PAGES:
        print(f"the PDF has {doc.page_count} pages; the limit is {MAX_PAGES}")
        return 1
    index = []
    for i, page in enumerate(doc, start=1):
        name = f"page-{i:03d}"
        pix = page.get_pixmap(dpi=RASTER_DPI, alpha=False)
        pix.save(pages_dir / f"{name}.png")
        text = page.get_text()
        (pages_dir / f"{name}.txt").write_text(text, encoding="utf-8")
        index.append({"n": i, "png": f"pages/{name}.png", "text": f"pages/{name}.txt", "width": pix.width,
                      "height": pix.height, "width_pt": page.rect.width, "height_pt": page.rect.height,
                      "text_chars": len(text.strip())})
        if i == 1 or i % 5 == 0 or i == doc.page_count:
            event("progress", f"Read page {i} of {doc.page_count}")
    (pages_dir / "index.json").write_text(json.dumps({"dpi": RASTER_DPI, "pages": index}, indent=1))
    result(pages=len(index))
    return 0


def place(job: Path) -> int:
    """Solve every piece's position from the plan's mates, then prove the fit (same checks as pipeline.run)."""
    from pipeline.assemble import collide, placement

    plan = json.loads((job / "plan.approved.json").read_text())
    parts_dir = job / "parts"
    placed = placement.solve(plan, parts_dir)
    (job / "placements.json").write_text(json.dumps(placed, indent=1))
    problems = list(placed["errors"])
    for c in collide.check(parts_dir, placed):
        problems.append(f"{c['a']} and {c['b']} overlap by {c['overlap_mm3']} mm3")
    for kind, items, key in (("hardware", plan["hardware"], "code"), ("part", plan["parts"], "id")):
        for it in items:
            n = sum(1 for inst in placed["instances"] if inst.split("#")[0] == it[key])
            if n != it["count"]:
                problems.append(f"{kind} {it[key]}: the guide lists {it['count']}, the steps use {n}")
    (job / "problems.json").write_text(json.dumps(problems, indent=1))
    if problems:
        print("The parts do not fit:\n  " + "\n  ".join(problems))
        return 1
    worst = max((v["residual_mm"] for v in placed["instances"].values()), default=0.0)
    event("thought", f"Placed {len(placed['instances'])} pieces; the worst mate is off by {worst:.2f} mm")
    result(pieces=len(placed["instances"]), worst_residual_mm=worst)
    return 0


def glb_json(path: Path) -> dict:
    with open(path, "rb") as f:
        magic, _version, _length = struct.unpack("<4sII", f.read(12))
        if magic != b"glTF":
            raise ValueError("not a GLB file")
        chunk_len, chunk_type = struct.unpack("<I4s", f.read(8))
        if chunk_type != b"JSON":
            raise ValueError("the GLB has no JSON chunk")
        return json.loads(f.read(chunk_len))


def export(job: Path, job_id: str) -> int:
    """Pack the GLB, cut the guide crops, check the GLB against the manifest contract (PLAN §4.3) and
    stamp the manifest with the job id."""
    from pipeline import crops, pack

    packed = pack.build(job)                             # optional: skipped when gltfpack is not installed
    if packed.get("packed"):
        event("thought", f"Compressed the model from {packed['from_bytes'] / 1e6:.1f} MB to {packed['bytes'] / 1e6:.2f} MB")
    plan = json.loads((job / "plan.approved.json").read_text())
    plan["guide"] = str(job / "guide.pdf")               # crops come from the uploaded guide, wherever the plan points
    (job / "logs").mkdir(exist_ok=True)
    plan_for_crops = job / "logs" / "plan.crops.json"
    plan_for_crops.write_text(json.dumps(plan))
    cut = crops.build(plan_for_crops, job)
    if cut.get("auto"):
        event("thought", f"Cut {cut['crops']} step drawings from the guide; {cut['auto']} by page layout, "
                         "not from a box in the plan, so check them by eye.", level="warn")

    checked = {}
    if os.environ.get("GEMINI_API_KEY"):                 # step renders vs the guide's drawings (PLAN §5.5)
        from pipeline.check import score

        def report(step, res):
            if "score" in res:
                verdict = "matches the guide's drawing" if res["pass"] else "may differ from the guide's drawing"
                event("image", f"Step {step['n']} {verdict}: {round(res['score'] * 100)}% alike", stepN=step["n"],
                      level="success" if res["pass"] else "warn", image_url=f"/files/{job_id}/renders/step_{step['n']:02d}.png",
                      score=res["score"])
        try:
            checked = score.build(job, report)
        except Exception as e:                           # noqa: BLE001 - the check is advice, never a failure
            event("progress", f"The drawing check did not run: {type(e).__name__}", level="warn")

    glb, manifest_path = job / "walkthrough.glb", job / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    gltf = glb_json(glb)
    clips = [a.get("name") for a in gltf.get("animations", [])]
    nodes = {n.get("name") for n in gltf.get("nodes", [])}
    problems = []
    if not clips or clips[0] != "walkthrough":
        problems.append(f"the first animation must be `walkthrough`, found {clips[:1]}")
    for step in manifest["steps"]:
        if step.get("clip") and step["clip"] not in clips:
            problems.append(f"step {step['n']}: clip `{step['clip']}` is missing from the GLB")
        missing = [n for n in step.get("visible", []) if n not in nodes]
        if missing:
            problems.append(f"step {step['n']}: nodes missing from the GLB: {missing[:5]}")
    if problems:
        print("The GLB does not match the manifest:\n  " + "\n  ".join(problems))
        return 1
    size = glb.stat().st_size
    if size > 5 * 1024 * 1024:
        event("progress", f"The GLB is {size / 1e6:.1f} MB, over the 5 MB budget", level="warn")
    manifest["jobId"] = job_id
    manifest["glb"] = "walkthrough.glb"
    tmp = manifest_path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(manifest, ensure_ascii=False, indent=1))
    tmp.replace(manifest_path)
    for step in manifest["steps"]:
        render = f"renders/step_{step['n']:02d}.png"
        event("step_ready", step.get("title") or f"Step {step['n']}", stepN=step["n"],
              image_url=f"/files/{job_id}/{render}" if (job / render).is_file() else None)
    result(animations=len(clips), steps=len(manifest["steps"]), glb_bytes=size, title=manifest.get("title"),
           packed_glb_bytes=packed.get("bytes"), step_crops=cut.get("crops", 0), drawing_check=checked or None)
    return 0


if __name__ == "__main__":
    command, job_dir = sys.argv[1], Path(sys.argv[2])
    if command == "ingest":
        sys.exit(ingest(job_dir))
    if command == "place":
        sys.exit(place(job_dir))
    if command == "export":
        sys.exit(export(job_dir, sys.argv[3]))
    sys.exit(f"unknown command `{command}`")
