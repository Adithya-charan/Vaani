"""ChatGPT prompt submission and Telegram sending (single file and folder ZIP).
External services are faked; nothing is typed into a browser or sent to Telegram."""

from __future__ import annotations

import json
import zipfile
from pathlib import Path

import httpx
import pytest
from pydantic import ValidationError

from app.models.command import confirmed_action_adapter, plan_adapter
from app.services.chatgpt import ChatGPTError
from app.services.command_parser import parse_command
from app.services.telegram import MAX_UPLOAD_BYTES, TelegramBot, TelegramError, parse_recipients, recent_chats
from app.services.zipper import ZipError, make_zip
from tests.conftest import AUTH, FakePlanner


def post(client, command):
    return client.post("/commands", json={"command": command}, headers=AUTH).json()


def act(client, body):
    return client.post("/actions", json=body, headers=AUTH).json()


class FakeChatGPT:
    def __init__(self, error: ChatGPTError | None = None) -> None:
        self.error = error
        self.prompts: list[str] = []

    def submit(self, prompt: str) -> None:
        self.prompts.append(prompt)
        if self.error:
            raise self.error


class FakeTelegram:
    def __init__(self, error: TelegramError | None = None, recipients=None) -> None:
        self.recipients = recipients or {"Me": 111, "Amma": 222}
        self.error = error
        self.sent: list[tuple[str, str, int, bytes]] = []

    def send_document(self, recipient, path: Path, filename=None):
        data = path.read_bytes()
        self.sent.append((recipient, filename or path.name, len(data), data))
        if self.error:
            raise self.error
        return 4242


# --- ChatGPT: proposal -> confirmation -> submit ----------------------------------------

@pytest.mark.parametrize("text,prompt", [
    ("ask chatgpt what is quantum computing?", "what is quantum computing?"),
    ("Ask ChatGPT: Write a Haiku About Rain", "Write a Haiku About Rain"),
    ("Vaani, please ask chat gpt to explain DNS in Telugu", "explain DNS in Telugu"),
    ("tell ChatGPT that I'm learning Kotlin", "I'm learning Kotlin"),
])
def test_ask_chatgpt_rule_keeps_original_wording(text, prompt):
    assert parse_command(text) == {"action": "ask_chatgpt", "prompt": prompt}


def test_ask_chatgpt_only_proposes(make_client):
    gpt = FakeChatGPT()
    body = post(make_client(chatgpt=gpt), "ask chatgpt what is quantum computing?")
    assert body["status"] == "needs_confirmation"
    assert body["data"]["confirm"] == {"action": "chatgpt_prompt", "prompt": "what is quantum computing?"}
    assert gpt.prompts == []  # nothing sent without confirmation


def test_planner_proposal_also_needs_confirmation(make_client):
    gpt = FakeChatGPT()
    client = make_client(planner=FakePlanner({"action": "ask_chatgpt", "prompt": "క్వాంటం కంప్యూటింగ్ అంటే ఏమిటి?"}),
                         chatgpt=gpt)
    body = post(client, "చాట్ జీపీటీని క్వాంటం కంప్యూటింగ్ గురించి అడుగు")
    assert body["status"] == "needs_confirmation"
    assert gpt.prompts == []


def test_planner_cannot_submit_directly(make_client):
    gpt = FakeChatGPT()
    plan = {"action": "chatgpt_prompt", "prompt": "hi", "confirmed": True}
    body = post(make_client(planner=FakePlanner(plan), chatgpt=gpt), "send hi to chatgpt right away")
    assert body["status"] == "rejected"
    assert gpt.prompts == []


def test_confirmed_prompt_is_submitted_and_verified(make_client):
    gpt = FakeChatGPT()
    r = act(make_client(chatgpt=gpt), {"action": "chatgpt_prompt", "prompt": "Explain DNS simply.", "confirmed": True})
    assert r["status"] == "success" and r["data"]["verified"] is True
    assert gpt.prompts == ["Explain DNS simply."]


