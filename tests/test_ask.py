"""Ask-the-guide tests (pipeline/ask.py, POST /api/jobs/{id}/ask). The model call is replaced by a stub."""
from __future__ import annotations

import base64
import io
import json

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from pipeline import ask
from server.app import create_app
from server.settings import Settings

KEY = "test-key"


def png(size=(1200, 800)) -> bytes:
    out = io.BytesIO()
    Image.new("L", size, 255).save(out, "PNG")
    return out.getvalue()


def jpeg(size=(640, 480)) -> bytes:
    out = io.BytesIO()
    Image.new("RGB", size, (90, 90, 90)).save(out, "JPEG")
    return out.getvalue()


def fasten(code, i, n):
    return {"type": "fasten", "partId": code, "code": code, "node": f"hw_{code}_{i}",
            "text": {"en": f"Take a long screw ({code}) and screw it in here. Screw {i} of {n}.",
                     "el": f"Πάρε μια μακριά βίδα ({code}) και βίδωσέ την εδώ. Βίδα {i} από {n}."}}


def place(part, n):
    return {"type": "place", "partId": part, "node": f"part_{part}_{n}",
            "text": {"en": f"Take the {part} and put it in place here.", "el": "Βάλε το στη θέση του."}}


MANIFEST = {
    "title": "KNAGGLIG box",
    "parts": [{"id": "bottom_panel", "name": "Bottom panel", "short": "bottom panel",
               "names": {"en": "bottom panel", "el": "κάτω πάνελ"}, "count": 1, "cite": {"page": 3}},
              {"id": "side_panel", "name": "Side panel", "short": "side panel",
               "names": {"en": "side panel", "el": "πλαϊνό πάνελ"}, "count": 2, "cite": {"page": 3}}],
    "hardware": [{"code": "108363", "name": "Long screw", "short": "long screw", "kind": "screw", "count": 4,
                  "names": {"en": "long screw", "el": "μακριά βίδα"}, "cite": {"page": 2}}],
    "steps": [
        {"n": 1, "title": "Stand a side panel on the bottom panel", "cite": {"page": 3}, "pose": "upright",
         "pickParts": ["bottom_panel", "side_panel"], "hardware": [], "crop": "crops/step-01.png",
         "actions": [place("bottom_panel", 1), place("side_panel", 1)]},
        {"n": 2, "title": "Fix it with two long screws", "cite": {"page": 3}, "pose": "upright", "pickParts": [],
         "hardware": [{"code": "108363", "qty": 2}], "crop": "crops/step-02.png",
         "actions": [fasten("108363", 1, 2), fasten("108363", 2, 2)]},
        {"n": 3, "title": "Turn it over and add the second side panel", "cite": {"page": 4}, "pose": "upside_down",
         "pickParts": ["side_panel"], "hardware": [], "crop": "crops/step-03.png",
         "actions": [{"type": "turn", "node": "assembly", "text": {"en": "Turn the assembly upside down.",
                                                                   "el": "Γύρισε την κατασκευή ανάποδα."}},
                     place("side_panel", 2)]},
    ],
    "flags": [{"ref": "step:3", "kind": "unclear_drawing", "message": "Check the batten side."}],
}
PLAN = {"parts": [{"id": "side_panel", "el": "το πλαϊνό πάνελ"}],
        "hardware": [{"code": "108363", "kind": "screw", "params": {"length": 60}, "dims": {"estimated": True}}],
        "steps": [{"n": 2, "add": [{"instance": "108363#1", "mates": [{"moving": "seat", "fixed": "side_panel#1.bolt_in_1"}]}],
                   "turn_text": {"en": "Turn the box the right way up."}}]}


@pytest.fixture()
def job_dir(tmp_path):
    d = tmp_path / "job"
    (d / "crops").mkdir(parents=True)
    for n in (1, 2, 3):
        (d / "crops" / f"step-{n:02d}.png").write_bytes(png())
    (d / "manifest.json").write_text(json.dumps(MANIFEST, ensure_ascii=False))
    (d / "plan.approved.json").write_text(json.dumps(PLAN, ensure_ascii=False))
    return d


