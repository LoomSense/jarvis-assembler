"""Parts inventory before assembly: the person lays out everything from the box and takes 1-6 photos; the agent
counts what it sees against the guide's parts list and says what is missing or extra.

    .venv/bin/python -m pipeline.inventory <job_dir> photo1.jpg [photo2.jpg ...]

Expected counts come from the plan (approved if it exists). Reference pictures are the part renders
(`renders/part-<id>-shaded.png`, PLAN D19; hardware has them too); an item without one is described from the plan's
fields. `gemini-4-hydrogen` counts per id or code with structured output. It may answer "not sure" (screws look alike):
then the item is `unsure` and the summary asks the person to count it. Statuses and summaries are made here, in code,
so their wording follows the voice rules (docs/design/BRAND.md §6).

Writes `<job>/inventory.json` and the photos it was given, upright and resized, as `inventory/photo-N.jpg`.
"""
from __future__ import annotations

import io
import json
import re
import sys
import time
from pathlib import Path

from PIL import Image

from pipeline.check import gemini
from server.jobs import write_atomic
from server.photos import open_photo

MODEL = "gemini-4-hydrogen"
THINKING = "medium"
MAX_PHOTOS = 6
PHOTO_SIDE = 2048         # px, long side of the photos sent; screws need the detail
PRICE_IN, PRICE_OUT = 1.50, 7.50     # $ per 1M tokens (PLAN §3)

SCHEMA = {
    "type": "object",
    "properties": {
        "items": {"type": "array", "items": {
            "type": "object",
            "properties": {
                "id": {"type": "string", "description": "The label from the parts list"},
                "seen": {"type": "integer", "description": "How many you count across all photos"},
                "sure": {"type": "boolean", "description": "False when you cannot count it reliably"},
                "note": {"type": "string", "description": "At most 15 words, only when useful"},
            },
            "required": ["id", "seen", "sure"],
        }},
        "others": {"type": "array", "items": {"type": "string"},
                   "description": "Objects on the table that match nothing on the list, a few words each"},
    },
    "required": ["items"],
}

ASK = """You check a flat-pack furniture box before assembly: {title}.
The person has laid out every part and every piece of hardware from the box on a table and photographed it.
Count how many of each item on the parts list you can see, across all photos together.

The parts list (label, what it is, how many the guide lists):
{lines}

A reference picture follows for most items. Reference pictures are computer renders: each is scaled to fill its
frame, so compare shape, holes and cut-outs, not size or colour; the sizes in the list are the real ones.
Then come the {n} photo(s) of the table. The same object may appear in more than one photo: count it once.
Items that are nearly identical (for example two screw lengths, or a plain slat and one with a cut-out) must be told apart
by the differences named in the list.

Return JSON: one entry in "items" for every label above, with "seen" (your count) and "sure".
Set "sure" to false when you cannot count an item reliably: small or similar hardware in a pile, parts hidden under
others, or a photo too blurred. Do not guess to make a count match the list. Report what you see.
Put anything that matches no label in "others"."""


def load_plan(job: Path) -> dict:
    for name in ("plan.approved.json", "plan.json"):
        if (job / name).is_file():
            return json.loads((job / name).read_text())
    raise FileNotFoundError(f"{job} has no plan yet")


def _mm(d: dict) -> str:
    """'60 mm long, 4.5 mm shank, 9 mm head' from a hardware entry's fields."""
    p = d.get("params") or {}
    bits = []
    length = d.get("length_mm") or p.get("length")
    if length:
        bits.append(f"{length:g} mm long")
    if p.get("shank_d"):
        bits.append(f"{p['shank_d']:g} mm thick")
    for key, word in (("head_d", "head"), ("flange_d", "flange")):
        if p.get(key):
            bits.append(f"{p[key]:g} mm {word}")
    return ", ".join(bits)


def _dims(d: dict) -> str:
    dims = d.get("dims_mm") or {}
    vals = []
    for k, v in dims.items():
        v = v.get("value") if isinstance(v, dict) else v
        if isinstance(v, (int, float)):
            vals.append(f"{k} {v:g}")
    return ("about " + ", ".join(vals) + " mm") if vals else ""


