"""Request/response schemas and the strict action-plan schema.

Every plan, whether produced by the rule-based parser or the AI planner, must
validate against ``plan_adapter`` before anything is executed.
"""

from __future__ import annotations

import re
import unicodedata
from typing import Annotated, Any, Literal, Union

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, field_validator, model_validator

ALLOWED_ACTIONS = ("open_calculator", "open_notepad", "open_explorer", "find_file", "get_status")

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
    planner: Literal["rules", "ai"] | None = None


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
    Union[OpenCalculator, OpenNotepad, OpenExplorer, GetStatus, FindFile, Clarify],
    Field(discriminator="action"),
]
plan_adapter: TypeAdapter[ActionPlan] = TypeAdapter(ActionPlan)