@pytest.fixture()
def model(monkeypatch):
    """Replace the model: `model.reply` is what it returns, `model.calls` what it was sent."""
    class Stub:
        reply: dict = {}
        calls: list = []

        def __call__(self, name, parts, **kw):
            self.calls.append({"model": name, "parts": parts, **kw})
            return json.dumps(self.reply, ensure_ascii=False)

    stub = Stub()
    stub.calls = []
    monkeypatch.setattr("pipeline.ask.gemini.generate", stub)
    return stub


def reply(answer="Use two long screws 108363.", actions=(), mistake=None, undo=None, cite=None):
    return {"answer": answer, "actions": list(actions), "mistake_step": mistake, "undo": undo, "cite_step": cite}


# ---- grounding and validation -----------------------------------------------------------------------------

def test_the_model_sees_the_step_its_drawing_the_frame_and_recent_history(job_dir, model):
    model.reply = reply()
    history = [{"q": f"q{i}", "a": f"a{i}"} for i in range(6)]
    out = ask.answer(job_dir, 2, "  which screw   here? ", "en", frame_jpeg=jpeg(), history=history)
    call = model.calls[0]
    assert call["model"] == "gemini-4-hydrogen" and call["thinking_budget"] == "low" and call["schema"] == ask.SCHEMA
    prompt = call["parts"][0]
    assert "STEP 2 · page 3" in prompt and "<== THE PERSON IS HERE" in prompt
    assert "108363: Long screw" in prompt and "60 mm (estimated)" in prompt
    assert "side_panel#1.bolt_in_1" in prompt and "Check the batten side." in prompt
    assert "camera view" in prompt                                        # the frame rule is only there with a frame
    images = [p for p in call["parts"] if isinstance(p, tuple)]
    assert len(images) == 2 and all(mime == "image/jpeg" for mime, _ in images)
    assert max(Image.open(io.BytesIO(images[0][1])).size) == ask.IMAGE_EDGE    # the drawing is shrunk
    texts = "\n".join(p for p in call["parts"] if isinstance(p, str))
    assert "q1" not in texts and "q2" in texts and "q5" in texts          # only the last 4 turns
    assert call["parts"][-1] == "The person asks: which screw here?"
    assert out["cites"] == {"page": 3, "step": 2} and out["model"] == "gemini-4-hydrogen"
    assert out["undo"] is None and isinstance(out["latency_ms"], int)


def test_only_ids_and_codes_the_job_has_survive(job_dir, model):
    model.reply = reply(answer="Use the long screw 108363 — two of them!", actions=[
        {"type": "highlight_hardware", "code": "108363"}, {"type": "highlight_hardware", "code": "999999"},
        {"type": "highlight_part", "part_id": "shelf_C"}, {"type": "highlight_part", "part_id": "side_panel"},
        {"type": "go_to_step", "step": 9}, {"type": "go_to_step", "step": True}, {"type": "highlight_hardware", "code": "108363"},
        {"type": "open_door"}, "junk"], cite=42)
    out = ask.answer(job_dir, 2, "which screw?", "en")
    assert out["actions"] == [{"type": "highlight_hardware", "code": "108363"},
                              {"type": "highlight_part", "part_id": "side_panel"}]
    assert out["answer"] == "Use the long screw 108363, two of them."    # no em dash, no exclamation mark
    assert out["cites"] == {"page": 3, "step": 2}                          # an unknown cite falls back to the step


def test_no_mistake_means_no_undo(job_dir, model):
    model.reply = reply(undo=["Unscrew everything."])
    assert ask.answer(job_dir, 2, "what next?", "en")["undo"] is None


def test_off_topic_keeps_no_actions_when_the_model_sends_none(job_dir, model):
    model.reply = reply(answer="I help with this build. Back to step 3.")
    out = ask.answer(job_dir, 3, "what's the weather?", "en")
    assert out["actions"] == [] and out["undo"] is None and out["answer"].startswith("I help")


