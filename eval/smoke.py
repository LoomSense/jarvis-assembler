"""End-to-end smoke test against a running server. Standard library only. See smoke.sh.

Checks, in order: health, upload, the SSE feed (and that a reconnect replays without loss),
the plan, approval, the build, and that the GLB has the `walkthrough` clip plus one clip per step.
"""
from __future__ import annotations

import json
import os
import struct
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
GOLDEN = REPO / "eval/guides/ikea/knagglig-box__AA-1159337-7.pdf"
BUILD_TIMEOUT_S = int(os.environ.get("SMOKE_TIMEOUT_S", 40 * 60))
PLAN_TIMEOUT_S = int(os.environ.get("SMOKE_PLAN_TIMEOUT_S", 15 * 60))


def env_key() -> str:
    env = REPO / ".env"
    for line in env.read_text().splitlines() if env.exists() else []:
        if line.startswith("DEMO_KEY="):
            return line.split("=", 1)[1].strip()
    return ""


class Api:
    def __init__(self, base: str, key: str):
        self.base, self.key = base.rstrip("/"), key

    def open(self, path: str, data: bytes | None = None, headers: dict | None = None, timeout: float = 60):
        req = urllib.request.Request(self.base + path, data=data,
                                     headers={"X-Demo-Key": self.key, **(headers or {})})
        return urllib.request.urlopen(req, timeout=timeout)

    def json(self, path: str, body: dict | None = None):
        data = json.dumps(body).encode() if body is not None else None
        with self.open(path, data, {"Content-Type": "application/json"} if data else None) as r:
            return json.loads(r.read())

    def upload(self, pdf: Path) -> dict:
        boundary = uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{pdf.name}"\r\n'
                "Content-Type: application/pdf\r\n\r\n").encode() + pdf.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        with self.open("/api/jobs", body, {"Content-Type": f"multipart/form-data; boundary={boundary}"}) as r:
            return json.loads(r.read())

    def events(self, job_id: str, last_event_id: int | None, until, timeout: float) -> list[dict]:
        """Read the SSE stream until `until(event)` is true. Returns the events read."""
        headers = {"Accept": "text/event-stream"}
        if last_event_id is not None:
            headers["Last-Event-ID"] = str(last_event_id)
        seen, deadline = [], time.time() + timeout
        with self.open(f"/api/jobs/{job_id}/events", headers=headers, timeout=30) as r:
            for raw in r:
                line = raw.decode().rstrip("\n")
                if line.startswith("data: "):
                    event = json.loads(line[6:])
                    seen.append(event)
                    if until(event):
                        return seen
                if time.time() > deadline:
                    break
        raise SystemExit(f"FAIL: the event stream did not reach the expected event in {timeout:.0f} s")


def glb_json(data: bytes) -> dict:
    magic, _, _ = struct.unpack_from("<4sII", data, 0)
    length, kind = struct.unpack_from("<I4s", data, 12)
    if magic != b"glTF" or kind != b"JSON":
        raise SystemExit("FAIL: walkthrough.glb is not a valid GLB")
    return json.loads(data[20:20 + length])


def step(msg: str) -> None:
    print(f"  ok  {msg}", flush=True)


