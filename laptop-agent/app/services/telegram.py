"""Sends a confirmed file to an allowlisted Telegram chat through the user's own bot.

The bot token stays in the laptop's .env. It is part of every API URL, so request errors are
never logged or returned with their URL. Recipients are configured aliases; the phone can
only pick one of those names, never type a chat id.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Protocol

import httpx

API = "https://api.telegram.org/bot{token}/{method}"
MAX_UPLOAD_BYTES = 50 * 1024 * 1024  # Bot API limit for sendDocument uploads


class TelegramError(Exception):
    """``kind``: unknown_recipient, too_large, bad_token, blocked, chat_not_found, rate_limited,
    unreachable, timeout, failed."""

    def __init__(self, kind: str, message: str) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message


class TelegramSender(Protocol):
    recipients: dict[str, int]

    def send_document(self, recipient: str, path: Path, filename: str | None = None) -> int:
        """Returns Telegram's message_id once the upload is accepted; raises TelegramError."""


def parse_recipients(raw: str | None) -> dict[str, int]:
    """``"Me=123,Amma=-100456"`` -> ``{"Me": 123, "Amma": -100456}``."""
    recipients: dict[str, int] = {}
    for entry in (raw or "").split(","):
        entry = entry.strip()
        if not entry:
            continue
        name, _, chat = entry.partition("=")
        name, chat = name.strip(), chat.strip()
        if not re.fullmatch(r"[\w .\-]{1,30}", name) or not re.fullmatch(r"-?\d{1,20}", chat):
            raise ValueError(f"Invalid TELEGRAM_RECIPIENTS entry {name!r}: use Name=numeric_chat_id")
        recipients[name] = int(chat)
    return recipients


class TelegramBot:
    def __init__(self, token: str, recipients: dict[str, int], client: httpx.Client | None = None,
                 timeout: float = 150.0) -> None:
        self._token = token
        self.recipients = dict(recipients)
        self._client = client
        self._timeout = timeout

    def _url(self, method: str) -> str:
        return API.format(token=self._token, method=method)

    def _post(self, method: str, **kwargs) -> httpx.Response:
        try:
            if self._client is not None:
                return self._client.post(self._url(method), **kwargs)
            with httpx.Client(timeout=self._timeout) as client:
                return client.post(self._url(method), **kwargs)
        except httpx.TimeoutException:
            raise TelegramError("timeout", "Telegram took too long. The file may not have been sent.") from None
        except httpx.HTTPError:
            # `from None`: the original exception's text can contain the URL, i.e. the token.
            raise TelegramError("unreachable", "The laptop couldn't reach Telegram. Check its internet connection.") from None

    def send_document(self, recipient: str, path: Path, filename: str | None = None) -> int:
        chat_id = self.recipients.get(recipient)
        if chat_id is None:
            raise TelegramError("unknown_recipient", f"'{recipient}' is not one of your Telegram recipients.")
        size = path.stat().st_size
        if size > MAX_UPLOAD_BYTES:
            raise TelegramError("too_large", "Telegram bots can send files up to 50 MB.")
        with open(path, "rb") as fh:
            resp = self._post("sendDocument", data={"chat_id": str(chat_id)},
                              files={"document": (filename or path.name, fh, "application/octet-stream")})
        try:
            body = resp.json()
        except ValueError:
            raise TelegramError("failed", f"Telegram returned an unexpected reply (HTTP {resp.status_code}).") from None
        if body.get("ok") is True and isinstance(body.get("result"), dict) and "message_id" in body["result"]:
            return int(body["result"]["message_id"])
        raise _error_for(resp.status_code, str(body.get("description", "")), recipient)


def _error_for(status: int, description: str, recipient: str) -> TelegramError:
    d = description.lower()
    if status == 401:
        return TelegramError("bad_token", "Telegram rejected the bot token in the laptop's .env.")
    if status == 403:
        return TelegramError("blocked", f"{recipient} hasn't started your bot yet, or has blocked it.")
    if status == 400 and "chat not found" in d:
        return TelegramError("chat_not_found", f"Telegram doesn't know the chat id configured for {recipient}.")
    if status == 413 or "too big" in d:
        return TelegramError("too_large", "Telegram bots can send files up to 50 MB.")
    if status == 429:
        return TelegramError("rate_limited", "Telegram is limiting messages right now. Try again shortly.")
    return TelegramError("failed", f"Telegram didn't accept the file (HTTP {status}).")


def recent_chats(token: str, client: httpx.Client | None = None) -> list[dict]:
    """Chats that recently messaged the bot, to find chat ids for TELEGRAM_RECIPIENTS."""
    bot = TelegramBot(token, {}, client)
    resp = bot._post("getUpdates", data={"limit": "100"})
    body = resp.json()
    if not body.get("ok"):
        raise _error_for(resp.status_code, str(body.get("description", "")), "the bot")
    chats: dict[int, dict] = {}
    for update in body.get("result", []):
        msg = update.get("message") or update.get("my_chat_member") or {}
        chat = msg.get("chat") or {}
        if "id" in chat:
            name = chat.get("title") or " ".join(filter(None, [chat.get("first_name"), chat.get("last_name")]))
            chats[chat["id"]] = {"chat_id": chat["id"], "name": name or chat.get("username") or "?",
                                 "username": chat.get("username")}
    return list(chats.values())