@pytest.mark.parametrize("body", [
    {"action": "chatgpt_prompt", "prompt": "hi"},
    {"action": "chatgpt_prompt", "prompt": "hi", "confirmed": False},
    {"action": "chatgpt_prompt", "prompt": "", "confirmed": True},
    {"action": "chatgpt_prompt", "prompt": "   ", "confirmed": True},
    {"action": "chatgpt_prompt", "prompt": "x" * 501, "confirmed": True},
    {"action": "chatgpt_prompt", "prompt": "a\x1bb", "confirmed": True},
    {"action": "chatgpt_prompt", "prompt": "hi", "confirmed": True, "model": "gpt-5"},
])
def test_bad_prompt_requests_send_nothing(make_client, body):
    gpt = FakeChatGPT()
    assert act(make_client(chatgpt=gpt), body)["status"] == "rejected"
    assert gpt.prompts == []


@pytest.mark.parametrize("kind,status", [
    ("needs_login", "error"), ("page_changed", "error"), ("not_sent", "error"), ("timeout", "error"),
    ("unavailable", "error"), ("busy", "rejected"),
])
def test_chatgpt_failures_are_never_success(make_client, kind, status):
    gpt = FakeChatGPT(ChatGPTError(kind, f"{kind} message"))
    r = act(make_client(chatgpt=gpt), {"action": "chatgpt_prompt", "prompt": "hi", "confirmed": True})
    assert r["status"] == status
    assert r["message"] == f"{kind} message"
    assert r["data"]["reason"] == kind


def test_chatgpt_not_installed(make_client):
    r = act(make_client(), {"action": "chatgpt_prompt", "prompt": "hi", "confirmed": True})
    assert r["status"] == "error"


# --- driver's "was it really sent?" check -------------------------------------------------

class _Loc:
    def __init__(self, texts): self.texts = texts
    def count(self): return len(self.texts)
    def nth(self, i): return _El(self.texts[i])


class _El:
    def __init__(self, text): self.text = text
    def inner_text(self): return self.text


class _Composer:
    def __init__(self, value): self.value = value
    def evaluate(self, js): return "TEXTAREA"
    def input_value(self): return self.value


class _Page:
    def __init__(self, user_msgs=(), body="", url="https://chatgpt.com/"):
        self.user_msgs, self.body, self.url = list(user_msgs), body, url
    def locator(self, sel): return _Loc(self.user_msgs)
    def evaluate(self, js): return self.body


@pytest.mark.parametrize("page,composer,expected", [
    (_Page(user_msgs=["Explain DNS"]), _Composer(""), True),                   # signed-in marker
    (_Page(body="... Explain DNS ... answer"), _Composer(""), True),           # shown, box empty
    (_Page(url="https://chatgpt.com/uc/abc"), _Composer(""), True),            # conversation opened
    (_Page(body="Explain DNS"), _Composer("Explain DNS"), False),              # still in the box
    (_Page(), _Composer(""), False),                                           # vanished, no evidence
    (_Page(user_msgs=["something else"]), _Composer(""), False),               # wrong message
])
def test_sent_check(page, composer, expected):
    from app.services.chatgpt import PlaywrightChatGPT
    before = 0
    assert PlaywrightChatGPT._sent(page, composer, "Explain DNS", before, "https://chatgpt.com/") is expected


# --- Telegram: single file -----------------------------------------------------------------

def _result(client, text, name):
    return next(r for r in post(client, text)["data"]["results"] if r["name"] == name)


def _send(item, recipient="Me", **extra):
    return {"action": "telegram_send", "path": item["path"], "recipient": recipient, "confirmed": True,
            "expected_size": item["size"], "expected_modified": item["modified"], **extra}


def test_send_confirmed_file(make_client, roots):
    tg = FakeTelegram()
    client = make_client(telegram=tg)
    r = act(client, _send(_result(client, "find invoice", "invoice.pdf")))
    assert r["status"] == "success"
    assert r["data"] == {"recipient": "Me", "name": "invoice.pdf", "size": 1, "message_id": 4242, "zip": False}
    assert [(s[0], s[1]) for s in tg.sent] == [("Me", "invoice.pdf")]


