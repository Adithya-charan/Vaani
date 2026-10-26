"""Executes validated action plans. The only place that touches Windows."""

from __future__ import annotations

import logging
import os
import platform
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable
from urllib.parse import quote_plus

from app.models.command import (
    AskChatGPT, ChatGPTPrompt, FindFile, GetStatus, OpenCalculator, OpenChatGPT, OpenExplorer, OpenFile,
    OpenNotepad, TelegramSend, WebSearch,
)
from app.services.chatgpt import ChatGPTDriver, ChatGPTError
from app.services.telegram import MAX_UPLOAD_BYTES, TelegramError, TelegramSender
from app.services.zipper import TEMP_ROOT, ZipError, make_zip
from app.services.file_search import FileSearcher, FolderNotApproved, ItemMissing, ItemNotAllowed, modified_iso

AGENT_VERSION = "0.3.0"
log = logging.getLogger("vaani.executor")

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
    chatgpt: ChatGPTDriver | None = None
    telegram: TelegramSender | None = None
    zip_temp_root: Path = TEMP_ROOT
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
        if isinstance(plan, AskChatGPT):
            # A proposal only: nothing is sent until the phone confirms this exact prompt.
            return Outcome("needs_confirmation", "Send this prompt to ChatGPT on your laptop?",
                           {"confirm": {"action": "chatgpt_prompt", "prompt": plan.prompt}})
        if isinstance(plan, ChatGPTPrompt):
            return self._chatgpt_prompt(plan.prompt)
        if isinstance(plan, TelegramSend):
            return self._telegram_send(plan)
        return Outcome("rejected", "This action is not supported.")

    def _telegram_send(self, plan: TelegramSend) -> Outcome:
        if self.telegram is None:
            return Outcome("error", "Telegram isn't set up on the laptop (TELEGRAM_BOT_TOKEN is missing).")
        if plan.recipient not in self.telegram.recipients:
            return Outcome("rejected", f"'{plan.recipient}' is not one of your Telegram recipients.")
        name = plan.path.rsplit("/", 1)[-1]
        try:
            target = self.searcher.resolve_item(plan.path)
        except FolderNotApproved:
            return Outcome("rejected", "That location is not an approved folder.")
        except ItemNotAllowed:
            return Outcome("rejected", "Vaani can only send items inside your approved folders.")
        except ItemMissing:
            return Outcome("not_found", f"{name} is no longer there. Search again.")

        archive = None
        try:
            if target.is_dir():
                if not plan.as_zip:
                    return Outcome("rejected", "Folders are sent as a ZIP. Confirm sending it as a ZIP.")
                try:
                    archive = make_zip(target, self.zip_temp_root, max_zip_bytes=MAX_UPLOAD_BYTES)
                except ZipError as exc:
                    return Outcome("error" if exc.kind in ("archive_failed", "no_space") else "rejected", exc.message)
                to_send, sent_name, size = archive.path, archive.path.name, archive.size
            else:
                if plan.as_zip:
                    return Outcome("rejected", "Only folders are sent as a ZIP.")
                stat = target.stat()
                if (plan.expected_size is not None and stat.st_size != plan.expected_size) or (
                    plan.expected_modified is not None and modified_iso(stat.st_mtime) != plan.expected_modified
                ):
                    return Outcome("rejected", f"{name} has changed since you searched. Search again to send it.")
                to_send, sent_name, size = target, target.name, stat.st_size
            try:
                message_id = self.telegram.send_document(plan.recipient, to_send, sent_name)
            except TelegramError as exc:
                return Outcome("rejected" if exc.kind in ("too_large", "unknown_recipient") else "error",
                               exc.message, {"reason": exc.kind})
            data = {"recipient": plan.recipient, "name": sent_name, "size": size, "message_id": message_id,
                    "zip": archive is not None}
            if archive is not None:
                data["files_in_zip"] = archive.files
                data["skipped"] = len(archive.skipped)
            skipped = f" ({len(archive.skipped)} item(s) skipped)" if archive and archive.skipped else ""
            return Outcome("success", f"Sent {sent_name} to {plan.recipient} on Telegram{skipped}.", data)
        finally:
            if archive is not None:
                problem = archive.cleanup()
                if problem:
                    log.warning("could not delete temporary ZIP (%s)", problem)

    def _chatgpt_prompt(self, prompt: str) -> Outcome:
        if self.chatgpt is None:
            return Outcome("error", "ChatGPT automation isn't installed on the laptop (Playwright is missing).")
        try:
            self.chatgpt.submit(prompt)
        except ChatGPTError as exc:
            status = "rejected" if exc.kind == "busy" else "error"
            return Outcome(status, exc.message, {"reason": exc.kind})
        return Outcome("success", "Prompt sent to ChatGPT. The answer is on your laptop screen.", {"verified": True})

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
                "chatgpt_automation": self.chatgpt is not None,
                "telegram_recipients": sorted(self.telegram.recipients) if self.telegram else [],
                "approved_folders": list(self.searcher.roots),
            },
        )
