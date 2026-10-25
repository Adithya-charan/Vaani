"""Executes validated action plans. The only place that touches Windows."""

from __future__ import annotations

import os
import platform
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from app.models.command import FindFile, OpenCalculator, OpenExplorer, OpenNotepad, GetStatus
from app.services.file_search import FileSearcher, FolderNotApproved

AGENT_VERSION = "0.1.0"

Launcher = Callable[[list[str]], None]

_WINDIR = Path(os.environ.get("SystemRoot", r"C:\Windows"))
# Fixed, absolute executables only. No user or model text ever reaches argv.
PROGRAMS = {
    "open_calculator": [str(_WINDIR / "System32" / "calc.exe")],
    "open_notepad": [str(_WINDIR / "System32" / "notepad.exe")],
    "open_explorer": [str(_WINDIR / "explorer.exe")],
}
_LAUNCH_MESSAGES = {
    "open_calculator": "Calculator opened.",
    "open_notepad": "Notepad opened.",
    "open_explorer": "File Explorer opened.",
}


def default_launcher(argv: list[str]) -> None:
    subprocess.Popen(argv, shell=False, close_fds=True, stdin=subprocess.DEVNULL,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


@dataclass
class Outcome:
    status: str
    message: str
    data: dict[str, Any] | None = None


@dataclass
class CommandExecutor:
    searcher: FileSearcher
    launcher: Launcher = default_launcher
    ai_enabled: bool = False
    voice_enabled: bool = False
    started_at: float = field(default_factory=time.time)

    def execute(self, plan: Any) -> Outcome:
        if isinstance(plan, (OpenCalculator, OpenNotepad, OpenExplorer)):
            return self._launch(plan.action)
        if isinstance(plan, FindFile):
            return self._find(plan)
        if isinstance(plan, GetStatus):
            return self._status()
        return Outcome("rejected", "This action is not supported.")

    def _launch(self, action: str) -> Outcome:
        try:
            self.launcher(list(PROGRAMS[action]))
        except OSError:
            return Outcome("error", f"Windows could not start the program for {action}.")
        return Outcome("success", _LAUNCH_MESSAGES[action])

    def _find(self, plan: FindFile) -> Outcome:
        try:
            found = self.searcher.search(plan.query, plan.folder, plan.extension, plan.newest_first)
        except FolderNotApproved:
            allowed = ", ".join(self.searcher.roots) or "none"
            return Outcome("rejected", f"'{plan.folder}' is not an approved folder. Approved: {allowed}.")
        count = len(found["results"])
        what = plan.query or f".{plan.extension} files"
        if count == 0:
            return Outcome("not_found", f"No matches for '{what}'.", found)
        top = found["results"][0]["path"]
        more = f" and {found['total_matches'] - 1} more" if found["total_matches"] > 1 else ""
        return Outcome("success", f"Found {top}{more}.", found)

    def _status(self) -> Outcome:
        return Outcome(
            "success",
            "Laptop agent is online.",
            {
                "agent_version": AGENT_VERSION,
                "hostname": platform.node(),
                "uptime_seconds": int(time.time() - self.started_at),
                "ai_planner": self.ai_enabled,
                "voice": self.voice_enabled,
                "approved_folders": list(self.searcher.roots),
            },
        )
