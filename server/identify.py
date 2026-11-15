"""Which product is in front of the camera: a flat-pack box, an IKEA label, a finished piece or loose parts.

    identify(store, jpeg, lang)      one `gemini-4-hydrogen` vision call, then matching on this server
    simulated(store, lang)           the same answer shape with no model call (no key, or PERCEPTION_SIMULATE)

The model reads the printed text (product name, article number, AA- document number) and describes the object.
It also sees one small picture of each guide built on this server (the maker's product photo when the catalog
fetched one, else the walkthrough's cover render), so a finished piece with no text on it can still be matched.
Matching is done here, not by the model: by article number, then document number, then product name, and
only then by the picture the model pointed at.

Grounding: an article or document number is returned only if the model read it on the image. The prompt never
shows the known guides' numbers, and a number whose digits are not in the text the model read is dropped.
"""
from __future__ import annotations

import io
import json
import logging
import re
import time
from pathlib import Path

from PIL import Image

from pipeline.check import gemini
from server import catalog
from server.fetch_guide import normalize_article
from server.jobs import JobStore

log = logging.getLogger("agj.identify")
MODEL = "gemini-4-hydrogen"         # PLAN §3. Do not substitute an older model
THINKING = "low"                    # the lowest level the model accepts (pipeline/ask.py)
FRAME_EDGE = 1280                   # labels carry small print: keep the camera picture large
THUMB_EDGE = 320
MAX_PICTURES = 6                    # known guides shown to the model, newest first
TIMEOUT_S = 25
KINDS = ("box", "label", "furniture", "parts", "unknown")
DOC_RE = re.compile(r"\bAA-?\d{5,8}-\d{1,3}\b", re.I)

SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "kind": {"type": "STRING", "enum": list(KINDS)},
        "name": {"type": "STRING", "nullable": True},
        "type": {"type": "STRING", "nullable": True},
        "article": {"type": "STRING", "nullable": True},
        "doc_number": {"type": "STRING", "nullable": True},
        "text": {"type": "STRING"},
        "picture": {"type": "INTEGER", "nullable": True},
        "confidence": {"type": "NUMBER"},
    },
    "required": ["kind", "name", "type", "article", "doc_number", "text", "picture", "confidence"],
}

PROMPT = """Image 1 is a camera view from a person who is about to assemble flat-pack furniture. Say what product it shows.

What is in Image 1 (`kind`): "box" (a flat-pack carton), "label" (a product label, sticker or price tag),
"furniture" (a finished or partly built piece), "parts" (loose panels and hardware), or "unknown".

Read the printed text and copy it exactly:
- `name`: the product name printed in capitals, such as MALM, KNAGGLIG or LACK. If no name is printed, you may
  give the name only when you clearly recognise the product; otherwise null.
- `type`: the kind of furniture in plain English (desk, drawer, box, side table, chest of drawers), or null.
- `article`: the article number exactly as printed (8 digits, often dotted like 123.456.78, sometimes with an S
  before it). Only a number you can read in Image 1; never guess one. Otherwise null.
- `doc_number`: an assembly-instruction number like AA-1234567-8, only if printed in Image 1. Otherwise null.
- `text`: the text you can read on the object, up to 200 characters, or "" if none.

{pictures}

`confidence`: from 0 to 1, how sure you are of the product (the name, or the picture you chose)."""

PICTURES = """Then come pictures of the products this server has guides for, one after the other ("Picture 1",
"Picture 2", ...). `picture`: the number of the picture that shows the same product as Image 1 (same shape and
build; the colour may differ), or null when none does. These pictures are only for `picture`: never copy a name
or a number from them into the other fields."""
NO_PICTURES = "`picture`: always null."

