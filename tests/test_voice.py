"""Voice control tests (server/voice.py, POST /api/voice/{id}). The model and the speech synthesis are stubbed."""
from __future__ import annotations

import base64
import json
import struct

import pytest
from fastapi.testclient import TestClient

from server import voice
from server.app import create_app
from server.routes import tts
from server.routes import voice as voice_route
from server.settings import Settings

KEY = "test-key"


def fasten(code, i, n):
    return {"type": "fasten", "partId": code, "code": code, "node": f"hw_{code}_{i}",
            "text": {"en": f"Take a wooden dowel ({code}) and screw it in here. Screw {i} of {n}.",
                     "el": f"Πάρε μια ξύλινη καβίλια ({code}) και βίδωσέ την εδώ. Βίδα {i} από {n}."}}


def place(part, en, el):
    return {"type": "place", "partId": part, "node": f"part_{part}_1",
            "text": {"en": f"Take the {en} and put it in place here.", "el": f"Πάρε {el} και βάλε το στη θέση του."}}


MANIFEST = {
    "title": "MALM desk drawer",
    "parts": [{"id": "drawer_front", "name": "Drawer front", "short": "drawer front", "count": 1, "cite": {"page": 4},
               "names": {"en": "drawer front", "el": "πρόσοψη"}},
              {"id": "side_left", "name": "Left side", "short": "left side panel", "count": 1, "cite": {"page": 5},
               "names": {"en": "left side panel", "el": "αριστερό πλαϊνό"}}],
    "hardware": [{"code": "101345", "name": "Wooden dowel", "short": "wooden dowel", "kind": "dowel", "count": 2,
                  "names": {"en": "wooden dowel", "el": "ξύλινη καβίλια"}, "cite": {"page": 3}},
                 {"code": "118331", "name": "Cam bolt", "short": "cam bolt", "kind": "bolt", "count": 2,
                  "names": {"en": "cam bolt", "el": "βίδα έκκεντρου"}, "cite": {"page": 3}}],
    "steps": [
        {"n": 1, "title": "Lay the drawer front face down", "cite": {"page": 4}, "hardware": [],
         "actions": [place("drawer_front", "drawer front", "την πρόσοψη")]},
        {"n": 2, "title": "Add the left side with two dowels", "cite": {"page": 5},
         "hardware": [{"code": "101345", "qty": 2}],
         "actions": [{"type": "note", "text": {"en": "A short dowel goes in each end.", "el": "Μια κοντή καβίλια."}},
                     place("side_left", "left side panel", "το αριστερό πλαϊνό"),
                     fasten("101345", 1, 2), fasten("101345", 2, 2)]},
        {"n": 3, "title": "Turn the drawer over", "cite": {"page": 6}, "hardware": [],
         "actions": [{"type": "turn", "node": "assembly",
                      "text": {"en": "Turn the drawer over.", "el": "Γύρισε το συρτάρι ανάποδα."}}]},
    ],
}


@pytest.fixture()
def job_dir(tmp_path):
    d = tmp_path / "job"
    d.mkdir()
    (d / "manifest.json").write_text(json.dumps(MANIFEST, ensure_ascii=False))
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
    monkeypatch.setattr("server.voice.gemini.generate", stub)
    return stub


def said(transcript, intent, answer="", actions=()):
    return {"transcript": transcript, "intent": intent, "answer": answer, "actions": list(actions)}


def wav_b64(seconds=0.5, header=True) -> str:
    pcm = b"\x00\x00" * int(16000 * seconds)
    return base64.b64encode(tts.wav(pcm, 16000) if header else pcm).decode()


# ---- keywords ------------------------------------------------------------------------------------------------

@pytest.mark.parametrize("text,intent", [
    ("επόμενο", "next"), ("Επόμενο βήμα.", "next"), ("πάμε", "next"), ("έγινε", "next"), ("το έκανα", "next"),
    ("next", "next"), ("Done.", "next"), ("ok next", "next"), ("continue", "next"),
    ("πίσω", "back"), ("πήγαινε πίσω", "back"), ("προηγούμενο", "back"), ("back", "back"), ("go back", "back"),
    ("ξανά", "repeat"), ("επανάλαβε", "repeat"), ("πες το ξανά", "repeat"), ("repeat", "repeat"),
    ("Can you repeat that?", "repeat"), ("say that again", "repeat"),
    ("πού είμαι;", "where_am_i"), ("Σε ποιο βήμα είμαι;", "where_am_i"), ("Where am I?", "where_am_i"),
    ("τι είναι αυτό;", "identify"), ("ποια βίδα είναι αυτή;", "identify"), ("What is this?", "identify"),
    ("ποια βίδα χρειάζομαι τώρα;", "ask"), ("πόσες καβίλιες θέλω", "ask"), ("Which screw do I need now?", "ask"),
    ("the panel does not fit here", "ask"),
    ("", "none"), ("   ", "none"), ("εμ", "none"),
])
def test_keywords_map_greek_and_english_commands(text, intent):
    assert voice.keywords(text) == intent


