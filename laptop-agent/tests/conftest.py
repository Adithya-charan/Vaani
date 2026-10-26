from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.services.ai_planner import PlannerError

TOKEN = "t" * 40
AUTH = {"Authorization": f"Bearer {TOKEN}"}


class RecordingLauncher:
    def __init__(self, fail: bool = False) -> None:
        self.calls: list[list[str]] = []
        self.fail = fail

    def __call__(self, argv: list[str]) -> None:
        self.calls.append(argv)
        if self.fail:
            raise OSError("boom")


class FakePlanner:
    name = "fake"

    def __init__(self, result: dict[str, Any] | Exception) -> None:
        self.result = result
        self.seen: list[str] = []

    async def plan(self, text: str) -> dict[str, Any]:
        self.seen.append(text)
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


@pytest.fixture
def roots(tmp_path: Path) -> dict[str, Path]:
    docs = tmp_path / "Documents"
    (docs / "Projects" / "JavaProject").mkdir(parents=True)
    (docs / "Projects" / "JavaProject" / "Main.java").write_text("class Main {}")
    (docs / "Project Report.docx").write_text("x")
    downloads = tmp_path / "Downloads"
    downloads.mkdir()
    (downloads / "invoice.pdf").write_text("x")
    (downloads / "notes.txt").write_text("x")
    (tmp_path / "secret.txt").write_text("outside")
    return {"Documents": docs, "Downloads": downloads}


@pytest.fixture
def launcher() -> RecordingLauncher:
    return RecordingLauncher()


@pytest.fixture
def opened() -> list[str]:
    """URLs and file paths handed to Windows' default handler (recorded, never opened)."""
    return []


@pytest.fixture
def make_client(roots, launcher, opened):
    def _make(planner=None, launcher_override=None, client_ip="192.168.0.5", voice=None,
              shell_open=None) -> TestClient:
        settings = Settings(auth_token=TOKEN, approved_folders=roots)
        app = create_app(settings, launcher=launcher_override or launcher, planner=planner,
                         use_configured_planner=False, voice=voice, use_configured_voice=False,
                         shell_open=shell_open or opened.append)
        return TestClient(app, client=(client_ip, 50000))

    return _make


@pytest.fixture
def client(make_client) -> TestClient:
    return make_client()


__all__ = ["AUTH", "FakePlanner", "PlannerError", "RecordingLauncher", "TOKEN"]
