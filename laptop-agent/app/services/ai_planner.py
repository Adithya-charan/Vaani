"""AI planner interface and the Groq implementation.

The planner only *suggests* a plan. Its output is untrusted: it is validated
against the strict plan schema and the approved-folder list before execution.
"""

from __future__ import annotations

import json
import re
from typing import Any, Protocol

import httpx

GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"


class PlannerError(Exception):
    """The AI provider could not produce a usable plan."""


class AIPlanner(Protocol):
    name: str

    async def plan(self, text: str) -> dict[str, Any]: ...


def build_system_prompt(folders: list[str]) -> str:
    folder_list = ", ".join(folders) or "(none)"
    return f"""You translate a user's request into ONE action for Vaani, a laptop assistant.
Reply with a single JSON object and nothing else. Allowed objects:
{{"action":"open_calculator"}}
{{"action":"open_notepad"}}
{{"action":"open_explorer"}}
{{"action":"get_status"}}
{{"action":"find_file","query":"<words in the file or folder name>","folder":<one of [{folder_list}] or null>,"extension":<e.g. "pdf" or null>,"newest_first":<true|false>}}
{{"action":"clarify","question":"<short question>"}}
Rules:
- Use only these actions and keys. Never invent other actions or keys.
- "query" holds name keywords only (no paths, no slashes). It may be "" if "extension" is set.
- Use "newest_first": true for words like latest, newest, recent.
- If the request is ambiguous, use "clarify".
- If the request asks for anything else (deleting, sending, running commands, browsing, etc.),
  reply {{"action":"clarify","question":"I can only open Calculator, Notepad or File Explorer, find files, or report status. What would you like?"}}"""


def _extract_json(content: str) -> dict[str, Any]:
    content = re.sub(r"<think>.*?</think>", "", content, flags=re.DOTALL).strip()
    content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content).strip()
    try:
        data = json.loads(content)
    except json.JSONDecodeError as exc:
        raise PlannerError("AI planner returned invalid JSON.") from exc
    if not isinstance(data, dict):
        raise PlannerError("AI planner returned a non-object.")
    return data


class GroqPlanner:
    name = "groq"

    def __init__(
        self,
        api_key: str,
        model: str,
        folders: list[str],
        client: httpx.AsyncClient | None = None,
        timeout: float = 15.0,
    ) -> None:
        self._api_key = api_key
        self.model = model
        self._system_prompt = build_system_prompt(folders)
        self._client = client
        self._timeout = timeout

    async def plan(self, text: str) -> dict[str, Any]:
        body = {
            "model": self.model,
            "temperature": 0,
            "max_completion_tokens": 300,
            "response_format": {"type": "json_object"},
            "reasoning_effort": "none",
            "messages": [
                {"role": "system", "content": self._system_prompt},
                {"role": "user", "content": text},
            ],
        }
        headers = {"Authorization": f"Bearer {self._api_key}"}
        try:
            if self._client is not None:
                resp = await self._client.post(GROQ_URL, json=body, headers=headers)
            else:
                async with httpx.AsyncClient(timeout=self._timeout) as client:
                    resp = await client.post(GROQ_URL, json=body, headers=headers)
        except httpx.HTTPError as exc:
            raise PlannerError("AI planner is unreachable.") from exc
        if resp.status_code == 429:
            raise PlannerError("AI planner is rate-limited.")
        if resp.status_code in (401, 403):
            raise PlannerError("AI planner rejected the API key.")
        if resp.status_code >= 400:
            raise PlannerError(f"AI planner failed (HTTP {resp.status_code}).")
        try:
            content = resp.json()["choices"][0]["message"]["content"] or ""
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise PlannerError("AI planner returned an unexpected response.") from exc
        return _extract_json(content)
