"""Fetch an assembly guide PDF from the manufacturer's public site (IKEA only), at the person's request.

IKEA's search page needs JavaScript, so it is not used. A bare article number still works: IKEA redirects
`/p/<any-slug>-<digits>/` to the real product page, whose HTML links the assembly-instruction PDFs.
"""
from __future__ import annotations

import html as html_lib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import httpx

from server.jobs import write_atomic

UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/130.0.0.0 Safari/537.36")
TIMEOUT_S = 30.0
MAX_PDF_BYTES = 40 * 1024 * 1024
MAX_PAGE_BYTES = 8 * 1024 * 1024
ARTICLE_RE = re.compile(r"^(s?)\s*(\d{3})[\s.\-]?(\d{3})[\s.\-]?(\d{2})$", re.I)
PRODUCT_URL_RE = re.compile(r"^https?://(?:www\.)?ikea\.com/[a-z]{2}/[a-z]{2}/p/[a-z0-9\-]*?-(s?\d{8})/?(?:[?#].*)?$",
                            re.I)
PDF_RE = re.compile(r"https://www\.ikea\.com/[a-z]{2}/[a-z]{2}/assembly_instructions/[^\"'\s<>]+\.pdf")
TITLE_RE = re.compile(r"""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)""", re.I)


class FetchError(Exception):
    def __init__(self, message: str, status: int = 404):
        super().__init__(message)
        self.status = status


@dataclass
class FetchedGuide:
    data: bytes
    filename: str
    product: str
    article: str          # "90289172", or "s29028917" for a combination (S-number)


def normalize_article(text: str) -> str | None:
    """"902.891.72", "90289172", "S29028917", a product URL → "90289172" / "s29028917"; anything else → None."""
    text = (text or "").strip()
    m = PRODUCT_URL_RE.match(text)
    if m:
        return m.group(1).lower()
    m = ARTICLE_RE.match(text)
    if m:
        return m.group(1).lower() + m.group(2) + m.group(3) + m.group(4)
    # a product link from another IKEA shop (ikea.gr is run separately and has its own addresses): the article
    # number is in the link, as 8 digits or dotted like 902.891.72; the last one is the product's own
    if re.match(r"^https?://([a-z0-9-]+\.)*ikea\.[a-z.]+/", text, re.I):
        found = re.findall(r"(?<!\d)(s?)(\d{3})[.\-]?(\d{3})[.\-]?(\d{2})(?!\d)", text, re.I)
        if found:
            s, a, b, c = found[-1]
            return s.lower() + a + b + c
    return None


def product_page_url(text: str) -> str | None:
    """The product page for an IKEA URL or article number. A bare number goes to the US site, which redirects."""
    text = (text or "").strip()
    if PRODUCT_URL_RE.match(text):
        return text.split("?")[0].split("#")[0]
    article = normalize_article(text)
    return f"https://www.ikea.com/us/en/p/item-{article}/" if article else None


def find_pdf_links(page: str) -> list[str]:
    seen: dict[str, None] = {}
    for m in PDF_RE.finditer(page):
        seen.setdefault(html_lib.unescape(m.group(0)), None)
    return list(seen)


def product_name(page: str) -> str | None:
    m = TITLE_RE.search(page)
    if not m:
        return None
    return html_lib.unescape(m.group(1)).removesuffix("- IKEA").strip(" -") or None


def _get(client: httpx.Client, url: str, cap: int) -> tuple[bytes, str]:
    """The body (decoded, at most `cap` bytes) and the URL it came from after redirects."""
    try:
        with client.stream("GET", url) as r:
            if r.status_code == 404:
                raise FetchError("IKEA has no page for that product")
            if r.status_code >= 400:
                raise FetchError(f"IKEA answered {r.status_code}", 502)
            body = bytearray()
            for chunk in r.iter_bytes():
                body += chunk
                if len(body) > cap:
                    raise FetchError(f"The file is larger than {cap // (1024 * 1024)} MB", 413)
            return bytes(body), str(r.url)
    except httpx.HTTPError as e:
        raise FetchError(f"Could not reach IKEA ({type(e).__name__})", 502) from e


