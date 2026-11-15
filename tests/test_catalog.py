"""Catalog and manufacturer-fetch tests. No network: the IKEA site is replaced by a mock transport."""
from __future__ import annotations

import json

import httpx
import pytest
from fastapi.testclient import TestClient

from server.app import create_app
from server.fetch_guide import FetchError, FetchedGuide, fetch_ikea_guide, find_pdf_links, normalize_article
from server.settings import Settings

KEY = "test-key"
FAKE_PDF = b"%PDF-1.4\n% a guide nobody has built\n"
PAGE = """<html><head><meta property="og:title" content="MARKUS office chair, Vissle dark gray  - IKEA"></head>
<body><a href="https://www.ikea.com/us/en/assembly_instructions/markus-office-chair__AA-251870-22-100.pdf">PDF</a>
<a href="https://www.ikea.com/us/en/assembly_instructions/markus-office-chair__AA-251870-22-100.pdf">again</a>
<a href="https://www.ikea.com/us/en/assembly_instructions/markus-tilt__AA-999-1.pdf">second</a></body></html>"""


@pytest.fixture()
def client(tmp_path):
    settings = Settings(_env_file=None, demo_key=KEY, jobs_dir=tmp_path / "jobs", gemini_api_key="",
                        client_dist=tmp_path / "no-client")
    with TestClient(create_app(settings), headers={"X-Demo-Key": KEY}) as c:
        yield c


def fake_done_job(client, pdf=FAKE_PDF, title="KNÄGGLIG kiste", guide_id="AA-1-1"):
    job = client.app.state.store.create(pdf, "guide.pdf")
    job.manifest.write_text(json.dumps({"title": title, "guideId": guide_id, "steps": [{"n": 1}, {"n": 2}],
                                        "parts": [{"id": "A"}]}))
    job.set_status(status="done", stage="done", progress=1.0)
    return job


def test_catalog_needs_the_key(client):
    assert client.get("/api/catalog", headers={"X-Demo-Key": "wrong"}).status_code == 401
    assert client.post("/api/catalog/open", json={"q": "x"}, headers={"X-Demo-Key": "wrong"}).status_code == 401


def test_catalog_lists_built_and_known_guides(client):
    job = fake_done_job(client)
    items = client.get("/api/catalog").json()["items"]
    built = [i for i in items if i["source"] == "built"]
    assert built == [{"product": "KNÄGGLIG kiste", "guide_id": "AA-1-1", "article": None, "job_id": job.id,
                      "steps": 2, "parts": 1, "created": job.status()["created"], "source": "built", "ready": True}]
    known = {i["guide_id"]: i for i in items if i["source"] == "known"}
    assert {"AA-1159337-7", "AA-251870-22"} <= set(known)
    assert known["AA-251870-22"]["article"] == "90289172" and known["AA-251870-22"]["job_id"] is None


def test_catalog_keeps_the_newest_done_job_per_guide(client):
    fake_done_job(client)
    newer = fake_done_job(client)
    newer.set_status(created=newer.status()["created"] + 1)
    built = [i for i in client.get("/api/catalog").json()["items"] if i["source"] == "built"]
    assert [i["job_id"] for i in built] == [newer.id]


def test_search_ignores_case_and_accents(client):
    fake_done_job(client)
    for q in ("knagglig", "KNÄGGLIG", "knägglig kiste", "aa-1-1"):
        products = [i["product"] for i in client.get("/api/catalog", params={"q": q}).json()["items"]]
        assert "KNÄGGLIG kiste" in products, q
    assert [i["product"] for i in client.get("/api/catalog", params={"q": "KNAGGLIG box"}).json()["items"]] \
        == ["KNAGGLIG box"]
    assert client.get("/api/catalog", params={"q": "902.891.72"}).json()["items"][0]["product"] \
        == "MARKUS office chair"
    assert client.get("/api/catalog", params={"q": "sofa"}).json()["items"] == []


def test_open_a_built_guide_is_ready_at_once(client):
    job = fake_done_job(client)
    assert client.post("/api/catalog/open", json={"q": "knagglig kiste"}).json() \
        == {"job_id": job.id, "ready": True, "product": "KNÄGGLIG kiste"}