def test_empty_answer_gets_a_safe_sentence(job_dir, model):
    model.reply = reply(answer="")
    assert ask.answer(job_dir, 3, "?", "el")["answer"] == "Δεν έχω απάντηση αυτή τη στιγμή. Δες το σχέδιο του βήματος 3, σελίδα 4."


# ---- undo ---------------------------------------------------------------------------------------------------

def test_undo_facts_follow_the_action_order_backwards(job_dir):
    job = ask.Job(job_dir)
    assert ask.undo_facts(job, 2) == ["unscrew the 2 long screws 108363 put in at step 2"]
    assert ask.undo_facts(job, 3) == ["take off the side panel placed at step 3",
                                      "turn the assembly back the way it stood before step 3"]
    assert "UNDO FACTS" in ask.facts(job, 3) and "- step 1: take off the side panel placed at step 1" in ask.facts(job, 3)


def test_a_mistake_in_an_earlier_step_gets_undo_a_go_to_step_and_a_carry_on(job_dir, model):
    model.reply = reply(answer="Take the side panel off and stand it the other way round, step 1, page 3.",
                        actions=[{"type": "highlight_part", "part_id": "side_panel"}], mistake=1,
                        undo=["If you already put in the long screws 108363 at step 2, unscrew them.",
                              "Take off the side panel.", "Turn it round as the drawing shows.", "Do step 1 again, page 3."])
    out = ask.answer(job_dir, 2, "I put the side panel on backwards in step 1", "en")
    assert out["undo"][:4] == model.reply["undo"]
    assert out["undo"][-1] == "Then carry on with step 2, page 3."
    assert out["actions"][0] == {"type": "go_to_step", "step": 1}
    assert {"type": "highlight_part", "part_id": "side_panel"} in out["actions"]
    assert out["cites"] == {"page": 3, "step": 1}


def test_undo_already_ending_on_a_later_step_is_left_alone(job_dir, model):
    model.reply = reply(mistake=1, undo=["Take off the side panel.", "Do step 1 again, then step 2, page 3."])
    out = ask.answer(job_dir, 2, "panel backwards in step 1", "en")
    assert out["undo"] == ["Take off the side panel.", "Do step 1 again, then step 2, page 3."]


def test_undo_from_the_action_order_when_the_model_writes_none(job_dir, model):
    model.reply = reply(mistake=2, undo=[], cite=None)
    out = ask.answer(job_dir, 3, "έβαλα τη βίδα στη λάθος τρύπα στο βήμα 2", "el")
    assert out["undo"] == ["Βγάλε: πλαϊνό πάνελ, από το βήμα 3.",
                           "Γύρισε την κατασκευή όπως ήταν πριν από το βήμα 3.",
                           "Ξεβίδωσε 2 x μακριά βίδα 108363 από το βήμα 2.",
                           "Κάνε ξανά το βήμα 2 όπως δείχνει το σχέδιο, σελίδα 3.",
                           "Μετά συνέχισε με το βήμα 3, σελίδα 4."]
    assert out["cites"] == {"page": 3, "step": 2}


def test_unknown_mistake_step_is_ignored(job_dir, model):
    model.reply = reply(mistake=7, undo=["Unscrew it."])
    out = ask.answer(job_dir, 2, "something", "en")
    assert out["undo"] is None and out["actions"] == []


# ---- input errors ----------------------------------------------------------------------------------------------

def test_bad_input_and_missing_manifest(job_dir, tmp_path, model):
    model.reply = reply()
    with pytest.raises(ValueError):
        ask.answer(job_dir, 9, "which screw?")
    with pytest.raises(ValueError):
        ask.answer(job_dir, 2, "   ")
    with pytest.raises(ValueError):
        ask.answer(job_dir, 2, "which screw?", frame_jpeg=b"\xff\xd8 not really a jpeg")
    with pytest.raises(ask.NoManifest):
        ask.answer(tmp_path, 1, "which screw?")
    model.reply = None
    with pytest.raises(RuntimeError):                       # "null" is JSON, but not an answer
        ask.answer(job_dir, 2, "which screw?")
    assert model.calls                                      # the bad-input cases never reached the model


