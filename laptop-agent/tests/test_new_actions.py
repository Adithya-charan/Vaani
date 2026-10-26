"""Google search, Open ChatGPT, and confirmed opening of a search result."""

from __future__ import annotations

import os
from urllib.parse import parse_qs, urlparse

import pytest
from pydantic import ValidationError

from app.models.command import confirmed_action_adapter, plan_adapter
from app.services.command_parser import parse_command
from tests.conftest import AUTH, FakePlanner


def post(client, command):
    return client.post("/commands", json={"command": command}, headers=AUTH).json()


def action(client, body, headers=AUTH):
    return client.post("/actions", json=body, headers=headers)


def _query(url: str) -> str:
    return parse_qs(urlparse(url).query)["q"][0]


# --- Google search -------------------------------------------------------------------

@pytest.mark.parametrize("text,query", [
    ("google best laptops 2026", "best laptops 2026"),
    ("Search Google for weather in Hyderabad", "weather in hyderabad"),
    ("search the web for python tutorials", "python tutorials"),
    ("look up IPL score on google", "ipl score"),
    ("find cheap flights on the internet", "cheap flights"),
])
def test_web_search_rules(text, query):
    assert parse_command(text) == {"action": "web_search", "query": query}


@pytest.mark.parametrize("text", ["search for resume", "find my resume", "search my documents for report"])
def test_plain_search_is_still_file_search(text):
    assert parse_command(text)["action"] == "find_file"


def test_web_search_opens_encoded_google_url(make_client, opened, launcher):
    body = post(make_client(), "google C++ & Rust: which? 100% fast")
    assert body["status"] == "success" and body["action"] == "web_search"
    assert body["data"]["page_load_verified"] is False
    (url,) = opened
    assert url.startswith("https://www.google.com/search?q=")
    assert _query(url) == "c++ & rust: which 100% fast"  # trailing "?" is dropped by normalisation
    assert " " not in url and "&" not in url.split("?q=")[1]
    assert launcher.calls == []  # nothing is run as a program


@pytest.mark.parametrize("query", ["తెలుగు సినిమాలు", "मौसम हैदराबाद", "naïve café", "a\"b'c<d>e;f|g$h"])
def test_web_search_any_text_is_safely_encoded(make_client, opened, query):
    client = make_client(planner=FakePlanner({"action": "web_search", "query": query}))
    body = post(client, "please look this up for me somewhere")
    assert body["status"] == "success"
    assert _query(opened[0]) == query


@pytest.mark.parametrize("plan", [
    {"action": "web_search"},
    {"action": "web_search", "query": ""},
    {"action": "web_search", "query": "   "},
    {"action": "web_search", "query": "x" * 201},
    {"action": "web_search", "query": "a\nb"},
    {"action": "web_search", "query": "a\x00b"},
    {"action": "web_search", "query": 42},
    {"action": "web_search", "query": "ok", "url": "https://evil.example"},
    {"action": "open_url", "url": "https://evil.example"},
])
def test_web_search_bad_plans_rejected(make_client, opened, plan):
    body = post(make_client(planner=FakePlanner(plan)), "please look this up for me somewhere")
    assert body["status"] == "rejected"
    assert opened == []


def test_browser_failure_is_an_error(make_client):
    def broken(target):
        raise OSError("no browser")

    body = post(make_client(shell_open=broken), "google something")
    assert body["status"] == "error"


# --- Open ChatGPT ------------------------------------------------------------------------

@pytest.mark.parametrize("text", ["open chatgpt", "Open ChatGPT, please.", "go to chat gpt", "launch chatgpt website"])
def test_open_chatgpt_rules(text):
    assert parse_command(text) == {"action": "open_chatgpt"}


def test_open_chatgpt(make_client, opened):
    body = post(make_client(), "open chatgpt")
    assert body["status"] == "success"
    assert opened == ["https://chatgpt.com/"]
    assert body["data"]["page_load_verified"] is False


def test_chatgpt_rejects_extra_fields(make_client, opened):
    body = post(make_client(planner=FakePlanner({"action": "open_chatgpt", "prompt": "hi"})), "ask chatgpt hi please")
    assert body["status"] == "rejected"
    assert opened == []


# --- open a selected search result (POST /actions) ----------------------------------------

def _result(client, text):
    return post(client, text)["data"]["results"]


def _open_body(item, **extra):
    return {"action": "open_file", "path": item["path"], "confirmed": True,
            "expected_size": item["size"], "expected_modified": item["modified"], **extra}


def test_open_selected_file(make_client, opened, roots):
    client = make_client()
    (item,) = [r for r in _result(client, "find invoice") if r["name"] == "invoice.pdf"]
    r = action(client, _open_body(item)).json()
    assert r["status"] == "success" and r["planner"] == "confirmed"
    assert opened == [str((roots["Downloads"] / "invoice.pdf").resolve())]


def test_open_selected_folder(make_client, opened, roots):
    client = make_client()
    (item,) = [r for r in _result(client, "find java project") if r["type"] == "folder"]
    r = action(client, {"action": "open_file", "path": item["path"], "confirmed": True}).json()
    assert r["status"] == "success" and r["data"]["type"] == "folder"
    assert opened == [str((roots["Documents"] / "Projects" / "JavaProject").resolve())]


