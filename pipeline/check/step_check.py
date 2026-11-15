"""Check every built step against the guide's own drawing of it, for the build agent (PLAN §5.5 step check, D8).

    .venv/bin/python -m pipeline.check.step_check <plan.json> <out_dir> [N]

For each step (or step N) it sends two images, the guide's drawing and our check render (`renders/step_NN.png`), to a
verifier with a few questions: same main parts, joined in the same places, screws in the same places, how it lies,
and the main difference (rules in `verdict`). It prints one line per step, with the failing questions in
words the agent can act on, writes `<out_dir>/step_check.json` and exits 0 only if every checked step passes.

The guide's drawing is `<out_dir>/crops/step-NN.png` when the pipeline cut one; otherwise it is cut from the page the
step cites (`guide/page-N.png` next to the agent's job, or `pages/page-NNN.png`), as pipeline/crops.py would cut it,
and saved as `<out_dir>/check/step-NN-guide.png`.
A verdict is reused while neither image changes. Each new failing render of a step counts as one round; after the
first build and ROUNDS fixes the line says to flag the step and move on.

Verifier: AGJ_VERIFIER=decision (default, gemini-decision-1) or iris (Oumi's Iris: a seam only, not wired yet).
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image

from pipeline import crops
from pipeline.check import decisions
from pipeline.perception.questions import validate

VERSION = 2                 # bump when the questions change: cached verdicts are then asked again
PASS = 0.5                  # least probability for every yes/no question
SURE = 0.6                  # least confidence for a `main_difference` other than none to fail a step on its own
ROUNDS = 3                  # fix attempts per step (PLAN D8)
FLAG_PREFIX = "Drawing check:"
INTRO = ("Image 1 is a drawing from a furniture assembly guide: one step. Image 2 is a 3D render of a computer model of "
         "the same step, when it is done. In the drawing, arrows, hands, dashed lines and loose screws show parts on "
         "their way to where they go: count those parts as already in place. Small pictures in a corner (a turn arrow "
         "with a small box, a magnified callout) are hints, not the step. Ignore drawing style, colours, shading, "
         "numbers and labels, and that the render may be seen from a slightly different angle.")
NOUL = ("same_parts", "same_arrangement", "screws_placed")
FIX = {
    "same_parts": "a main part of the guide's drawing is missing from the render, or the render has one the drawing "
                  "does not: check `add` and `parts_added` of step {n}",
    "same_arrangement": "parts are joined in other places than in the drawing (another side, end or face): "
                        "check the mates of step {n}",
    "turned": "the assembly looks {pose} compared with the drawing: check `pose` and `assembly_rotation_deg` of step {n}",
    "screws_placed": "the screws go in at other places than in the drawing: check the screw mates and "
                     "`hardware_used` of step {n}",
    "other": "the render differs from the drawing in some other way: look at both images",
}
DIFFERENCES = {
    "none": "No real difference: the same parts in the same places, the same way up",
    "missing_or_extra_part": "A main part is missing from the render, or the render has an extra one",
    "attached_elsewhere": "A part is attached at another side, end or face than in the drawing",
    "turned_differently": "The whole assembly is upside down, on a side or on an end compared with the drawing",
    "screws_differ": "Screws go into other places, or a different number of places",
    "other": "Another difference, or the images cannot be compared",
}
DIFF_FIX = {"missing_or_extra_part": "same_parts", "attached_elsewhere": "same_arrangement", "turned_differently": "turned",
            "screws_differ": "screws_placed", "other": "other"}
POSES = {"same": "The same face is on top", "upside_down": "Upside down", "on_side_or_end": "Turned onto a side or an end",
         "cannot_tell": "Cannot tell"}
POSE_WORDS = {"upside_down": "upside down", "on_side_or_end": "turned onto a side or an end"}


def questions(step: dict) -> dict:
    """Measured on KNAGGLIG (8 right and 7 wrong renders, Sun 15 Nov): the yes/no answers alone hardly separate them;
    `pose` and `main_difference` named every wrong one and only one right one."""
    q = {
        "same_parts": {"type": "noul", "instructions": "Does image 2 have the same main parts as image 1, none missing "
                       "and none extra (ignore screws)?",
                       "criteria": {"true_description": "Every main part of the drawing is in the render, and no other",
                                    "false_description": "A main part of the drawing is missing from the render, or "
                                                         "the render has an extra one"}},
        "same_arrangement": {"type": "noul", "instructions": "Are the parts joined in the same places relative to each "
                             "other in both images?",
                             "criteria": {"true_description": "Each part touches the same parts at the same side, end "
                                                              "or face as in the drawing",
                                          "false_description": "A part is attached at another side, end or face"}},
    }
    if step.get("hardware_used"):
        q["screws_placed"] = {"type": "noul", "instructions": "Do the screws in image 2 go into the same places as in "
                              "image 1: the same part, the same side or end, the same number of places?",
                              "criteria": {"true_description": "The screws go in where the drawing shows them",
                                           "false_description": "The screws go in elsewhere, or into a different "
                                                                "number of places"}}
    q["pose"] = {"type": "choice", "instructions": "How does the assembly in image 2 lie compared with image 1? Judge "
                 "which face is on top, not which side faces the viewer.", "criteria": dict(POSES)}
    q["main_difference"] = {"type": "choice", "instructions": "What is the main difference between the furniture in "
                            "the two images?", "criteria": dict(DIFFERENCES)}
    return q


# ---- verifiers: (inputs, questions) -> {"answers": {...}, "latency_ms": int}, as the Decisions API answers ----

def _decision(inputs: list, qs: dict) -> dict:
    return decisions.ask(inputs, qs, resolution="medium", timeout=60)


def _iris(inputs: list, qs: dict) -> dict:
    """Oumi's Iris decision model. Its API is not known yet: this is where it plugs in."""
    if not (os.environ.get("OUMI_API_KEY") and os.environ.get("OUMI_IRIS_URL")):
        raise RuntimeError("AGJ_VERIFIER=iris, but Oumi's Iris is not configured: set OUMI_API_KEY and OUMI_IRIS_URL")
    raise NotImplementedError("the Iris verifier is not wired yet: add the call in pipeline/check/step_check.py _iris")