def expected(plan: dict) -> list[dict]:
    """One entry per part and hardware item: id, kind, name, expected count, description for the prompt."""
    out = []
    for p in plan.get("parts", []):
        desc = ", ".join(x for x in (p.get("name") or p["id"], _dims(p), p.get("material")) if x)
        out.append({"id": p["id"], "kind": "part", "name": p.get("name") or p["id"], "expected": int(p.get("count", 1)),
                    "desc": desc, "short": p.get("short"), "el": p.get("el")})
    for h in plan.get("hardware", []):
        code = str(h["code"])
        desc = ", ".join(x for x in (h.get("name") or h.get("kind"), h.get("kind"), _mm(h)) if x)
        out.append({"id": code, "kind": "hardware", "name": h.get("name") or h.get("kind") or code,
                    "expected": int(h.get("count", 1)), "desc": desc, "hw_kind": h.get("kind") or "piece"})
    return out


def reference(job: Path, item_id: str) -> Path | None:
    for name in (f"part-{item_id}-shaded.png", f"part-{item_id}.png"):
        if (job / "renders" / name).is_file():
            return job / "renders" / name
    return None


def save_photos(job: Path, photos: list[bytes]) -> list[Path]:
    """Upright (EXIF), at most PHOTO_SIDE px, JPEG, as inventory/photo-N.jpg. Raises server.photos.PhotoError."""
    if not 1 <= len(photos) <= MAX_PHOTOS:
        raise ValueError(f"Send 1 to {MAX_PHOTOS} photos; got {len(photos)}")
    imgs = [open_photo(data, f"Photo {i}") for i, data in enumerate(photos, start=1)]
    folder = job / "inventory"
    folder.mkdir(exist_ok=True)
    for old in folder.glob("photo-*.jpg"):
        old.unlink()
    paths = []
    for i, img in enumerate(imgs, start=1):
        img.thumbnail((PHOTO_SIDE, PHOTO_SIDE), Image.Resampling.LANCZOS)
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=88)
        path = folder / f"photo-{i}.jpg"
        write_atomic(path, buf.getvalue())
        paths.append(path)
    return paths


def prompt_parts(plan: dict, items: list[dict], job: Path, photos: list[Path]) -> list:
    lines = "\n".join(f"- {it['id']}: {it['desc']}. Guide lists {it['expected']}." for it in items)
    parts: list = [ASK.format(title=plan.get("title") or "furniture", lines=lines, n=len(photos))]
    for it in items:
        ref = reference(job, it["id"])
        if ref:
            parts += [f"Reference picture for {it['id']} ({it['name']}):", ref]
    for i, p in enumerate(photos, start=1):
        parts += [f"Photo {i} of {len(photos)} of the table:", p]
    return parts


def status_of(expected_n: int, seen: int | None, sure: bool) -> str:
    if seen is None or not sure:
        return "unsure"
    return "ok" if seen == expected_n else "missing" if seen < expected_n else "extra"


# ---- summaries (BRAND §6: short, calm, the fact first, never "wrong", no exclamation marks, no em dashes) ----------

ARTICLE = re.compile(r"^(το|τη|την|τον|τα|τις|τους|μια|μία|ένα|έναν)\s+")


def _label_en(it: dict) -> str:
    """Hardware by its printed code (the label on the bag); a part by its short name."""
    return it["id"] if it["kind"] == "hardware" else it.get("short") or it["name"]


def _label_el(it: dict) -> str:
    if it["kind"] == "part" and it.get("el"):
        return ARTICLE.sub("", it["el"])
    return _label_en(it)


def _phrase(it: dict) -> tuple[str, str]:
    st, exp, seen = it["status"], it["expected"], it["seen"]
    if st == "missing":
        return f"{exp - seen} × {_label_en(it)} missing", f"Δεν βλέπω {exp - seen} × {_label_el(it)}"
    if st == "extra":
        return f"{seen - exp} × {_label_en(it)} extra", f"Βλέπω {seen - exp} × {_label_el(it)} παραπάνω"
    if it["kind"] == "hardware":
        what_en, what_el = f"the {it['id']} {it.get('hw_kind', 'piece')}s", f"τα {it['id']}"
        verb_en, verb_el = "Count", "Μέτρησε"
    else:
        what_en = f"the {_label_en(it)}"
        what_el = it.get("el") or _label_el(it)
        verb_en, verb_el = "Check", "Έλεγξε"
    if seen is None:
        return (f"{verb_en} {what_en}: I can't count them in the photos",
                f"{verb_el} {what_el}: δεν μπορώ να τα μετρήσω στις φωτογραφίες")
    return (f"{verb_en} {what_en}: I see {seen}, the guide lists {exp}",
            f"{verb_el} {what_el}: βλέπω {seen}, ο οδηγός γράφει {exp}")


