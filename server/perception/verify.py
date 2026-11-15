"""Is this step finished? One careful look at one photo, with the reasons.

    verify(job_dir, step_n, jpeg, lang)   one `gemini-4-hydrogen` vision call: the person's photo next to the
                                          render of the finished step (and of the step before it)

The live checks (decider.py) answer many small questions per frame in half a second and are good at "which part
is in the hand". On real phone pictures their `step_complete` answer stayed low (Sun 15 Nov, the drawer: above
0.5 on 5 % of 1,231 frames while every step was built), so nothing ever told the person a step was done. This is
the slower, deliberate check for that one question: asked when the person, or the voice coach, thinks the step
is finished. It answers what is done, what is still missing, and one sentence to say.
"""
from __future__ import annotations

import json
import logging
import tempfile
import time
from pathlib import Path

from pipeline.check import gemini

log = logging.getLogger("agj.perception")
MODEL = "gemini-4-hydrogen"          # PLAN §3. Do not substitute an older model
THINKING = "low"

PROMPT = """You check one step of a flat-pack furniture assembly from a phone photo.

Image 1 is the person's photo, taken just now.
Image 2 is a 3D render of the assembly when step {n} is finished.{before}
The renders are drawn from another viewpoint, in plain colours, without hands or a floor. Compare which pieces are joined, where, and which way each one faces, not how the picture looks.

Product: {product}
Its pieces, with their sizes. Pieces of the same colour are told apart by size and thickness:
{pieces}

Step {n} of {total}: {title}
What the person does in this step, in order:
{actions}
Pieces this step adds: {adds}

How the pieces this step adds are made (notes of the 3D modeller; ignore the lines about axes and frames):
{made}

Check two things.

1. Is everything there? Every piece and every fastener this step adds must be visibly in its place. A piece held in the hand, lying beside the assembly, or only partly pushed in is not in place. Count fasteners when the step has several.

2. Does each piece face the right way? This is where people go wrong, and a piece fitted the wrong way round still looks "in place" at a glance. For every piece this step adds, find the features that show which way it faces: a groove, a channel, a row of holes, pre-fitted locks or fittings, a finished face against a raw one, a cut-out. From the render and the notes above, work out where each feature must be (on the inner or the outer face, at the top or the bottom edge, at the front or the rear end). Then find it in the photo and compare. The mistakes to look for:
   - back to front: the face with the groove or channel is turned the wrong way (a groove that must face inwards faces outwards);
   - upside down: a groove or edge that must be low is high;
   - end for end: the end with the locks or holes is at the wrong end;
   - a left and a right piece swapped.
   A piece can have two different slots: for example a wide runner channel on its outer face and a narrow groove near the lower edge of its inner face. A slot seen on a face proves nothing until you know which slot it is: tell them apart by their width, by where they run, and by what is beside them (locks, holes, the edge). Call a check "wrong" only when you have identified the feature and it is plainly in the wrong place. If you cannot tell which slot or which face you are looking at, the result is "cannot_see", not "wrong".
   If the photo shows such a feature in the wrong place, the step is not done, however complete it looks. If the feature that decides it cannot be seen in the photo at all, do not guess: say which side to show.

Reply with one JSON object:
{{"visible": true or false (false only if the photo does not show this part of the assembly, or is too dark or blurred),
 "checks": [{{"what": "one thing you checked, at most 12 words, e.g. left side panel: groove faces inwards", "result": "ok" or "wrong" or "cannot_see"}}],
 "done": true only if every piece is in place and no check is "wrong" and no deciding feature is "cannot_see",
 "confidence": number from 0 to 1,
 "have": "what is correctly in place, at most 12 words",
 "wrong": ["each mistake you can see: what is the wrong way round and how to turn it, at most 12 words each"],
 "missing": ["each thing not yet in place, or each side you need to be shown, at most 10 words each"],
 "say_en": "one or two short sentences for the person: the result; if something is wrong, exactly what and how to put it right",
 "say_el": "the same in Greek, informal singular (εσύ)"}}"""


