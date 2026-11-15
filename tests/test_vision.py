"""POST /api/identify and POST /api/perception/{job_id}/locate. No network: the model calls are replaced."""
from __future__ import annotations

import base64
import io
import json

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from pipeline.perception import request as bench
from server import identify as vision
from server.app import create_app
from server.perception import locate as locator
from server.perception.decider import DecisionError
from server.settings import Settings
from tests.test_perception import MANIFEST, make_job

KEY = "test-key"


def jpeg_b64(grey: int = 120, size=(96, 72)) -> str:
    out = io.BytesIO()
    Image.new("RGB", size, (grey, grey, grey)).save(out, "JPEG")
    return base64.b64encode(out.getvalue()).decode()


def app_client(tmp_path, gemini_key="fake-key", simulate=False):
    settings = Settings(_env_file=None, demo_key=KEY, jobs_dir=tmp_path / "jobs", gemini_api_key=gemini_key,
                        perception_simulate=simulate, client_dist=tmp_path / "no-client", perception_logs=False)
    return TestClient(create_app(settings), headers={"X-Demo-Key": KEY})


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "fake-key")       # never reaches a network: every call is mocked
    bench.step_pack.cache_clear()
    bench.sheet_jpeg.cache_clear()
    with app_client(tmp_path) as c:
        yield c


def done_job(client, title="MALM desk drawer", guide_id="AA-516949-7", article=None, pdf=b"%PDF-1.4\n% malm\n",
             picture=True):
    job = client.app.state.store.create(pdf, "malm-desk-drawer__AA-516949-7.pdf")
    job.manifest.write_text(json.dumps({"title": title, "guideId": guide_id, "steps": [{"n": 1}], "parts": []}))
    if picture:
        (job.dir / "renders").mkdir(exist_ok=True)
        Image.new("RGB", (64, 64), (200, 180, 150)).save(job.dir / "renders" / "cover.png")
    job.set_status(status="done", stage="done", progress=1.0, **({"article": article} if article else {}))
    return job


def fake_model(monkeypatch, reply: dict, seen: list | None = None):
    def generate(model, parts, **kw):
        assert model == "gemini-4-hydrogen"
        if seen is not None:
            seen.append(parts)
        return json.dumps(reply)
    monkeypatch.setattr(vision.gemini, "generate", generate)


def reply(**kw) -> dict:
    base = {"kind": "box", "name": None, "type": None, "article": None, "doc_number": None, "text": "",
            "picture": None, "confidence": 0.8}
    return {**base, **kw}


# ---- identify ------------------------------------------------------------------------------------

def test_both_routes_need_the_key(client):
    make_job(client.app.state.settings.jobs_dir)
    for path in ("/api/identify", "/api/perception/kn-test/locate"):
        assert client.post(path, json={"image": jpeg_b64()}, headers={"X-Demo-Key": "wrong"}).status_code == 401


def test_identify_checks_the_image(client):
    assert client.post("/api/identify", json={}).status_code == 422
    assert client.post("/api/identify", json={"image": "not base64!!"}).status_code == 422
    png = io.BytesIO()
    Image.new("RGB", (8, 8)).save(png, "PNG")
    assert client.post("/api/identify", json={"image": base64.b64encode(png.getvalue()).decode()}).status_code == 422
    assert client.post("/api/identify", json={"image": base64.b64encode(b"\xff\xd8broken").decode()}).status_code == 422
    big = base64.b64encode(b"\xff\xd8" + b"\0" * (2 * 1024 * 1024)).decode()
    assert client.post("/api/identify", json={"image": big}).status_code in (413, 422)
    assert client.post("/api/identify", json={"image": jpeg_b64(), "lang": "fr"}).status_code == 422


