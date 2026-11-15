"""Score every step's render against the guide's own drawing with Gemini Decision 1 (PLAN §5.5, step check).

    .venv/bin/python -m pipeline.check.score <job_dir>

For each step it sends two images, the guide's drawing (`crops/step-NN.png`) and our check render from the guide's
angle (`renders/step_NN.png`), and asks whether they show the same thing. Writes `check` on every manifest step:
    {"score": 0.86, "pass": true, "orientation": "same", "answers": {...}, "latency_ms": 412}
`score` is the mean of `same_state` and `same_parts`; `pass` needs score >= 0.6 and the same orientation.
Needs GEMINI_API_KEY. Steps without a crop or a render are skipped. Requests run 6 at a time.
"""
from __future__ import annotations

import json
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from pipeline.check import decisions

PASS_SCORE = 0.6
INTRO = ("Image 1 is a drawing from a furniture assembly guide: the state after one step. Image 2 is a 3D render of a "
         "computer model of the same step. Ignore drawing style, colours, shading, hands, arrows, magnified callouts, "
         "numbers and labels; judge only the furniture parts themselves.")
QUESTIONS = {
    "same_state": {"type": "noul", "instructions": "Do the two images show the same parts joined in the same arrangement?",
                   "criteria": {"true_description": "The same parts, in the same places relative to each other",
                                "false_description": "A part is missing, extra, or attached somewhere else"}},
    "same_parts": {"type": "noul", "instructions": "Does image 2 contain every main part visible in image 1 (ignore screws)?",
                   "criteria": {"true_description": "Every main part in the drawing is also in the render",
                                "false_description": "At least one main part of the drawing is not in the render"}},
    "orientation": {"type": "choice", "instructions": "How is the assembly in image 2 oriented compared with image 1?",
                    "criteria": {"same": "Same way up and facing the same way, perhaps seen from a slightly different angle",
                                 "upside_down": "Turned upside down",
                                 "turned_on_side": "Turned onto a side or an end",
                                 "mirrored_or_rotated": "Mirrored, or rotated about the vertical axis",
                                 "other": "Cannot tell, or the images show different things"}},
}


def score_step(job: Path, step: dict) -> dict | None:
    crop = job / (step.get("crop") or "")
    render = job / f"renders/step_{step['n']:02d}.png"
    if not step.get("crop") or not crop.is_file() or not render.is_file():
        return None
    out = decisions.ask([INTRO, "Image 1:", crop, "Image 2:", render], QUESTIONS, resolution="medium", timeout=60)
    a = out.get("answers", {})
    same = float(a.get("same_state", {}).get("noul", 0.0))
    parts = float(a.get("same_parts", {}).get("noul", 0.0))
    orient = a.get("orientation", {}).get("choice", "other")
    score = round((same + parts) / 2, 3)
    return {"score": score, "pass": score >= PASS_SCORE and orient == "same", "orientation": orient,
            "answers": {"same_state": round(same, 3), "same_parts": round(parts, 3),
                        "orientation_confidence": round(float(a.get("orientation", {}).get("confidence", 0.0)), 3)},
            "latency_ms": out.get("latency_ms")}


def build(job: Path, on_step=None) -> dict:
    path = job / "manifest.json"
    manifest = json.loads(path.read_text())
    steps = manifest["steps"]

    def one(step):
        try:
            return step, score_step(job, step)
        except Exception as e:                                    # noqa: BLE001 - a failed check never fails a job
            return step, {"error": f"{type(e).__name__}: {e}"[:300]}

    results = {}
    with ThreadPoolExecutor(6) as pool:
        for step, res in pool.map(one, steps):
            if res is None:
                continue
            results[step["n"]] = res
            if on_step:
                on_step(step, res)
    for step in steps:
        if step["n"] in results:
            step["check"] = results[step["n"]]
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(manifest, indent=1, ensure_ascii=False))
    tmp.replace(path)
    scored = [r for r in results.values() if "score" in r]
    return {"scored": len(scored), "passed": sum(r["pass"] for r in scored),
            "mean_score": round(sum(r["score"] for r in scored) / len(scored), 3) if scored else None,
            "errors": [r["error"] for r in results.values() if "error" in r][:3]}


if __name__ == "__main__":
    job = Path(sys.argv[1])
    res = build(job, lambda s, r: print(f"step {s['n']:2d}: {r}"))
    print("RESULT " + json.dumps(res))