def fetch_ikea_guide(query: str, cache_dir: Path, client: httpx.Client | None = None) -> FetchedGuide:
    """Download the first assembly-instruction PDF of the product. Cached by article number under `cache_dir`."""
    article = normalize_article(query)
    page_url = product_page_url(query)
    if not article or not page_url:
        raise FetchError("Give an IKEA article number (e.g. 902.891.72) or an IKEA product page URL", 404)
    pdf_file, meta_file = cache_dir / f"{article}.pdf", cache_dir / f"{article}.json"
    try:
        meta = json.loads(meta_file.read_text())
        return FetchedGuide(pdf_file.read_bytes(), meta["filename"], meta["product"], article)
    except (OSError, KeyError, json.JSONDecodeError):
        pass

    own = client is None
    client = client or httpx.Client(headers={"User-Agent": UA, "Accept-Language": "en"}, timeout=TIMEOUT_S,
                                    follow_redirects=True)
    try:
        body, final_url = _get(client, page_url, MAX_PAGE_BYTES)
        if "/p/" not in urlparse(final_url).path:
            raise FetchError(f"IKEA has no product with the number {article.upper()}")
        text = body.decode("utf-8", "replace")
        links = find_pdf_links(text)
        if not links:
            raise FetchError("The product page lists no assembly instructions")
        pdf, _ = _get(client, links[0], MAX_PDF_BYTES)
    finally:
        if own:
            client.close()
    if not pdf.startswith(b"%PDF-"):
        raise FetchError("The assembly-instructions link did not return a PDF", 502)
    filename = links[0].rsplit("/", 1)[-1]
    product = product_name(text) or filename.split("__")[0].replace("-", " ")
    write_atomic(pdf_file, pdf)
    write_atomic(meta_file, json.dumps({"product": product, "filename": filename, "page": final_url,
                                        "pdf": links[0]}, ensure_ascii=False))
    return FetchedGuide(pdf, filename, product, article)


SEARCH_URL = "https://sik.search.blue.cdtapps.com/us/en/search-result-page"
_search_cache: dict[str, tuple[float, list[dict]]] = {}


def search_ikea(query: str, client: httpx.Client | None = None, size: int = 8) -> list[dict]:
    """Products whose name matches, from IKEA's public product search (the one ikea.com's own search box calls).
    The international (US) catalogue: the Greek shop is run separately and has no such search, but the product
    names and article numbers are the same. Each: {name, type, article, url, image}. Cached for 10 minutes."""
    import time

    query = " ".join((query or "").split())[:60]
    if len(query) < 2:
        return []
    hit = _search_cache.get(query.lower())
    if hit and time.monotonic() - hit[0] < 600:
        return hit[1]
    own = client is None
    client = client or httpx.Client(headers={"User-Agent": UA}, timeout=10, follow_redirects=True)
    try:
        r = client.get(SEARCH_URL, params={"q": query, "types": "PRODUCT", "size": size})
        if r.status_code != 200:
            raise FetchError(f"IKEA's search answered {r.status_code}", 502)
        items = ((r.json().get("searchResultPage") or {}).get("products") or {}).get("main", {}).get("items") or []
    except (httpx.HTTPError, ValueError) as e:
        raise FetchError(f"Could not reach IKEA's search ({type(e).__name__})", 502) from e
    finally:
        if own:
            client.close()
    out = []
    for it in items:
        p = it.get("product") or {}
        article = str(p.get("itemNo") or "")
        if not re.fullmatch(r"s?\d{8}", article, re.I):
            continue
        out.append({"name": p.get("name"), "type": p.get("typeName"), "article": article.lower(),
                    "url": p.get("pipUrl"), "image": p.get("mainImageUrl")})
    _search_cache[query.lower()] = (time.monotonic(), out)
    return out