def test_identify_matches_a_built_job_by_the_article_number_it_read(client, monkeypatch):
    job = done_job(client, article="00214157")
    sent = []
    fake_model(monkeypatch, reply(kind="label", name="MALM", type="desk", article="002.141.57",
                                  text="MALM desk 002.141.57 white"), sent)
    out = client.post("/api/identify", json={"image": jpeg_b64(), "lang": "en"}).json()
    assert out["seen"] == {"kind": "label", "name": "MALM", "type": "desk", "article": "002.141.57",
                           "text": "MALM desk 002.141.57 white"}
    assert out["match"] == {"job_id": job.id, "product": "MALM desk drawer", "ready": True}
    assert out["query"] == "002.141.57" and out["confidence"] >= 0.9
    assert out["message"] == "This looks like the MALM desk drawer. Its guide is ready."
    # the prompt shows the known guide's picture, never its numbers
    text = " ".join(p for p in sent[0] if isinstance(p, str))
    assert "Picture 1" in text and "00214157" not in text and "002.141.57" not in text


def test_identify_matches_by_name_and_type_and_by_document_number(client, monkeypatch):
    malm = done_job(client)
    lack = done_job(client, title="LACK side table", guide_id="AA-2606170-1", pdf=b"%PDF-1.4\n% lack\n")
    fake_model(monkeypatch, reply(kind="furniture", name="lack", type="side table", text=""))
    out = client.post("/api/identify", json={"image": jpeg_b64(), "lang": "el"}).json()
    assert out["match"]["job_id"] == lack.id and out["query"] == "lack side table"
    assert out["message"] == "Μοιάζει με LACK side table. Ο οδηγός είναι έτοιμος."
    fake_model(monkeypatch, reply(kind="parts", doc_number="AA-516949-7", text="AA-516949-7"))
    out = client.post("/api/identify", json={"image": jpeg_b64()}).json()
    assert out["match"]["job_id"] == malm.id and out["query"] == "AA-516949-7"


def test_identify_matches_a_finished_piece_by_its_picture(client, monkeypatch):
    first = done_job(client)
    second = done_job(client, title="LACK side table", guide_id="AA-2606170-1", pdf=b"%PDF-1.4\n% lack\n")
    second.set_status(created=first.status()["created"] + 1)             # newest first: LACK is Picture 1
    fake_model(monkeypatch, reply(kind="furniture", picture=2, confidence=0.7))
    out = client.post("/api/identify", json={"image": jpeg_b64()}).json()
    assert out["match"]["job_id"] == first.id and out["confidence"] == 0.7
    assert out["seen"]["article"] is None and out["query"] == "AA-516949-7"     # the matched guide's own id


def test_identify_never_returns_an_article_number_it_did_not_read(client, monkeypatch):
    done_job(client, article="00214157")
    fake_model(monkeypatch, reply(kind="furniture", name="MALM", type="desk", article="002.141.57", text="MALM"))
    out = client.post("/api/identify", json={"image": jpeg_b64()}).json()
    assert out["seen"]["article"] is None
    assert out["query"] == "MALM desk"                                   # by name: still the MALM job
    assert out["match"]["product"] == "MALM desk drawer"


def test_identify_unknown_product_gives_a_query_for_the_catalog(client, monkeypatch):
    done_job(client)
    fake_model(monkeypatch, reply(kind="box", name="HEMNES", type="bookcase", article="S29028917",
                                  text="HEMNES bookcase S290.289.17"))
    out = client.post("/api/identify", json={"image": jpeg_b64()}).json()
    assert out["match"] is None and out["query"] == "S290.289.17" and out["seen"]["article"] == "S290.289.17"
    assert out["message"] == "I read S290.289.17. I can fetch its guide from IKEA."
    fake_model(monkeypatch, reply(kind="unknown", confidence=0.1))
    out = client.post("/api/identify", json={"image": jpeg_b64()}).json()
    assert out["match"] is None and out["query"] is None and out["seen"]["kind"] == "unknown"
    assert "label on the box" in out["message"]


def test_identify_reports_a_model_failure_as_502(client, monkeypatch):
    def broken(*a, **kw):
        raise RuntimeError("gemini-4-hydrogen 500: boom")
    monkeypatch.setattr(vision.gemini, "generate", broken)
    r = client.post("/api/identify", json={"image": jpeg_b64()})
    assert r.status_code == 502 and "did not answer" in r.json()["detail"]


