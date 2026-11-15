"""Find the part in the pile: where, in one camera picture, the parts and hardware the person must pick up next lie.

    items(guide, want)                   the wanted parts and hardware, with names, counts, sizes and reference renders
    find(guide, jpeg, wanted, lang)      one `gemini-4-hydrogen` vision call per 6 items (in parallel when more)
    simulated(guide, wanted, lang)       the same answer shape with no model call (no key, or PERCEPTION_SIMULATE)

The model sees the camera picture, then one labelled reference render per wanted item (`renders/part-<id>-shaded.png`,
cropped to the object), then the renders of look-alikes that are in the box but not wanted (the other screw length,
the mirrored side panel), and returns a box per wanted item in Gemini's own convention: `[ymin, xmin, ymax, xmax]`,
0-1000, normalised to the picture. Sizes and counts come from the manifest and the plan; when the plan gives no size,
the render's own scale (`renders/parts.json` px_per_mm) gives an estimate, so a long screw and a short one differ in
the prompt even when the renders look alike.

No invented boxes: an item the model did not mark as seen, with a low confidence, or with an empty or malformed box,
goes to `missing`. Boxes are clamped to 0-1000 and put in min/max order here, not trusted as sent.
"""
from __future__ import annotations

import io
import json
import logging
import re
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from PIL import Image

from pipeline.check import gemini
from server.perception.guide import Guide

log = logging.getLogger("agj.perception")
MODEL = "gemini-4-hydrogen"          # PLAN §3. Do not substitute an older model
THINKING = "low"
FRAME_EDGE = 1280                    # small hardware needs the detail
THUMB_EDGE = 320
PER_CALL = 6                         # wanted items in one request; more are split over parallel requests
MAX_WANT = 12
MAX_LOOKALIKES = 4                   # per request
MIN_CONFIDENCE = 0.25                # below this a "seen" answer is taken as not seen
MIN_SIDE = 2                         # a box thinner than this (0-1000 units) is not a box
TIMEOUT_S = 25
WHITE = 245                          # the renders' background is white: brighter than this in all channels
SIM_BOX = [350, 350, 650, 650]
_pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="pile")

SCHEMA_ITEM = {
    "type": "OBJECT",
    "properties": {
        "label": {"type": "STRING"},
        "visible": {"type": "BOOLEAN"},
        "box_2d": {"type": "ARRAY", "items": {"type": "INTEGER"}, "nullable": True},
        "confidence": {"type": "NUMBER"},
    },
    "required": ["label", "visible", "box_2d", "confidence"],
}

PROMPT = """Image 1 is a camera view of a work area where a person is about to assemble flat-pack furniture ({title}).
Loose parts and hardware lie in it, maybe in a pile, maybe partly covered by others or by hands. Find where each wanted
item lies in Image 1.

Wanted items (label: what it is):
{wanted}
{lookalikes}
After Image 1 comes a reference picture of each item, labelled. The references are computer renders, each scaled to
fill its picture: compare shape, holes, grooves and cut-outs, not size or colour; the sizes in the list are the real ones.
Mirror-image parts (left and right) differ only in where their holes and grooves are: check that.

Return one entry in `items` for every wanted label, with:
- `label`: the wanted label, exactly as written above.
- `visible`: true only when you can see that item in Image 1. False when it is not there, hidden, too small to tell, or
  when you cannot tell it from a look-alike. Never guess.
- `box_2d`: [ymin, xmin, ymax, xmax], normalised to 0-1000 over Image 1, tight around the item. When several of that
  item lie there, box the one easiest to pick up; for small hardware lying together, box the small heap. null when not visible.
- `confidence`: from 0 to 1, how sure you are that the box holds that item."""

LOOKALIKES = """
Also in the box and easy to mistake for a wanted item. These are NOT wanted: never box one of them as a wanted item.
{items}
"""

