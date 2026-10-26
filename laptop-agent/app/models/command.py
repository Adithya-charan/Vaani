"""Request/response schemas and the strict action-plan schema.

Every plan, whether produced by the rule-based parser or the AI planner, must
validate against ``plan_adapter`` before anything is executed.
"""

from __future__ import annotations

import re
import unicodedata
from typing import Annotated, Any, Literal, Union

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, field_validator, model_validator

ALLOWED_ACTIONS = (
    "open_calculator", "open_notepad", "open_explorer", "find_file", "get_status",
    "web_search", "open_chatgpt",
)
# Actions that change something outside Vaani's own windows. They are never produced from
# free text (rules or AI); only the phone's confirmation dialog can request them.
CONFIRMED_ACTIONS = ("open_file",)

_QUERY_PUNCTUATION = set(" .-_()&'")


def _is_query_char(ch: str) -> bool:
    """Letters/digits in any script (incl. Devanagari/Telugu vowel signs) and a little punctuation.
    Path separators, drive colons, wildcards and control characters are never allowed."""
    if ch in _QUERY_PUNCTUATION or ch.isalnum():
        return True
    return unicodedata.category(ch) in ("Mn", "Mc") or ch in ("‌", "‍")  # combining marks, ZWNJ/ZWJ


class CommandRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    command: str = Field(min_length=1, max_length=500)


class CommandResponse(BaseModel):
    request_id: str
    status: Literal["success", "not_found", "rejected", "error", "needs_clarification"]
    action: str | None = None
    message: str
    data: dict[str, Any] | None = None
    planner: Literal["rules", "ai", "confirmed"] | None = None


class _Plan(BaseModel):
    # strict: no type coercion of untrusted model output ("yes" is not a bool, 1 is not a str)
    model_config = ConfigDict(extra="forbid", strict=True)


class OpenCalculator(_Plan):
    action: Literal["open_calculator"]


class OpenNotepad(_Plan):
    action: Literal["open_notepad"]


class OpenExplorer(_Plan):
    action: Literal["open_explorer"]


class GetStatus(_Plan):
    action: Literal["get_status"]


class FindFile(_Plan):
    action: Literal["find_file"]
    query: str = Field(default="", max_length=100)
    folder: str | None = Field(default=None, max_length=40)
    extension: str | None = Field(default=None, pattern=r"^[A-Za-z0-9]{1,10}$")
    newest_first: bool = False

    @field_validator("query")
    @classmethod
    def _query_is_a_name_not_a_path(cls, v: str) -> str:
        if ".." in v or not all(_is_query_char(ch) for ch in v):
            raise ValueError("query may only contain letters, digits, spaces and . - _ ( ) & '")
        return " ".join(v.split())

    @field_validator("folder")
    @classmethod
    def _folder_is_an_alias(cls, v: str | None) -> str | None:
        if v is not None and not re.fullmatch(r"[A-Za-z0-9_]{1,40}", v):
            raise ValueError("folder must be an approved folder name, not a path")
        return v

    @model_validator(mode="after")
    def _needs_something_to_search(self) -> "FindFile":
        if not self.extension and len(self.query.replace(" ", "")) < 2:
            raise ValueError("find_file needs a query of at least 2 characters or an extension")
        return self


def _no_control_chars(v: str) -> str:
    if any(unicodedata.category(ch)[0] == "C" and ch not in ("‌", "‍") for ch in v):
        raise ValueError("control characters are not allowed")
    return " ".join(v.split())


class WebSearch(_Plan):
    """Open a Google search in the default browser. Any text is fine: it is URL-encoded,
    never placed on a command line."""

    action: Literal["web_search"]
    query: str = Field(min_length=1, max_length=200)

    @field_validator("query")
    @classmethod
    def _plain_query(cls, v: str) -> str:
        v = _no_control_chars(v)
        if not v:
            raise ValueError("empty search")
        return v


class OpenChatGPT(_Plan):
    action: Literal["open_chatgpt"]


class OpenFile(_Plan):
    """Open one file or folder from search results. ``path`` is the alias-relative path the
    agent returned (e.g. "Desktop/B TECH/notes.pdf"); ``confirmed`` must be true."""

    action: Literal["open_file"]
    path: str = Field(min_length=3, max_length=400)
    confirmed: Literal[True]
    # From the selected search result, so a file that changed since is not opened by mistake.
    expected_size: int | None = Field(default=None, ge=0)
    expected_modified: str | None = Field(default=None, max_length=40)

    @field_validator("path")
    @classmethod
    def _alias_relative(cls, v: str) -> str:
        if any(unicodedata.category(ch)[0] == "C" for ch in v):
            raise ValueError("control characters are not allowed")
        parts = v.split("/")
        if ("\\" in v or ":" in v or v.startswith("/") or any(p in ("", ".", "..") for p in parts)
                or len(parts) < 2 or not re.fullmatch(r"[A-Za-z0-9_]{1,40}", parts[0])):
            raise ValueError("path must be <ApprovedFolder>/<relative path>")
        return v


class Clarify(_Plan):
    action: Literal["clarify"]
    question: str = Field(min_length=1, max_length=300)

    @field_validator("question")
    @classmethod
    def _plain_text(cls, v: str) -> str:
        # Shown and spoken to the user: drop control/format characters and collapse whitespace.
        v = "".join(ch for ch in v if unicodedata.category(ch)[0] != "C" or ch in ("‌", "‍"))
        v = " ".join(v.split())
        if not v:
            raise ValueError("empty question")
        return v


ActionPlan = Annotated[
    Union[OpenCalculator, OpenNotepad, OpenExplorer, GetStatus, FindFile, WebSearch, OpenChatGPT, Clarify],
    Field(discriminator="action"),
]
# Plans that may come from free text (rule parser or AI planner).
plan_adapter: TypeAdapter[ActionPlan] = TypeAdapter(ActionPlan)

ConfirmedAction = Annotated[Union[OpenFile], Field(discriminator="action")]
# Structured requests sent by the phone after the user confirmed them (POST /actions).
confirmed_action_adapter: TypeAdapter[ConfirmedAction] = TypeAdapter(ConfirmedAction)