# ---- the route ---------------------------------------------------------------------------------------------------

@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "stub")           # restored after the test; the route only sets a default
    settings = Settings(_env_file=None, demo_key=KEY, jobs_dir=tmp_path / "jobs", gemini_api_key="stub",
                        client_dist=tmp_path / "no-client")
    with TestClient(create_app(settings), headers={"X-Demo-Key": KEY}) as c:
        yield c


def done_job(client, job_dir):
    job = client.app.state.store.create(b"%PDF-1.4\n", "guide.pdf")
    for path in job_dir.rglob("*"):
        if path.is_file():
            target = job.dir / path.relative_to(job_dir)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(path.read_bytes())
    return job


def test_route_needs_the_key_a_job_and_a_manifest(client, model):
    assert client.post("/api/jobs/x/ask", json={"step": 1, "question": "q"}, headers={"X-Demo-Key": "wrong"}).status_code == 401
    assert client.post("/api/jobs/nope123/ask", json={"step": 1, "question": "q"}).status_code == 404
    job = client.app.state.store.create(b"%PDF-1.4\n", "guide.pdf")
    assert client.post(f"/api/jobs/{job.id}/ask", json={"step": 1, "question": "q"}).status_code == 409
    assert not model.calls


def test_route_answers_with_the_frame_and_last_four_turns(client, job_dir, model):
    job = done_job(client, job_dir)
    model.reply = reply(actions=[{"type": "highlight_hardware", "code": "108363"}, {"type": "highlight_part", "part_id": "X"}])
    frame = base64.b64encode(jpeg()).decode()
    r = client.post(f"/api/jobs/{job.id}/ask", json={
        "step": 2, "question": "which screw?", "lang": "en", "frame": "data:image/jpeg;base64," + frame,
        "history": [{"q": f"q{i}", "a": f"a{i}"} for i in range(6)]})
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body) == {"answer", "actions", "undo", "cites", "latency_ms", "model"}
    assert body["actions"] == [{"type": "highlight_hardware", "code": "108363"}]
    parts = model.calls[0]["parts"]
    assert sum(isinstance(p, tuple) for p in parts) == 2
    assert "q1" not in "\n".join(p for p in parts if isinstance(p, str))


def test_route_refuses_bad_input(client, job_dir, model):
    job = done_job(client, job_dir)
    model.reply = reply()
    url = f"/api/jobs/{job.id}/ask"
    assert client.post(url, json={"step": 9, "question": "q"}).status_code == 422
    assert client.post(url, json={"step": 2, "question": ""}).status_code == 422
    assert client.post(url, json={"step": 2, "question": "x" * 501}).status_code == 422
    assert client.post(url, json={"step": 2, "question": "q", "lang": "de"}).status_code == 422
    assert client.post(url, json={"step": 2, "question": "q", "frame": "not base64!"}).status_code == 422
    png_b64 = base64.b64encode(png((10, 10))).decode()
    assert client.post(url, json={"step": 2, "question": "q", "frame": png_b64}).status_code == 422
    assert client.post(url, json={"step": 2, "question": "q", "frame": "A" * (4 * 1024 * 1024)}).status_code == 422


def test_route_reports_model_failures_and_a_missing_key(client, job_dir, monkeypatch):
    job = done_job(client, job_dir)

    def down(*a, **kw):
        raise RuntimeError("gemini-4-hydrogen 503: overloaded")

    monkeypatch.setattr("pipeline.ask.gemini.generate", down)
    r = client.post(f"/api/jobs/{job.id}/ask", json={"step": 2, "question": "q"})
    assert r.status_code == 502 and "overloaded" in r.json()["detail"]
    client.app.state.settings.gemini_api_key = ""
    assert client.post(f"/api/jobs/{job.id}/ask", json={"step": 2, "question": "q"}).status_code == 503
