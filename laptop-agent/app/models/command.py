"""Request/response schemas and the strict action-plan schema.

Every plan, whether produced by the rule-based parser or the AI planner, must
validate against ``plan_adapter`` before anything is executed.
"""

from __future__ import annotations

import re
from typing import Annotated, Any, Literal, Union

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, field_validator, model_validator

ALLOWED_ACTIONS = ("open_calculator", "open_notepad", "open_explorer", "find_file", "get_status")

_SAFE_QUERY = re.compile(r"^[\w .\-()&']*$")


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
    model_config = ConfigDict(extra="forbid")


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
        v = v.strip()
        if ".." in v or not _SAFE_QUERY.match(v):
            raise ValueError("query may only contain letters, digits, spaces and . - _ ( ) & '")
        return v

    @field_validator("folder")
    @classmethod
    def _folder_is_an_alias(cls, v: str | None) -> str | None:
        if v is not None and not v.replace("_", "").isalnum():
            raise ValueError("folder must be an approved folder name, not a path")
        return v

    @model_validator(mode="after")
    def _needs_something_to_search(self) -> "FindFile":
        if not self.query and not self.extension:
            raise ValueError("find_file needs a query or an extension")
        return self


class Clarify(_Plan):
    action: Literal["clarify"]
    question: str = Field(min_length=1, max_length=300)


ActionPlan = Annotated[
    Union[OpenCalculator, OpenNotepad, OpenExplorer, GetStatus, FindFile, Clarify],
    Field(discriminator="action"),
]
plan_adapter: TypeAdapter[ActionPlan] = TypeAdapter(ActionPlan)
