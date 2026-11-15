"""Parts inventory before assembly (pipeline/inventory.py, POST/GET /api/jobs/{id}/inventory). The model is mocked."""
from __future__ import annotations

import io
import json
import shutil

import pytest
from PIL import Image

from pipeline import inventory
from server.settings import ROOT
from tests.test_server import client  # noqa: F401  (client is a fixture)

PLAN = ROOT / "eval/golden/knagglig/plan.json"     # 4 part types, hardware 108363 x4 and 108560 x24


def photo(w=640, h=480, exif_orientation: int | None = None) -> bytes:
    img = Image.new("RGB", (w, h), (196, 190, 180))
    exif = Image.Exif()
    if exif_orientation:
        exif[0x0112] = exif_orientation
    buf = io.BytesIO()
    img.save(buf, "JPEG", exif=exif.tobytes())
    return buf.getvalue()


def answer(**seen) -> str:
    """The model's JSON: every item seen as the guide lists it, except the ones given as id=(seen, sure)."""
    items = []
    for it in inventory.expected(json.loads(PLAN.read_text())):
        n, sure = seen.get(f"x{it['id']}", seen.get(it["id"], (it["expected"], True)))
        items.append({"id": it["id"], "seen": n, "sure": sure, "note": ""})
    return json.dumps({"items": items, "others": []})


class Calls(list):
    reply = ""


@pytest.fixture()
def fake_model(monkeypatch):
    calls = Calls()

    def generate(model, parts, **kw):
        calls.append({"model": model, "parts": parts, **kw})
        generate.last = {"usage": {"promptTokenCount": 8000, "candidatesTokenCount": 300, "thoughtsTokenCount": 1000}}
        return calls.reply

    calls.reply = answer()
    monkeypatch.setattr(inventory.gemini, "generate", generate)
    return calls


@pytest.fixture()
def job_dir(tmp_path):
    job = tmp_path / "job"
    (job / "renders").mkdir(parents=True)
    shutil.copy(PLAN, job / "plan.json")
    Image.new("RGB", (64, 64), "white").save(job / "renders" / "part-end_slat-shaded.png")
    return job


# ---- the check itself -----------------------------------------------------------------------------------------

def test_everything_present(job_dir, fake_model):
    res = inventory.check(job_dir, [photo()])
    assert res["all_present"] is True
    assert {i["status"] for i in res["items"]} == {"ok"}
    assert res["summary"]["en"] == "Everything on the parts list is here."
    assert res["model"] == "gemini-4-hydrogen" and isinstance(res["latency_ms"], int)
    assert json.loads((job_dir / "inventory.json").read_text()) == res
    call = fake_model[0]
    assert call["model"] == "gemini-4-hydrogen" and call["schema"] == inventory.SCHEMA
    # the render is sent as the reference for the part that has one; the others are described in words
    assert job_dir / "renders/part-end_slat-shaded.png" in call["parts"]
    assert "108363" in call["parts"][0] and "60 mm long" in call["parts"][0]


def test_missing_extra_and_unsure_are_reported_calmly(job_dir, fake_model):
    fake_model.reply = answer(x108363=(2, True), end_slat=(5, True), x108560=(20, False))
    res = inventory.check(job_dir, [photo(), photo()])
    by = {i["id"]: i for i in res["items"]}
    assert (by["108363"]["status"], by["108363"]["expected"], by["108363"]["seen"]) == ("missing", 4, 2)
    assert by["end_slat"]["status"] == "extra"
    assert by["108560"]["status"] == "unsure" and by["108560"]["kind"] == "hardware"
    assert res["all_present"] is False
    en, el = res["summary"]["en"], res["summary"]["el"]
    assert en.startswith("2 × 108363 missing.")
    assert "Count the 108560 screws: I see 20, the guide lists 24." in en
    assert "Δεν βλέπω 2 × 108363." in el and "Μέτρησε τα 108560: βλέπω 20, ο οδηγός γράφει 24." in el
    for text in (en, el):
        assert "!" not in text and "—" not in text and "wrong" not in text.lower()
    assert res["photos"] == ["inventory/photo-1.jpg", "inventory/photo-2.jpg"]


def test_items_the_model_skips_or_garbles_are_unsure(job_dir, fake_model):
    fake_model.reply = json.dumps({"items": [{"id": "bottom_panel", "seen": 1, "sure": True},
                                             {"id": "side_panel", "seen": "two", "sure": True}]})
    res = inventory.check(job_dir, [photo()])
    by = {i["id"]: i for i in res["items"]}
    assert by["bottom_panel"]["status"] == "ok"
    assert by["side_panel"]["status"] == "unsure" and by["side_panel"]["seen"] is None
    assert by["108560"]["status"] == "unsure"
    assert "I can't count them in the photos" in res["summary"]["en"]


def test_photos_are_turned_upright(job_dir, fake_model):
    inventory.check(job_dir, [photo(640, 480, exif_orientation=6)])     # stored landscape, viewed portrait
    assert Image.open(job_dir / "inventory/photo-1.jpg").size == (480, 640)


def test_job_without_plan_is_refused(tmp_path, fake_model):
    with pytest.raises(FileNotFoundError):
        inventory.check(tmp_path, [photo()])
    assert not fake_model


# ---- routes ---------------------------------------------------------------------------------------------------

def make_job(client, plan=True):
    job = client.app.state.store.create(b"%PDF-1.4 test")
    if plan:
        shutil.copy(PLAN, job.dir / "plan.json")
    return job


def send(client, job_id, blobs):
    return client.post(f"/api/jobs/{job_id}/inventory",
                       files=[("files", (f"p{i}.jpg", b, "image/jpeg")) for i, b in enumerate(blobs)])


def test_inventory_routes(client, fake_model):
    job = make_job(client)
    assert client.get(f"/api/jobs/{job.id}/inventory").status_code == 404
    fake_model.reply = answer(x108363=(2, True))
    r = send(client, job.id, [photo(), photo()])
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["all_present"] is False and body["summary"]["en"].startswith("2 × 108363 missing.")
    assert set(body["items"][0]) == {"id", "kind", "name", "expected", "seen", "status", "note"}
    assert client.get(f"/api/jobs/{job.id}/inventory").json() == body
    event = client.get(f"/api/jobs/{job.id}/events/poll?since=0&wait=0").json()["events"][-1]
    assert event["type"] == "inventory" and event["level"] == "warn"
    assert event["text"] == body["summary"]["en"] and event["text_el"] == body["summary"]["el"]
    assert event["url"] == f"/files/{job.id}/inventory.json"
    assert client.get(event["image_url"]).status_code == 200


def test_inventory_route_refusals(client, fake_model):
    no_plan = make_job(client, plan=False)
    assert send(client, no_plan.id, [photo()]).status_code == 409
    job = make_job(client)
    assert client.post(f"/api/jobs/{job.id}/inventory").status_code == 422
    assert send(client, job.id, [photo()] * 7).status_code == 422
    assert send(client, job.id, [b"not an image"]).status_code == 415
    assert send(client, "nosuchjob", [photo()]).status_code == 404
    r = client.post(f"/api/jobs/{job.id}/inventory", headers={"X-Demo-Key": "wrong"},
                    files=[("files", ("p.jpg", photo(), "image/jpeg"))])
    assert r.status_code == 401
    assert client.get(f"/api/jobs/{job.id}/inventory", headers={"X-Demo-Key": "wrong"}).status_code == 401
    assert not fake_model                                           # nothing above reached the model
    fake_model.reply = "not json"
    assert send(client, job.id, [photo()]).status_code == 502