def test_open_a_known_guide_starts_one_build(client):
    first = client.post("/api/catalog/open", json={"q": "MARKUS", "size": "62 x 60 x 135 cm"}).json()
    assert first["ready"] is False and first["product"] == "MARKUS office chair"
    status = client.get(f"/api/jobs/{first['job_id']}").json()
    assert status["filename"] == "markus-office-chair__AA-251870-22.pdf"
    again = client.post("/api/catalog/open",
                        json={"q": "https://www.ikea.com/us/en/p/markus-office-chair-vissle-dark-gray-90289172/"})
    assert again.json()["job_id"] == first["job_id"]                  # the same build, not a second one


def test_open_fetches_unknown_articles_from_the_manufacturer(client, monkeypatch):
    calls = []

    def fake_fetch(q, cache_dir):
        calls.append((q, cache_dir.name))
        return FetchedGuide(FAKE_PDF, "stefan-chair__AA-21977-11-2.pdf", "STEFAN chair", "00211088")

    monkeypatch.setattr("server.routes.catalog.fetch_ikea_guide", fake_fetch)
    r = client.post("/api/catalog/open", json={"q": "002.110.88"}).json()
    assert r["ready"] is False and r["product"] == "STEFAN chair"
    assert calls == [("002.110.88", "_catalog")]
    job = client.app.state.store.get(r["job_id"])
    assert job.status()["article"] == "00211088" and job.guide.read_bytes() == FAKE_PDF


def test_open_with_nothing_to_find_is_404(client, monkeypatch):
    def no_product(q, cache_dir):
        raise FetchError("IKEA has no product with the number 12345678")

    monkeypatch.setattr("server.routes.catalog.fetch_ikea_guide", no_product)
    r = client.post("/api/catalog/open", json={"q": "a sofa nobody makes"})
    assert r.status_code == 404 and "article number" in r.json()["detail"]
    r = client.post("/api/catalog/open", json={"q": "123.456.78"})
    assert r.status_code == 404 and "12345678" in r.json()["detail"]
    assert client.post("/api/catalog/open", json={"q": ""}).status_code == 422
    assert client.get("/api/jobs").json()["jobs"] == []


@pytest.mark.parametrize("text, article", [
    ("002.110.88", "00211088"), ("00211088", "00211088"), ("S29028917", "s29028917"), ("s290.289.17", "s29028917"),
    ("902.891.72", "90289172"), (" 902 891 72 ", "90289172"), ("902-891-72", "90289172"),
    ("https://www.ikea.com/us/en/p/markus-office-chair-vissle-dark-gray-90289172/", "90289172"),
    ("https://www.ikea.com/gr/el/p/markus-s29028917/?foo=1", "s29028917"),
    ("markus", None), ("1234567", None), ("902.891.721", None), ("https://example.com/p/x-90289172/", None),
])
def test_article_numbers_in_any_common_form(text, article):
    assert normalize_article(text) == article


def test_pdf_links_are_found_in_a_product_page():
    assert find_pdf_links(PAGE) == [
        "https://www.ikea.com/us/en/assembly_instructions/markus-office-chair__AA-251870-22-100.pdf",
        "https://www.ikea.com/us/en/assembly_instructions/markus-tilt__AA-999-1.pdf"]
    assert find_pdf_links("<a href='https://evil.example/assembly_instructions/x.pdf'>") == []


def _ikea(pdf: bytes, seen: list[str]) -> httpx.Client:
    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(str(request.url))
        if request.url.path == "/us/en/p/item-90289172/":
            return httpx.Response(301, headers={"Location": "https://www.ikea.com/us/en/p/markus-90289172/"})
        if request.url.path == "/us/en/p/markus-90289172/":
            return httpx.Response(200, html=PAGE)
        if request.url.path.endswith(".pdf"):
            return httpx.Response(200, content=pdf)
        return httpx.Response(404)
    return httpx.Client(transport=httpx.MockTransport(handler), follow_redirects=True)


def test_fetch_follows_the_article_redirect_and_caches(tmp_path):
    seen: list[str] = []
    got = fetch_ikea_guide("902.891.72", tmp_path, client=_ikea(FAKE_PDF, seen))
    assert (got.data, got.article, got.product) == (FAKE_PDF, "90289172", "MARKUS office chair, Vissle dark gray")
    assert got.filename == "markus-office-chair__AA-251870-22-100.pdf"
    assert (tmp_path / "90289172.pdf").read_bytes() == FAKE_PDF
    seen.clear()
    assert fetch_ikea_guide("90289172", tmp_path, client=_ikea(FAKE_PDF, seen)).data == FAKE_PDF
    assert seen == []                                                  # served from the cache