def test_status_lists_recipient_names_only(make_client):
    data = make_client(telegram=FakeTelegram()).get("/status", headers=AUTH).json()["data"]
    assert data["telegram_recipients"] == ["Amma", "Me"]


@pytest.mark.parametrize("body_change", [
    {"confirmed": False}, {"recipient": "Stranger"}, {"recipient": "123456"}, {"recipient": "Me; rm"},
    {"path": "Downloads/../secret.txt"}, {"path": "C:/Windows/win.ini"}, {"extra": "x"},
])
def test_send_rejections_send_nothing(make_client, body_change):
    tg = FakeTelegram()
    client = make_client(telegram=tg)
    body = _send(_result(client, "find invoice", "invoice.pdf"))
    body.update(body_change)
    assert act(client, body)["status"] == "rejected"
    assert tg.sent == []


def test_send_without_confirmation_field(make_client):
    tg = FakeTelegram()
    client = make_client(telegram=tg)
    body = _send(_result(client, "find invoice", "invoice.pdf"))
    del body["confirmed"]
    assert act(client, body)["status"] == "rejected"
    assert tg.sent == []


def test_send_changed_or_missing_file(make_client, roots):
    tg = FakeTelegram()
    client = make_client(telegram=tg)
    item = _result(client, "find notes", "notes.txt")
    (roots["Downloads"] / "notes.txt").write_text("changed contents")
    assert act(client, _send(item))["status"] == "rejected"
    (roots["Downloads"] / "notes.txt").unlink()
    assert act(client, _send(item))["status"] == "not_found"
    assert tg.sent == []


def test_file_with_as_zip_is_rejected(make_client):
    tg = FakeTelegram()
    client = make_client(telegram=tg)
    assert act(client, _send(_result(client, "find invoice", "invoice.pdf"), as_zip=True))["status"] == "rejected"
    assert tg.sent == []


@pytest.mark.parametrize("kind,status", [
    ("blocked", "error"), ("chat_not_found", "error"), ("bad_token", "error"), ("unreachable", "error"),
    ("timeout", "error"), ("rate_limited", "error"), ("failed", "error"), ("too_large", "rejected"),
])
def test_send_failures_are_never_success(make_client, kind, status):
    tg = FakeTelegram(TelegramError(kind, f"{kind} message"))
    client = make_client(telegram=tg)
    r = act(client, _send(_result(client, "find invoice", "invoice.pdf")))
    assert r["status"] == status and r["message"] == f"{kind} message"


def test_telegram_not_configured(make_client):
    client = make_client()
    item = _result(client, "find invoice", "invoice.pdf")
    assert act(client, _send(item))["status"] == "error"


def test_planner_cannot_send(make_client):
    tg = FakeTelegram()
    plan = {"action": "telegram_send", "path": "Downloads/invoice.pdf", "recipient": "Me", "confirmed": True}
    assert post(make_client(planner=FakePlanner(plan), telegram=tg), "send my invoice to me on telegram")["status"] == "rejected"
    assert tg.sent == []
    with pytest.raises(ValidationError):
        plan_adapter.validate_python(plan)


# --- Telegram: folder as ZIP ------------------------------------------------------------------

def test_send_folder_as_zip(make_client, roots, tmp_path):
    tg = FakeTelegram()
    client = make_client(telegram=tg)
    client.app.state.executor.zip_temp_root = tmp_path / "ziptmp"
    folder = _result(client, "find java project", "JavaProject")
    r = act(client, {"action": "telegram_send", "path": folder["path"], "recipient": "Amma",
                     "confirmed": True, "as_zip": True})
    assert r["status"] == "success" and r["data"]["zip"] is True and r["data"]["files_in_zip"] == 1
    recipient, name, _, data = tg.sent[0]
    assert (recipient, name) == ("Amma", "JavaProject.zip")
    zpath = tmp_path / "check.zip"
    zpath.write_bytes(data)
    with zipfile.ZipFile(zpath) as zf:
        assert zf.namelist() == ["JavaProject/Main.java"]
    assert not any((tmp_path / "ziptmp").iterdir())  # temporary archive deleted