MESSAGES = {
    "en": {"found": "The {name} is {where}.",
           "none": "I can't see the {name}. Show me more of the parts.",
           "nothing": "Step {step} needs no new parts.",
           "simulated": "Simulated: no vision model on this server, so the box is a placeholder."},
    "el": {"found": "{Name}: {where}.",
           "none": "Δεν βλέπω: {name}. Δείξε μου κι άλλα κομμάτια.",
           "nothing": "Το βήμα {step} δεν θέλει νέα κομμάτια.",
           "simulated": "Προσομοίωση: ο server δεν έχει μοντέλο όρασης, οπότε το πλαίσιο είναι ενδεικτικό."},
}
WHERE = {
    "en": {("top", "left"): "at the top left", ("top", "centre"): "at the top", ("top", "right"): "at the top right",
           ("middle", "left"): "on the left", ("middle", "centre"): "in the middle", ("middle", "right"): "on the right",
           ("bottom", "left"): "at the bottom left", ("bottom", "centre"): "at the bottom",
           ("bottom", "right"): "at the bottom right"},
    "el": {("top", "left"): "πάνω αριστερά", ("top", "centre"): "πάνω", ("top", "right"): "πάνω δεξιά",
           ("middle", "left"): "αριστερά", ("middle", "centre"): "στη μέση", ("middle", "right"): "δεξιά",
           ("bottom", "left"): "κάτω αριστερά", ("bottom", "centre"): "κάτω", ("bottom", "right"): "κάτω δεξιά"},
}
EL_ARTICLE = re.compile(r"^(το|τη|την|τον|τα|τις|τους|ο|η|οι|μια|μία|ένα|έναν|ένας)\s+", re.I)
SIDE_WORDS = re.compile(r"(^|_)(left|right|l|r)(?=_|$)")


@dataclass(frozen=True)
class Item:
    id: str
    kind: str                     # "part" | "hardware"
    name_en: str                  # short English name: "left side panel"
    name_el: str | None           # Greek name, article dropped: "αριστερό πλαϊνό"
    full: str                     # the manifest's long name
    hw_kind: str | None
    count: int | None
    size: str                     # "60 mm long", "about 400 mm across (estimated)", or ""
    thumb: Path | None

    def label(self, lang: str) -> str:
        """`Left side panel` / `Cam lock · 120076` / `Αριστερό πλαϊνό`."""
        name = (self.name_el if lang == "el" and self.name_el else self.name_en) or self.id
        name = name[:1].upper() + name[1:]
        return f"{name} · {self.id}" if self.kind == "hardware" else name

    def describe(self) -> str:
        text = f"{self.kind}, {self.name_en}"
        if self.full and self.full.lower() != self.name_en.lower():
            text += f" ({self.full})"
        if self.hw_kind:
            text += f", a {self.hw_kind.replace('_', ' ')}"
        if self.count:
            text += f", {self.count} in the box"
        if self.size:
            text += f", {self.size}"
        return text


# ---- the job's items --------------------------------------------------------------------------------