def main(argv: list[str]) -> int:
    base = argv[1] if len(argv) > 1 else "http://localhost:8000"
    key = argv[2] if len(argv) > 2 else env_key()
    pdf = Path(argv[3]) if len(argv) > 3 else GOLDEN
    if not key:
        raise SystemExit("FAIL: no demo key (pass it as the second argument or set DEMO_KEY in .env)")
    api, t0 = Api(base, key), time.time()
    print(f"smoke: {base}  guide: {pdf.name}")

    health = api.json("/healthz")
    if not health.get("ok") or "blender_version" not in health:
        raise SystemExit(f"FAIL: /healthz rejected the key or is not healthy: {health}")
    waited = 0
    while health.get("cadquery_ok") is None and waited < 200:   # the server probes its tools right after it starts
        time.sleep(4)
        waited += 4
        health = api.json("/healthz")
    if not health.get("cadquery_ok") or not health.get("blender_version"):
        raise SystemExit(f"FAIL: the host cannot build: {health}")
    step(f"healthz: Blender {health['blender_version']}, CadQuery ok")

    try:
        urllib.request.urlopen(urllib.request.Request(base.rstrip("/") + "/api/jobs"), timeout=20)
        raise SystemExit("FAIL: /api/jobs answered without the demo key")
    except urllib.error.HTTPError as e:
        if e.code != 401:
            raise SystemExit(f"FAIL: expected 401 without the key, got {e.code}") from None
    step("routes refuse requests without the demo key")

    job_id = api.upload(pdf)["job_id"]
    step(f"uploaded: job {job_id}")

    first = api.events(job_id, None, lambda e: e["type"] in ("approval_required", "error"), PLAN_TIMEOUT_S)
    if first[-1]["type"] == "error":
        raise SystemExit(f"FAIL: before approval: {first[-1]['text']}")
    seqs = [e["seq"] for e in first]
    if seqs != list(range(1, len(seqs) + 1)):
        raise SystemExit(f"FAIL: event seq numbers are not 1..n: {seqs}")
    step(f"event feed reached approval_required ({len(first)} events, {time.time() - t0:.1f} s)")

    plan = api.json(f"/api/jobs/{job_id}/plan")
    n_steps = len(plan["plan"]["steps"])
    step(f"plan: {len(plan['plan']['parts'])} part types, {n_steps} steps, {len(plan['pages'])} page images")

    if api.json(f"/api/jobs/{job_id}/plan/approve", {})["stage"] != "building":
        raise SystemExit("FAIL: approve did not return stage=building")
    resume_from = seqs[len(seqs) // 2]                  # reconnect mid-history, as a phone would after a drop
    rest = api.events(job_id, resume_from, lambda e: e["type"] in ("done", "error"), BUILD_TIMEOUT_S)
    if rest[0]["seq"] != resume_from + 1:
        raise SystemExit(f"FAIL: reconnect with Last-Event-ID {resume_from} resumed at seq {rest[0]['seq']}")
    if [e["seq"] for e in rest] != list(range(resume_from + 1, resume_from + 1 + len(rest))):
        raise SystemExit("FAIL: the replayed stream skipped or repeated events")
    if rest[-1]["type"] == "error":
        raise SystemExit(f"FAIL: the build stopped at `{rest[-1]['stage']}`: {rest[-1]['text']}")
    step(f"reconnect replayed from seq {resume_from + 1} without loss; build finished")

    status = api.json(f"/api/jobs/{job_id}")
    if status["status"] != "done" or status["progress"] != 1.0:
        raise SystemExit(f"FAIL: status after done: {status}")
    with api.open(f"/files/{job_id}/manifest.json") as r:
        manifest = json.loads(r.read())
    with api.open(f"/files/{job_id}/walkthrough.glb") as r:
        glb = r.read()
    clips = [a.get("name") for a in glb_json(glb).get("animations", [])]
    wanted = [s["clip"] for s in manifest["steps"] if s.get("clip")]
    missing = [c for c in wanted if c not in clips]
    if clips[:1] != ["walkthrough"] or missing or len(manifest["steps"]) != n_steps:
        raise SystemExit(f"FAIL: GLB clips {clips} do not cover the manifest steps (missing {missing})")
    if manifest.get("jobId") != job_id:
        raise SystemExit("FAIL: manifest.json has no jobId")
    step(f"GLB: {len(glb) / 1e6:.2f} MB, `walkthrough` + {len(wanted)} step clips for {n_steps} steps")
    print(f"PASS in {time.time() - t0:.1f} s  →  {base}/?job={job_id}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
