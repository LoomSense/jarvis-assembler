"""The FastAPI app: REST, SSE, WebSocket and the static client on one HTTPS origin (PLAN D4).

    uvicorn server.app:create_app --factory --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import asyncio
import importlib
import logging
import os
import sys
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from server.auth import KeyGate, has_key, hide_key_in_logs
from server.jobs import JobStore
from server.orchestrator import Orchestrator
from server.orchestrator.stages import STAGES
from server.routes import ask as ask_routes
from server.routes import catalog as catalog_routes
from server.routes import diag as diag_routes
from server.routes import jobs as job_routes
from server.routes import live as live_routes
from server.routes import tts as tts_routes
from server.settings import ROOT, Settings

log = logging.getLogger("agj")


class ClientFiles(StaticFiles):
    """Serves client/dist. HTML is never cached, so a phone always loads the latest build."""

    async def get_response(self, path: str, scope):
        response = await super().get_response(path, scope)
        if path in ("", ".") or path.endswith(".html"):
            response.headers["Cache-Control"] = "no-cache"
        return response


PROBE_TIMEOUT_S = 180     # the first CadQuery import after an install compiles bytecode and can take a minute
PROBE_RETRY_S = 20


async def _first_line(*argv: str, timeout: float = PROBE_TIMEOUT_S) -> str | None:
    proc = None
    try:
        proc = await asyncio.create_subprocess_exec(*argv, cwd=ROOT, stdout=asyncio.subprocess.PIPE,
                                                    stderr=asyncio.subprocess.DEVNULL)
        out, _ = await asyncio.wait_for(proc.communicate(), timeout)
        lines = out.decode("utf-8", "replace").strip().splitlines()
        return lines[0].strip() if proc.returncode == 0 and lines else None
    except (OSError, asyncio.TimeoutError):
        return None
    finally:
        if proc and proc.returncode is None:
            proc.kill()


async def _probe_tools(app: FastAPI) -> None:
    """Find out, in the background, whether Blender and CadQuery work on this host."""
    blender, cadquery = await asyncio.gather(
        _first_line(app.state.settings.blender, "-b", "--version"),
        _first_line(sys.executable, "-c", "import cadquery; print(cadquery.__version__)"))
    version = blender.split()[1] if blender and blender.startswith("Blender ") and len(blender.split()) > 1 else None
    app.state.health = {"blender_version": version, "cadquery_ok": cadquery is not None}
    app.state.probed_at = time.monotonic()
    log.info("tools: blender=%s cadquery=%s", blender, cadquery)


def _ensure_probe(app: FastAPI) -> None:
    """Start a probe unless one is running. Called at startup, and again later while a tool looks broken."""
    task = getattr(app.state, "probe", None)
    if task is None or task.done():
        app.state.probe = asyncio.create_task(_probe_tools(app))


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()
    if not settings.demo_key:
        raise RuntimeError("DEMO_KEY is not set. Run `make env`, or add DEMO_KEY to .env (see .env.example).")
    hide_key_in_logs()                                 # `?key=` must not end up in the request log
    if settings.agj_build_agent:                       # the agent hooks read the environment (orchestrator/stages.py)
        os.environ.setdefault("AGJ_BUILD_AGENT", settings.agj_build_agent)
    store = JobStore(settings.jobs_dir)
    orch = Orchestrator(store, settings, STAGES)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        _ensure_probe(app)
        resumed = orch.resume_unfinished()
        if resumed:
            log.info("resumed jobs: %s", ", ".join(resumed))
        yield
        app.state.probe.cancel()
        store.close()                                  # ends the open SSE streams
        await orch.shutdown()

    app = FastAPI(title="Assembly Guide Agent", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.settings, app.state.store, app.state.orch = settings, store, orch
    app.state.health = {"blender_version": None, "cadquery_ok": None}   # None: not probed yet
    app.state.probed_at = 0.0

    app.add_middleware(KeyGate, demo_key=settings.demo_key,
                       max_body_bytes=(settings.max_upload_mb + 1) * 1024 * 1024)
    if settings.cors:                                  # added last, so it wraps the gate and its 401s get CORS headers
        app.add_middleware(CORSMiddleware, allow_origins=settings.cors, allow_methods=["*"],
                           allow_headers=["X-Demo-Key", "Content-Type", "Last-Event-ID"])

    @app.get("/healthz")
    async def healthz(request: Request) -> dict:
        """Without the key: only `{ok}`. With it: the tool and job state (PLAN §4.5)."""
        if not has_key(request):
            return {"ok": True}
        health = app.state.health
        broken = not health["blender_version"] or not health["cadquery_ok"]
        if broken and time.monotonic() - app.state.probed_at > PROBE_RETRY_S:
            _ensure_probe(app)                         # e.g. Blender was installed after the server started
        return {"ok": True, **health, "job_running": orch.running_id is not None,
                "running_job": orch.running_id, "gemini_key": bool(settings.gemini_api_key),
                "client_built": (settings.client_dist / "index.html").is_file()}

    app.include_router(job_routes.router)
    app.include_router(catalog_routes.router)
    app.include_router(ask_routes.router)               # questions during assembly (pipeline/ask.py)
    app.include_router(live_routes.router)
    app.include_router(tts_routes.router)
    app.include_router(diag_routes.router)             # device check reports (the headset page posts here)
    try:                                               # phase-2 perception WebSocket (LIV-01, person E)
        app.include_router(importlib.import_module("server.perception.routes").router)
    except ModuleNotFoundError as e:
        if e.name not in ("server.perception", "server.perception.routes"):
            raise

    if (settings.client_dist / "index.html").is_file():
        app.mount("/", ClientFiles(directory=settings.client_dist, html=True), name="client")
    else:
        @app.get("/")
        async def no_client() -> dict:
            return {"service": "assembly-guide-agent", "client": "not built yet (run `make client`)"}

    return app
