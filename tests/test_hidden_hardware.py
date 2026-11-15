"""step_complete for a step whose added hardware does not show on the reference sheet (MALM drawer steps 3 and 7):
the wording, the close-up (`pipeline.perception.details`) and the in-memory update of older question files.
No network and no Blender: the renders are made here."""
from __future__ import annotations

import json
from pathlib import Path

import pytest
from PIL import Image

from pipeline.perception import details
from pipeline.perception import questions as writer
from pipeline.perception import request as bench
from server.perception.guide import Guide
from server.perception.questions import body_of, build_ask, fallback_pack, load_pack
from tests.test_perception import LONG, jpeg, make_job

ORANGE = (255, 107, 13)
WOOD = (170, 150, 120)


def picture(path: Path, blob: tuple | None = None, size: int = 768, colour=ORANGE) -> None:
    im = Image.new("RGB", (size, size), "white")
    im.paste(WOOD, (size // 4, size // 4, 3 * size // 4, 3 * size // 4))
    if blob:
        im.paste(colour, blob)
    im.save(path)


def job_with_renders(tmp_path, detail: bool = False) -> Path:
    """The test job with the reference pairs of steps 1 and 2: step 1's additions show (an orange block), step 2's
    two long screws do not (`added` and `ref` are the same picture), as on the MALM drawer."""
    d = make_job(tmp_path)
    refs = d / "refs"
    picture(refs / "step-01-ref.png")
    picture(refs / "step-01-added.png", (300, 300, 400, 400))
    picture(refs / "step-02-ref.png")
    picture(refs / "step-02-added.png")
    if detail:
        Image.new("RGB", (1024, 512), "white").save(refs / "step-02-detail.png")
        sheet = json.loads((refs / "step-02-sheet.json").read_text())
        (refs / "step-02-sheet.json").write_text(json.dumps({**sheet, "detail": "refs/step-02-detail.png"}))
    writer.build(d, use_model=False)
    bench.step_pack.cache_clear()
    return d


def pack_of(d: Path, n: int) -> dict:
    return json.loads((d / "refs" / f"step-{n:02d}-questions.json").read_text())


def test_added_px_tells_whether_the_additions_show(tmp_path):
    d = job_with_renders(tmp_path)
    sheet = lambda n: json.loads((d / "refs" / f"step-{n:02d}-sheet.json").read_text())   # noqa: E731
    assert writer.added_px(d, sheet(1)) == 100 * 100 and not writer.hidden(100 * 100)
    assert writer.added_px(d, sheet(2)) == 0 and writer.hidden(0)
    assert writer.added_px(d, sheet(3)) is None and not writer.hidden(None)     # no render pair: unknown, old behaviour
    assert writer.added_px(d, {"candidates": []}) is None                       # an older sheet


def test_visible_additions_keep_the_orange_wording_and_say_that_steps_are_done_in_order(tmp_path):
    d = job_with_renders(tmp_path)
    pack = pack_of(d, 1)
    q = pack["questions"]["step_complete"]
    assert "drawn in ORANGE in the LEFT half of Image 2" in q["instructions"]
    assert writer.LATER_NOTE.format(n=1) in q["instructions"]
    assert "drawn in ORANGE" in pack["sheet_note"] and pack["added_px"] == 10000 and pack["detail"] is None
    assert pack["step_complete_v"] == writer.STEP_COMPLETE_V
    assert len(bench.build(d, 1, jpeg())[0]) == 4


def test_hidden_hardware_without_a_close_up_is_asked_for_itself_and_cannot_tell_means_no(tmp_path):
    d = job_with_renders(tmp_path)
    pack = pack_of(d, 2)
    q = pack["questions"]["step_complete"]
    assert pack["added_px"] == 0 and pack["detail"] is None and pack["detail_note"] is None
    assert "ORANGE" not in q["instructions"] and "ORANGE" not in pack["sheet_note"]
    assert "not evidence that the step is done" in q["instructions"] and f"label {LONG}" in q["instructions"]
    assert "too small or too hidden to show there" in pack["sheet_note"]
    assert "hidden, too small or too far away to tell" in q["criteria"]["false_description"]
    assert writer.validate(pack["questions"]) == [] and len(bench.build(d, 2, jpeg())[0]) == 4


def test_hidden_hardware_with_a_close_up_gets_a_third_image_in_both_request_builders(tmp_path):
    d = job_with_renders(tmp_path, detail=True)
    pack = pack_of(d, 2)
    q = pack["questions"]["step_complete"]
    assert pack["detail"] == "refs/step-02-detail.png" and pack["detail_note"] == writer.DETAIL_NOTE.format(n=2)
    assert "use Image 3" in q["instructions"] and "AFTER half of Image 3" in q["criteria"]["true_description"]
    assert "BEFORE half of Image 3" in q["criteria"]["false_description"]
    shot = jpeg()
    inputs, questions = bench.build(d, 2, shot)
    assert [type(b) for b in inputs] == [str, tuple, str, tuple, str, tuple] and inputs[4] == pack["detail_note"]
    guide = Guide(d)
    ask = build_ask(guide, load_pack(guide, 2), shot, extra=False)
    assert ask.inputs == inputs and ask.questions == questions
    body = body_of(ask)
    assert [b["type"] for b in body["input"]] == ["text", "image", "text", "image", "text", "image"]
    assert fallback_pack(guide, 2) == pack                                      # the in-memory writer agrees
    (d / "refs" / "step-02-detail.png").unlink()                               # a lost close-up: the request has none
    assert len(bench.build(d, 2, shot)[0]) == 4
    assert len(bench.build(d, 1, shot)[0]) == 4                                 # a step that needs none has none


def test_an_older_question_file_is_brought_up_to_date_in_memory_only(tmp_path, caplog):
    d = job_with_renders(tmp_path, detail=True)
    old_question = {"type": "noul", "instructions": "Step 2 adds 2 Long screws, drawn in ORANGE in the LEFT half of Image 2. "
                    "In Image 1, are the parts shown in orange there now attached to the assembly?",
                    "criteria": {"true_description": "attached", "false_description": "not attached"}}
    for n in (1, 2):
        pack = pack_of(d, n)
        old = {k: v for k, v in pack.items() if k not in ("step_complete_v", "added_px", "detail", "detail_note")}
        old["sheet_note"] = writer.SHEET_NOTE.format(n=n, orange=writer.ORANGE_NOTE.format(n=n))
        old["questions"] = {**pack["questions"], "step_complete": old_question}
        (d / "refs" / f"step-{n:02d}-questions.json").write_text(json.dumps(old))
    bench.step_pack.cache_clear()
    on_disk = (d / "refs" / "step-02-questions.json").read_text()
    guide = Guide(d)
    with caplog.at_level("INFO", logger="agj.perception"):
        sp = load_pack(guide, 2)
    assert sp.origin == "file" and sp.pack["step_complete_v"] == writer.STEP_COMPLETE_V
    assert "use Image 3" in sp.pack["questions"]["step_complete"]["instructions"]
    assert "ORANGE" not in sp.pack["sheet_note"] and sp.pack["detail"] == "refs/step-02-detail.png"
    assert "brought to wording" in caplog.text and "close-up" in caplog.text
    assert (d / "refs" / "step-02-questions.json").read_text() == on_disk        # the job folder is not touched
    assert bench.step_pack(str(d), 2)["questions"]["step_complete"] == old_question   # nor the cached file content
    other = {k: v for k, v in sp.pack["questions"].items() if k != "step_complete"}
    assert other == {k: v for k, v in json.loads(on_disk)["questions"].items() if k != "step_complete"}
    assert len(build_ask(guide, sp, jpeg(), extra=False).inputs) == 6
    visible = load_pack(guide, 1).pack                                           # a step whose additions show
    assert "drawn in ORANGE" in visible["questions"]["step_complete"]["instructions"]
    assert writer.LATER_NOTE.format(n=1) in visible["questions"]["step_complete"]["instructions"]
    assert load_pack(guide, 2).pack == sp.pack                                   # and it is stable


def test_criteria_written_by_the_model_are_kept_unless_the_additions_are_hidden(tmp_path):
    d = job_with_renders(tmp_path)
    manifest = json.loads((d / "manifest.json").read_text())
    for n, shown, kept in ((1, 10000, True), (2, 0, False)):
        sheet = json.loads((d / "refs" / f"step-{n:02d}-sheet.json").read_text())
        pack = pack_of(d, n)
        words = {"true_description": "the model's yes", "false_description": "the model's no"}
        old = {**{k: v for k, v in pack.items() if k != "step_complete_v"}, "written_by": "gemini-4-hydrogen",
               "questions": {**pack["questions"], "step_complete": {**pack["questions"]["step_complete"], "criteria": words}}}
        new = writer.current(old, manifest, sheet, n - 1, shown)
        assert (new["questions"]["step_complete"]["criteria"] == words) is kept
        assert new["step_complete_v"] == writer.STEP_COMPLETE_V and "step_complete_v" not in old
        assert writer.current(new, manifest, sheet, n - 1, shown) is new


def test_details_renders_other_sides_and_keeps_the_one_that_shows_the_hardware(tmp_path, monkeypatch):
    d = job_with_renders(tmp_path)
    picture(d / "refs" / "step-04-ref.png")                 # step 4's screws show from no side at all
    picture(d / "refs" / "step-04-added.png")
    size, calls = 384, []
    monkeypatch.setattr(details, "SIZE", size)

    def blender(script, job, edge, view, out, bg, mode):
        calls.append((view, mode))
        out = Path(out)
        out.mkdir(parents=True, exist_ok=True)
        for n in (1, 2, 3, 4):
            # step 2's screws show only from the second side, as a 12 x 12 px block near the lower right
            shows = n == 2 and view == "-135,30" and mode == "added"
            picture(out / f"step-{n:02d}-{mode}.png", (300, 280, 312, 292) if shows else None, size=size)
        return {"refs": []}

    monkeypatch.setattr(details.refs, "blender", blender)
    out = details.build(d)
    assert out["hidden_steps"] == [2, 4] and out["still_hidden"] == [4]
    assert out["details"] == {2: {"view_az_el_deg": [-135, 30], "px": 144}}
    assert len(calls) == 3 * len(details.VIEWS) and {m for _, m in calls} == {"ref", "added", "before"}
    sheet = json.loads((d / "refs" / "step-02-sheet.json").read_text())
    assert sheet["detail"] == "refs/step-02-detail.png" and sheet["detail_view_az_el_deg"] == [-135, 30]
    close_up = Image.open(d / "refs" / "step-02-detail.png").convert("RGB")
    assert close_up.size == (2 * details.PANEL, details.PANEL)
    r, g, b = close_up.getpixel((details.PANEL + details.PANEL // 2, details.PANEL // 2))   # the AFTER half is centred on the screw
    assert r > 200 and b < 80
    assert close_up.getpixel((details.PANEL // 2, details.PANEL // 2)) != (r, g, b)         # the BEFORE half is not orange there
    assert "detail" not in json.loads((d / "refs" / "step-04-sheet.json").read_text())
    assert not (d / "refs" / "step-04-detail.png").exists()
    # the questions written next pick the close-up up
    writer.build(d, use_model=False)
    assert pack_of(d, 2)["detail"] == "refs/step-02-detail.png" and pack_of(d, 4)["detail"] is None


def test_a_job_without_hidden_steps_renders_nothing(tmp_path, monkeypatch):
    d = make_job(tmp_path)

    def boom(*a, **k):
        raise AssertionError("no render expected")
    monkeypatch.setattr(details.refs, "blender", boom)
    assert details.build(d) == {"hidden_steps": [], "details": {}, "seconds": 0.0}
