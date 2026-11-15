"""POST /api/perception/{job_id}/parts: find the part in the pile. No network: the model call is replaced."""
from __future__ import annotations

import io
import json
import pytest
from PIL import Image

from server.perception import pile
from server.perception.guide import Guide
from server.perception.routes import registry
from tests.test_perception import LONG, SHORT, make_job
from tests.test_vision import app_client, jpeg_b64

URL = "/api/perception/kn-test/parts"


def add_renders(job_dir):
    """White renders with a coloured object in the middle, as pipeline/blender writes them."""
    (job_dir / "renders").mkdir(exist_ok=True)
    for i, item in enumerate(("bottom_panel", "side_panel", "end_slat", "end_slat_handle", LONG, SHORT)):
        im = Image.new("RGBA", (512, 512), (255, 255, 255, 255))
        im.paste((40 * i, 90, 160, 255), (156, 200, 356, 312))
        im.save(job_dir / "renders" / f"part-{item}-shaded.png")
    (job_dir / "renders" / "parts.json").write_text(json.dumps({"end_slat": {"px_per_mm": 0.5}}))


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "fake-key")       # never reaches a network: every call is mocked
    with app_client(tmp_path) as c:
        add_renders(make_job(c.app.state.settings.jobs_dir))
        yield c


def fake_model(monkeypatch, reply, calls: list | None = None):
    """`reply`: the JSON the model returns, or a function of the wanted labels that makes it."""
    def generate(model, parts, **kw):
        assert model == "gemini-4-hydrogen"
        wanted = kw["schema"]["properties"]["items"]["items"]["properties"]["label"]["enum"]
        if calls is not None:
            calls.append({"parts": parts, "wanted": wanted, **kw})
        return json.dumps(reply(wanted) if callable(reply) else reply)
    monkeypatch.setattr(pile.gemini, "generate", generate)


def item(label, box, conf=0.9, visible=True):
    return {"label": label, "visible": visible, "box_2d": box, "confidence": conf}


def texts(parts) -> str:
    return "\n".join(p for p in parts if isinstance(p, str))


# ---- validation and auth ------------------------------------------------------------------------

def test_needs_the_key(client):
    assert client.post(URL, json={"image": jpeg_b64()}, headers={"X-Demo-Key": "wrong"}).status_code == 401


def test_checks_the_body(client, tmp_path):
    assert client.post(URL, json={}).status_code == 422
    assert client.post(URL, json={"image": "not base64!!"}).status_code == 422
    assert client.post(URL, json={"image": jpeg_b64(), "lang": "fr"}).status_code == 422
    r = client.post(URL, json={"image": jpeg_b64(), "want": ["end_slat", "no_such_part"]})
    assert r.status_code == 422 and "no_such_part" in r.json()["detail"]
    assert client.post(URL, json={"image": jpeg_b64(), "step": 9}).status_code == 422
    assert client.post(URL, json={"image": jpeg_b64(), "want": ["end_slat"] * 13}).status_code == 422
    assert client.post("/api/perception/no-such-job/parts", json={"image": jpeg_b64()}).status_code == 404
    job = client.app.state.store.create(b"%PDF-1.4\n", "x.pdf")
    assert client.post(f"/api/perception/{job.id}/parts", json={"image": jpeg_b64()}).status_code == 409


# ---- what is looked for -------------------------------------------------------------------------

def test_wanted_items_default_to_the_given_step(client, monkeypatch):
    calls = []
    fake_model(monkeypatch, {"items": []}, calls)
    out = client.post(URL, json={"image": jpeg_b64(), "step": 3}).json()
    assert calls[0]["wanted"] == ["end_slat", SHORT]
    assert out["step"] == 3 and out["missing"] == ["end_slat", SHORT] and out["found"] == []
    prompt = texts(calls[0]["parts"])
    # the look-alikes are shown as NOT wanted, and the screws differ by their plan lengths and counts
    assert f"- {SHORT}: hardware, short screw, a screw, 24 in the box, 30 mm long" in prompt
    assert f"Look-alike `{LONG}`, NOT wanted" in prompt and "60 mm long" in prompt
    assert "Look-alike `end_slat_handle`" not in prompt          # a different part id, not a mirrored one
    # the frame, then one reference per wanted item and look-alike, each cropped to the object
    images = [p for p in calls[0]["parts"] if isinstance(p, tuple)]
    assert len(images) == 1 + 2 + 1
    ref = Image.open(io.BytesIO(images[1][1]))
    assert ref.size[0] < 260 and ref.size[1] < 160               # 200 x 112 object plus a margin, not 512 x 512
    assert calls[0]["thinking_budget"] == "low"


