"""Authenticated command endpoints."""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, Request
from fastapi.concurrency import run_in_threadpool
from pydantic import ValidationError

from app.models.command import Clarify, CommandRequest, CommandResponse, GetStatus, plan_adapter
from app.security.auth import require_token
from app.services.ai_planner import PlannerError
from app.services.command_parser import parse_command

audit = logging.getLogger("vaani.audit")

router = APIRouter(dependencies=[Depends(require_token)])


def _respond(rid: str, status: str, message: str, action: str | None = None,
             data: dict | None = None, planner: str | None = None) -> CommandResponse:
    # Audit: action and outcome only, never command text, tokens or file contents.
    audit.info("request=%s action=%s status=%s planner=%s", rid, action, status, planner)
    return CommandResponse(request_id=rid, status=status, action=action, message=message,
                           data=data, planner=planner)


@router.get("/status", response_model=CommandResponse)
async def get_status(request: Request) -> CommandResponse:
    outcome = request.app.state.executor.execute(GetStatus(action="get_status"))
    return _respond(uuid.uuid4().hex, outcome.status, outcome.message, "get_status", outcome.data)


@router.post("/commands", response_model=CommandResponse)
async def run_command(body: CommandRequest, request: Request) -> CommandResponse:
    rid = uuid.uuid4().hex
    raw_plan = parse_command(body.command)
    source = "rules"

    if raw_plan is None:
        planner = request.app.state.planner
        if planner is None:
            return _respond(rid, "rejected",
                            "I don't recognise that command, and AI planning is not configured. "
                            "Try 'open calculator', 'open notepad', 'open file explorer' or 'find <name>'.")
        source = "ai"
        try:
            raw_plan = await planner.plan(body.command)
        except PlannerError as exc:
            return _respond(rid, "error",
                            f"{exc} Basic commands like 'open calculator' still work.", planner=source)

    try:
        plan = plan_adapter.validate_python(raw_plan)
    except ValidationError:
        return _respond(rid, "rejected", "This action is not supported.", planner=source)

    if isinstance(plan, Clarify):
        return _respond(rid, "needs_clarification", plan.question, planner=source)

    outcome = await run_in_threadpool(request.app.state.executor.execute, plan)
    return _respond(rid, outcome.status, outcome.message, plan.action, outcome.data, source)
