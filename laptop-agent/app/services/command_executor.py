"""Executes validated action plans. The only place that touches Windows."""

from __future__ import annotations

import os
import platform
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable
from urllib.parse import quote_plus

from app.models.command import (
    FindFile, GetStatus, OpenCalculator, OpenChatGPT, OpenExplorer, OpenFile, OpenNotepad, WebSearch,
)
from app.services.file_search import FileSearcher, FolderNotApproved, ItemMissing, ItemNotAllowed, modified_iso

AGENT_VERSION = "0.2.0"

Launcher = Callable[[list[str]], None]
ShellOpener = Callable[[str], None]

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

GOOGLE_SEARCH_URL = "https://www.google.com/search?q="
CHATGPT_URL = "https://chatgpt.com/"

# Only documents and media are opened. Programs, scripts, shortcuts and code files are
# excluded: on Windows "opening" them can run code (e.g. .py, .js, .bat, .lnk, .url).
OPENABLE_EXTENSIONS = {
    "pdf", "txt", "md", "rtf", "csv", "doc", "docx", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp",
    "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "tif", "tiff",
    "mp3", "wav", "m4a", "aac", "flac", "ogg", "mp4", "mkv", "mov", "avi", "webm",
    "zip", "rar", "7z",
}


def default_launcher(argv: list[str]) -> None:
    subprocess.Popen(argv, shell=False, close_fds=True, stdin=subprocess.DEVNULL,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def default_shell_open(target: str) -> None:
    """Hands a URL or file path to Windows' default handler (ShellExecute "open").
    The target is a single argument, never parsed by a command shell."""
    os.startfile(target)  # type: ignore[attr-defined]  # Windows only


@dataclass
class Outcome:
    status: str
    message: str
    data: dict[str, Any] | None = None


@dataclass
class CommandExecutor:
    searcher: FileSearcher
    launcher: Launcher = default_launcher
    shell_open: ShellOpener = default_shell_open
    ai_enabled: bool = False
    voice_enabled: bool = False
    started_at: float = field(default_factory=time.time)

    def execute(self, plan: Any) -> Outcome:
        # Dispatch on validated plan types only; anything unrecognised is refused, never guessed at.
        if isinstance(plan, (OpenCalculator, OpenNotepad, OpenExplorer)):
            return self._launch(plan.action)
        if isinstance(plan, FindFile):
            return self._find(plan)
        if isinstance(plan, GetStatus):
            return self._status()
        if isinstance(plan, WebSearch):
            return self._open_url(GOOGLE_SEARCH_URL + quote_plus(plan.query),
                                  f"Opening a Google search for \u201c{plan.query}\u201d in your browser.")
        if isinstance(plan, OpenChatGPT):
            return self._open_url(CHATGPT_URL, "Opening ChatGPT in your browser.")
        if isinstance(plan, OpenFile):
            return self._open_file(plan)
        return Outcome("rejected", "This action is not supported.")

    def _launch(self, action: str) -> Outcome:
        try:
            self.launcher(list(PROGRAMS[action]))
        except OSError:
            return Outcome("error", f"Windows could not start the program for {action}.")
        return Outcome("success", _LAUNCH_MESSAGES[action])

    def _open_url(self, url: str, message: str) -> Outcome:
        try:
            self.shell_open(url)
        except OSError:
            return Outcome("error", "Windows could not open your web browser.")
        # Windows accepted the request; Vaani cannot see whether the page finished loading.
        return Outcome("success", message, {"url": url, "page_load_verified": False})

    def _open_file(self, plan: OpenFile) -> Outcome:
        name = plan.path.rsplit("/", 1)[-1]
        try:
            target = self.searcher.resolve_item(plan.path)
        except FolderNotApproved:
            return Outcome("rejected", "That location is not an approved folder.")
        except ItemNotAllowed:
            return Outcome("rejected", "Vaani can only open items inside your approved folders.")
        except ItemMissing:
            return Outcome("not_found", f"{name} is no longer there. Search again.")

        is_dir = target.is_dir()
        if not is_dir:
            if target.suffix.lower().lstrip(".") not in OPENABLE_EXTENSIONS:
                kind = target.suffix or "this kind of"
                return Outcome("rejected", f"For safety, Vaani doesn't open {kind} files.")
            stat = target.stat()
            if (plan.expected_size is not None and stat.st_size != plan.expected_size) or (
                plan.expected_modified is not None and modified_iso(stat.st_mtime) != plan.expected_modified
            ):
                return Outcome("rejected", f"{name} has changed since you searched. Search again to open it.")
        try:
            self.shell_open(str(target))
        except OSError:
            return Outcome("error", f"Windows could not open {name}.")
        what = "folder" if is_dir else "file"
        return Outcome("success", f"Opening {name} on your laptop.", {"path": plan.path, "type": what})

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
