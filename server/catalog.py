"""Guide catalog: the guides built on this server, plus the known golden guides, searchable by name or code.

Built guides are read from the job folders (status.json + manifest.json), so the catalog has no state of its own.
"""
from __future__ import annotations

import functools
import json
import unicodedata

from server.fetch_guide import normalize_article
from server.jobs import Job, JobStore
from server.orchestrator.runner import find_goldens

ACTIVE = ("queued", "running", "waiting_approval")
KNOWN_ARTICLES = {"AA-251870-22": "90289172"}      # guide id → IKEA article; checked on ikea.com/us


def fold(text: str | None) -> str:
    """Case- and accent-insensitive form, so "KNAGGLIG" finds "knägglig"."""
    decomposed = unicodedata.normalize("NFKD", text or "")
    return "".join(c for c in decomposed if not unicodedata.combining(c)).casefold()


@functools.cache
def known_guides() -> tuple[dict, ...]:
    out = []
    for sha, folder in find_goldens().items():
        plan = json.loads((folder / "plan.json").read_text())
        out.append({"product": plan.get("title") or folder.name, "guide_id": plan.get("guide_id"),
                    "guide": plan["guide"], "sha256": sha})
    return tuple(sorted(out, key=lambda g: g["product"]))


def _manifest(job: Job) -> dict:
    try:
        return json.loads(job.manifest.read_text())
    except (OSError, json.JSONDecodeError):
        return {}


def _built(store: JobStore) -> dict[str, dict]:
    """guide sha256 → catalog item of the newest done job for that guide."""
    built: dict[str, dict] = {}
    for job in store.all():                                  # newest first
        s = job.status()
        sha = s.get("guide_sha256")
        if s.get("status") != "done" or not sha or sha in built:
            continue
        m = _manifest(job)
        guide_id = m.get("guideId")
        built[sha] = {"product": m.get("title") or s.get("product") or s.get("title") or s.get("filename"),
                      "guide_id": guide_id, "article": s.get("article") or KNOWN_ARTICLES.get(guide_id),
                      "job_id": job.id, "steps": len(m.get("steps") or []), "parts": len(m.get("parts") or []),
                      "created": s.get("created"), "source": "built", "ready": True}
    return built


def items(store: JobStore) -> list[dict]:
    """Ready guides first (newest first), then the known guides nobody has built yet."""
    built = _built(store)
    known = []
    for g in known_guides():
        item = {"product": g["product"], "guide_id": g["guide_id"], "article": KNOWN_ARTICLES.get(g["guide_id"]),
                "guide": g["guide"], "job_id": None, "source": "known", "ready": False}
        done = built.pop(g["sha256"], None)
        if done:
            item.update({k: done[k] for k in ("job_id", "steps", "parts", "created", "ready")})
        known.append(item)
    ready = list(built.values()) + [k for k in known if k["ready"]]
    ready.sort(key=lambda i: i.get("created") or 0, reverse=True)
    return ready + [k for k in known if not k["ready"]]


def matches(item: dict, q: str) -> bool:
    article = normalize_article(q)
    if article and item.get("article") == article:
        return True
    hay = fold(" ".join(filter(None, (item.get("product"), item.get("guide_id"), item.get("article")))))
    return all(word in hay for word in fold(q).split())


def search(store: JobStore, q: str = "") -> list[dict]:
    return [i for i in items(store) if not q.strip() or matches(i, q)]


def job_for_guide(store: JobStore, sha: str) -> Job | None:
    """The newest done or still-running job for this PDF, so opening a guide twice never builds it twice."""
    jobs = [j for j in store.all() if j.status().get("guide_sha256") == sha]
    return next((j for j in jobs if j.status().get("status") == "done"), None) or \
        next((j for j in jobs if j.status().get("status") in ACTIVE), None)
