"""Runs a job through its stages, one heavy stage at a time across the whole server.

Each stage is re-runnable: when it succeeds it writes `stages/<stage>.done` with the hash of its
inputs. A restarted job skips every stage whose marker still matches its inputs, so a crash loses
at most the stage that was running. `retry` deletes the markers from a stage onward.

Heavy work runs in subprocesses. A subprocess talks back on stdout:
    EVENT {"type": "thought", "text": "..."}     relayed into events.jsonl (PLAN §4.4 fields)
    RESULT {...}                                 the stage's result, kept in the marker
Anything else on stdout, and all of stderr, goes to `logs/<stage>.stdout|stderr`.
"""
from __future__ import annotations

import asyncio
import collections
import hashlib
import json
import os
import signal
import subprocess
import time
import traceback
from dataclasses import dataclass
from pathlib import Path
from typing import Awaitable, Callable

from server.events import TYPES, EventLog
from server.jobs import STAGES, Job, JobStore, sha256_files, write_atomic
from server.settings import ROOT, Settings

LINE_LIMIT = 8 * 1024 * 1024
RESUME_WINDOW_S = 6 * 3600


class StageError(Exception):
    """A stage failed in a way the user should read (shown in the feed and in the job status)."""


@dataclass(frozen=True)
class Stage:
    name: str
    run: Callable[["Ctx"], Awaitable[dict | None]] | None     # None: the approval pause
    inputs: Callable[[Job], list[Path]]
    progress: float                                           # job progress once this stage is done
    timeout_s: float = 300.0