def how_made(job_dir: Path, ids: list[str]) -> str:
    """What the modeller wrote about each of these pieces: where its groove, channel, holes and fittings are.
    From the docstring of the part's script (the plan names the script of each piece)."""
    import ast

    try:
        plan = json.loads((Path(job_dir) / "plan.approved.json").read_text())
    except (OSError, json.JSONDecodeError):
        return "- (no notes)"
    scripts = {p["id"]: p.get("script") for p in plan.get("parts", [])}
    scripts.update({h["code"]: h.get("script") for h in plan.get("hardware", [])})
    out, seen = [], set()
    for pid in ids:
        rel = scripts.get(pid)
        if not rel or rel in seen:
            continue
        seen.add(rel)
        try:
            doc = ast.get_docstring(ast.parse((Path(job_dir) / rel).read_text())) or ""
        except (OSError, SyntaxError):
            continue
        if doc:
            out.append("- " + " ".join(doc.split())[:700])
    return "\n".join(out) or "- (no notes)"


def part_sizes(job_dir: Path) -> dict[str, str]:
    """How large each piece is, in words a person can check by eye: "380 x 456 mm, 4 mm thin sheet".
    From the built parts' bounding boxes (parts/<id>.mates.json). Two grey panels differ only by this."""
    out = {}
    for f in sorted((Path(job_dir) / "parts").glob("*.mates.json")):
        try:
            d = json.loads(f.read_text())
            box = d["bbox_mm"]
            a, b, c = sorted((box["max"][i] - box["min"][i] for i in range(3)), reverse=True)
        except (OSError, KeyError, ValueError, TypeError):
            continue
        if d.get("kind") == "hardware":
            out[d["id"]] = f"{round(a)} mm long, {round(b)} mm across"
        else:
            shape = "thin sheet" if c <= 6 else "board"
            out[d["id"]] = f"{round(a)} x {round(b)} mm, {round(c)} mm thick {shape}"
    return out


def pieces_text(job_dir: Path, manifest: dict) -> str:
    """One line per piece: its name, how many, and its size, largest first, so look-alikes can be told apart."""
    sizes = part_sizes(job_dir)
    rows = [(p["id"], p.get("name", p["id"]), p.get("count", 1)) for p in manifest.get("parts", [])]
    rows += [(h["code"], h.get("name", h["code"]), h.get("count", 1)) for h in manifest.get("hardware", [])]
    return "\n".join(f"- {name} (x{count}): {sizes.get(pid, 'size unknown')}" for pid, name, count in rows)


def _step(manifest: dict, n: int) -> dict:
    for s in manifest.get("steps", []):
        if s.get("n") == n:
            return s
    raise ValueError(f"the guide has no step {n}")


def verify(job_dir: Path, n: int, jpeg: bytes, lang: str = "en") -> dict:
    """Two looks at the same photo, side by side, and one answer.

    Whether a groove faces in or out is hard to read off one photo, and one look alone both missed real mistakes
    and raised false ones (Sun 15 Nov, the drawer: the same photo was called "back to front" twice and "right"
    once). So: the step is done only when both looks say so; a mistake is stated as a fact only when both see one;
    a mistake only one of them sees is passed on as a doubt, with what to check (`certain: false`)."""
    from concurrent.futures import ThreadPoolExecutor

    with ThreadPoolExecutor(max_workers=2) as pool:
        looks = list(pool.map(lambda _: _safe_look(job_dir, n, jpeg, lang), range(2)))
    looks = [r for r in looks if r]
    if not looks:
        raise RuntimeError("the vision model did not answer")
    a = looks[0]
    b = looks[1] if len(looks) > 1 else a
    done = a["done"] and b["done"]
    both_wrong = bool(a["wrong"]) and bool(b["wrong"])
    one_wrong = bool(a["wrong"]) != bool(b["wrong"])
    lead = a if (a["wrong"] or not a["done"]) else b          # the look that has something to say
    if done:
        lead = a
    elif both_wrong:
        lead = a
    elif one_wrong:
        lead = a if a["wrong"] else b
    say = dict(lead["say"])
    if one_wrong and not done:
        say = {"en": "I am not sure about this, so check it: " + say["en"], "el": "Δεν είμαι σίγουρος γι' αυτό, οπότε έλεγξέ το: " + say["el"]}
    result = {"step": n, "done": done, "visible": lead["visible"], "certain": done or both_wrong or not one_wrong,
              "confidence": min(a["confidence"], b["confidence"]), "have": lead["have"],
              "wrong": [] if done else lead["wrong"], "missing": [] if done else lead["missing"], "checks": lead["checks"],
              "say": say, "message": say.get(lang) or say["en"], "looks": len(looks),
              "latency_ms": max(r["latency_ms"] for r in looks), "model": MODEL}
    try:                                               # every check is kept, so a confusing answer can be looked at later
        log_dir = Path(job_dir) / "perception"
        log_dir.mkdir(exist_ok=True)
        with (log_dir / "verify.jsonl").open("a", encoding="utf-8") as f:
            f.write(json.dumps({"t": round(time.time(), 1), **result, "each": [
                {"done": r["done"], "wrong": r["wrong"], "missing": r["missing"]} for r in looks]}, ensure_ascii=False) + "\n")
    except OSError:
        pass
    return result