VERIFIERS = {"decision": _decision, "iris": _iris}


def verifier_name() -> str:
    name = os.environ.get("AGJ_VERIFIER", "decision").strip().lower() or "decision"
    if name not in VERIFIERS:
        raise RuntimeError(f"AGJ_VERIFIER={name!r}: use one of {', '.join(VERIFIERS)}")
    return name


# ---- images ----

def _jpeg(im: Image.Image) -> bytes:
    buf = io.BytesIO()
    im.convert("RGB").save(buf, "JPEG", quality=85)
    return buf.getvalue()


def _page_image(plan_path: Path, out: Path, page: int) -> Path | None:
    for p in (plan_path.parent.parent / "guide" / f"page-{page}.png", plan_path.parent / "pages" / f"page-{page:03d}.png",
              out / "pages" / f"page-{page:03d}.png", out.parent / "pages" / f"page-{page:03d}.png"):
        if p.is_file():
            return p
    return None


def drawing(plan_path: Path, plan: dict, step: dict, out: Path) -> tuple[Path | None, str]:
    """The guide's drawing of this step and where it came from: crop, page_box, page_auto, or (None, reason)."""
    crop = out / "crops" / f"step-{step['n']:02d}.png"
    if crop.is_file():
        return crop, "crop"
    page = step.get("cite", {}).get("page")
    src = _page_image(plan_path, out, page) if page else None
    if src is None:
        return None, f"no crop and no image of guide page {page}"
    im = Image.open(src)
    box, how = crops.step_boxes(im, [s for s in plan["steps"] if s.get("cite", {}).get("page") == page])[step["n"]]
    dest = out / "check" / f"step-{step['n']:02d}-guide.png"
    dest.parent.mkdir(parents=True, exist_ok=True)
    crops.cut(im, box).save(dest)
    return dest, f"page_{how}"


def _sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()[:16]


def flagged(plan: dict, n: int) -> bool:
    return any(f.get("ref") == f"step:{n}" and str(f.get("message", "")).startswith(FLAG_PREFIX) for f in plan.get("flags", []))


# ---- one step ----

def verdict(step: dict, out: dict) -> dict:
    """Answers -> pass, score (mean of the yes/no answers) and what failed, in words.

    A step fails on a yes/no answer below PASS; on `pose` turned while `main_difference` is not none; or on a
    `main_difference` other than none with confidence SURE or more."""
    a = out.get("answers", {})
    probs = {k: round(float(a[k].get("noul", 0.0)), 3) for k in NOUL if k in a}
    if not probs:
        raise RuntimeError(f"the verifier gave no yes/no answers: {str(a)[:200]}")
    pose, diff = a.get("pose", {}), a.get("main_difference", {})
    p, d, d_conf = pose.get("choice"), diff.get("choice"), float(diff.get("confidence", 0.0))
    failed = [k for k, v in probs.items() if v < PASS]
    if p in POSE_WORDS and d not in (None, "none"):
        failed.append("turned")
    if d in DIFF_FIX and d_conf >= SURE:
        failed.append(DIFF_FIX[d])
    failed = list(dict.fromkeys(failed))
    return {"pass": not failed, "score": round(sum(probs.values()) / len(probs), 3), "answers": probs,
            "pose": p, "pose_confidence": round(float(pose.get("confidence", 0.0)), 3),
            "main_difference": d, "main_difference_confidence": round(d_conf, 3), "failed": failed,
            "fix": [FIX[k].format(n=step["n"], pose=POSE_WORDS.get(p, "turned another way")) for k in failed],
            "latency_ms": out.get("latency_ms")}


