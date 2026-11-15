"""Guide finder: link handling and the safety rules, without the network or the agent."""
import asyncio

import httpx
import pytest

from pipeline import find_guide as fg

PDF = b"%PDF-1.4\n" + b"x" * 2000
PAGE = b"""<html><head><title>BILLY shelf - Maker</title></head><body>
<a href="/files/warranty.pdf">Warranty</a>
<a href="/files/billy-assembly-instructions.pdf">Assembly instructions</a>
<a href="/files/catalogue-2026.pdf">Catalogue</a></body></html>"""


def client(routes):
    def handler(request):
        hit = routes.get(str(request.url))
        if hit is None:
            return httpx.Response(404)
        return hit if isinstance(hit, httpx.Response) else httpx.Response(200, content=hit)
    return httpx.Client(transport=httpx.MockTransport(handler))


@pytest.fixture(autouse=True)
def all_public(monkeypatch):
    monkeypatch.setattr(fg, "public_host", lambda host: host.endswith(".example"))


def test_a_link_to_a_pdf_is_taken_as_it_is():
    got, _ = fg.direct("https://maker.example/g.pdf", client({"https://maker.example/g.pdf": PDF}))
    assert got.how == "link" and got.data == PDF and got.filename == "g.pdf"


def test_a_product_page_gives_its_assembly_pdf_not_the_warranty():
    c = client({"https://maker.example/p/billy": PAGE, "https://maker.example/files/billy-assembly-instructions.pdf": PDF})
    got, title = fg.direct("https://maker.example/p/billy", c)
    assert got.how == "page" and got.url.endswith("billy-assembly-instructions.pdf") and "BILLY" in title


def test_a_short_link_is_followed_and_every_hop_is_checked():
    c = client({"https://qr.example/x": httpx.Response(302, headers={"location": "https://maker.example/g.pdf"}),
                "https://maker.example/g.pdf": PDF})
    assert fg.direct("https://qr.example/x", c)[0].url == "https://maker.example/g.pdf"
    c = client({"https://qr.example/x": httpx.Response(302, headers={"location": "http://127.0.0.1:8000/healthz"})})
    with pytest.raises(fg.NotFound):
        fg.direct("https://qr.example/x", c)


@pytest.mark.parametrize("url", ["http://localhost/a.pdf", "http://127.0.0.1/a.pdf", "http://192.168.1.5/a.pdf",
                                 "http://169.254.169.254/latest/meta-data", "file:///etc/passwd", "ftp://maker.example/a.pdf"])
def test_private_addresses_and_other_schemes_are_refused(url, monkeypatch):
    monkeypatch.undo()                       # the real address check
    with pytest.raises(fg.NotFound):
        fg.fetch(url, 1000, client({}))


def test_a_page_without_a_guide_returns_a_hint_for_the_search():
    got, hint = fg.direct("https://maker.example/p", client({"https://maker.example/p": b"<title>Some chair</title><a href='/w/warranty.pdf'>w</a>"}))
    assert got is None and "Some chair" in hint


def test_something_that_only_claims_to_be_a_pdf_is_not_one():
    c = client({"https://maker.example/p": b"<a href='/assembly-instructions.pdf'>x</a>", "https://maker.example/assembly-instructions.pdf": b"<html>sign in</html>"})
    assert fg.direct("https://maker.example/p", c)[0] is None


def test_too_big_is_refused():
    with pytest.raises(fg.NotFound):
        fg.fetch("https://maker.example/g.pdf", 100, client({"https://maker.example/g.pdf": PDF}))


def test_without_the_agent_a_name_is_not_searched():
    with pytest.raises(fg.NotFound):
        asyncio.run(fg.find("billy bookcase", use_agent=False))


def test_the_agents_link_is_downloaded_and_checked_by_us(monkeypatch):
    async def fake(query, hint, emit):
        return {"found": True, "pdf_url": "https://maker.example/g.pdf", "product": "BILLY", "maker": "Maker"}
    monkeypatch.setattr(fg, "ask_agent", fake)
    monkeypatch.setattr(fg, "fetch", lambda url, cap, client=None: (PDF, "application/pdf", url))
    got = asyncio.run(fg.find("5901234123457"))
    assert got.how == "agent" and got.product == "BILLY" and got.data == PDF


def test_an_agent_link_to_this_machine_is_refused(monkeypatch):
    async def fake(query, hint, emit):
        return {"found": True, "pdf_url": "http://127.0.0.1:8000/files/x.pdf", "product": "x", "maker": "x"}
    monkeypatch.setattr(fg, "ask_agent", fake)
    with pytest.raises(fg.NotFound):
        asyncio.run(fg.find("anything"))