def _safe_look(job_dir: Path, n: int, jpeg: bytes, lang: str) -> dict | None:
    try:
        return look(job_dir, n, jpeg, lang)
    except ValueError:
        raise                                          # no such step, no reference render: the caller's mistake
    except Exception as e:                             # noqa: BLE001 - one failed look must not lose the other
        log.warning("verify look failed: %s", str(e)[:200])
        return None


def look(job_dir: Path, n: int, jpeg: bytes, lang: str = "en") -> dict:
    job_dir = Path(job_dir)
    manifest = json.loads((job_dir / "manifest.json").read_text())
    step = _step(manifest, n)
    names = {p["id"]: p.get("name", p["id"]) for p in manifest.get("parts", [])}
    names.update({h["code"]: h.get("name", h["code"]) for h in manifest.get("hardware", [])})
    after = job_dir / "refs" / f"step-{n:02d}-ref.png"
    before = job_dir / "refs" / f"step-{n - 1:02d}-ref.png"
    if not after.is_file():
        raise ValueError(f"step {n} has no reference render")
    actions = [(a.get("text") or {}).get("en") for a in step.get("actions", [])]
    adds = [f"{names.get(p, p)}" for p in step.get("pickParts", [])]
    adds += [f"{h.get('qty', '?')} x {names.get(h.get('code'), h.get('code'))}" for h in step.get("hardware", [])]
    prompt = PROMPT.format(
        n=n, total=len(manifest.get("steps", [])), product=manifest.get("title", "furniture"), title=step.get("title", ""),
        pieces=pieces_text(job_dir, manifest),
        made=how_made(job_dir, list(step.get("pickParts", [])) + [h.get("code") for h in step.get("hardware", [])]),
        before="\nImage 3 is a render of the assembly before this step." if before.is_file() else "",
        actions="\n".join(f"- {t}" for t in actions if t) or "- (see the title)", adds=", ".join(adds) or "nothing new")
    t0 = time.perf_counter()
    with tempfile.NamedTemporaryFile(suffix=".jpg") as photo:
        photo.write(jpeg)
        photo.flush()
        parts = [prompt, Path(photo.name), after] + ([before] if before.is_file() else [])
        text = gemini.generate(MODEL, parts, json_out=True, timeout=40, thinking_budget=THINKING)
    try:
        out = json.loads(text)
    except json.JSONDecodeError:
        raise RuntimeError(f"the vision model's answer was not JSON: {text[:160]}") from None
    checks = [{"what": str(c.get("what", ""))[:120], "result": c.get("result")} for c in (out.get("checks") or [])
              if isinstance(c, dict) and c.get("result") in ("ok", "wrong", "cannot_see")][:8]
    wrong = [str(x)[:120] for x in (out.get("wrong") or []) if x][:3]
    # the model's own "done" is not trusted against its own findings: a mistake it listed means not done
    done = (bool(out.get("done")) and out.get("visible") is not False and not wrong
            and not any(c["result"] == "wrong" for c in checks))
    missing = [str(x)[:80] for x in (out.get("missing") or []) if x][:4]
    say = {"en": str(out.get("say_en") or "")[:200], "el": str(out.get("say_el") or out.get("say_en") or "")[:200]}
    result = {"step": n, "done": done, "visible": out.get("visible") is not False,
            "confidence": round(float(out.get("confidence") or 0), 2), "have": str(out.get("have") or "")[:120],
            "wrong": [] if done else wrong, "missing": [] if done else missing, "checks": checks,
            "say": say, "message": say.get(lang) or say["en"],
            "latency_ms": round((time.perf_counter() - t0) * 1000), "model": MODEL}
    return result