def check_step(plan_path: Path, plan: dict, step: dict, out: Path, prev: dict | None, ask) -> dict:
    n = step["n"]
    render = out / "renders" / f"step_{n:02d}.png"
    if not render.is_file():
        return {"n": n, "skipped": f"no render {render.name}: run ./build first"}
    guide, source = drawing(plan_path, plan, step, out)
    if guide is None:
        return {"n": n, "skipped": source}
    sha = _sha(render)
    key = f"{VERSION}:{verifier_name()}:{sha}:{_sha(guide)}"
    base = {"n": n, "render": str(render), "drawing": str(guide), "drawing_source": source, "render_sha": sha, "key": key}
    if prev and prev.get("key") == key and "pass" in prev:
        res = {**prev, **base, "cached": True}
    else:
        qs = questions(step)
        bad = validate(qs)
        if bad:
            raise ValueError("; ".join(bad))
        inputs = [INTRO, "Image 1 (the guide's drawing):", ("image/jpeg", _jpeg(Image.open(guide))),
                  "Image 2 (the 3D render):", ("image/jpeg", _jpeg(Image.open(render)))]
        res = {**base, **verdict(step, ask(inputs, qs))}
    # a new failing render is one more fix round; the same render checked again is not
    rounds = (prev or {}).get("rounds", 0)
    if not res["pass"] and (not prev or prev.get("render_sha") != sha or not rounds):
        rounds += 1
    res["rounds"] = rounds
    res["flagged"] = flagged(plan, n)
    return res


def used_up(r: dict) -> bool:
    """`rounds` counts failing renders: the first build plus each fix. ROUNDS fixes are allowed."""
    return r.get("rounds", 0) > ROUNDS


def _line(r: dict, rel) -> str:
    n = r["n"]
    if "skipped" in r:
        return f"step {n:2d}: SKIP  {r['skipped']}"
    if "error" in r:
        return f"step {n:2d}: ERROR {r['error']}"
    if r["pass"]:
        return f"step {n:2d}: pass  {r['score']:.2f}"
    look = f"compare {rel(r['render'])} with {rel(r['drawing'])}"
    if r["flagged"]:
        tail = "flagged for a human: leave it"
    elif used_up(r):
        tail = (f"{ROUNDS} fixes tried: keep the best attempt, add the flag "
                f"{{\"ref\": \"step:{n}\", \"kind\": \"unclear_drawing\", \"message\": \"{FLAG_PREFIX} ...\"}} and move on")
    else:
        tail = f"fix {r['rounds']} of {ROUNDS}"
    return f"step {n:2d}: FAIL  {r['score']:.2f}  {'; '.join(r['fix'])}. {look} ({tail})"


def run(plan_path: Path, out: Path, only: int | None = None, ask=None) -> dict:
    """Check the steps, write <out>/step_check.json and return it."""
    plan_path, out = Path(plan_path), Path(out)
    plan = json.loads(plan_path.read_text())
    ask = ask or VERIFIERS[verifier_name()]
    path = out / "step_check.json"
    old = json.loads(path.read_text()) if path.is_file() else {}
    prev = {r["n"]: r for r in old.get("results", [])}
    steps = [s for s in plan["steps"] if only is None or s["n"] == only]
    if only is not None and not steps:
        raise ValueError(f"the plan has no step {only}")

    def one(step):
        try:
            return check_step(plan_path, plan, step, out, prev.get(step["n"]), ask)
        except Exception as e:                                    # noqa: BLE001 - one step's failure is reported, not raised
            return {"n": step["n"], "error": f"{type(e).__name__}: {e}"[:300]}

    t0 = time.perf_counter()
    with ThreadPoolExecutor(6) as pool:
        fresh = {r["n"]: r for r in pool.map(one, steps)}
    results = [fresh.get(s["n"]) or prev[s["n"]] for s in plan["steps"] if s["n"] in fresh or s["n"] in prev]
    scored = [r for r in results if "pass" in r]
    report = {"verifier": verifier_name(), "version": VERSION, "pass_at": PASS, "checked_at": round(time.time()),
              "wall_s": round(time.perf_counter() - t0, 2), "steps": len(plan["steps"]), "checked": len(scored),
              "passed": sum(r["pass"] for r in scored), "mean_score": round(sum(r["score"] for r in scored) / len(scored), 3) if scored else None,
              "failed": [r["n"] for r in scored if not r["pass"]], "errors": [r["n"] for r in results if "error" in r],
              "results": results}
    out.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(report, indent=1))
    tmp.replace(path)
    report["lines"] = [_line(fresh[s["n"]], _rel(plan_path)) for s in steps]
    return report


def _rel(plan_path: Path):
    base = plan_path.resolve().parent.parent

    def rel(p: str) -> str:
        try:
            return str(Path(p).resolve().relative_to(base))
        except ValueError:
            return p
    return rel


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3):
        print(__doc__.strip().splitlines()[2].strip())
        return 2
    only = int(argv[2]) if len(argv) == 3 else None
    report = run(Path(argv[0]), Path(argv[1]), only)
    for line in report.pop("lines"):
        print(line, flush=True)
    mine = [r for r in report["results"] if only is None or r["n"] == only]
    ok = bool(mine) and all(r.get("pass") for r in mine)
    left = [r["n"] for r in mine if "pass" in r and not r["pass"] and not r.get("flagged") and not used_up(r)]
    print("RESULT " + json.dumps({k: report[k] for k in ("verifier", "steps", "checked", "passed", "mean_score", "failed", "errors", "wall_s")}
                                 | {"to_fix": left}))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
