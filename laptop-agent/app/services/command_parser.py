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
    (re.compile(rf"^(?:{_OPEN}|go to\s+)(?:chatgpt|chat gpt|chat gbt)(?:\s+website)?$"), "open_chatgpt"),
]

# Web search needs an explicit "google"/"web" word; plain "search for X" stays a file search.
_WEB_SEARCH = [
    re.compile(r"^(?:google|google search(?: for)?|search google for|search on google for|"
               r"search (?:the )?(?:web|internet) for|web search(?: for)?)\s+(?P<q>.+)$"),
    re.compile(r"^(?:search(?: for)?|look up|find)\s+(?P<q>.+?)\s+on (?:google|the web|the internet)$"),
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
# "find it" names nothing; searching for "it" would match half the disk ("Adithya", "edit"...).
_VAGUE_WORDS = {"it", "that", "this", "them", "those", "these", "something", "anything", "everything",
                "stuff", "thing", "things", "one", "some", "all", "any"}
_EXTENSIONS = {"pdf", "doc", "docx", "txt", "xls", "xlsx", "csv", "ppt", "pptx", "jpg", "jpeg",
               "png", "mp3", "mp4", "zip", "java", "py"}


def _normalise(text: str) -> str:
    text = text.strip().lower()
    # Speech-to-text adds punctuation ("Open calculator, please."); drop it and polite endings.
    text = re.sub(r"[?!,;]+", " ", text)
    text = re.sub(r"\.+$", "", text).strip()
    text = re.sub(r"\s+(?:please|thanks|thank you)$", "", text)
    text = _FILLER.sub("", text)
    return re.sub(r"\s+", " ", text).strip()


def parse_command(text: str) -> dict[str, Any] | None:
    norm = _normalise(text)

    # Exact action names, e.g. {"command": "open_calculator"}.
    if norm in ALLOWED_ACTIONS and norm not in ("find_file", "web_search"):
        return {"action": norm}

    for pattern, action in _SIMPLE_RULES:
        if pattern.match(norm):
            return {"action": action}

    for pattern in _WEB_SEARCH:
        match = pattern.match(norm)
        if match:
            return {"action": "web_search", "query": match.group("q").strip()}

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
        if "extension" not in plan and all(w in _VAGUE_WORDS or len(w) < 2 for w in words):
            return None  # too vague for a rule; the planner can ask what to look for
        plan["query"] = " ".join(words)
        if match.group("folder"):
            plan["folder"] = match.group("folder")
        return plan

    return None
