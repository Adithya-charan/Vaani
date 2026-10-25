from __future__ import annotations

import asyncio
import json
import os
import sys
from pathlib import Path

import httpx
import pytest

from app.config import ConfigError, load_settings, parse_approved_folders
from app.services.ai_planner import GroqPlanner, PlannerError
from app.services.command_parser import parse_command
from app.services.file_search import FileSearcher, FolderNotApproved


@pytest.mark.parametrize("text,expected", [
    ("open calculator", {"action": "open_calculator"}),
    ("Launch the calc!", {"action": "open_calculator"}),
    ("start notepad", {"action": "open_notepad"}),
    ("open explorer", {"action": "open_explorer"}),
    ("status", {"action": "get_status"}),
    ("find my java project", {"action": "find_file", "query": "java project"}),
    ("search for budget file in downloads", {"action": "find_file", "query": "budget", "folder": "downloads"}),
    ("find the latest pdf in my downloads",
     {"action": "find_file", "query": "", "extension": "pdf", "newest_first": True, "folder": "downloads"}),
    ("open calculator and delete my files", None),
    ("what's the weather", None),
])
def test_parser(text, expected):
    assert parse_command(text) == expected


def test_searcher_rejects_unknown_root(roots):
    with pytest.raises(FolderNotApproved):
        FileSearcher(roots).search("x", folder="Windows")


def test_searcher_folder_alias_case_insensitive(roots):
    assert FileSearcher(roots).search("invoice", folder="downloads")["results"][0]["name"] == "invoice.pdf"


def test_searcher_respects_result_limit(tmp_path):
    for i in range(30):
        (tmp_path / f"report{i}.txt").write_text("x")
    found = FileSearcher({"Docs": tmp_path}, max_results=5).search("report")
    assert len(found["results"]) == 5
    assert found["total_matches"] == 30
    assert found["truncated"] is True


def test_searcher_does_not_follow_links_out_of_root(tmp_path):
    root, outside = tmp_path / "root", tmp_path / "outside"
    root.mkdir()
    outside.mkdir()
    (outside / "leak.txt").write_text("x")
    try:
        os.symlink(outside, root / "link", target_is_directory=True)
    except OSError:
        pytest.skip("creating symlinks needs Developer Mode or admin on Windows")
    assert FileSearcher({"Root": root}).search("leak")["results"] == []


def test_settings_require_strong_token(tmp_path, monkeypatch):
    monkeypatch.setenv("VAANI_AUTH_TOKEN", "short")
    with pytest.raises(ConfigError):
        load_settings(tmp_path / "missing.env")


def test_settings_repr_hides_secrets(tmp_path, monkeypatch):
    monkeypatch.setenv("VAANI_AUTH_TOKEN", "s" * 40)
    monkeypatch.setenv("GROQ_API_KEY", "gsk_secretvalue")
    s = load_settings(tmp_path / "missing.env")
    assert "s" * 40 not in repr(s) and "gsk_secretvalue" not in repr(s)


@pytest.mark.skipif(sys.platform != "win32", reason="Windows known folders")
def test_known_folders_resolve_like_explorer():
    import ctypes.wintypes  # noqa: F401  (ensures ctypes is usable)
    folders = parse_approved_folders("Desktop,Downloads")
    assert set(folders) == {"Desktop", "Downloads"}
    for path in folders.values():
        assert path.is_dir() and path.is_absolute()


def test_unknown_alias_falls_back_to_home(tmp_path, monkeypatch):
    (tmp_path / "Projects").mkdir()
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: tmp_path))
    assert parse_approved_folders("Projects") == {"Projects": (tmp_path / "Projects").resolve()}


def test_approved_folders_parsing(tmp_path):
    folders = parse_approved_folders(f"Work={tmp_path},Missing={tmp_path / 'nope'}")
    assert folders == {"Work": tmp_path.resolve()}
    with pytest.raises(ConfigError):
        parse_approved_folders("Work=relative\\path")
    with pytest.raises(ConfigError):
        parse_approved_folders(f"bad/alias={tmp_path}")


def _groq(handler) -> GroqPlanner:
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return GroqPlanner("key", "qwen/qwen3.8-27b", ["Documents"], client=client)


def _reply(content: str, status: int = 200):
    return lambda request: httpx.Response(status, json={"choices": [{"message": {"content": content}}]})


def test_groq_parses_json_and_strips_thinking():
    planner = _groq(_reply('<think>hmm</think>\n{"action": "open_notepad"}'))
    assert asyncio.run(planner.plan("notepad pls")) == {"action": "open_notepad"}


def test_groq_sends_key_and_model():
    seen = {}

    def handler(request: httpx.Request):
        seen["auth"] = request.headers["Authorization"]
        seen["body"] = json.loads(request.content)
        return _reply('{"action": "get_status"}')(request)

    asyncio.run(_groq(handler).plan("hi"))
    assert seen["auth"] == "Bearer key"
    assert seen["body"]["model"] == "qwen/qwen3.8-27b"
    assert seen["body"]["messages"][-1] == {"role": "user", "content": "hi"}


@pytest.mark.parametrize("handler,msg", [
    (_reply("not json"), "invalid JSON"),
    (_reply("[1, 2]"), "non-object"),
    (_reply("{}", status=429), "rate-limited"),
    (_reply("{}", status=401), "API key"),
    (_reply("{}", status=500), "HTTP 500"),
    (lambda r: httpx.Response(200, json={"oops": True}), "unexpected"),
])
def test_groq_errors(handler, msg):
    with pytest.raises(PlannerError, match=msg):
        asyncio.run(_groq(handler).plan("x"))


def test_groq_network_failure():
    def handler(request):
        raise httpx.ConnectError("down")

    with pytest.raises(PlannerError, match="unreachable"):
        asyncio.run(_groq(handler).plan("x"))
