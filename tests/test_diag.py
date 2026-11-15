"""Device check reports: POST/GET /api/diag (the headset page `/xr-check.html` posts its results here)."""
from __future__ import annotations

import json
import logging

import pytest
from fastapi.testclient import TestClient

from server.app import create_app
from server.settings import Settings

KEY = "test-key"


@pytest.fixture()
def client(tmp_path):
    settings = Settings(_env_file=None, demo_key=KEY, jobs_dir=tmp_path / "jobs", gemini_api_key="",
                        client_dist=tmp_path / "no-client")
    with TestClient(create_app(settings), headers={"X-Demo-Key": KEY}) as c:
        yield c


def test_report_is_saved_listed_and_read_back(client, tmp_path):
    report = {"kind": "XR check!", "summary": {"g2": "option B"}, "rows": [{"id": "hands", "result": "pass"}]}
    saved = client.post("/api/diag", json=report).json()["saved"]
    assert saved.endswith("-xr-check.json")                         # the kind is made safe for a file name
    stored = json.loads((tmp_path / "jobs" / "_diag" / saved).read_text())
    assert stored["report"] == report and stored["kind"] == "xr-check" and stored["received"] > 0
    listing = client.get("/api/diag").json()["reports"]
    assert [r["name"] for r in listing] == [saved] and listing[0]["summary"] == {"g2": "option B"}
    assert client.get(f"/api/diag/{saved}").json()["report"] == report
    assert client.get("/api/jobs").json()["jobs"] == []            # the report folder is not a job


def test_report_needs_the_key_and_must_be_a_small_json_object(client):
    assert TestClient(client.app).post("/api/diag", json={}).status_code == 401
    assert TestClient(client.app).post(f"/api/diag?key={KEY}", json={"kind": "phone"}).status_code == 200   # as the page sends it
    assert client.post("/api/diag", content=b"not json").status_code == 400
    assert client.post("/api/diag", json=[1, 2]).status_code == 400
    assert client.post("/api/diag", json={"blob": "x" * (300 * 1024)}).status_code == 413
    assert client.get("/api/diag/..%2F..%2Fstatus.json").status_code == 404
    assert client.get("/api/diag/20261114-000000-000-nope.json").status_code == 404


def test_request_log_never_shows_the_demo_key(client):
    """uvicorn logs request lines and WebSocket URLs with their query string; the key in `?key=` must be hidden."""
    lines: list[str] = []

    class Collect(logging.Handler):
        def emit(self, record):
            lines.append(record.getMessage())

    handler = Collect()
    access, errors = logging.getLogger("uvicorn.access"), logging.getLogger("uvicorn.error")
    for logger in (access, errors):
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
    try:   # the two shapes uvicorn uses: an HTTP access line and an accepted WebSocket
        access.info('%s - "%s %s HTTP/%s" %d', "1.2.3.4:5", "GET", f"/files/j/manifest.json?key={KEY}&v=2", "1.1", 200)
        errors.info('%s - "WebSocket %s" [accepted]', ("1.2.3.4", 5), f"/ws/perception/j?role=display&key={KEY}")
        errors.info(f"closing /api/jobs/j/events?key={KEY}")
    finally:
        for logger in (access, errors):
            logger.removeHandler(handler)
    assert len(lines) == 3 and not any(KEY in line for line in lines)
    assert "key=<hidden>&v=2" in lines[0] and "role=display&key=<hidden>" in lines[1] and lines[2].endswith("key=<hidden>")