MESSAGES = {
    "en": {"ready": "This looks like the {product}. Its guide is ready.",
           "building": "This looks like the {product}. Its guide is still being built.",
           "fetch": "I read {what}. I can fetch its guide from IKEA.",
           "search": "I read {what}. I can look for its guide on the maker's site.",
           "unknown": "I can't tell which product this is. Point the camera at the label on the box.",
           "simulated": "Simulated: no vision model on this server, so the first guide on it is shown.",
           "simulated_none": "Simulated: no vision model on this server, and no guide is built on it yet."},
    "el": {"ready": "Μοιάζει με {product}. Ο οδηγός είναι έτοιμος.",
           "building": "Μοιάζει με {product}. Ο οδηγός ετοιμάζεται ακόμα.",
           "fetch": "Διάβασα {what}. Μπορώ να φέρω τον οδηγό από την IKEA.",
           "search": "Διάβασα {what}. Μπορώ να ψάξω τον οδηγό στη σελίδα του κατασκευαστή.",
           "unknown": "Δεν καταλαβαίνω ποιο προϊόν είναι. Δείξε στην κάμερα την ετικέτα στο κουτί.",
           "simulated": "Προσομοίωση: ο server δεν έχει μοντέλο όρασης, οπότε δείχνει τον πρώτο οδηγό του.",
           "simulated_none": "Προσομοίωση: ο server δεν έχει μοντέλο όρασης ούτε έτοιμο οδηγό."},
}


# ---- the guides this server knows ---------------------------------------------------------------