# ---- commands from the model's intent --------------------------------------------------------------------------

def test_next_within_a_step_reads_the_next_action_and_skips_notes(job_dir, model):
    model.reply = said("επόμενο", "next", answer="ignored")
    out = voice.understand(job_dir, 2, 1, "el", audio=("audio/wav", b"RIFF"))
    assert out["intent"] == "next" and out["transcript"] == "επόμενο"
    assert out["target"] == {"step": 2, "action": 2}
    assert out["answer"] == "Πάρε μια ξύλινη καβίλια (101345) και βίδωσέ την εδώ. Βίδα 1 από 2."
    assert out["model"] == "gemini-4-hydrogen" and out["simulated"] is False and isinstance(out["latency_ms"], int)
    call = model.calls[0]
    assert call["model"] == "gemini-4-hydrogen" and call["thinking_budget"] == "low" and call["schema"] == voice.SCHEMA
    assert call["parts"][-1] == ("audio/wav", b"RIFF")                    # the clip goes inline
    assert "STEP 2 of 3" in call["parts"][0] and "101345: Wooden dowel" in call["parts"][0]


def test_next_across_steps_names_the_step_and_page(job_dir, model):
    model.reply = said("next", "next")
    out = voice.understand(job_dir, 2, 3, "en", audio=("audio/wav", b"RIFF"))
    assert out["target"] == {"step": 3, "action": 0}
    assert out["answer"] == "On to step 3, page 6. Turn the drawer over."
    model.reply = said("επόμενο", "next")
    out = voice.understand(job_dir, 1, 0, "el", audio=("audio/wav", b"RIFF"))
    assert out["target"] == {"step": 2, "action": 1}                     # the note at action 0 is skipped
    assert out["answer"].startswith("Πάμε στο βήμα 2, σελίδα 5. ")


def test_ends_of_the_guide(job_dir, model):
    model.reply = said("next", "next")
    out = voice.understand(job_dir, 3, 0, "en", audio=("audio/wav", b"RIFF"))
    assert out["target"] is None and out["answer"] == "That was the last step. The MALM desk drawer is done."
    model.reply = said("πίσω", "back")
    out = voice.understand(job_dir, 1, 0, "el", audio=("audio/wav", b"RIFF"))
    assert out["target"] is None and out["answer"] == "Είσαι στο πρώτο βήμα."


def test_back_repeat_where_and_identify(job_dir, model):
    model.reply = said("πήγαινε πίσω", "back")
    out = voice.understand(job_dir, 2, 1, "el", audio=("audio/wav", b"RIFF"))
    assert out["target"] == {"step": 1, "action": 0}
    assert out["answer"] == "Πίσω στο βήμα 1, σελίδα 4. Πάρε την πρόσοψη και βάλε το στη θέση του."
    model.reply = said("repeat", "repeat")
    out = voice.understand(job_dir, 2, 2, "en", audio=("audio/wav", b"RIFF"))
    assert out["target"] == {"step": 2, "action": 2} and out["answer"].endswith("Screw 1 of 2.")
    model.reply = said("where am I", "where_am_i")
    out = voice.understand(job_dir, 2, 2, "en", audio=("audio/wav", b"RIFF"))
    assert out["answer"] == "You are at step 2 of 3, page 5. Take a wooden dowel (101345) and screw it in here."
    model.reply = said("τι είναι αυτό", "identify")
    out = voice.understand(job_dir, 2, 2, "el", audio=("audio/wav", b"RIFF"))
    assert out["intent"] == "identify" and out["answer"] == "Δείξε το από κοντά στην κάμερα." and out["target"] is None


