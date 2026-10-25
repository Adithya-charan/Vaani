"""Rule-based command parser. Works with no AI provider configured.

Returns an unvalidated plan dict (or ``None`` if nothing matched); the caller
validates it against the plan schema.
"""

from __future__ import annotations

import re
from typing import Any

from app.models.command import ALLOWED_ACTIONS

_FILLER = re.compile(r"^(?:(?:hey|ok|okay)\s+)?(?:vaani[\s,]+)?(?:please\s+|can you\s+|could you\s+)*")
_OPEN = r"(?:open|launch|start|run|show)\s+(?:the\s+|my\s+)?"

_SIMPLE_RULES: list[tuple[re.Pattern[str], str]] = [
    (re.compile(rf"^{_OPEN}(?:calculator|calc)(?:\s+app)?$"), "open_calculator"),
    (re.compile(rf"^{_OPEN}(?:notepad|note pad)(?:\s+app)?$"), "open_notepad"),
    (re.compile(rf"^{_OPEN}(?:file explorer|explorer|files|my files|windows explorer)$"), "open_explorer"),
    (
        re.compile(
            r"^(?:status|get status|ping|are you there|are you online|"
            r"(?:is )?(?:my )?laptop (?:status|connected|online)|is my laptop (?:connected|online))$"
        ),
        "get_status",
    ),
]

_SEARCH_FOLDER_FOR = re.compile(
    r"^search\s+(?:in\s+)?(?:my\s+|the\s+)?(?P<folder>\w+)(?:\s+folder)?\s+for\s+(?:my\s+|the\s+|a\s+)?(?P<q>.+)$"
)
_FIND_IN_FOLDER = re.compile(
    r"^(?:find|search for|search|look for|locate)\s+(?:my\s+|the\s+|a\s+)?(?P<q>.+?)"
    r"(?:\s+in\s+(?:my\s+|the\s+)?(?P<folder>\w+)(?:\s+folder)?)?$"
)
_NOISE_WORDS = {"file", "files", "document", "documents", "my", "the", "a"}
_NEWEST_WORDS = {"latest", "newest", "recent", "most", "last"}
_EXTENSIONS = {"pdf", "doc", "docx", "txt", "xls", "xlsx", "csv", "ppt", "pptx", "jpg", "jpeg",
               "png", "mp3", "mp4", "zip", "java", "py"}


def _normalise(text: str) -> str:
    text = text.strip().lower()
    text = re.sub(r"[?!,]+$|\.+$", "", text).strip()
    text = _FILLER.sub("", text)
    return re.sub(r"\s+", " ", text)


def parse_command(text: str) -> dict[str, Any] | None:
    norm = _normalise(text)

    # Exact action names, e.g. {"command": "open_calculator"}.
    if norm in ALLOWED_ACTIONS and norm != "find_file":
        return {"action": norm}

    for pattern, action in _SIMPLE_RULES:
        if pattern.match(norm):
            return {"action": action}

    match = _SEARCH_FOLDER_FOR.match(norm) or _FIND_IN_FOLDER.match(norm)
    if match:
        plan: dict[str, Any] = {"action": "find_file"}
        words = []
        q_words = match.group("q").split()
        for i, word in enumerate(q_words):
            # A type word counts as an extension only at the end: "latest pdf", "pdf files".
            is_last = all(w in _NOISE_WORDS for w in q_words[i + 1:])
            if word in _NEWEST_WORDS:
                plan["newest_first"] = True
            elif word in _EXTENSIONS and is_last and "extension" not in plan:
                plan["extension"] = word
            elif word not in _NOISE_WORDS:
                words.append(word)
        plan["query"] = " ".join(words)
        if match.group("folder"):
            plan["folder"] = match.group("folder")
        return plan

    return None
