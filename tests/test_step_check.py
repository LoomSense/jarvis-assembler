"""The build agent's step check (pipeline/check/step_check.py) and its wiring in the agent's workspace. No network:
the verifier is a stand-in, and one test replaces the Decisions API's HTTP call."""
from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest
from claude_agent_sdk import AssistantMessage, ToolResultBlock, ToolUseBlock, UserMessage
from PIL import Image, ImageDraw

from pipeline.agent import extract
from pipeline.check import decisions, step_check
from pipeline.perception.questions import validate

PASS = {"same_parts": {"noul": 0.85}, "same_arrangement": {"noul": 0.8}, "screws_placed": {"noul": 0.8},
        "pose": {"choice": "same", "confidence": 0.7}, "main_difference": {"choice": "none", "confidence": 0.5}}
TURNED = {**PASS, "pose": {"choice": "upside_down", "confidence": 0.5},
          "main_difference": {"choice": "turned_differently", "confidence": 0.5}}
MISSING = {**PASS, "same_parts": {"noul": 0.2}}


def _page(path: Path, blocks: int) -> None:
    """A white page with `blocks` black drawings stacked top to bottom."""
    im = Image.new("RGB", (400, 600), "white")
    d = ImageDraw.Draw(im)
    for i in range(blocks):
        y = 40 + i * 260
        d.rectangle((60, y, 340, y + 180), outline="black", width=6)
    im.save(path)


def _render(path: Path, shade: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (300, 300), (shade, shade, shade)).save(path)


@pytest.fixture()
def ws(tmp_path):
    """An agent workspace: two steps on page 1 (no box), one on page 2 with a box; three renders."""
    (tmp_path / "guide").mkdir()
    _page(tmp_path / "guide" / "page-1.png", 2)
    _page(tmp_path / "guide" / "page-2.png", 1)
    plan = {"title": "Box", "steps": [
        {"n": 1, "cite": {"page": 1, "label": "1"}, "hardware_used": {}},
        {"n": 2, "cite": {"page": 1, "label": "2"}, "hardware_used": {"108363": 2}},
        {"n": 3, "cite": {"page": 2, "label": "3", "box_2d": [50, 100, 400, 900]}, "hardware_used": {"108560": 4}}],
        "flags": []}
    (tmp_path / "job").mkdir()
    (tmp_path / "job" / "plan.json").write_text(json.dumps(plan))
    for n in (1, 2, 3):
        _render(tmp_path / "job" / "out" / "renders" / f"step_{n:02d}.png", 100 + n)
    return tmp_path


class Verifier:
    """Answers by step, read from the render's grey level; records every call."""

    def __init__(self, answers: dict):
        self.answers, self.calls = answers, []

    def __call__(self, inputs, qs):
        assert not validate(qs)
        render = Image.open(__import__("io").BytesIO(inputs[4][1]))
        n = (render.getpixel((5, 5))[0] - 100) % 10 or 1
        self.calls.append((n, sorted(qs)))
        a = {k: v for k, v in self.answers[n].items() if k in qs}
        return {"answers": a, "latency_ms": 12}


def test_each_step_gets_a_verdict_a_drawing_and_actionable_words(ws):
    v = Verifier({1: PASS, 2: TURNED, 3: MISSING})
    r = step_check.run(ws / "job" / "plan.json", ws / "job" / "out", ask=v)
    by = {x["n"]: x for x in r["results"]}
    assert (r["checked"], r["passed"], r["failed"]) == (3, 1, [2, 3])
    assert by[1]["pass"] and by[1]["drawing_source"] == "page_auto"
    assert by[3]["drawing_source"] == "page_box"
    # the two steps on page 1 are cut apart, not both given the whole page
    a, b = (Image.open(ws / "job" / "out" / "check" / f"step-0{n}-guide.png") for n in (1, 2))
    assert a.height < 400 and b.height < 400
    assert by[2]["failed"] == ["turned"] and "upside down" in by[2]["fix"][0] and "assembly_rotation_deg" in by[2]["fix"][0]
    assert by[3]["failed"] == ["same_parts"] and "parts_added" in by[3]["fix"][0]
    # screws are asked about only where the step uses hardware
    assert "screws_placed" not in dict(v.calls)[1] and "screws_placed" in dict(v.calls)[2]
    saved = json.loads((ws / "job" / "out" / "step_check.json").read_text())
    assert saved["verifier"] == "decision" and [x["n"] for x in saved["results"]] == [1, 2, 3]
    lines = r["lines"]
    assert lines[0].startswith("step  1: pass") and "FAIL" in lines[1]
    assert "job/out/renders/step_02.png" in lines[1] and "job/out/check/step-02-guide.png" in lines[1]
    assert "fix 1 of 3" in lines[1]


