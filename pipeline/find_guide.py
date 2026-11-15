"""Find the assembly guide PDF for a product from what the box gives: a QR link, a barcode number or a name.

    .venv/bin/python -m pipeline.find_guide "<url | barcode | product name>" [out.pdf]
    found = await find("https://maker.example/qr/12345", emit=print)     # Found(data, filename, product, maker, url, how)

Two stages, the cheap one first:
  1. direct   the text is a link: follow it. If it is a PDF, done. If it is a page, take the link on it that
              looks most like assembly instructions.
  2. agent    otherwise a short agent session with web search and page reading (Claude Agent SDK) names the PDF
              on the maker's own site. Our code then downloads and checks it; the agent never hands over a file.

Every download goes through `fetch`: http(s) only, public addresses only (a QR code is untrusted input and
must not reach this machine's own network), at most 5 redirects, size caps, and a PDF must start with %PDF.
"""
from __future__ import annotations

import asyncio
import html as html_lib
import ipaddress
import json
import os
import re
import socket
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urljoin, urlparse

import httpx

UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/130.0.0.0 Safari/537.36")
MAX_PDF, MAX_PAGE, TIMEOUT_S = 40 << 20, 4 << 20, 25.0
MODEL = os.environ.get("AGJ_FIND_MODEL", "claude-sonnet-5-5")
MAX_USD = float(os.environ.get("AGJ_FIND_MAX_USD", "0.6"))
MAX_S = float(os.environ.get("AGJ_FIND_MAX_S", "120"))
GOOD = ("assembly", "instruction", "manual", "montage", "aufbau", "anleitung", "notice", "istruzioni", "montaje",
        "οδηγ", "συναρμολ", "guide")
BAD = ("warranty", "catalog", "brochure", "privacy", "terms", "energy", "datasheet", "declaration", "safety-data")
LINK = re.compile(r"""(?:href|data-href|data-url|src)\s*=\s*["']([^"'<>\s]+)["']""", re.I)
TITLE = re.compile(r"<title[^>]*>([^<]{1,200})", re.I)


class NotFound(Exception):
    pass


@dataclass
class Found:
    data: bytes
    filename: str
    product: str
    maker: str
    url: str
    how: str            # "link" | "page" | "agent"


def is_url(text: str) -> bool:
    u = urlparse(text.strip())
    return u.scheme in ("http", "https") and bool(u.hostname)


def public_host(host: str) -> bool:
    """True only if every address the name resolves to is a public one."""
    try:
        infos = socket.getaddrinfo(host, None)
    except OSError:
        return False
    for info in infos:
        ip = ipaddress.ip_address(info[4][0].split("%")[0])
        if not ip.is_global:
            return False
    return bool(infos)


def fetch(url: str, cap: int, client: httpx.Client | None = None) -> tuple[bytes, str, str]:
    """(body, content type, final url). Redirects are followed by hand so every hop is checked."""
    own = client is None
    client = client or httpx.Client(timeout=TIMEOUT_S, headers={"User-Agent": UA, "Accept-Language": "en,el;q=0.8"})
    try:
        for _ in range(6):
            u = urlparse(url)
            if u.scheme not in ("http", "https") or not u.hostname or not public_host(u.hostname):
                raise NotFound(f"not a public web address: {url[:120]}")
            with client.stream("GET", url, follow_redirects=False) as r:
                if r.status_code in (301, 302, 303, 307, 308) and r.headers.get("location"):
                    url = urljoin(url, r.headers["location"])
                    continue
                if r.status_code != 200:
                    raise NotFound(f"{u.hostname} answered {r.status_code}")
                body = b""
                for chunk in r.iter_bytes():
                    body += chunk
                    if len(body) > cap:
                        raise NotFound(f"more than {cap >> 20} MB at {u.hostname}")
                return body, r.headers.get("content-type", "").split(";")[0].strip().lower(), url
        raise NotFound("too many redirects")
    except httpx.HTTPError as e:
        raise NotFound(f"could not reach {urlparse(url).hostname}: {type(e).__name__}") from None
    finally:
        if own:
            client.close()