def test_identify_simulated_without_a_key(tmp_path, monkeypatch):
    def never(*a, **kw):
        raise AssertionError("no model call when simulated")
    monkeypatch.setattr(vision.gemini, "generate", never)
    with app_client(tmp_path, gemini_key="") as c:
        job = done_job(c)
        out = c.post("/api/identify", json={"image": jpeg_b64()}).json()
    assert out["simulated"] is True and out["confidence"] == 0.5
    assert out["match"] == {"job_id": job.id, "product": "MALM desk drawer", "ready": True}
    assert out["message"].startswith("Simulated")
    assert set(out) >= {"seen", "match", "query", "confidence", "message"}


def test_identify_lists_hand_placed_jobs_without_a_status_file(tmp_path):
    jobs = tmp_path / "jobs"
    d = make_job(jobs)
    (d / "walkthrough.glb").write_bytes(b"glTF")
    with app_client(tmp_path, simulate=True) as c:
        out = c.post("/api/identify", json={"image": jpeg_b64(), "lang": "el"}).json()
    assert out["match"] == {"job_id": "kn-test", "product": "KNAGGLIG box", "ready": True}
    assert out["simulated"] is True and out["message"].startswith("Προσομοίωση")


# ---- locate --------------------------------------------------------------------------------------

def step(n, p):
    return {"n": n, "p": p}


@pytest.mark.parametrize("ps, done, nxt", [
    ([0.9, 0.8, 0.7, 0.6], 4, 4),               # all done: next_step stays on the last step
    ([0.1, 0.2, 0.05, 0.1], 0, 1),              # nothing done
    ([0.9, 0.9, 0.1, 0.1], 2, 3),
    ([0.9, 0.1, 0.1, 0.9], 1, 2),               # a gap: one "done" after two "not done" is noise
    ([0.9, 0.4, 0.9, 0.1], 3, 4),               # one uncertain step inside the run is stepped over
    ([0.9, 0.9, 0.4, 0.1], 2, 3),               # a run does not end on an uncertain step
    ([0.9, None, 0.9, 0.2], 3, 4),              # a failed request counts as uncertain
    ([0.1, 0.1, 0.1, 0.4], 0, 1),               # one uncertain step at the end does not make the rest done
    # real answers (Decision 1, MALM drawer renders, Sun 15 Nov): step 1's parts are hidden later on
    ([0.168, 0.748, 0.807, 0.829, 0.914, 0.242, 0.523], 5, 6),     # render of step 5
    ([0.213, 0.691, 0.633, 0.917, 0.764, 0.883, 0.607], 7, 7),     # render of step 7: all done
    ([0.859, 0.117, 0.176, 0.078, 0.177, 0.171, 0.21], 1, 2),      # render of step 1
])
def test_done_through_logic(ps, done, nxt):
    order = list(range(1, len(ps) + 1))
    run = locator.run_of([step(n, p) for n, p in zip(order, ps)], order)
    assert (run["done_through"], run["next_step"]) == (done, nxt)
    assert 0.0 <= run["confidence"] <= 1.0


def test_done_through_confidence_is_high_when_the_answers_agree():
    order = [1, 2, 3, 4]
    clear = locator.run_of([step(n, p) for n, p in zip(order, [0.95, 0.9, 0.05, 0.1])], order)["confidence"]
    murky = locator.run_of([step(n, p) for n, p in zip(order, [0.5, 0.45, 0.4, 0.45])], order)["confidence"]
    assert clear > 0.8 > 0.5 > murky


def test_locate_messages():
    order = [1, 2, 3, 4]
    assert locator.message("en", order, 2, 3) == "Steps 1–2 look done. Next: step 3."
    assert locator.message("en", order, 1, 2) == "Step 1 looks done. Next: step 2."
    assert locator.message("en", order, 0, 1) == "Nothing looks done yet. Start with step 1."
    assert locator.message("en", order, 4, 4) == "All 4 steps look done."
    assert locator.message("el", order, 2, 3) == "Τα βήματα 1–2 φαίνονται έτοιμα. Επόμενο: βήμα 3."