def _read(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def _picture(job_dir: Path) -> Path | None:
    """The maker's product photo (the catalog stores it), else the cover render, else the last step's render."""
    renders = job_dir / "renders"
    steps = sorted(renders.glob("step_*.png")) if renders.is_dir() else []
    for path in (job_dir / "product.jpg", renders / "cover.png", *reversed(steps)):
        if path.is_file():
            return path
    return None


def candidates(store: JobStore) -> list[dict]:
    """Every guide a camera picture could be matched to: the catalog (built and known guides) plus job folders that
    have a walkthrough but no status file (put on the server by hand). Each: {product, guide_id, article, job_id,
    ready, names, picture}. Built ones first, newest first."""
    out, seen = [], set()
    for item in catalog.items(store):
        job = store.get(item["job_id"]) if item.get("job_id") else None
        out.append({**{k: item.get(k) for k in ("product", "guide_id", "article", "job_id", "ready")},
                    "names": [], "picture": None, "dir": job.dir if job else None})
        if job:
            seen.add(job.id)
    for job in store.all():
        if job.id in seen or not job.manifest.is_file():
            continue
        status = _read(job.status_file) if job.status_file.is_file() else None
        if status is None and not job.glb.is_file():
            continue
        if status is not None and status.get("status") not in ("done", *catalog.ACTIVE):
            continue
        m = _read(job.manifest)
        if not m.get("steps"):
            continue
        status = status or {}
        out.append({"product": m.get("title") or status.get("product") or job.id, "guide_id": m.get("guideId"),
                    "article": status.get("article") or catalog.KNOWN_ARTICLES.get(m.get("guideId")),
                    "job_id": job.id, "ready": status.get("status", "done") == "done", "names": [],
                    "picture": None, "dir": job.dir})
    for c in out:
        d = c.pop("dir")
        if d is None:
            continue
        status = _read(d / "status.json")
        product = _read(d / "product.json")
        c["names"] = [n for n in (status.get("product"), status.get("filename"), product.get("name"),
                                  product.get("type")) if isinstance(n, str) and n]
        c["picture"] = _picture(d)
    return out


# ---- matching -----------------------------------------------------------------------------------

def _digits(text: str | None) -> str:
    return re.sub(r"\D", "", text or "")


def _norm_doc(text: str | None) -> str:
    return re.sub(r"[^0-9a-z]", "", catalog.fold(text))


def read_article(raw, text: str) -> str | None:
    """The article number as `002.141.57` (or `S290.289.17`), only if it is valid and its digits are in `text`."""
    article = normalize_article(str(raw or ""))
    if not article or article.removeprefix("s") not in _digits(text):
        return None
    d = article.removeprefix("s")
    return ("S" if article.startswith("s") else "") + f"{d[:3]}.{d[3:6]}.{d[6:]}"


def read_doc(raw, text: str) -> str | None:
    m = DOC_RE.search(str(raw or ""))
    if not m or _norm_doc(m.group(0)).removeprefix("aa") not in _norm_doc(text):
        return None
    return m.group(0).upper()


def _hay(c: dict) -> str:
    return catalog.fold(" ".join(filter(None, [c.get("product"), c.get("guide_id"), *c.get("names", [])])))


def match(cands: list[dict], seen: dict, picture: int | None, pictured: list[dict]) -> tuple[dict | None, str]:
    """The guide the camera picture is, and how it was found: article, doc, name, picture or none."""
    if seen["article"]:
        a = normalize_article(seen["article"])
        hit = next((c for c in cands if c.get("article") and normalize_article(c["article"]) == a), None)
        if hit:
            return hit, "article"
    if seen["doc"]:
        d = _norm_doc(seen["doc"])
        hit = next((c for c in cands if c.get("guide_id") and _norm_doc(c["guide_id"]) == d), None)
        if hit:
            return hit, "doc"
    if seen["name"]:
        words = catalog.fold(seen["name"]).split()
        named = [c for c in cands if words and all(re.search(rf"\b{re.escape(w)}\b", _hay(c)) for w in words)]
        if named:
            kind = catalog.fold(seen.get("type") or "").split()
            typed = [c for c in named if kind and any(w in _hay(c) for w in kind)]
            if len(typed) == 1 or len(named) == 1:
                return (typed or named)[0], "name"
            if typed or named:                       # several guides of that name: the picture decides, if it agrees
                pool = typed or named
                if picture and 1 <= picture <= len(pictured) and pictured[picture - 1] in pool:
                    return pictured[picture - 1], "name"
                return pool[0], "name"
    if picture and 1 <= picture <= len(pictured):
        return pictured[picture - 1], "picture"
    return None, "none"


def _thumb(path: Path, edge: int) -> bytes:
    im = Image.open(path)
    im.thumbnail((edge, edge))
    if im.mode in ("RGBA", "LA", "P"):
        im = im.convert("RGBA")
        bg = Image.new("RGB", im.size, (255, 255, 255))
        bg.paste(im, mask=im.split()[-1])
        im = bg
    out = io.BytesIO()
    im.convert("RGB").save(out, "JPEG", quality=82)
    return out.getvalue()


def _frame(jpeg: bytes) -> bytes:
    im = Image.open(io.BytesIO(jpeg))
    if max(im.size) <= FRAME_EDGE:
        return jpeg
    im.thumbnail((FRAME_EDGE, FRAME_EDGE))
    out = io.BytesIO()
    im.convert("RGB").save(out, "JPEG", quality=85)
    return out.getvalue()


def _number(v, lo=0.0, hi=1.0, default=0.0) -> float:
    return max(lo, min(hi, float(v))) if isinstance(v, (int, float)) and not isinstance(v, bool) else default


def _clean(v, n: int) -> str | None:
    if not isinstance(v, str):
        return None
    v = " ".join(v.split())[:n]
    return v or None


def _message(lang: str, key: str, **kw) -> str:
    return MESSAGES.get(lang, MESSAGES["en"])[key].format(**kw)


def _public(c: dict | None) -> dict | None:
    return {"job_id": c["job_id"], "product": c["product"], "ready": bool(c["ready"])} if c and c.get("job_id") else None


def answer(cands: list[dict], raw: dict, pictured: list[dict], lang: str) -> dict:
    """The response body from the model's JSON (`raw`)."""
    text = _clean(raw.get("text"), 200) or ""
    kind = raw.get("kind") if raw.get("kind") in KINDS else "unknown"
    seen = {"kind": kind, "name": _clean(raw.get("name"), 60), "type": _clean(raw.get("type"), 60),
            "article": read_article(raw.get("article"), text), "text": text}
    doc = read_doc(raw.get("doc_number"), text)
    if raw.get("article") and not seen["article"]:
        log.info("identify: dropped an article number that is not in the text read")
    picture = raw.get("picture") if isinstance(raw.get("picture"), int) and not isinstance(raw.get("picture"), bool) else None
    conf = _number(raw.get("confidence"))
    hit, how = match(cands, {**seen, "doc": doc}, picture, pictured)
    if how in ("article", "doc"):
        conf = max(conf, 0.9)
    found = _public(hit)
    # what to give POST /api/catalog/open: what was read, else the guide that matched but is not built here
    name = " ".join(filter(None, (seen["name"], seen["type"]))) or None
    # Another maker's box: the name alone ("Teknik bookcase") is too little to find its guide on the web, and the
    # model number is in the printed text. So the search gets the text as it was read.
    ikea = bool(seen["article"] or doc or re.search(r"\bikea\b", text, re.I))
    printed = _clean(text, 90) if not ikea and len(text.split()) >= 2 else None
    query = seen["article"] or printed or (name if seen["name"] else None) or doc
    if query is None and hit is not None:
        query = hit.get("article") or hit.get("guide_id") or hit.get("product")
    if found:
        message = _message(lang, "ready" if found["ready"] else "building", product=found["product"])
    elif query:
        what = seen["article"] or seen["name"] or doc or query
        message = _message(lang, "fetch" if ikea else "search", what=what)
    else:
        message = _message(lang, "unknown")
    return {"seen": seen, "match": found, "query": query, "confidence": round(conf, 2), "message": message,
            "matched_by": how}


def identify(store: JobStore, jpeg: bytes, lang: str = "en") -> dict:
    """One model call. Raises RuntimeError when the model cannot be reached or returns no JSON."""
    t0 = time.perf_counter()
    cands = candidates(store)
    pictured = [c for c in cands if c.get("picture") and c.get("job_id")][:MAX_PICTURES]
    parts: list = [PROMPT.format(pictures=PICTURES if pictured else NO_PICTURES),
                   "Image 1, the camera view:", ("image/jpeg", _frame(jpeg))]
    for i, c in enumerate(pictured, 1):
        try:
            parts += [f"Picture {i}:", ("image/jpeg", _thumb(c["picture"], THUMB_EDGE))]
        except OSError:
            parts += [f"Picture {i}: (missing)"]
    try:
        raw = json.loads(gemini.generate(MODEL, parts, timeout=TIMEOUT_S, thinking_budget=THINKING, schema=SCHEMA))
    except json.JSONDecodeError:
        raise RuntimeError("the model returned no usable JSON") from None
    except OSError as e:
        raise RuntimeError(f"{MODEL} did not answer: {type(e).__name__}") from None
    if not isinstance(raw, dict):
        raise RuntimeError("the model returned no usable JSON")
    out = answer(cands, raw, pictured, lang)
    out["latency_ms"] = round((time.perf_counter() - t0) * 1000)
    out["model"] = MODEL
    log.info("identify: kind=%s name=%s article=%s match=%s by %s (%s ms)", out["seen"]["kind"], out["seen"]["name"],
             out["seen"]["article"], (out["match"] or {}).get("job_id"), out["matched_by"], out["latency_ms"])
    return out


def simulated(store: JobStore, lang: str = "en") -> dict:
    """No model: the first guide built on this server, at confidence 0.5."""
    built = [c for c in candidates(store) if c.get("job_id")]
    first = next((c for c in built if c.get("ready")), built[0] if built else None)
    found = _public(first)
    return {"seen": {"kind": "unknown", "name": None, "type": None, "article": None, "text": ""},
            "match": found, "query": None, "confidence": 0.5,
            "message": _message(lang, "simulated" if found else "simulated_none"), "matched_by": "simulated",
            "simulated": True}