def test_none_is_silent_and_an_unknown_intent_falls_back_to_keywords(job_dir, model):
    model.reply = said("", "none", answer="Hello there.")
    out = voice.understand(job_dir, 2, 1, "en", audio=("audio/wav", b"RIFF"))
    assert out["intent"] == "none" and out["answer"] == "" and out["actions"] == []
    model.reply = said("επόμενο", "dance")
    assert voice.understand(job_dir, 2, 1, "el", audio=("audio/wav", b"RIFF"))["intent"] == "next"


# ---- questions and grounding ---------------------------------------------------------------------------------

def test_a_question_keeps_only_codes_the_job_has(job_dir, model):
    model.reply = said("ποια βίδα χρειάζομαι τώρα;", "ask",
                       answer="Βάλε 2 ξύλινες καβίλιες 101345! Μετά η βίδα 999999 στο πλάι.",
                       actions=[{"type": "highlight_hardware", "code": "101345"},
                                {"type": "highlight_hardware", "code": "999999"}, {"type": "go_to_step", "step": 9}])
    out = voice.understand(job_dir, 2, 2, "el", audio=("audio/wav", b"RIFF"))
    assert out["intent"] == "ask" and out["answer"] == "Βάλε 2 ξύλινες καβίλιες 101345."
    assert out["actions"] == [{"type": "highlight_hardware", "code": "101345"}]
    model.reply = said("which screw", "ask", answer="Use screw 123456.")
    out = voice.understand(job_dir, 2, 2, "en", audio=("audio/wav", b"RIFF"))
    assert out["answer"] == "Check the drawing for step 2, page 5."         # nothing grounded was left


def test_a_long_answer_is_cut_to_two_sentences(job_dir, model):
    model.reply = said("why", "ask", answer="One. Two. Three. Four.")
    assert voice.understand(job_dir, 2, 2, "en", audio=("audio/wav", b"RIFF"))["answer"] == "One. Two."


def test_typed_commands_need_no_model_and_typed_questions_do(job_dir, model):
    out = voice.understand(job_dir, 2, 1, "el", text="επόμενο")
    assert out["intent"] == "next" and out["model"] == "keywords" and not model.calls
    model.reply = said("model transcript", "ask", answer="Θέλεις 2 ξύλινες καβίλιες 101345.")
    out = voice.understand(job_dir, 2, 1, "el", text="ποια βίδα χρειάζομαι τώρα;")
    assert out["intent"] == "ask" and out["transcript"] == "ποια βίδα χρειάζομαι τώρα;"
    assert "ποια βίδα χρειάζομαι τώρα;" in model.calls[0]["parts"][-1]


def test_simulate_answers_from_the_manifest(job_dir, model):
    out = voice.understand(job_dir, 2, 1, "en", text="which screw do I need?", use_model=False)
    assert out["intent"] == "ask" and out["simulated"] is True and out["model"] == "simulated"
    assert out["answer"] == "Step 2 uses 2 x wooden dowel 101345, page 5."
    out = voice.understand(job_dir, 1, 0, "el", text="ποια βίδα;", use_model=False)
    assert out["answer"] == "Δες το σχέδιο του βήματος 1, σελίδα 4."
    out = voice.understand(job_dir, 2, 1, "el", audio=("audio/wav", b"RIFF"), use_model=False)
    assert out["intent"] == "none" and out["simulated"] is True and not model.calls


def test_bad_step_and_bad_json(job_dir, monkeypatch):
    with pytest.raises(ValueError):
        voice.understand(job_dir, 9, 0, "en", text="next")
    monkeypatch.setattr("server.voice.gemini.generate", lambda *a, **k: "not json")
    with pytest.raises(RuntimeError):
        voice.understand(job_dir, 2, 0, "en", audio=("audio/wav", b"RIFF"))


# ---- the route -------------------------------------------------------------------------------------------------

def make_client(tmp_path, monkeypatch, gemini_key="stub", simulate=False):
    monkeypatch.setenv("GEMINI_API_KEY", gemini_key or "unused")
    voice_route._calls.clear()
    settings = Settings(_env_file=None, demo_key=KEY, jobs_dir=tmp_path / "jobs", gemini_api_key=gemini_key,
                        perception_simulate=simulate, client_dist=tmp_path / "no-client")
    return TestClient(create_app(settings), headers={"X-Demo-Key": KEY})


def done_job(client, job_dir):
    job = client.app.state.store.create(b"%PDF-1.4\n", "guide.pdf")
    (job.dir / "manifest.json").write_bytes((job_dir / "manifest.json").read_bytes())
    return job


