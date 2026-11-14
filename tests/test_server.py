"""Server tests. `make test`. The end-to-end test needs Blender and is skipped without it."""
from __future__ import annotations

import asyncio
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from server.app import create_app
from server.events import EventLog
from server.settings import MAC_BLENDER, ROOT, Settings

KEY = "test-key"
GOLDEN = ROOT / "eval/guides/ikea/knagglig-box__AA-1159337-7.pdf"
HAS_BLENDER = bool(shutil.which("blender")) or Path(MAC_BLENDER).exists()


@pytest.fixture()
def client(tmp_path):
    settings = Settings(_env_file=None, demo_key=KEY, jobs_dir=tmp_path / "jobs", gemini_api_key="",
                        client_dist=tmp_path / "no-client")
    with TestClient(create_app(settings), headers={"X-Demo-Key": KEY}) as c:
        yield c


def wait_for(client, job_id, predicate, timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        status = client.get(f"/api/jobs/{job_id}").json()
        if predicate(status):
            return status
        time.sleep(0.2)
    raise AssertionError(f"timed out; last status: {status}")


def upload(client, data: bytes, name="guide.pdf"):
    return client.post("/api/jobs", files={"file": (name, data, "application/pdf")})


# ---- auth and input checks -----------------------------------------------------------------------

def test_server_refuses_to_start_without_a_demo_key(tmp_path):
    with pytest.raises(RuntimeError):
        create_app(Settings(_env_file=None, demo_key="", jobs_dir=tmp_path))


def test_every_route_needs_the_key(client):
    for method, path in [("get", "/api/jobs"), ("get", "/api/jobs/abc123"), ("get", "/api/jobs/abc123/events"),
                         ("get", "/api/jobs/abc123/plan"), ("post", "/api/jobs/abc123/plan/approve"),
                         ("post", "/api/jobs/abc123/retry"), ("get", "/files/abc123/manifest.json"),
                         ("post", "/api/live/token"), ("post", "/api/jobs")]:
        r = getattr(client, method)(path, headers={"X-Demo-Key": "wrong"})
        assert r.status_code == 401, (method, path, r.status_code)
    assert client.get("/api/jobs?key=" + KEY, headers={"X-Demo-Key": ""}).status_code == 200


def test_gate_stops_big_or_keyless_uploads_before_reading_them(client):
    big = {"Content-Length": str(60 * 1024 * 1024), "Content-Type": "multipart/form-data; boundary=x"}
    assert client.post("/api/jobs", content=b"", headers=big).status_code == 413
    assert client.post("/api/jobs", content=b"", headers={**big, "X-Demo-Key": "wrong"}).status_code == 401
    assert client.get("/").status_code == 200                       # the client page itself is public


def test_plan_scripts_must_stay_inside_parts():
    from server.orchestrator.stages import validate_plan
    plan = {"parts": [{"id": "a", "script": "parts/a.py"}], "hardware": [], "steps": [{"n": 1}]}
    assert validate_plan(plan) == []
    for bad in ("../a.py", "/etc/a.py", "parts/../../a.py", "parts/sub/a.py", "guide.pdf", "parts/a.txt", 7):
        plan["parts"][0]["script"] = bad
        assert validate_plan(plan), bad


def test_healthz_says_little_without_the_key(client):
    assert client.get("/healthz", headers={"X-Demo-Key": ""}).json() == {"ok": True}
    full = client.get("/healthz").json()
    assert {"ok", "blender_version", "cadquery_ok", "job_running"} <= set(full)


def test_upload_must_be_a_pdf(client):
    assert upload(client, b"GIF89a not a pdf").status_code == 415
    assert client.get("/api/jobs").json()["jobs"] == []


def test_unknown_job_is_404_and_bad_ids_cannot_escape(client):
    assert client.get("/api/jobs/nope123").status_code == 404
    assert client.get("/api/jobs/..%2F..%2Fetc").status_code in (404, 422)


def test_unknown_guide_stops_with_a_readable_error(client):
    job_id = upload(client, GOLDEN.read_bytes() + b"\n% changed").json()["job_id"]
    status = wait_for(client, job_id, lambda s: s["status"] in ("error", "waiting_approval"))
    assert status["status"] == "error" and status["stage"] == "extract_plan"
    assert "AGT-02" in status["error"]


# ---- files -------------------------------------------------------------------------------------

def test_files_cannot_leave_the_job_folder(client, tmp_path):
    job_id = upload(client, GOLDEN.read_bytes()).json()["job_id"]
    wait_for(client, job_id, lambda s: s["status"] == "waiting_approval")
    (tmp_path / "jobs" / "secret.txt").write_text("outside")
    assert client.get(f"/files/{job_id}/guide.pdf").status_code == 200
    assert client.get(f"/files/{job_id}/pages/page-001.png").headers["content-type"] == "image/png"
    for bad in ("../secret.txt", "%2e%2e/secret.txt", "pages/../../secret.txt", ".hidden", "nope.png"):
        assert client.get(f"/files/{job_id}/{bad}").status_code == 404, bad


# ---- events ------------------------------------------------------------------------------------

def test_event_log_numbers_persists_and_truncates(tmp_path):
    log = EventLog(tmp_path / "events.jsonl", "j1")
    a = log.emit("thought", "one", stage="parts")
    b = log.emit("tool_call", "two", stage="parts", tool="run_cadquery", args={"build_src": "x" * 900}, partId="A")
    assert (a["seq"], b["seq"]) == (1, 2)
    assert len(b["args"]["build_src"]) == 401 and b["partId"] == "A" and b["stepN"] is None
    assert {"seq", "ts", "jobId", "stage", "type", "level", "text"} <= set(a)
    with pytest.raises(ValueError):
        log.emit("not_a_type", "x")
    reopened = EventLog(tmp_path / "events.jsonl", "j1")           # a restart continues the numbering
    assert reopened.emit("progress", "three")["seq"] == 3
    assert [e["seq"] for e in reopened.read(after=1)] == [2, 3]


def test_event_log_survives_a_torn_last_line(tmp_path):
    path = tmp_path / "events.jsonl"
    EventLog(path, "j").emit("thought", "ok")
    with open(path, "a") as f:
        f.write('{"seq": 2, "text": "cut off by a cra')
    assert [e["seq"] for e in EventLog(path, "j").read()] == [1]


def test_follow_replays_then_tails(tmp_path):
    log = EventLog(tmp_path / "events.jsonl", "j")
    for i in range(3):
        log.emit("thought", f"old {i}")

    async def scenario():
        got = []

        async def reader():
            async for e in log.follow(after=1, heartbeat=5):
                if e:
                    got.append(e["seq"])
                if len(got) == 4:
                    return

        task = asyncio.create_task(reader())
        await asyncio.sleep(0.05)
        log.emit("thought", "new 1")
        log.emit("thought", "new 2")
        await asyncio.wait_for(task, 5)
        return got

    assert asyncio.run(scenario()) == [2, 3, 4, 5]


# ---- the whole flow ----------------------------------------------------------------------------

@pytest.mark.skipif(not HAS_BLENDER, reason="Blender is not installed")
def test_golden_guide_end_to_end_then_retry_skips_finished_stages(client, tmp_path):
    job_id = upload(client, GOLDEN.read_bytes()).json()["job_id"]
    assert client.post(f"/api/jobs/{job_id}/retry", json={"from_stage": "nonsense"}).status_code == 422
    wait_for(client, job_id, lambda s: s["status"] == "waiting_approval")
    plan = client.get(f"/api/jobs/{job_id}/plan").json()
    assert plan["approved"] is False and len(plan["plan"]["steps"]) == 8 and len(plan["pages"]) == 8
    assert client.post(f"/api/jobs/{job_id}/plan/approve", json={"plan": {"steps": []}}).status_code == 422

    assert client.post(f"/api/jobs/{job_id}/plan/approve", json={"note": "looks right"}).json() == {"stage": "building"}
    assert client.post(f"/api/jobs/{job_id}/plan/approve").status_code == 409          # only once
    status = wait_for(client, job_id, lambda s: s["status"] in ("done", "error"), timeout=300)
    assert status["status"] == "done", status
    manifest = client.get(f"/files/{job_id}/manifest.json").json()
    assert manifest["jobId"] == job_id and len(manifest["steps"]) == 8
    assert client.get(f"/files/{job_id}/walkthrough.glb").content[:4] == b"glTF"
    assert client.get(f"/api/jobs/{job_id}/plan").json()["approved"] is True

    job_dir = tmp_path / "jobs" / job_id
    polled = client.get(f"/api/jobs/{job_id}/events/poll?since=5&wait=0").json()       # the long-poll fallback
    assert polled["events"][0]["seq"] == 6 and polled["events"][-1]["seq"] == polled["last_seq"]
    t0 = time.time()
    assert client.get(f"/api/jobs/{job_id}/events/poll?since={polled['last_seq']}&wait=0.3").json()["events"] == []
    assert time.time() - t0 >= 0.25                                                    # it waited for news
    events = [json.loads(line) for line in (job_dir / "events.jsonl").read_text().splitlines()]
    assert [e["seq"] for e in events] == list(range(1, len(events) + 1))
    assert events[-1]["type"] == "done" and {"approval_required", "part_status", "step_ready"} <= {e["type"] for e in events}

    # The server must produce everything `pipeline.run` produces (it runs the same steps stage by stage).
    reference = tmp_path / "reference"
    run = subprocess.run([sys.executable, "-m", "pipeline.run", str(ROOT / "eval/golden/knagglig/plan.json"),
                          str(reference)], cwd=ROOT, capture_output=True, text=True, timeout=300)
    assert run.returncode == 0, run.stdout[-800:] + run.stderr[-800:]
    produced = lambda d: {str(p.relative_to(d)) for p in d.rglob("*") if p.is_file()}   # noqa: E731
    assert produced(reference) - produced(job_dir) == set(), "the server stages fell behind pipeline/run.py"
    ref_manifest = json.loads((reference / "manifest.json").read_text())
    assert set(ref_manifest) <= set(manifest) | {"glbPacked"}
    assert set(ref_manifest["steps"][0]) <= set(client.get(f"/files/{job_id}/manifest.json").json()["steps"][0])

    before = {p.name: p.stat().st_mtime_ns for p in (job_dir / "stages").iterdir()}
    assert client.post(f"/api/jobs/{job_id}/retry", json={"from_stage": "refs"}).json() == {"stage": "refs"}
    wait_for(client, job_id, lambda s: s["status"] in ("done", "error") and s["last_seq"] > len(events), timeout=300)
    after = {p.name: p.stat().st_mtime_ns for p in (job_dir / "stages").iterdir()}
    rerun = sorted(name for name in after if after[name] != before[name])
    assert rerun == ["export.done", "refs.done"]                                   # everything earlier was skipped
