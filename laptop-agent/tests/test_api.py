from __future__ import annotations

import pytest

from app.services.ai_planner import PlannerError
from app.services.command_executor import PROGRAMS
from tests.conftest import AUTH, FakePlanner, RecordingLauncher


def post(client, command, headers=AUTH):
    return client.post("/commands", json={"command": command}, headers=headers)


# --- authentication -----------------------------------------------------------

def test_health_is_public_and_minimal(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok", "service": "vaani-laptop-agent"}


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer wrong"}, {"Authorization": "Basic abc"}])
def test_status_and_commands_require_valid_token(client, launcher, headers):
    assert client.get("/status", headers=headers).status_code == 401
    assert post(client, "open calculator", headers).status_code == 401
    assert launcher.calls == []


def test_status_with_token(client):
    body = client.get("/status", headers=AUTH).json()
    assert body["status"] == "success"
    assert body["data"]["approved_folders"] == ["Documents", "Downloads"]
    assert body["data"]["ai_planner"] is False


def test_docs_disabled_by_default(client):
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404


# --- validation ---------------------------------------------------------------

@pytest.mark.parametrize("payload", [{}, {"command": ""}, {"command": "x" * 501},
                                     {"command": "open calculator", "action": "open_notepad"}, {"command": 5}])
def test_malformed_requests_rejected(client, launcher, payload):
    assert client.post("/commands", json=payload, headers=AUTH).status_code == 422
    assert launcher.calls == []


# --- allowlisted actions --------------------------------------------------------

@pytest.mark.parametrize("command,action", [
    ("Open Calculator.", "open_calculator"),
    ("open_calculator", "open_calculator"),
    ("Vaani, please open notepad", "open_notepad"),
    ("open file explorer", "open_explorer"),
])
def test_launch_actions(client, launcher, command, action):
    body = post(client, command).json()
    assert body["status"] == "success"
    assert body["action"] == action
    assert body["planner"] == "rules"
    assert len(body["request_id"]) == 32
    assert launcher.calls == [PROGRAMS[action]]


def test_launch_failure_reported_as_error(make_client):
    client = make_client(launcher_override=RecordingLauncher(fail=True))
    body = post(client, "open notepad").json()
    assert body["status"] == "error"


def test_unknown_command_without_ai_is_rejected(client, launcher):
    body = post(client, "delete everything in system32").json()
    assert body["status"] == "rejected"
    assert body["action"] is None
    assert launcher.calls == []


def test_status_command(client):
    assert post(client, "is my laptop connected?").json()["action"] == "get_status"


# --- file search ----------------------------------------------------------------

def test_find_folder(client):
    body = post(client, "Find my Java project").json()
    assert body["status"] == "success"
    assert body["data"]["results"][0]["path"] == "Documents/Projects/JavaProject"


def test_search_folder_for(client):
    body = post(client, "Search my Documents folder for the project report").json()
    assert body["status"] == "success"
    assert body["data"]["results"][0]["name"] == "Project Report.docx"
    assert body["data"]["searched"] == ["Documents"]


def test_find_not_found(client):
    body = post(client, "find unicorn spreadsheet").json()
    assert body["status"] == "not_found"


def test_find_in_unapproved_folder_rejected(client):
    body = post(client, "find secret in windows").json()
    assert body["status"] == "rejected"


def test_find_never_returns_files_outside_roots(client):
    body = post(client, "find secret").json()
    assert body["status"] == "not_found"


@pytest.mark.parametrize("command", [r"find ..\secret", "find ../../secret", r"find C:\Windows\win.ini"])
def test_path_traversal_rejected(client, command):
    assert post(client, command).json()["status"] == "rejected"


# --- AI planner ----------------------------------------------------------------

def test_ai_plan_is_validated_and_executed(make_client):
    planner = FakePlanner({"action": "find_file", "query": "", "folder": "Downloads",
                           "extension": "pdf", "newest_first": True})
    client = make_client(planner=planner)
    body = post(client, "get me the latest pdf I downloaded").json()
    assert body["status"] == "success"
    assert body["planner"] == "ai"
    assert body["data"]["results"][0]["name"] == "invoice.pdf"
    assert planner.seen == ["get me the latest pdf I downloaded"]


def test_rules_take_priority_over_ai(make_client, launcher):
    planner = FakePlanner({"action": "open_notepad"})
    client = make_client(planner=planner)
    assert post(client, "open calculator").json()["action"] == "open_calculator"
    assert planner.seen == []


@pytest.mark.parametrize("plan", [
    {"action": "run_shell", "cmd": "format c:"},
    {"action": "open_calculator", "args": ["/c", "del"]},
    {"action": "find_file", "query": "x", "folder": "C:\\Windows"},
    {"action": "find_file", "query": "../secret"},
    {"action": "find_file"},
    ["open_calculator"],
    "open_calculator",
])
def test_bad_ai_output_rejected(make_client, launcher, plan):
    client = make_client(planner=FakePlanner(plan))
    body = post(client, "do something clever").json()
    assert body["status"] == "rejected"
    assert launcher.calls == []


def test_ai_unapproved_folder_rejected(make_client):
    client = make_client(planner=FakePlanner({"action": "find_file", "query": "x", "folder": "System32"}))
    assert post(client, "look in system32 for x please").json()["status"] == "rejected"


def test_ai_clarification(make_client):
    client = make_client(planner=FakePlanner({"action": "clarify", "question": "Which file?"}))
    body = post(client, "open that thing").json()
    assert body == {**body, "status": "needs_clarification", "message": "Which file?"}


def test_ai_failure_reported(make_client):
    client = make_client(planner=FakePlanner(PlannerError("AI planner is rate-limited.")))
    body = post(client, "something unusual").json()
    assert body["status"] == "error"
    assert "rate-limited" in body["message"]