def is_pdf(body: bytes) -> bool:
    return body[:1024].lstrip().startswith(b"%PDF")


def pdf_links(page: str, base: str) -> list[tuple[int, str]]:
    """PDF links on a page, best first. Score: words that mean assembly instructions, in the link or its label."""
    out = {}
    for m in LINK.finditer(page):
        href = html_lib.unescape(m.group(1))
        if ".pdf" not in href.lower():
            continue
        url = urljoin(base, href)
        near = (href + " " + re.sub(r"<[^>]*>", " ", page[m.end():m.end() + 120])).lower()   # the link and its own label
        score = sum(2 for w in GOOD if w in href.lower()) + sum(1 for w in GOOD if w in near) - sum(3 for w in BAD if w in near)
        out[url] = max(score, out.get(url, -99))
    return sorted(((s, u) for u, s in out.items()), reverse=True)


def name_of(url: str) -> str:
    name = re.sub(r"[^A-Za-z0-9._-]+", "-", Path(urlparse(url).path).name or "guide.pdf")[:80]
    return name if name.lower().endswith(".pdf") else name + ".pdf"


def direct(link: str, client: httpx.Client | None = None) -> tuple[Found | None, str]:
    """Follow a link. Returns (guide or None, what the page is about, to help the agent)."""
    body, kind, final = fetch(link, MAX_PDF, client)
    host = urlparse(final).hostname or ""
    if is_pdf(body):
        return Found(body, name_of(final), "", host, final, "link"), ""
    page = body[:MAX_PAGE].decode("utf-8", "replace")
    title = html_lib.unescape((TITLE.search(page) or [None, ""])[1]).strip()
    for score, url in pdf_links(page, final)[:3]:
        if score < 2:
            break
        try:
            data, _, got = fetch(url, MAX_PDF, client)
        except NotFound:
            continue
        if is_pdf(data):
            return Found(data, name_of(got), title, host, got, "page"), title
    return None, f"{title} ({final})" if title else final


ASK = """A person scanned or typed this from the box of a piece of flat-pack furniture or a similar product that
must be assembled:

    {query}
{hint}
Find the manufacturer's ASSEMBLY INSTRUCTIONS for exactly this product as a PDF file. Use web search and read pages.
Rules:
- Prefer the manufacturer's own site, then a large retailer that hosts the maker's PDF. No file-sharing sites,
  no sites that ask for a sign-in or payment.
- It must be the step-by-step assembly guide, not a user manual, warranty, catalogue or data sheet.
- If the text is a barcode number (EAN/UPC), first find which product it is, then its guide.
- The link must point to the PDF file itself. Check it if you can.
- If several sizes or variants exist and the text does not say which, pick the one that matches best and say so in "note".
- Do not guess a link. If you cannot find the guide, say so.

Finish with one JSON object on the last line and nothing after it:
{{"found": true, "pdf_url": "https://...", "product": "name of the product", "maker": "manufacturer", "note": ""}}
or {{"found": false, "product": "what you think it is, or empty", "maker": "", "note": "what you tried"}}"""