def test_wanted_items_default_to_the_reported_step_then_step_one(client, monkeypatch):
    calls = []
    fake_model(monkeypatch, {"items": []}, calls)
    assert client.post(URL, json={"image": jpeg_b64()}).json()["step"] == 1
    assert calls[-1]["wanted"] == ["bottom_panel", "side_panel"]
    job = client.app.state.store.get("kn-test")
    registry(client.app).session(job.id, job.dir).position = {"step": 4, "action": 0, "at": 0}   # a display reported it
    assert client.post(URL, json={"image": jpeg_b64()}).json()["step"] == 4
    assert calls[-1]["wanted"] == ["end_slat_handle", SHORT]


def test_given_want_wins_over_the_step_and_takes_codes_as_numbers(client, monkeypatch):
    calls = []
    fake_model(monkeypatch, {"items": []}, calls)
    out = client.post(URL, json={"image": jpeg_b64(), "want": [int(LONG), "end_slat", LONG]}).json()
    assert calls[0]["wanted"] == [LONG, "end_slat"] and out["step"] is None


def test_a_step_with_nothing_to_pick_makes_no_call(tmp_path, monkeypatch):
    def boom(*a, **k):
        raise AssertionError("no call expected")
    monkeypatch.setattr(pile.gemini, "generate", boom)
    with app_client(tmp_path) as c:
        d = make_job(c.app.state.settings.jobs_dir)
        m = json.loads((d / "manifest.json").read_text())
        m["steps"][1]["hardware"] = []
        (d / "manifest.json").write_text(json.dumps(m))
        out = c.post(URL, json={"image": jpeg_b64(), "step": 2}).json()
    assert out["found"] == [] and out["missing"] == [] and out["message"] == "Step 2 needs no new parts."


def test_more_than_six_items_are_split_over_two_calls(client, monkeypatch):
    calls = []
    fake_model(monkeypatch, lambda wanted: {"items": [item(w, [10, 10, 100, 100]) for w in wanted]}, calls)
    pile.PER_CALL, saved = 4, pile.PER_CALL
    try:
        want = ["bottom_panel", "side_panel", "end_slat", "end_slat_handle", LONG, SHORT]
        out = client.post(URL, json={"image": jpeg_b64(), "want": want}).json()
    finally:
        pile.PER_CALL = saved
    assert sorted(len(c["wanted"]) for c in calls) == [2, 4]
    assert [f["id"] for f in out["found"]] == want


# ---- boxes ----------------------------------------------------------------------------------------

def test_boxes_are_clamped_ordered_and_never_invented(client, monkeypatch):
    fake_model(monkeypatch, {"items": [
        item("end_slat", [1200, 640, -20, 100], 0.8),             # out of range and swapped
        item(SHORT, [500, 500, 500, 700], 0.9),                   # no height: not a box
        item("end_slat", [10, 10, 20, 20], 0.3),                  # a second, less sure answer for the same item
        item(LONG, [100, 100, 200, 200], 0.9),                    # not wanted: dropped
    ]})
    out = client.post(URL, json={"image": jpeg_b64(), "step": 3}).json()
    assert out["found"] == [{"id": "end_slat", "kind": "part", "box_2d": [0, 100, 1000, 640], "confidence": 0.8,
                             "label": "Plain slat"}]
    assert out["missing"] == [SHORT]
    assert out["message"] == "The plain slat is in the middle."
    assert out["model"] == "gemini-4-hydrogen" and out["simulated"] is False and isinstance(out["latency_ms"], int)


@pytest.mark.parametrize("entry", [
    item("end_slat", [100, 100, 300, 300], visible=False),
    item("end_slat", None),
    item("end_slat", [100, 100, 300], 0.9),
    item("end_slat", [100, "a", 300, 300], 0.9),
    item("end_slat", [100, 100, 300, 300], 0.1),
])
def test_unseen_unsure_or_malformed_answers_go_to_missing(entry):
    assert pile.read_answer({"items": [entry]}, ["end_slat"]) == {}