class Ctx:
    """What a stage function gets: the job, an event emitter and a subprocess runner."""

    def __init__(self, orch: "Orchestrator", job: Job, log: EventLog, stage: Stage):
        self.orch, self.job, self.log, self.stage = orch, job, log, stage
        self.settings: Settings = orch.settings

    @property
    def golden(self) -> Path | None:
        """Folder of the hand-checked plan for this guide, when the guide is a known one."""
        return self.orch.goldens.get(self.job.status().get("guide_sha256", ""))

    def emit(self, type: str, text: str = "", **fields) -> dict:
        return self.log.emit(type, text, stage=self.stage.name, **fields)

    async def run(self, argv: list[str], timeout: float | None = None) -> dict:
        """Run a subprocess, relay its EVENT lines, return its RESULT. Killed on timeout or cancel."""
        timeout = timeout or self.stage.timeout_s
        env = {k: v for k, v in os.environ.items() if not _is_secret(k)}
        env.update(PYTHONUNBUFFERED="1", BLENDER_BIN=self.settings.blender)
        if self.settings.gemini_api_key:
            env["GEMINI_API_KEY"] = self.settings.gemini_api_key
        self.job.logs_dir.mkdir(parents=True, exist_ok=True)
        out_path = self.job.logs_dir / f"{self.stage.name}.stdout"
        err_path = self.job.logs_dir / f"{self.stage.name}.stderr"
        result: dict = {}
        tail: collections.deque[str] = collections.deque(maxlen=40)
        proc = await asyncio.create_subprocess_exec(
            *[str(a) for a in argv], cwd=ROOT, env=env, limit=LINE_LIMIT, start_new_session=True,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        pid_file = self.job.stages_dir / f"{self.stage.name}.pid"     # lets a restarted server find an orphan
        pid_file.parent.mkdir(parents=True, exist_ok=True)
        pid_file.write_text(str(proc.pid))

        async def pump(stream: asyncio.StreamReader, path: Path, is_stdout: bool) -> None:
            with open(path, "ab") as f:
                async for raw in stream:
                    line = raw.decode("utf-8", "replace").rstrip("\r\n")
                    if is_stdout and line.startswith("EVENT "):
                        self._relay(line[6:])
                    elif is_stdout and line.startswith("RESULT "):
                        try:
                            result.update(json.loads(line[7:]))
                        except json.JSONDecodeError:
                            tail.append(line[:300])
                    else:
                        tail.append(line[:300])
                    f.write(raw)

        tasks = [asyncio.ensure_future(t) for t in (pump(proc.stdout, out_path, True),
                                                    pump(proc.stderr, err_path, False), proc.wait())]
        try:
            await asyncio.wait_for(asyncio.gather(*tasks), timeout)
        except asyncio.TimeoutError:
            await _kill(proc)
            raise StageError(f"`{Path(str(argv[0])).name}` was stopped after {timeout:.0f} s") from None
        except BaseException:                         # cancelled, or a pump failed (disk full, an endless line)
            for t in tasks:
                t.cancel()
            await _kill(proc)
            raise
        finally:
            pid_file.unlink(missing_ok=True)
        if proc.returncode != 0:
            detail = "\n".join(tail)[-1500:] or "no output"
            raise StageError(f"exit code {proc.returncode}:\n{detail}")
        return result

    def _relay(self, raw: str) -> None:
        try:
            e = json.loads(raw)
            kind = e.pop("type", "progress")
            if kind not in TYPES or kind in ("done", "approval_required"):   # only the runner ends or pauses a job
                kind = "progress"
            for reserved in ("seq", "ts", "jobId", "stage"):
                e.pop(reserved, None)
            self.emit(kind, str(e.pop("text", "")), level=e.pop("level", "info"), **e)
        except (ValueError, TypeError, AttributeError):
            with open(self.job.logs_dir / f"{self.stage.name}.stdout", "ab") as f:
                f.write(f"[bad EVENT line] {raw[:300]}\n".encode())


SECRET_NAMES = {"DEMO_KEY", "FAL_KEY", "BOXD_API_KEY", "BOXD_TOKEN", "TREG_TOKEN"}


def _is_secret(name: str) -> bool:
    """Environment variables a stage process must not inherit. GEMINI_API_KEY is added back on purpose."""
    upper = name.upper()
    return upper in SECRET_NAMES or upper.endswith(("_KEY", "_TOKEN", "_SECRET", "_PASSWORD"))


def kill_leftovers(job: Job) -> None:
    """A stage process has its own session, so it outlives a crashed server. Stop it before the stage runs again."""
    for pid_file in job.stages_dir.glob("*.pid"):
        try:
            pid = int(pid_file.read_text())
            command = subprocess.run(["ps", "-o", "command=", "-p", str(pid)], capture_output=True, text=True,
                                     timeout=5).stdout
            if str(job.dir) in command:               # same pid and it names this job folder: it is ours
                os.killpg(pid, signal.SIGKILL)
        except (ValueError, OSError, subprocess.SubprocessError):
            pass
        pid_file.unlink(missing_ok=True)


async def _kill(proc: asyncio.subprocess.Process) -> None:
    if proc.returncode is None:
        try:
            os.killpg(proc.pid, signal.SIGKILL)       # the whole group: Blender, or an agent's children
        except (ProcessLookupError, PermissionError):
            proc.kill()
    try:
        await asyncio.wait_for(proc.wait(), 5)
    except asyncio.TimeoutError:
        pass


def find_goldens() -> dict[str, Path]:
    """sha256 of a known guide PDF → folder holding its hand-checked plan.json and part scripts."""
    found = {}
    for plan in (ROOT / "eval" / "golden").glob("*/plan.json"):
        try:
            guide = ROOT / json.loads(plan.read_text())["guide"]
            found[hashlib.sha256(guide.read_bytes()).hexdigest()] = plan.parent
        except (KeyError, OSError, json.JSONDecodeError):
            continue
    return found


class Orchestrator:
    def __init__(self, store: JobStore, settings: Settings, stages: list[Stage]):
        self.store, self.settings, self.stages = store, settings, stages
        assert tuple(s.name for s in stages) == STAGES
        self.goldens = find_goldens() if settings.allow_golden_plan else {}
        self.running_id: str | None = None
        self._heavy = asyncio.Lock()                  # one heavy stage at a time (PLAN §8.1)
        self._tasks: dict[str, asyncio.Task] = {}
        self._approved: dict[str, asyncio.Event] = {}

    # ---- control -----------------------------------------------------------------------------
    def active(self, job_id: str) -> bool:
        return job_id in self._tasks and not self._tasks[job_id].done()

    def start(self, job: Job) -> None:
        if not self.active(job.id):
            self._approved.setdefault(job.id, asyncio.Event())
            self._tasks[job.id] = asyncio.create_task(self._run(job), name=f"job-{job.id}")

    async def stop(self, job_id: str) -> None:
        task = self._tasks.pop(job_id, None)
        if task and not task.done():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    def approve(self, job: Job, plan: dict, note: str | None = None) -> None:
        write_atomic(job.approved_plan, json.dumps(plan, ensure_ascii=False, indent=1))
        self.store.log(job).emit("progress", "Plan approved" + (f": {note[:200]}" if note else ""),
                                 stage="await_approval", level="success")
        self._approved.setdefault(job.id, asyncio.Event()).set()
        self.start(job)                               # no-op while the job task is waiting on the event

    async def retry(self, job: Job, from_stage: str) -> None:
        idx = STAGES.index(from_stage)
        await self.stop(job.id)
        for name in STAGES[idx:]:
            job.marker(name).unlink(missing_ok=True)
        if idx <= STAGES.index("await_approval"):
            job.approved_plan.unlink(missing_ok=True)
            self._approved.setdefault(job.id, asyncio.Event()).clear()
        job.set_status(stage=from_stage, status="queued", error=None)
        self.store.log(job).emit("progress", f"Retrying from `{from_stage}`", stage=from_stage, level="warn")
        self.start(job)

    def resume_unfinished(self) -> list[str]:
        """After a restart, pick up jobs that were mid-flight (recent ones only)."""
        resumed = []
        for job in self.store.all():
            s = job.status()
            recent = time.time() - s.get("updated", 0) / 1000 < RESUME_WINDOW_S
            if s.get("status") in ("queued", "running", "waiting_approval") and recent:
                self.start(job)
                resumed.append(job.id)
        return resumed

    async def shutdown(self) -> None:
        for job_id in list(self._tasks):
            await self.stop(job_id)                   # statuses stay as they are, so the jobs resume

    # ---- the job loop ------------------------------------------------------------------------
    def _marker_ok(self, job: Job, stage: Stage) -> bool:
        try:
            done = json.loads(job.marker(stage.name).read_text())
        except (OSError, json.JSONDecodeError):
            return False
        return done.get("input_sha256") == sha256_files(stage.inputs(job), job.dir)

    async def _run(self, job: Job) -> None:
        log = self.store.log(job)
        kill_leftovers(job)
        spent = 0.0                                   # build time since approval; time in the queue does not count
        previous = 0.0
        stage = self.stages[0]
        try:
            for stage in self.stages:
                if self._marker_ok(job, stage):
                    previous = stage.progress
                    continue
                inputs_hash = sha256_files(stage.inputs(job), job.dir)
                t0 = time.monotonic()
                result = None
                if stage.run is None:
                    await self._await_approval(job, log, stage, previous)
                    spent = 0.0                       # the 40-minute budget counts build time only
                else:
                    job.set_status(stage=stage.name, status="queued" if self._heavy.locked() else "running",
                                   progress=previous, error=None)
                    async with self._heavy:
                        self.running_id = job.id
                        try:
                            job.set_status(status="running")
                            log.emit("progress", f"Stage `{stage.name}` started", stage=stage.name, progress=previous)
                            left = self.settings.job_wall_budget_s - spent
                            if left <= 0:
                                raise StageError("the job used up its 40-minute budget")
                            t0 = time.monotonic()     # from here: the stage is running, not queued
                            try:
                                result = await asyncio.wait_for(stage.run(Ctx(self, job, log, stage)),
                                                                min(stage.timeout_s, left))
                            except asyncio.TimeoutError:
                                raise StageError(f"stage `{stage.name}` ran out of time") from None
                            finally:
                                spent += time.monotonic() - t0
                        finally:
                            self.running_id = None
                ms = round((time.monotonic() - t0) * 1000)
                write_atomic(job.marker(stage.name), json.dumps(
                    {"input_sha256": inputs_hash, "finished_at": int(time.time() * 1000), "ms": ms, "result": result},
                    ensure_ascii=False))
                previous = stage.progress
                if stage.run is not None:
                    log.emit("progress", f"Stage `{stage.name}` finished", stage=stage.name, level="success",
                             ms=ms, progress=previous)
            job.set_status(stage="done", status="done", progress=1.0, error=None)
            log.emit("done", "Walkthrough ready", stage="done", level="success", progress=1.0,
                     glb_url=job.url("walkthrough.glb"), manifest_url=job.url("manifest.json"))
        except asyncio.CancelledError:
            raise
        except Exception as e:                        # noqa: BLE001 - every failure must reach the feed
            text = str(e) if isinstance(e, StageError) else f"{type(e).__name__}: {e}"
            if not isinstance(e, StageError):
                job.logs_dir.mkdir(parents=True, exist_ok=True)
                (job.logs_dir / f"{stage.name}.traceback").write_text(traceback.format_exc())
            job.set_status(stage=stage.name, status="error", error=text[:2000])
            log.emit("error", text[:2000], stage=stage.name, level="error")

    async def _await_approval(self, job: Job, log: EventLog, stage: Stage, progress: float) -> None:
        flag = self._approved.setdefault(job.id, asyncio.Event())
        if job.approved_plan.is_file():
            return
        flag.clear()
        job.set_status(stage=stage.name, status="waiting_approval", progress=progress, error=None)
        last = log.read(after=log.last_seq - 1)
        if not (last and last[-1]["type"] == "approval_required"):     # after a restart, do not ask twice
            log.emit("approval_required", "The plan is ready. Check it against the guide, then approve.",
                     stage=stage.name, progress=progress, plan_url=f"/api/jobs/{job.id}/plan")
        await flag.wait()