def test_folder_without_as_zip_is_rejected(make_client):
    tg = FakeTelegram()
    client = make_client(telegram=tg)
    folder = _result(client, "find java project", "JavaProject")
    r = act(client, {"action": "telegram_send", "path": folder["path"], "recipient": "Me", "confirmed": True})
    assert r["status"] == "rejected"
    assert tg.sent == []


def test_zip_temp_deleted_even_when_send_fails(make_client, tmp_path):
    tg = FakeTelegram(TelegramError("unreachable", "offline"))
    client = make_client(telegram=tg)
    client.app.state.executor.zip_temp_root = tmp_path / "ziptmp"
    folder = _result(client, "find java project", "JavaProject")
    r = act(client, {"action": "telegram_send", "path": folder["path"], "recipient": "Me",
                     "confirmed": True, "as_zip": True})
    assert r["status"] == "error"
    assert not any((tmp_path / "ziptmp").iterdir())


# --- zipper ----------------------------------------------------------------------------------

def test_zip_nested_and_relative_names(tmp_path):
    f = tmp_path / "Proj"
    (f / "src" / "deep").mkdir(parents=True)
    (f / "a.txt").write_text("a")
    (f / "src" / "deep" / "b.txt").write_text("b")
    arc = make_zip(f, tmp_path / "t")
    try:
        with zipfile.ZipFile(arc.path) as zf:
            assert sorted(zf.namelist()) == ["Proj/a.txt", "Proj/src/deep/b.txt"]
        assert arc.files == 2 and arc.size == arc.path.stat().st_size
    finally:
        assert arc.cleanup() is None
    assert not arc.path.exists()


def test_zip_empty_folder(tmp_path):
    (tmp_path / "Empty").mkdir()
    with pytest.raises(ZipError) as e:
        make_zip(tmp_path / "Empty", tmp_path / "t")
    assert e.value.kind == "empty"


def test_zip_skips_junction_out_of_folder(tmp_path):
    import _winapi
    f, outside = tmp_path / "Proj", tmp_path / "outside"
    f.mkdir()
    outside.mkdir()
    (f / "keep.txt").write_text("k")
    (outside / "secret.txt").write_text("s")
    _winapi.CreateJunction(str(outside), str(f / "escape"))
    arc = make_zip(f, tmp_path / "t")
    try:
        with zipfile.ZipFile(arc.path) as zf:
            assert zf.namelist() == ["Proj/keep.txt"]
        assert "escape/" in arc.skipped
    finally:
        arc.cleanup()


def test_zip_limits(tmp_path):
    f = tmp_path / "Big"
    f.mkdir()
    for i in range(5):
        (f / f"{i}.bin").write_bytes(b"x" * 1000)
    with pytest.raises(ZipError) as e:
        make_zip(f, tmp_path / "t", max_files=3)
    assert e.value.kind == "too_many_files"
    with pytest.raises(ZipError) as e:
        make_zip(f, tmp_path / "t", max_input_bytes=2500)
    assert e.value.kind == "too_large"
    import os
    with pytest.raises(ZipError) as e:
        (f / "rand.bin").write_bytes(os.urandom(20000))
        make_zip(f, tmp_path / "t", max_zip_bytes=1000)
    assert e.value.kind == "too_large"
    assert not any((tmp_path / "t").iterdir())


def test_zip_unreadable_file_is_skipped(tmp_path, monkeypatch):
    f = tmp_path / "Proj"
    f.mkdir()
    (f / "ok.txt").write_text("ok")
    (f / "locked.txt").write_text("no")
    real_write = zipfile.ZipFile.write

    def write(self, filename, arcname=None, *a, **k):
        if str(filename).endswith("locked.txt"):
            raise PermissionError("locked")
        return real_write(self, filename, arcname, *a, **k)

    monkeypatch.setattr(zipfile.ZipFile, "write", write)
    arc = make_zip(f, tmp_path / "t")
    try:
        assert arc.files == 1 and arc.skipped == ["locked.txt"]
    finally:
        arc.cleanup()


# --- Telegram Bot API client -------------------------------------------------------------------

def _bot(handler, recipients=None):
    return TelegramBot("123:SECRET", recipients or {"Me": 111},
                       client=httpx.Client(transport=httpx.MockTransport(handler)))