def summarize(items: list[dict], limit: int = 4) -> dict:
    off = [it for it in items if it["status"] != "ok"]
    if not off:
        return {"en": "Everything on the parts list is here.", "el": "Όλα όσα γράφει η λίστα είναι εδώ."}
    order = {"missing": 0, "extra": 1, "unsure": 2}
    off.sort(key=lambda it: order[it["status"]])
    phrases = [_phrase(it) for it in off[:limit]]
    en = [p[0] + "." for p in phrases]
    el = [p[1] + "." for p in phrases]
    if len(off) > limit:
        en.append(f"{len(off) - limit} more to check.")
        el.append(f"Άλλα {len(off) - limit} για έλεγχο.")
    if len(off) < len(items):
        en.append("The rest is here.")
        el.append("Τα υπόλοιπα είναι εδώ.")
    return {"en": " ".join(en), "el": " ".join(el)}


# ---- the check ------------------------------------------------------------------------------------------------

def check(job: Path, photos: list[bytes]) -> dict:
    """Run the inventory on `photos` (raw image bytes) for the job folder `job`; writes and returns inventory.json."""
    job = Path(job)
    plan = load_plan(job)
    items = expected(plan)
    paths = save_photos(job, photos)
    t0 = time.perf_counter()
    text = gemini.generate(MODEL, prompt_parts(plan, items, job, paths), schema=SCHEMA, timeout=180,
                           thinking_budget=THINKING)
    latency_ms = round((time.perf_counter() - t0) * 1000)
    try:
        answer = json.loads(text)
    except json.JSONDecodeError:
        answer = None
    if not isinstance(answer, dict):
        raise RuntimeError(f"{MODEL} did not answer with the JSON object asked for: {text[:200]}")
    got = {}
    for a in answer.get("items") or []:
        if isinstance(a, dict) and str(a.get("id")) not in got:
            got[str(a.get("id"))] = a
    rows, full = [], []
    for it in items:
        a = got.get(it["id"]) or {}
        seen = a.get("seen")
        seen = seen if isinstance(seen, int) and not isinstance(seen, bool) and seen >= 0 else None
        sure = bool(a.get("sure")) and seen is not None
        row = {"id": it["id"], "kind": it["kind"], "name": it["name"], "expected": it["expected"], "seen": seen,
               "status": status_of(it["expected"], seen, sure), "note": str(a.get("note") or "")[:200]}
        rows.append(row)
        full.append({**it, **row})
    usage = (getattr(gemini.generate, "last", None) or {}).get("usage") or {}
    tok_in = usage.get("promptTokenCount", 0)
    tok_out = usage.get("candidatesTokenCount", 0) + usage.get("thoughtsTokenCount", 0)
    result = {
        "items": rows,
        "all_present": all(r["status"] == "ok" for r in rows),
        "others": [str(o)[:120] for o in (answer.get("others") or [])][:20],
        "summary": summarize(full),
        "photos": [str(p.relative_to(job)) for p in paths],
        "latency_ms": latency_ms,
        "model": MODEL,
        "tokens": {"in": tok_in, "out": tok_out},
        "cost_usd": round((tok_in * PRICE_IN + tok_out * PRICE_OUT) / 1e6, 4),
        "ts": int(time.time() * 1000),
    }
    write_atomic(job / "inventory.json", json.dumps(result, ensure_ascii=False, indent=1))
    return result


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit("usage: python -m pipeline.inventory <job_dir> photo1.jpg [photo2.jpg ...]")
    res = check(Path(sys.argv[1]), [Path(p).read_bytes() for p in sys.argv[2:]])
    print(json.dumps(res, ensure_ascii=False, indent=1))
