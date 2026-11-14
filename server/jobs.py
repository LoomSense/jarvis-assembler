"""Job folders on disk (PLAN §4.1). The folder is the single source of truth; nothing lives only in memory."""
from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import time
from pathlib import Path

from server.events import EventLog

# Stage ids, in order. They are the `stage` value in events and in GET /api/jobs/{id}.
STAGES = ("ingest", "extract_plan", "await_approval", "parts", "place", "steps", "refs", "export")
STATUSES = ("queued", "running", "waiting_approval", "done", "error")
ID_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{2,39}$")
SUBDIRS = ("pages", "crops", "parts", "renders", "refs", "stages", "logs")


def write_atomic(path: Path, data: bytes | str) -> None:
    """Write to a temp file, fsync, then rename: readers never see a half-written file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.{secrets.token_hex(4)}.tmp")
    with open(tmp, "wb") as f:
        f.write(data.encode("utf-8") if isinstance(data, str) else data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def sha256_files(paths: list[Path], base: Path) -> str:
    h = hashlib.sha256()
    for p in sorted(paths):
        h.update(str(p.relative_to(base)).encode())
        h.update(b"\0")
        h.update(p.read_bytes() if p.is_file() else b"<missing>")
        h.update(b"\0")
    return h.hexdigest()


class Job:
    def __init__(self, root: Path, job_id: str):
        self.id = job_id
        self.dir = root / job_id
        self.guide = self.dir / "guide.pdf"
        self.plan = self.dir / "plan.json"
        self.approved_plan = self.dir / "plan.approved.json"
        self.manifest = self.dir / "manifest.json"
        self.glb = self.dir / "walkthrough.glb"
        self.events_file = self.dir / "events.jsonl"
        self.status_file = self.dir / "status.json"
        self.stages_dir = self.dir / "stages"
        self.logs_dir = self.dir / "logs"

    def status(self) -> dict:
        try:
            return json.loads(self.status_file.read_text())
        except (FileNotFoundError, json.JSONDecodeError):
            return {"job_id": self.id, "stage": STAGES[0], "status": "queued", "progress": 0.0, "error": None}

    def set_status(self, **changes) -> dict:
        s = self.status()
        s.update(changes)
        s["updated"] = int(time.time() * 1000)
        write_atomic(self.status_file, json.dumps(s, ensure_ascii=False, indent=1))
        return s

    def marker(self, stage: str) -> Path:
        return self.stages_dir / f"{stage}.done"

    def resolve(self, rel: str) -> Path | None:
        """A path inside the job folder, or None if `rel` tries to leave it or names a hidden file."""
        if not rel or any(part.startswith(".") for part in Path(rel).parts):
            return None
        base = self.dir.resolve()
        target = (base / rel).resolve()
        return target if target.is_relative_to(base) and target.is_file() else None

    def url(self, rel: str) -> str:
        return f"/files/{self.id}/{rel}"


class JobStore:
    def __init__(self, root: Path):
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)
        self._logs: dict[str, EventLog] = {}

    def create(self, pdf: bytes, filename: str = "guide.pdf") -> Job:
        job = Job(self.root, time.strftime("%m%d-%H%M-") + secrets.token_hex(3))
        for d in SUBDIRS:
            (job.dir / d).mkdir(parents=True, exist_ok=True)
        write_atomic(job.guide, pdf)
        job.set_status(job_id=job.id, stage=STAGES[0], status="queued", progress=0.0, error=None,
                       filename=filename[:120], guide_sha256=hashlib.sha256(pdf).hexdigest(),
                       created=int(time.time() * 1000))
        return job

    def get(self, job_id: str) -> Job | None:
        if not ID_RE.match(job_id):
            return None
        job = Job(self.root, job_id)
        return job if job.guide.is_file() else None

    def all(self) -> list[Job]:
        jobs = [self.get(p.name) for p in self.root.iterdir() if p.is_dir()]
        return sorted((j for j in jobs if j), key=lambda j: j.status().get("created", 0), reverse=True)

    def log(self, job: Job) -> EventLog:
        if job.id not in self._logs:
            self._logs[job.id] = EventLog(job.events_file, job.id)
        return self._logs[job.id]

    def close(self) -> None:
        for log in self._logs.values():
            log.close()
