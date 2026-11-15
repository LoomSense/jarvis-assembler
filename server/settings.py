"""Server settings, read from the environment and from `.env` at the repo root (never committed)."""
from __future__ import annotations

import shutil
from pathlib import Path

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

ROOT = Path(__file__).resolve().parents[1]
MAC_BLENDER = "/Applications/Blender.app/Contents/MacOS/Blender"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=ROOT / ".env", env_file_encoding="utf-8", extra="ignore")

    gemini_api_key: str = ""
    fal_key: str = ""
    demo_key: str = ""                      # required on every /api, /files and /ws route
    jobs_dir: Path = ROOT / "jobs"
    client_dist: Path = ROOT / "client" / "dist"
    blender_bin: str = ""                   # empty: look on PATH, then in /Applications
    cors_origins: str = ""                  # comma-separated origins for the Vercel mirror (PLAN D4)
    allow_golden_plan: bool = True          # known guides may use their hand-checked plan until AGT-02 lands
    max_upload_mb: int = 40
    job_wall_budget_s: int = 40 * 60        # PLAN §5.6
    agj_build_agent: str = ""               # "claude": new guides are read by the build agent (pipeline/agent/extract.py)
    live_model: str = "gemini-3.8-live"
    live_token_min_interval_s: float = 10.0
    perception_simulate: bool = False       # phase 2 answers come from a debug script, not from Decision 1
    perception_logs: bool = True            # write jobs/<id>/perception/<session>/{frames,events,hands}.jsonl
    perception_tuning: str = ""             # JSON overrides for server/perception/config.py, e.g. {"max_hz": 2}

    @field_validator("jobs_dir", "client_dist")
    @classmethod
    def _under_root(cls, v: Path) -> Path:
        return v if v.is_absolute() else ROOT / v

    @property
    def blender(self) -> str:
        return self.blender_bin or shutil.which("blender") or MAC_BLENDER

    @property
    def cors(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]