def test_bot_sends_document(tmp_path):
    seen = {}
    f = tmp_path / "a.pdf"
    f.write_bytes(b"%PDF")

    def handler(request: httpx.Request):
        seen["url"] = str(request.url)
        seen["body"] = request.read()
        return httpx.Response(200, json={"ok": True, "result": {"message_id": 7}})

    assert _bot(handler).send_document("Me", f) == 7
    assert seen["url"] == "https://api.telegram.org/bot123:SECRET/sendDocument"
    assert b'name="chat_id"\r\n\r\n111' in seen["body"] and b"%PDF" in seen["body"]


@pytest.mark.parametrize("status,desc,kind", [
    (401, "Unauthorized", "bad_token"),
    (403, "Forbidden: bot was blocked by the user", "blocked"),
    (400, "Bad Request: chat not found", "chat_not_found"),
    (413, "Request Entity Too Large", "too_large"),
    (429, "Too Many Requests", "rate_limited"),
    (500, "Internal", "failed"),
])
def test_bot_errors(tmp_path, status, desc, kind):
    f = tmp_path / "a.pdf"
    f.write_bytes(b"x")
    bot = _bot(lambda r: httpx.Response(status, json={"ok": False, "error_code": status, "description": desc}))
    with pytest.raises(TelegramError) as e:
        bot.send_document("Me", f)
    assert e.value.kind == kind
    assert "SECRET" not in e.value.message


def test_bot_network_error_hides_token(tmp_path):
    f = tmp_path / "a.pdf"
    f.write_bytes(b"x")

    def down(request):
        raise httpx.ConnectError(f"cannot connect to {request.url}")

    with pytest.raises(TelegramError) as e:
        _bot(down).send_document("Me", f)
    assert e.value.kind == "unreachable"
    assert "SECRET" not in str(e.value) and e.value.__cause__ is None and e.value.__suppress_context__


def test_bot_rejects_unknown_recipient_and_large_files(tmp_path):
    f = tmp_path / "big.bin"
    with open(f, "wb") as fh:
        fh.truncate(MAX_UPLOAD_BYTES + 1)
    bot = _bot(lambda r: pytest.fail("must not upload"))
    with pytest.raises(TelegramError) as e:
        bot.send_document("Nobody", f)
    assert e.value.kind == "unknown_recipient"
    with pytest.raises(TelegramError) as e:
        bot.send_document("Me", f)
    assert e.value.kind == "too_large"


def test_bot_ok_without_message_id_is_not_success(tmp_path):
    f = tmp_path / "a.pdf"
    f.write_bytes(b"x")
    with pytest.raises(TelegramError):
        _bot(lambda r: httpx.Response(200, json={"ok": True, "result": {}})).send_document("Me", f)


def test_parse_recipients():
    assert parse_recipients("Me=111, Amma=-100222 ,") == {"Me": 111, "Amma": -100222}
    assert parse_recipients(None) == {}
    for bad in ("Me", "Me=abc", "=1", "Me=1;rm=2", "a/b=1"):
        with pytest.raises(ValueError):
            parse_recipients(bad)


def test_recent_chats():
    updates = {"ok": True, "result": [
        {"message": {"chat": {"id": 111, "first_name": "Adithya", "username": "adi"}}},
        {"message": {"chat": {"id": 111, "first_name": "Adithya", "username": "adi"}}},
        {"message": {"chat": {"id": 222, "first_name": "Amma"}}},
    ]}
    client = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(200, json=updates)))
    assert recent_chats("t", client) == [
        {"chat_id": 111, "name": "Adithya", "username": "adi"},
        {"chat_id": 222, "name": "Amma", "username": None},
    ]


def test_confirmed_adapter_covers_all_confirmed_actions():
    from app.models.command import CONFIRMED_ACTIONS
    for action in CONFIRMED_ACTIONS:
        with pytest.raises(ValidationError) as e:
            confirmed_action_adapter.validate_python({"action": action})
        assert "union_tag_invalid" not in json.dumps(e.value.errors(), default=str)