def test_nothing_found_asks_to_see_more_in_both_languages(client, monkeypatch):
    fake_model(monkeypatch, {"items": [item("end_slat", None, 0.0, visible=False)]})
    out = client.post(URL, json={"image": jpeg_b64(), "step": 3}).json()
    assert out["found"] == [] and out["message"] == "I can't see the plain slat. Show me more of the parts."
    out = client.post(URL, json={"image": jpeg_b64(), "want": [SHORT], "lang": "el"}).json()
    assert out["message"] == f"Δεν βλέπω: κοντή βίδα ({SHORT}). Δείξε μου κι άλλα κομμάτια."


def test_greek_labels_and_where_the_part_lies(client, monkeypatch):
    fake_model(monkeypatch, {"items": [item(SHORT, [700, 750, 800, 900]), item("end_slat", [0, 0, 100, 100])]})
    out = client.post(URL, json={"image": jpeg_b64(), "step": 3, "lang": "el"}).json()
    assert [f["label"] for f in out["found"]] == ["Απλή σανίδα", f"Κοντή βίδα · {SHORT}"]   # the article is dropped
    assert out["message"] == "Απλή σανίδα: πάνω αριστερά."
    assert pile.where([700, 750, 800, 900], "en") == "at the bottom right"


def test_render_scale_gives_a_size_when_the_plan_has_none(tmp_path):
    d = make_job(tmp_path, plan=False)
    add_renders(d)
    cat = pile.catalogue(Guide(d))
    assert cat["end_slat"].size == "about 400 mm across (estimated)"    # 200 px wide at 0.5 px/mm
    assert cat[LONG].size == "" and cat[LONG].thumb.name == f"part-{LONG}-shaded.png"
    assert pile.lookalikes(cat, [LONG]) == [SHORT]


def test_mirrored_parts_are_look_alikes():
    cat = {i: pile.Item(i, "part", i, None, "", None, 1, "", None)
           for i in ("drawer_side_left", "drawer_side_right", "drawer_front", "runner_l", "runner_r")}
    assert pile.lookalikes(cat, ["drawer_side_left", "runner_r"]) == ["drawer_side_right", "runner_l"]


# ---- failures and simulation ----------------------------------------------------------------------

def test_model_failures(client, monkeypatch):
    def refuse(code):
        def generate(*a, **k):
            raise RuntimeError(f"gemini-4-hydrogen {code}: quota")
        return generate
    monkeypatch.setattr(pile.gemini, "generate", refuse(429))
    assert client.post(URL, json={"image": jpeg_b64()}).status_code == 429
    monkeypatch.setattr(pile.gemini, "generate", refuse(500))
    assert client.post(URL, json={"image": jpeg_b64()}).status_code == 502
    monkeypatch.setattr(pile.gemini, "generate", lambda *a, **k: "not json")
    assert client.post(URL, json={"image": jpeg_b64()}).status_code == 502


@pytest.mark.parametrize("key,simulate", [("", False), ("fake-key", True)])
def test_simulated_without_a_key_or_when_asked(tmp_path, monkeypatch, key, simulate):
    def boom(*a, **k):
        raise AssertionError("no call expected")
    monkeypatch.setattr(pile.gemini, "generate", boom)
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    with app_client(tmp_path, gemini_key=key, simulate=simulate) as c:
        make_job(c.app.state.settings.jobs_dir)
        out = c.post(URL, json={"image": jpeg_b64(), "step": 3}).json()
        again = c.post(URL, json={"image": jpeg_b64(), "step": 3}).json()
        assert c.post(URL, json={"image": jpeg_b64()}, headers={"X-Demo-Key": "wrong"}).status_code == 401
    assert out == again
    assert out["simulated"] is True and out["model"] is None
    assert out["found"] == [{"id": "end_slat", "kind": "part", "box_2d": [350, 350, 650, 650], "confidence": 0.5,
                             "label": "Plain slat"}]
    assert out["missing"] == [SHORT] and out["message"].startswith("Simulated:")


def test_the_contract_keys(client, monkeypatch):
    fake_model(monkeypatch, {"items": [item("bottom_panel", [100, 200, 300, 400])]})
    out = client.post(URL, json={"image": jpeg_b64()}).json()
    assert set(out) == {"found", "missing", "message", "step", "latency_ms", "model", "simulated"}
    assert set(out["found"][0]) == {"id", "kind", "box_2d", "confidence", "label"}