async def ask_agent(query_text: str, hint: str, emit) -> dict:
    from claude_agent_sdk import AssistantMessage, ClaudeAgentOptions, ClaudeSDKError, ResultMessage, ToolUseBlock, query
    options = ClaudeAgentOptions(
        model=MODEL, tools=["WebSearch", "WebFetch"], allowed_tools=["WebSearch", "WebFetch"],
        setting_sources=[], max_turns=20, max_budget_usd=MAX_USD,
        env={"CLAUDE_AGENT_SDK_CLIENT_APP": "assembly-guide-agent"})
    prompt = ASK.format(query=query_text[:500], hint=f"\nThe link leads to: {hint[:300]}\n" if hint else "")
    result, text = None, ""
    try:
        async with asyncio.timeout(MAX_S):
            async for message in query(prompt=prompt, options=options):
                if isinstance(message, AssistantMessage):
                    for block in message.content:
                        if isinstance(block, ToolUseBlock):
                            what = block.input.get("query") or block.input.get("url") or ""
                            emit("tool_call", ("Searching the web: " if block.name == "WebSearch" else "Reading ") + str(what)[:120])
                if isinstance(message, ResultMessage):
                    result = message
    except TimeoutError:
        raise NotFound(f"the search took more than {MAX_S:.0f} s") from None
    except ClaudeSDKError as e:
        raise NotFound(f"the search agent failed ({type(e).__name__})") from None
    text = getattr(result, "result", "") or ""
    answer = None
    for line in reversed(text.strip().splitlines()):
        m = re.search(r"\{.*\}", line)
        if m:
            try:
                answer = json.loads(m.group(0))
                break
            except json.JSONDecodeError:
                continue
    if not isinstance(answer, dict):
        raise NotFound("the search agent gave no usable answer")
    answer["cost_usd"], answer["turns"] = getattr(result, "total_cost_usd", None), getattr(result, "num_turns", None)
    return answer


async def find(text: str, emit=lambda *a, **k: None, use_agent: bool = True) -> Found:
    text, hint, t0 = text.strip(), "", time.perf_counter()
    if not text:
        raise NotFound("nothing to look for")
    if is_url(text):
        emit("thought", "Following the link from the code.")
        try:
            got, hint = await asyncio.to_thread(direct, text)
        except NotFound as e:
            got, hint = None, f"(the link did not open: {e})"
        if got:
            emit("thought", f"Found a guide PDF on {got.maker} ({len(got.data) / 1e6:.1f} MB, {time.perf_counter() - t0:.1f} s).")
            return got
    if not use_agent:
        raise NotFound("the link does not lead to an assembly guide" if is_url(text) else "only links can be followed without the search agent")
    emit("thought", "Searching the web for this product's assembly guide.")
    answer = await ask_agent(text, hint, emit)
    if not answer.get("found") or not is_url(str(answer.get("pdf_url", ""))):
        raise NotFound("no assembly guide found" + (f" for {answer['product']}" if answer.get("product") else "")
                       + (f": {answer['note']}"[:200] if answer.get("note") else ""))
    try:
        data, _, final = await asyncio.to_thread(fetch, answer["pdf_url"], MAX_PDF)
    except NotFound as e:
        raise NotFound(f"the guide link the search gave does not open: {e}") from None
    if not is_pdf(data):
        got, _ = await asyncio.to_thread(direct, answer["pdf_url"])      # the agent named a page, not the file
        if not got:
            raise NotFound("the search gave a page, not a PDF guide")
        data, final = got.data, got.url
    emit("thought", f"Found: {answer.get('product') or 'guide'} from {answer.get('maker') or urlparse(final).hostname} "
                    f"({len(data) / 1e6:.1f} MB, {time.perf_counter() - t0:.0f} s).")
    return Found(data, name_of(final), str(answer.get("product") or ""), str(answer.get("maker") or urlparse(final).hostname),
                 final, "agent")


if __name__ == "__main__":
    def show(kind, msg, **_):
        print(f"  [{kind}] {msg}", flush=True)
    t = time.perf_counter()
    try:
        f = asyncio.run(find(sys.argv[1], show))
    except NotFound as e:
        print(f"NOT FOUND: {e} ({time.perf_counter() - t:.0f} s)")
        sys.exit(1)
    if len(sys.argv) > 2:
        Path(sys.argv[2]).write_bytes(f.data)
    print("RESULT " + json.dumps({"how": f.how, "product": f.product, "maker": f.maker, "url": f.url, "filename": f.filename,
                                  "bytes": len(f.data), "seconds": round(time.perf_counter() - t, 1)}))