def test_a_sure_main_difference_fails_a_step_on_its_own_and_a_crop_is_preferred(ws):
    out = ws / "job" / "out"
    (out / "crops").mkdir()
    Image.new("RGB", (200, 100), "white").save(out / "crops" / "step-01.png")
    sure = {**PASS, "main_difference": {"choice": "attached_elsewhere", "confidence": 0.7}}
    r = step_check.run(ws / "job" / "plan.json", out, only=1, ask=Verifier({1: sure}))
    res = r["results"][0]
    assert res["drawing_source"] == "crop" and res["failed"] == ["same_arrangement"]


def test_unchanged_renders_are_not_asked_again_and_fix_rounds_end_in_a_flag(ws):
    plan, out = ws / "job" / "plan.json", ws / "job" / "out"
    v = Verifier({1: PASS, 2: TURNED, 3: PASS})
    step_check.run(plan, out, ask=v)
    step_check.run(plan, out, ask=v)
    assert len(v.calls) == 3                                     # second run: all cached
    for k in range(3):                                           # three fixes, all still wrong
        _render(out / "renders" / "step_02.png", 112 + 10 * k)
        r = step_check.run(plan, out, only=2, ask=v)
    res = r["results"][1]
    assert res["rounds"] == 4 and step_check.used_up(res)
    assert "3 fixes tried" in r["lines"][0] and '"ref": "step:2"' in r["lines"][0]
    p = json.loads(plan.read_text())
    p["flags"].append({"ref": "step:2", "kind": "unclear_drawing", "message": "Drawing check: still turned"})
    plan.write_text(json.dumps(p))
    r = step_check.run(plan, out, only=2, ask=v)
    assert "flagged for a human" in r["lines"][0]


def test_cli_exit_code_and_one_step(ws, monkeypatch, capsys):
    plan, out = str(ws / "job" / "plan.json"), str(ws / "job" / "out")
    monkeypatch.setitem(step_check.VERIFIERS, "decision", Verifier({1: PASS, 2: TURNED, 3: PASS}))
    assert step_check.main([plan, out]) == 1
    text = capsys.readouterr().out
    assert text.count("\nstep ") + text.startswith("step ") == 3
    result = json.loads(text.strip().splitlines()[-1][7:])
    assert result["failed"] == [2] and result["to_fix"] == [2]
    assert step_check.main([plan, out, "3"]) == 0
    assert capsys.readouterr().out.splitlines()[0].startswith("step  3: pass")
    with pytest.raises(ValueError):
        step_check.main([plan, out, "9"])


def test_a_missing_render_is_skipped_not_failed(ws):
    (ws / "job" / "out" / "renders" / "step_03.png").unlink()
    r = step_check.run(ws / "job" / "plan.json", ws / "job" / "out", ask=Verifier({1: PASS, 2: PASS}))
    assert r["results"][2]["skipped"].startswith("no render") and r["checked"] == 2


def test_the_decision_verifier_sends_both_images_to_gemini_decision_1(ws, monkeypatch):
    sent = []

    def post(body, timeout):
        sent.append(json.loads(body))
        return 200, json.dumps({"answers": PASS}).encode()

    monkeypatch.setattr(decisions, "_post", post)
    r = step_check.run(ws / "job" / "plan.json", ws / "job" / "out", only=2)
    assert [x["n"] for x in r["results"]] == [2] and r["results"][0]["pass"]
    body = sent[0]
    assert body["model"] == "gemini-decision-1"
    images = [b for b in body["input"] if b["type"] == "image"]
    assert len(images) == 2 and all(b["mime_type"] == "image/jpeg" and b["resolution"] == "medium" for b in images)
    assert set(body["questions"]) == {"same_parts", "same_arrangement", "screws_placed", "pose", "main_difference"}