def test_duplicate_names_open_the_selected_one(make_client, opened, roots):
    (roots["Documents"] / "report.pdf").write_text("docs copy")
    (roots["Downloads"] / "report.pdf").write_text("downloads copy")
    client = make_client()
    results = _result(client, "find report")
    chosen = next(r for r in results if r["path"] == "Downloads/report.pdf")
    assert action(client, _open_body(chosen)).json()["status"] == "success"
    assert opened == [str((roots["Downloads"] / "report.pdf").resolve())]


def test_open_requires_confirmation(make_client, opened):
    client = make_client()
    for body in ({"action": "open_file", "path": "Downloads/invoice.pdf"},
                 {"action": "open_file", "path": "Downloads/invoice.pdf", "confirmed": False},
                 {"action": "open_file", "path": "Downloads/invoice.pdf", "confirmed": "yes"}):
        assert action(client, body).json()["status"] == "rejected"
    assert opened == []


def test_open_requires_token(make_client, opened):
    r = action(make_client(), {"action": "open_file", "path": "Downloads/invoice.pdf", "confirmed": True}, headers={})
    assert r.status_code == 401
    assert opened == []


@pytest.mark.parametrize("path", [
    "Downloads/../secret.txt", "Downloads/./invoice.pdf", "../secret.txt", "Downloads", "Downloads/",
    r"Downloads\invoice.pdf", "C:/Windows/win.ini", "/Downloads/invoice.pdf", "Downloads//invoice.pdf",
    "Windows/System32/calc.exe", "Downloads/invoice.pdf\x00.exe",
])
def test_open_rejects_unsafe_paths(make_client, opened, path):
    r = action(make_client(), {"action": "open_file", "path": path, "confirmed": True}).json()
    assert r["status"] in ("rejected", "not_found")
    assert opened == []


def test_open_missing_file(make_client, opened, roots):
    client = make_client()
    (item,) = [r for r in _result(client, "find notes") if r["name"] == "notes.txt"]
    (roots["Downloads"] / "notes.txt").unlink()
    r = action(client, _open_body(item)).json()
    assert r["status"] == "not_found"
    assert opened == []


def test_open_changed_file_is_refused(make_client, opened, roots):
    client = make_client()
    (item,) = [r for r in _result(client, "find notes") if r["name"] == "notes.txt"]
    (roots["Downloads"] / "notes.txt").write_text("rewritten with different content")
    r = action(client, _open_body(item)).json()
    assert r["status"] == "rejected" and "changed" in r["message"]
    assert opened == []


@pytest.mark.parametrize("name", ["setup.exe", "run.bat", "tool.ps1", "script.py", "app.js", "link.lnk",
                                  "site.url", "macro.vbs", "noext"])
def test_open_refuses_programs_and_scripts(make_client, opened, roots, name):
    (roots["Downloads"] / name).write_text("x")
    r = action(make_client(), {"action": "open_file", "path": f"Downloads/{name}", "confirmed": True}).json()
    assert r["status"] == "rejected"
    assert opened == []


def test_open_refuses_links_out_of_root(make_client, opened, roots, tmp_path):
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "leak.pdf").write_text("x")
    try:
        os.symlink(outside, roots["Downloads"] / "link", target_is_directory=True)
    except OSError:
        # Symlinks need Developer Mode; directory junctions don't, and escape a folder the same way.
        import _winapi
        _winapi.CreateJunction(str(outside), str(roots["Downloads"] / "link"))
    r = action(make_client(), {"action": "open_file", "path": "Downloads/link/leak.pdf", "confirmed": True}).json()
    assert r["status"] == "rejected"
    assert opened == []


def test_open_file_failure_is_an_error(make_client, roots):
    def broken(target):
        raise OSError("no app")

    r = action(make_client(shell_open=broken),
               {"action": "open_file", "path": "Downloads/invoice.pdf", "confirmed": True}).json()
    assert r["status"] == "error"


def test_malformed_action_bodies(make_client, opened):
    client = make_client()
    for body in ([], "open", {"action": "open_calculator"}, {"action": "find_file", "query": "x1"},
                 {"action": "open_file", "path": "Downloads/invoice.pdf", "confirmed": True, "extra": 1}):
        assert action(client, body).json()["status"] == "rejected"
    r = client.post("/actions", content=b"not json", headers={**AUTH, "content-type": "application/json"})
    assert r.json()["status"] == "rejected"
    assert opened == []


# --- open_file can never come from free text --------------------------------------------

def test_planner_cannot_open_files(make_client, opened):
    plan = {"action": "open_file", "path": "Downloads/invoice.pdf", "confirmed": True}
    body = post(make_client(planner=FakePlanner(plan)), "open my invoice right now please")
    assert body["status"] == "rejected"
    assert opened == []
    with pytest.raises(ValidationError):
        plan_adapter.validate_python(plan)


def test_confirmed_adapter_only_allows_confirmed_actions():
    with pytest.raises(ValidationError):
        confirmed_action_adapter.validate_python({"action": "web_search", "query": "x"})


# --- regression: the original five actions still work -----------------------------------

@pytest.mark.parametrize("text,expected", [
    ("open calculator", "open_calculator"), ("open notepad", "open_notepad"),
    ("open file explorer", "open_explorer"), ("find invoice", "find_file"), ("status", "get_status"),
])
def test_original_actions_unchanged(make_client, text, expected):
    assert post(make_client(), text)["action"] == expected