def test_fetch_refuses_what_is_not_a_pdf(tmp_path):
    with pytest.raises(FetchError, match="not return a PDF"):
        fetch_ikea_guide("902.891.72", tmp_path, client=_ikea(b"<html>blocked</html>", []))
    assert not (tmp_path / "90289172.pdf").exists()
    with pytest.raises(FetchError):
        fetch_ikea_guide("123.456.78", tmp_path, client=_ikea(FAKE_PDF, []))


def test_links_from_other_ikea_shops_give_the_article_number():
    from server.fetch_guide import normalize_article
    assert normalize_article("https://www.ikea.gr/el/products/kathismata/karekles-grafeiou/markus-karekla-grafeiou-90289172/") == "90289172"
    assert normalize_article("https://www.ikea.gr/el/products/markus/902.891.72") == "90289172"
    assert normalize_article("https://example.com/markus-90289172/") is None


def test_a_query_that_names_several_guides_is_not_opened_blindly(client):
    r = client.post("/api/catalog/open", json={"q": "a"})
    assert r.status_code == 409
    assert len(r.json()["detail"]["items"]) > 1


def test_ikea_product_search_by_name(client, monkeypatch):
    import httpx
    import server.fetch_guide as fg
    page = {"searchResultPage": {"products": {"main": {"items": [
        {"product": {"name": "BILLY", "typeName": "Bookcase", "itemNo": "20522046",
                     "pipUrl": "https://www.ikea.com/us/en/p/billy-bookcase-white-20522046/", "mainImageUrl": "https://x/i.jpg"}},
        {"product": {"name": "Gift card", "itemNo": "not-a-number"}}]}}}}
    transport = httpx.MockTransport(lambda req: httpx.Response(200, json=page))
    real = httpx.Client
    monkeypatch.setattr(fg.httpx, "Client", lambda **kw: real(transport=transport, **kw))
    fg._search_cache.clear()
    items = client.get("/api/catalog/ikea?q=billy").json()["items"]
    assert items == [{"name": "BILLY", "type": "Bookcase", "article": "20522046",
                      "url": "https://www.ikea.com/us/en/p/billy-bookcase-white-20522046/", "image": "https://x/i.jpg",
                      "design": None, "colors": []}]
    assert client.get("/api/catalog/ikea?q=b").json()["items"] == []


def test_open_follows_a_link_from_a_code_to_another_makers_guide(client, monkeypatch):
    from pipeline import find_guide
    from server.routes import catalog as routes

    async def fake_find(q, emit=None, use_agent=True):
        assert q == "https://maker.example/qr/123" and use_agent is False     # links only by default: no paid search
        return find_guide.Found(b"%PDF-1.4\n% another maker\n", "shelf.pdf", "Shelf 80", "maker.example", q, "page")
    monkeypatch.setattr(routes.find_guide, "find", fake_find)
    monkeypatch.setattr(routes, "start_job", lambda request, pdf, filename, size, files=None, **st: type("J", (), {"id": "j1", "st": st})())
    r = client.post("/api/catalog/open", json={"q": "https://maker.example/qr/123"})
    assert r.status_code == 200 and r.json() == {"job_id": "j1", "ready": False, "product": "Shelf 80"}


def test_open_does_not_search_the_web_for_a_name_unless_switched_on(client, monkeypatch):
    from server.routes import catalog as routes
    called = []

    async def fake_find(q, emit=None, use_agent=True):
        called.append(q)
        raise routes.find_guide.NotFound("nothing")
    monkeypatch.setattr(routes.find_guide, "find", fake_find)
    assert client.post("/api/catalog/open", json={"q": "some unknown shelf"}).status_code == 404 and called == []
    monkeypatch.setattr(routes, "FIND", "agent")
    r = client.post("/api/catalog/open", json={"q": "some unknown shelf"})
    assert r.status_code == 404 and called == ["some unknown shelf"] and "No guide found" in r.json()["detail"]