def test_iris_is_a_seam_that_says_what_is_missing(ws, monkeypatch):
    monkeypatch.setenv("AGJ_VERIFIER", "iris")
    monkeypatch.delenv("OUMI_API_KEY", raising=False)
    r = step_check.run(ws / "job" / "plan.json", ws / "job" / "out", only=1)
    assert "not configured" in r["results"][0]["error"] and r["verifier"] == "iris"
    monkeypatch.setenv("OUMI_API_KEY", "x")
    monkeypatch.setenv("OUMI_IRIS_URL", "https://example.invalid")
    r = step_check.run(ws / "job" / "plan.json", ws / "job" / "out", only=1)
    assert "NotImplementedError" in r["results"][0]["error"]
    monkeypatch.setenv("AGJ_VERIFIER", "nope")
    with pytest.raises(RuntimeError, match="AGJ_VERIFIER"):
        step_check.verifier_name()


# ---- the agent's workspace ----

def test_workspace_has_check_and_the_guard_allows_only_build_and_check(tmp_path):
    (tmp_path / "pages").mkdir()
    _page(tmp_path / "pages" / "page-001.png", 1)
    ws = extract.setup_workspace(tmp_path, "")
    check = ws / "check"
    assert check.stat().st_mode & 0o111 and "pipeline.check.step_check" in check.read_text()
    brief = (ws / "BRIEF.md").read_text()
    assert "    ./check [N]" in brief and "pipeline.check.step_check" not in brief
    assert "./check" in (ws / "FACTS.md").read_text()
    hook = extract.guard(ws)

    def decide(cmd):
        out = asyncio.run(hook({"tool_name": "Bash", "tool_input": {"command": cmd}}, "id", None))
        return out.get("hookSpecificOutput", {}).get("permissionDecision", "allow")

    assert [decide(c) for c in ("./build", "./check", "./check 3", " ./check 12 ")] == ["allow"] * 4
    assert [decide(c) for c in ("./check 3 4", "./check; ls", "./check $(cat x)", "./build 1", "ls")] == ["deny"] * 5


def test_check_calls_and_results_reach_the_feed():
    events, state = [], {"builds": 0, "passed": 0, "bash": set(), "checks": 0, "check": set()}
    emit = lambda type, text="", **f: events.append((type, text, f))  # noqa: E731
    extract._report(AssistantMessage(content=[ToolUseBlock(id="c1", name="Bash", input={"command": "./check 2"})],
                                     model="m"), Path("."), emit, state)
    ok = 'step  2: pass  0.80\nRESULT {"verifier": "decision", "steps": 3, "checked": 3, "passed": 2, "mean_score": 0.7, ' \
         '"failed": [3], "errors": [], "wall_s": 1.0, "to_fix": [3]}'
    extract._report(UserMessage(content=[ToolResultBlock(tool_use_id="c1", content=ok)]), Path("."), emit, state)
    assert state["checks"] == 1 and state["builds"] == 0
    assert events[0] == ("tool_call", "Comparing step 2 with the guide's drawing", {"tool": "check"})
    assert events[1][0] == "tool_result" and events[1][2] == {"tool": "check", "level": "warn"}
    assert "2 of 3 steps match" in events[1][1] and "step 3" in events[1][1]
    extract._report(UserMessage(content=[ToolResultBlock(tool_use_id="c1", content="Traceback: boom")]), Path("."), emit, state)
    assert events[2][1].startswith("The drawing check did not run") and events[2][2]["level"] == "warn"


def test_final_check_flags_failing_steps_and_never_raises(ws, monkeypatch):
    monkeypatch.setitem(step_check.VERIFIERS, "decision", Verifier({1: PASS, 2: TURNED, 3: PASS}))
    events = []
    emit = lambda type, text="", **f: events.append((type, text, f))  # noqa: E731
    plan = json.loads((ws / "job" / "plan.json").read_text())
    stats = extract.final_check(ws, plan, emit)
    assert stats["failed"] == [2] and stats["scores"].keys() == {"1", "2", "3"}
    assert [f["ref"] for f in plan["flags"]] == ["step:2"] and plan["flags"][0]["kind"] == "unclear_drawing"
    assert events[-1][2] == {"tool": "check", "level": "warn"}
    extract.final_check(ws, plan, emit)                          # an existing flag is not added twice
    assert len(plan["flags"]) == 1

    def broken(*a, **k):
        raise RuntimeError("GEMINI_API_KEY is not set")

    monkeypatch.setattr(step_check, "run", broken)
    assert "error" in extract.final_check(ws, plan, emit) and events[-1][2]["level"] == "warn"