def _read_json(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def _inside(base: Path, rel) -> Path | None:
    if not isinstance(rel, str) or not rel:
        return None
    base = base.resolve()
    path = (base / rel).resolve()
    return path if path.is_relative_to(base) and path.is_file() else None


def _thumb_path(job: Path, item_id: str, entry: dict) -> Path | None:
    for rel in (entry.get("thumb"), f"renders/part-{item_id}-shaded.png", f"renders/part-{item_id}.png"):
        path = _inside(job, rel)
        if path:
            return path
    return None


@lru_cache(maxsize=256)
def _content_box(path: str, mtime_ns: int) -> tuple[int, int, int, int] | None:
    """The bounding box of the non-white pixels of a render (left, top, right, bottom)."""
    try:
        im = Image.open(path).convert("RGB")
    except OSError:
        return None
    mask = Image.eval(im.convert("L"), lambda v: 255 if v < WHITE else 0)
    return mask.getbbox()


def _render_size(job: Path, item_id: str, thumb: Path | None, scales: dict) -> str:
    """`about 400 mm across (estimated)` from the render's own scale, or "" when it is not known."""
    ppm = (scales.get(item_id) or {}).get("px_per_mm")
    if not thumb or not isinstance(ppm, (int, float)) or ppm <= 0:
        return ""
    box = _content_box(str(thumb), thumb.stat().st_mtime_ns)
    if not box:
        return ""
    mm = max(box[2] - box[0], box[3] - box[1]) / ppm
    return f"about {mm:.0f} mm across (estimated)" if mm >= 1 else ""


def _plan_size(entry: dict) -> str:
    p = entry.get("params") or {}
    length = entry.get("length_mm") or p.get("length")
    if isinstance(length, (int, float)) and length > 0:
        return f"{length:g} mm long"
    vals = []
    for k, v in (entry.get("dims_mm") or {}).items():
        v = v.get("value") if isinstance(v, dict) else v
        if isinstance(v, (int, float)) and v > 0:
            vals.append(f"{k} {v:g}")
    return ("about " + " x ".join(vals) + " mm") if vals else ""


def _greek(entry: dict, plan: dict) -> str | None:
    name = (entry.get("names") or {}).get("el") or entry.get("el") or plan.get("el")
    if not isinstance(name, str) or not name.strip():
        return None
    return EL_ARTICLE.sub("", name.strip())


def catalogue(guide: Guide) -> dict[str, Item]:
    """Every part and hardware item of the job, by id or code, in manifest order."""
    job = guide.dir
    plan = _read_json(job / "plan.approved.json") or _read_json(job / "plan.json")
    from_plan = {str(p.get("id")): p for p in plan.get("parts", []) if isinstance(p, dict)}
    from_plan.update({str(h.get("code")): h for h in plan.get("hardware", []) if isinstance(h, dict)})
    scales = _read_json(job / "renders" / "parts.json")
    out: dict[str, Item] = {}
    for kind, key, entries in (("part", "id", guide.manifest.get("parts") or []),
                               ("hardware", "code", guide.manifest.get("hardware") or [])):
        for e in entries:
            if not isinstance(e, dict) or e.get(key) is None:
                continue
            i = str(e[key])
            p = from_plan.get(i, {})
            thumb = _thumb_path(job, i, e)
            count = e.get("count", p.get("count"))
            out[i] = Item(id=i, kind=kind, name_en=str((e.get("names") or {}).get("en") or e.get("short") or e.get("name") or i).lower(),
                          name_el=_greek(e, p), full=str(e.get("name") or ""),
                          hw_kind=(e.get("kind") or p.get("kind")) if kind == "hardware" else None,
                          count=count if isinstance(count, int) and not isinstance(count, bool) else None,
                          size=_plan_size(p) or _render_size(job, i, thumb, scales), thumb=thumb)
    return out


def step_items(guide: Guide, n: int) -> list[str]:
    """What step `n` picks up: its parts, then its hardware, without repeats."""
    seen: list[str] = []
    for i in guide.parts(n) + [c for c, _ in guide.hardware(n)]:
        if i not in seen:
            seen.append(i)
    return seen


def lookalikes(cat: dict[str, Item], wanted: list[str]) -> list[str]:
    """Items not wanted that are easy to mistake for a wanted one: hardware of the same kind, the mirrored part."""
    out = []
    for w in wanted:
        a = cat[w]
        for b in cat.values():
            if b.id in wanted or b.id in out or b.kind != a.kind:
                continue
            same = (a.kind == "hardware" and a.hw_kind and a.hw_kind == b.hw_kind) or (
                a.kind == "part" and SIDE_WORDS.sub(r"\1", a.id) == SIDE_WORDS.sub(r"\1", b.id) and a.id != b.id)
            if same:
                out.append(b.id)
    return out[:MAX_LOOKALIKES]


# ---- images ------------------------------------------------------------------------------------------

@lru_cache(maxsize=128)
def _thumb_jpeg(path: str, mtime_ns: int) -> bytes:
    """The render cropped to the object (with a margin), on white, as a small JPEG."""
    im = Image.open(path)
    if im.mode in ("RGBA", "LA", "P"):
        im = im.convert("RGBA")
        bg = Image.new("RGB", im.size, (255, 255, 255))
        bg.paste(im, mask=im.split()[-1])
        im = bg
    im = im.convert("RGB")
    box = _content_box(path, mtime_ns)
    if box:
        pad = max(4, int(0.06 * max(box[2] - box[0], box[3] - box[1])))
        im = im.crop((max(0, box[0] - pad), max(0, box[1] - pad), min(im.width, box[2] + pad), min(im.height, box[3] + pad)))
    im.thumbnail((THUMB_EDGE, THUMB_EDGE))
    out = io.BytesIO()
    im.save(out, "JPEG", quality=85)
    return out.getvalue()


def _frame(jpeg: bytes) -> bytes:
    im = Image.open(io.BytesIO(jpeg))
    if max(im.size) <= FRAME_EDGE:
        return jpeg
    im.thumbnail((FRAME_EDGE, FRAME_EDGE))
    out = io.BytesIO()
    im.convert("RGB").save(out, "JPEG", quality=85)
    return out.getvalue()


def _picture(item: Item, caption: str) -> list:
    if item.thumb is None:
        return [f"{caption} (no reference picture: use the description)"]
    try:
        return [caption, ("image/jpeg", _thumb_jpeg(str(item.thumb), item.thumb.stat().st_mtime_ns))]
    except OSError:
        return [f"{caption} (no reference picture: use the description)"]


def request(guide: Guide, cat: dict[str, Item], frame: bytes, wanted: list[str]) -> tuple[list, dict]:
    """The prompt parts and the response schema for one call (at most PER_CALL wanted items)."""
    others = lookalikes(cat, wanted)
    text = PROMPT.format(title=guide.title, wanted="\n".join(f"- {w}: {cat[w].describe()}" for w in wanted),
                         lookalikes=LOOKALIKES.format(items="\n".join(f"- {o}: {cat[o].describe()}" for o in others))
                         if others else "")
    parts: list = [text, "Image 1, the camera view:", ("image/jpeg", frame)]
    for w in wanted:
        parts += _picture(cat[w], f"Reference for wanted label `{w}`:")
    for o in others:
        parts += _picture(cat[o], f"Look-alike `{o}`, NOT wanted:")
    item = {**SCHEMA_ITEM, "properties": {**SCHEMA_ITEM["properties"], "label": {"type": "STRING", "enum": list(wanted)}}}
    schema = {"type": "OBJECT", "properties": {"items": {"type": "ARRAY", "items": item}}, "required": ["items"]}
    return parts, schema


# ---- answers ------------------------------------------------------------------------------------------

def _number(v) -> float | None:
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def clean_box(box) -> list[int] | None:
    """`[ymin, xmin, ymax, xmax]` as integers in 0-1000, each pair in order; None when it is not a usable box."""
    if not isinstance(box, (list, tuple)) or len(box) != 4:
        return None
    vals = [_number(v) for v in box]
    if any(v is None for v in vals):
        return None
    y0, x0, y1, x1 = (int(round(min(1000.0, max(0.0, v)))) for v in vals)
    y0, y1 = sorted((y0, y1))
    x0, x1 = sorted((x0, x1))
    if y1 - y0 < MIN_SIDE or x1 - x0 < MIN_SIDE:
        return None
    return [y0, x0, y1, x1]


def read_answer(raw, wanted: list[str]) -> dict[str, dict]:
    """The model's `items` as {id: {box_2d, confidence}} for the wanted items it saw. Anything else is dropped."""
    out: dict[str, dict] = {}
    entries = raw.get("items") if isinstance(raw, dict) else None
    for e in entries if isinstance(entries, list) else []:
        if not isinstance(e, dict) or e.get("label") not in wanted or e.get("visible") is not True:
            continue
        conf = _number(e.get("confidence"))
        conf = 0.0 if conf is None else min(1.0, max(0.0, conf))
        box = clean_box(e.get("box_2d"))
        if box is None or conf < MIN_CONFIDENCE:
            continue
        if e["label"] not in out or conf > out[e["label"]]["confidence"]:
            out[e["label"]] = {"box_2d": box, "confidence": round(conf, 2)}
    return out


def where(box: list[int], lang: str) -> str:
    cy, cx = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    row = "top" if cy < 1000 / 3 else "bottom" if cy > 2000 / 3 else "middle"
    col = "left" if cx < 1000 / 3 else "right" if cx > 2000 / 3 else "centre"
    return WHERE.get(lang, WHERE["en"])[(row, col)]


def _spoken(item: Item, lang: str) -> str:
    name = (item.name_el if lang == "el" and item.name_el else item.name_en) or item.id
    return f"{name} ({item.id})" if item.kind == "hardware" else name


def message(cat: dict[str, Item], found: list[dict], missing: list[str], lang: str, step: int | None) -> str:
    m = MESSAGES.get(lang, MESSAGES["en"])
    if found:
        f = found[0]
        name = _spoken(cat[f["id"]], lang)
        return m["found"].format(name=name, Name=name[:1].upper() + name[1:], where=where(f["box_2d"], lang))
    if missing:
        return m["none"].format(name=_spoken(cat[missing[0]], lang))
    return m["nothing"].format(step=step if step is not None else "")


def answer(cat: dict[str, Item], wanted: list[str], seen: dict[str, dict], lang: str, step: int | None) -> dict:
    found = [{"id": w, "kind": cat[w].kind, **seen[w], "label": cat[w].label(lang)} for w in wanted if w in seen]
    missing = [w for w in wanted if w not in seen]
    return {"found": found, "missing": missing, "message": message(cat, found, missing, lang, step), "step": step}


def _call(guide: Guide, cat: dict[str, Item], frame: bytes, wanted: list[str]) -> dict[str, dict]:
    parts, schema = request(guide, cat, frame, wanted)
    try:
        raw = json.loads(gemini.generate(MODEL, parts, timeout=TIMEOUT_S, thinking_budget=THINKING, schema=schema))
    except json.JSONDecodeError:
        raise RuntimeError("the model returned no usable JSON") from None
    except OSError as e:
        raise RuntimeError(f"{MODEL} did not answer: {type(e).__name__}") from None
    if not isinstance(raw, dict):
        raise RuntimeError("the model returned no usable JSON")
    return read_answer(raw, wanted)


def find(guide: Guide, jpeg: bytes, wanted: list[str], lang: str = "en", step: int | None = None,
         cat: dict[str, Item] | None = None) -> dict:
    """The response body. Raises RuntimeError when a model request fails or returns no JSON."""
    t0 = time.perf_counter()
    cat = cat or catalogue(guide)
    seen: dict[str, dict] = {}
    if wanted:
        frame = _frame(jpeg)
        chunks = [wanted[i:i + PER_CALL] for i in range(0, len(wanted), PER_CALL)]
        if len(chunks) == 1:
            seen = _call(guide, cat, frame, chunks[0])
        else:
            for got in _pool.map(lambda c: _call(guide, cat, frame, c), chunks):
                seen.update(got)
    out = answer(cat, wanted, seen, lang, step)
    out["latency_ms"] = round((time.perf_counter() - t0) * 1000)
    out["model"] = MODEL
    out["simulated"] = False
    log.info("pile %s: want=%s found=%s missing=%s (%s ms)", guide.dir.name, wanted,
             [(f["id"], f["box_2d"], f["confidence"]) for f in out["found"]], out["missing"], out["latency_ms"])
    return out


def simulated(guide: Guide, wanted: list[str], lang: str = "en", step: int | None = None,
              cat: dict[str, Item] | None = None) -> dict:
    """No model: the first wanted item in the middle of the picture at confidence 0.5, the rest missing."""
    cat = cat or catalogue(guide)
    seen = {wanted[0]: {"box_2d": list(SIM_BOX), "confidence": 0.5}} if wanted else {}
    out = answer(cat, wanted, seen, lang, step)
    if wanted:
        out["message"] = MESSAGES.get(lang, MESSAGES["en"])["simulated"]
    return {**out, "latency_ms": 0, "model": None, "simulated": True}
