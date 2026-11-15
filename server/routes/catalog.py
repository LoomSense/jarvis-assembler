"""Find a guide by product name or code; open its walkthrough, or start building it. Every route needs the demo key."""
from __future__ import annotations

import asyncio
import hashlib
import json
import os

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from pipeline import find_guide
from server import catalog
from server.auth import require_key
from server.fetch_guide import FetchError, fetch_ikea_guide, normalize_article, product_photo, search_ikea
from server.routes.jobs import start_job
from server.settings import ROOT

router = APIRouter(dependencies=[Depends(require_key)])
# What to do with a code or name that is not a known guide or an IKEA article: `links` (default) follows a link to
# the maker's page or PDF, `agent` also searches the web with a short agent session (costs a few cents), `off` neither.
FIND = os.environ.get("AGJ_FIND_GUIDE", "links")


class OpenBody(BaseModel):
    q: str = Field(min_length=1, max_length=500)    # article number, product name, guide id or IKEA product URL
    size: str | None = None


@router.get("/api/catalog")
async def list_catalog(request: Request, q: str = "") -> dict:
    return {"items": catalog.search(request.app.state.store, q[:500])}


@router.get("/api/catalog/ikea")
async def search_ikea_products(q: str = "") -> dict:
    """Products by name from IKEA's public search: `{items: [{name, type, article, url, image}]}`. Open one with
    POST /api/catalog/open {q: article}; its guide is fetched from IKEA and built."""
    try:
        return {"items": await asyncio.to_thread(search_ikea, q)}
    except FetchError as e:
        raise HTTPException(e.status, str(e)) from e


def _same(a: str, b: str | None) -> bool:
    norm = lambda s: catalog.fold(s).replace(".", "").replace(" ", "").replace("-", "")  # noqa: E731
    return bool(b) and norm(a) == norm(b)


@router.post("/api/catalog/open")
async def open_guide(request: Request, body: OpenBody) -> dict:
    """A built guide opens at once (`ready: true`). Otherwise the PDF comes from the known guides or from the
    manufacturer's site and a build starts (`ready: false`; follow it with the job routes)."""
    store, q = request.app.state.store, body.q.strip()
    if not q:
        raise HTTPException(422, "Type a product name, an article number or a product page URL")
    found = catalog.search(store, q)
    exact = [i for i in found if _same(q, i.get("product")) or _same(q, i.get("guide_id")) or _same(q, i.get("article"))]
    if len({i.get("guide_id") or i["product"] for i in found}) > 1 and not exact:
        # "box" or "table" names several guides: say which, instead of opening the first one
        raise HTTPException(409, {"message": f"Several guides match “{q[:80]}”. Choose one.", "items": found[:8]})
    found = exact or found
    ready = next((i for i in found if i["ready"]), None)
    if ready:
        return {"job_id": ready["job_id"], "ready": True, "product": ready["product"]}
    known = next((i for i in found if i["source"] == "known"), None)
    if known:
        pdf = (ROOT / known["guide"]).read_bytes()
        filename, product, article = known["guide"].rsplit("/", 1)[-1], known["product"], known["article"]
    elif normalize_article(q):
        try:
            got = await asyncio.to_thread(fetch_ikea_guide, q, store.root / "_catalog")
        except FetchError as e:
            raise HTTPException(e.status, str(e)) from e
        pdf, filename, product, article = got.data, got.filename, got.product, got.article
    elif FIND != "off" and (FIND == "agent" or find_guide.is_url(q)):
        # any other maker: follow the link from the box's QR code, or let the search agent find the guide (D22)
        try:
            got = await find_guide.find(q, use_agent=FIND == "agent")
        except find_guide.NotFound as e:
            raise HTTPException(404, f"No guide found for “{q[:80]}”: {e}") from e
        pdf, filename, product, article = got.data, got.filename, got.product or got.filename, ""
    else:
        raise HTTPException(404, f"No guide matches “{q[:80]}”. Try the IKEA article number "
                                 "(e.g. 902.891.72) or paste the product page URL.")
    existing = catalog.job_for_guide(store, hashlib.sha256(pdf).hexdigest())
    if existing:
        return {"job_id": existing.id, "ready": existing.status().get("status") == "done", "product": product}
    extra = {}
    photo = await asyncio.to_thread(product_photo, article) if article else None
    if photo:                                          # the agent reads the real colours from it
        extra = {"product.jpg": photo[0], "product.json": json.dumps(photo[1], ensure_ascii=False).encode()}
    job = start_job(request, pdf, filename, body.size, files=extra, product=product, article=article)
    return {"job_id": job.id, "ready": False, "product": product}