def test_route_needs_the_demo_key(tmp_path, monkeypatch, job_dir):
    with make_client(tmp_path, monkeypatch) as c:
        job = done_job(c, job_dir)
        r = c.post(f"/api/voice/{job.id}", json={"text": "next"}, headers={"X-Demo-Key": "wrong"})
        assert r.status_code == 401
        assert c.post("/api/voice/nope", json={"text": "next"}).status_code == 404


def test_route_audio_goes_to_the_model_and_comes_back_spoken(tmp_path, monkeypatch, job_dir, model):
    spoken = []

    async def fake_synthesize(text, key):
        spoken.append(text)
        return b"RIFFfake"

    monkeypatch.setattr(tts, "synthesize", fake_synthesize)
    tts._cache.clear()
    model.reply = said("επόμενο", "next")
    with make_client(tmp_path, monkeypatch) as c:
        job = done_job(c, job_dir)
        r = c.post(f"/api/voice/{job.id}", json={"audio": wav_b64(header=False), "mime": "audio/wav", "lang": "el",
                                                 "step": 2, "action": 1, "speak": True})
        assert r.status_code == 200, r.text
        out = r.json()
        assert out["intent"] == "next" and out["target"] == {"step": 2, "action": 2}
        assert base64.b64decode(out["tts"]) == b"RIFFfake" and spoken == [out["answer"]]
        mime, clip = model.calls[0]["parts"][-1]
        assert mime == "audio/wav" and clip[:4] == b"RIFF" and struct.unpack("<I", clip[24:28])[0] == 16000


def test_route_accepts_ogg_and_rejects_bad_audio(tmp_path, monkeypatch, job_dir, model):
    model.reply = said("next", "next")
    with make_client(tmp_path, monkeypatch) as c:
        job = done_job(c, job_dir)
        ogg = base64.b64encode(b"OggS" + b"\x00" * 60).decode()
        r = c.post(f"/api/voice/{job.id}", json={"audio": ogg, "mime": "audio/ogg;codecs=opus", "step": 2})
        assert r.status_code == 200 and model.calls[-1]["parts"][-1][0] == "audio/ogg"
        assert "tts" not in r.json()                                          # only with speak: true
        assert c.post(f"/api/voice/{job.id}", json={"audio": "!!", "step": 2}).status_code == 422
        assert c.post(f"/api/voice/{job.id}", json={"audio": ogg, "mime": "audio/mp4", "step": 2}).status_code == 415
        assert c.post(f"/api/voice/{job.id}", json={"step": 2}).status_code == 422
        assert c.post(f"/api/voice/{job.id}", json={"text": "next", "step": 99}).status_code == 422


def test_route_simulates_without_a_gemini_key(tmp_path, monkeypatch, job_dir, model):
    with make_client(tmp_path, monkeypatch, gemini_key="") as c:
        job = done_job(c, job_dir)
        out = c.post(f"/api/voice/{job.id}", json={"text": "πού είμαι;", "lang": "el", "step": 2, "action": 2,
                                                   "speak": True}).json()
        assert out["intent"] == "where_am_i" and out["simulated"] is True and "tts" not in out
        assert out["answer"].startswith("Είσαι στο βήμα 2 από 3, σελίδα 5.")
        assert not model.calls


def test_route_simulate_flag_wins_over_a_key(tmp_path, monkeypatch, job_dir, model):
    with make_client(tmp_path, monkeypatch, simulate=True) as c:
        job = done_job(c, job_dir)
        out = c.post(f"/api/voice/{job.id}", json={"text": "ποια βίδα χρειάζομαι;", "lang": "el", "step": 2}).json()
        assert out["intent"] == "ask" and out["simulated"] is True
        assert out["answer"] == "Στο βήμα 2 θέλεις 2 x ξύλινη καβίλια 101345, σελίδα 5."
        assert not model.calls


def test_route_model_failure_is_a_502(tmp_path, monkeypatch, job_dir):
    def boom(*a, **k):
        raise RuntimeError("gemini-4-hydrogen 500")

    monkeypatch.setattr("server.voice.gemini.generate", boom)
    with make_client(tmp_path, monkeypatch) as c:
        job = done_job(c, job_dir)
        r = c.post(f"/api/voice/{job.id}", json={"audio": wav_b64(), "step": 2})
        assert r.status_code == 502