def fake_decisions(monkeypatch, ps: dict, sent: list | None = None):
    """Decision 1 answers step_complete with ps[n]; a DecisionError instance in ps is raised."""
    def ask(inputs, questions, resolution="medium", timeout=60):
        assert list(questions) == ["step_complete"] and resolution == "medium"
        n = int(inputs[0].split("STEP ")[1].split()[0])
        if sent is not None:
            sent.append(n)
        p = ps[n]
        if isinstance(p, Exception):
            raise p
        return {"answers": {"step_complete": {"type": "noul", "noul": p, "confidence": max(p, 1 - p)}}, "latency_ms": 5}
    monkeypatch.setattr(locator.decisions, "ask", ask)


def test_locate_asks_every_step_and_finds_the_next_one(client, monkeypatch):
    make_job(client.app.state.settings.jobs_dir)
    sent = []
    fake_decisions(monkeypatch, {1: 0.92, 2: 0.81, 3: 0.12, 4: 0.2}, sent)
    out = client.post("/api/perception/kn-test/locate", json={"image": jpeg_b64()}).json()
    assert sorted(sent) == [1, 2, 3, 4]
    assert (out["next_step"], out["action"], out["done_through"]) == (3, 0, 2)
    assert out["steps"] == [{"n": 1, "complete": True, "p": 0.92}, {"n": 2, "complete": True, "p": 0.81},
                            {"n": 3, "complete": False, "p": 0.12}, {"n": 4, "complete": False, "p": 0.2}]
    assert out["message"] == "Steps 1–2 look done. Next: step 3." and "simulated" not in out
    assert 0.0 <= out["confidence"] <= 1.0


def test_locate_all_done_and_greek(client, monkeypatch):
    make_job(client.app.state.settings.jobs_dir)
    fake_decisions(monkeypatch, {1: 0.9, 2: 0.9, 3: 0.9, 4: 0.9})
    out = client.post("/api/perception/kn-test/locate", json={"image": jpeg_b64(), "lang": "el"}).json()
    assert (out["next_step"], out["done_through"]) == (4, 4)
    assert out["message"] == "Και τα 4 βήματα φαίνονται έτοιμα."


def test_locate_survives_one_failed_request_and_reports_total_failure(client, monkeypatch):
    make_job(client.app.state.settings.jobs_dir)
    fake_decisions(monkeypatch, {1: 0.9, 2: DecisionError("timeout"), 3: 0.9, 4: 0.1})
    out = client.post("/api/perception/kn-test/locate", json={"image": jpeg_b64()}).json()
    assert out["done_through"] == 3 and out["steps"][1] == {"n": 2, "complete": False, "p": 0.5, "error": "timeout"}
    billing = DecisionError("billing", "HTTP 402")
    fake_decisions(monkeypatch, {n: billing for n in range(1, 5)})
    r = client.post("/api/perception/kn-test/locate", json={"image": jpeg_b64()})
    assert r.status_code == 503 and "billing" in r.json()["detail"]


def test_locate_unknown_job_and_job_without_manifest(client):
    assert client.post("/api/perception/no-such-job/locate", json={"image": jpeg_b64()}).status_code == 404
    job = client.app.state.store.create(b"%PDF-1.4\n", "guide.pdf")
    assert client.post(f"/api/perception/{job.id}/locate", json={"image": jpeg_b64()}).status_code == 409
    make_job(client.app.state.settings.jobs_dir)
    assert client.post("/api/perception/kn-test/locate", json={"image": "nope"}).status_code == 422


def test_locate_simulated(tmp_path, monkeypatch):
    def never(*a, **kw):
        raise AssertionError("no Decision 1 call when simulated")
    monkeypatch.setattr(locator.decisions, "ask", never)
    make_job(tmp_path / "jobs")
    with app_client(tmp_path, simulate=True) as c:
        out = c.post("/api/perception/kn-test/locate", json={"image": jpeg_b64()}).json()
    assert out["simulated"] is True and (out["done_through"], out["next_step"], out["action"]) == (0, 1, 0)
    assert [s["n"] for s in out["steps"]] == [s["n"] for s in MANIFEST["steps"]]
    assert all(s["complete"] is False for s in out["steps"])
