"""MCP server tests: the tools against the real FastAPI app, served by uvicorn on a free local port in a thread.
The end-to-end test needs Blender (about 20 s) and is skipped without it."""
from __future__ import annotations

import asyncio
import socket
import threading
import time

import pytest
import uvicorn

from server import mcp_server as M
from server.app import create_app
from server.settings import Settings
from tests.test_server import GOLDEN, HAS_BLENDER, KEY

TOOLS = {"create_guide", "guide_status", "wait_for_guide", "get_plan", "approve_plan", "get_steps",
         "guide_links", "list_guides"}


def run(coro):
    return asyncio.run(coro)


@pytest.fixture()
def backend(tmp_path, monkeypatch):
    settings = Settings(_env_file=None, demo_key=KEY, jobs_dir=tmp_path / "jobs", gemini_api_key="",
                        client_dist=tmp_path / "no-client")
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(create_app(settings), host="127.0.0.1", port=port, log_level="warning"))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.time() + 15
    while not server.started:
        assert time.time() < deadline, "the backend did not start"
        time.sleep(0.05)
    monkeypatch.setenv("AGJ_BASE_URL", f"http://127.0.0.1:{port}")
    monkeypatch.setenv("AGJ_DEMO_KEY", KEY)
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(timeout=10)


def test_tools_are_listed_with_their_schemas():
    tools = {t.name: t for t in run(M.mcp.list_tools())}
    assert set(tools) == TOOLS
    for t in tools.values():
        assert t.description and len(t.description) > 40, t.name
    assert tools["create_guide"].input_schema["required"] == ["pdf_path"]
    assert set(tools["create_guide"].input_schema["properties"]) == {"pdf_path", "size"}
    assert tools["wait_for_guide"].input_schema["properties"]["until"]["enum"] == ["approval", "done"]
    assert tools["get_steps"].input_schema["properties"]["lang"]["enum"] == ["en", "el"]
    assert tools["get_plan"].annotations.read_only_hint is True
    templates = run(M.mcp.list_resource_templates())
    assert [t.uri_template for t in templates] == ["guide://{job_id}/manifest"]


def test_unknown_job_and_bad_input_are_readable_errors(backend, tmp_path):
    with pytest.raises(M.ToolError, match="Not found: No such job"):
        run(M.guide_status("nope123"))
    with pytest.raises(M.ToolError, match="not a valid job id"):
        run(M.get_steps("../../etc"))
    with pytest.raises(M.ToolError, match="No file"):
        run(M.create_guide(str(tmp_path / "missing.pdf")))
    (tmp_path / "x.pdf").write_bytes(b"GIF89a")
    with pytest.raises(M.ToolError, match="not a PDF"):
        run(M.create_guide(str(tmp_path / "x.pdf")))
    # the same through the MCP call path: an error result the model can read, not a crash
    result = run(M.mcp._handle_call_tool(None, type("P", (), {"name": "guide_status",
                                                              "arguments": {"job_id": "nope123"}})()))
    assert result.is_error and "No such job" in result.content[0].text
    assert run(M.list_guides()) == {"jobs": [], "catalog": None}


def test_wrong_key_or_no_backend_never_leaks_the_key(backend, monkeypatch):
    monkeypatch.setenv("AGJ_DEMO_KEY", "wrong-secret-123")
    with pytest.raises(M.ToolError, match="refused the demo key") as e:
        run(M.list_guides())
    assert "wrong-secret-123" not in str(e.value)
    monkeypatch.setenv("AGJ_BASE_URL", "http://127.0.0.1:9")
    with pytest.raises(M.ToolError, match="Cannot reach") as e:
        run(M.guide_status("abc123"))
    assert "wrong-secret-123" not in str(e.value)


def test_links_carry_the_key_only_for_a_local_backend(monkeypatch):
    monkeypatch.setenv("AGJ_DEMO_KEY", KEY)
    monkeypatch.setenv("AGJ_BASE_URL", "http://127.0.0.1:8000")
    assert M.link("/files/j/a.png") == f"http://127.0.0.1:8000/files/j/a.png?key={KEY}"
    monkeypatch.setenv("AGJ_BASE_URL", "https://demo.example.com/")
    assert M.link("/files/j/a.png") == "/files/j/a.png"


@pytest.mark.skipif(not HAS_BLENDER, reason="Blender is not installed")
def test_golden_guide_through_the_tools(backend):
    created = run(M.create_guide(str(GOLDEN), size="46 x 31 x 25 cm"))
    job_id = created["job_id"]

    waited = run(M.wait_for_guide(job_id, until="approval", timeout_s=120))
    assert waited["reached"] == "approval", waited
    assert waited["status"]["status"] == "waiting_approval" and waited["recent_events"]

    plan = run(M.get_plan(job_id))
    assert plan["approved"] is False and len(plan["steps"]) == 8 and plan["plan"]["steps"]
    assert {h["code"] for h in plan["hardware"]} >= {"108363"} and all(p["count"] for p in plan["parts"])
    assert plan["pages"][0].startswith(backend) and f"key={KEY}" in plan["pages"][0]

    with pytest.raises(M.ToolError, match="not ready"):
        run(M.get_steps(job_id))
    assert run(M.approve_plan(job_id, note="looks right"))["stage"] == "building"
    with pytest.raises(M.ToolError, match="409"):
        run(M.approve_plan(job_id))

    done = run(M.wait_for_guide(job_id, until="done", timeout_s=300))
    assert done["reached"] == "done", done
    assert done["status"]["status"] == "done" and done["recent_events"][-1]["type"] == "done"

    steps = run(M.get_steps(job_id))
    assert steps["step_count"] == 8 and steps["lang"] == "en"
    s2 = steps["steps"][1]
    assert s2["n"] == 2 and s2["title"] and s2["page"]
    assert {"code": "108363", "name": "long screw", "qty": 2} in s2["hardware"]
    assert s2["actions"] and all(isinstance(a, str) and a for a in s2["actions"])
    assert s2["render"].startswith(backend) and s2["guide_crop"]
    greek = run(M.get_steps(job_id, lang="el"))["steps"][1]
    assert greek["actions"] != s2["actions"] and greek["hardware"][0]["name"] != "long screw"

    links = run(M.guide_links(job_id))
    assert links["phone"] == f"{backend}/?job={job_id}&key={KEY}" and links["headset"].endswith("&mode=xr")
    listed = run(M.list_guides())
    assert listed["jobs"][0]["job_id"] == job_id and listed["jobs"][0]["status"] == "done"
    manifest = run(M.mcp.read_resource(f"guide://{job_id}/manifest"))
    assert job_id in manifest[0].content
