"""Types a confirmed prompt into ChatGPT in Vaani's own Edge window and verifies it was sent.

Vaani never sees or stores the user's password: the user signs in to ChatGPT once, themselves,
in this separate browser profile (``%LOCALAPPDATA%\\Vaani\\chatgpt-profile``). The profile keeps
that sign-in like any browser would; Vaani does not read its cookies.

ChatGPT changes its page often, so selectors are tried in order and a missing composer is
reported as "the page changed" instead of guessing.
"""

from __future__ import annotations

import os
import queue
import threading
from concurrent.futures import Future
from pathlib import Path
from typing import Protocol

CHATGPT_URL = "https://chatgpt.com/"
PROFILE_DIR = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "Vaani" / "chatgpt-profile"

COMPOSER_SELECTORS = (
    "#prompt-textarea",
    "textarea[aria-label='Chat with ChatGPT']",
    "#mobile-composer-prompt",
    "div[contenteditable='true'][role='textbox']",
)
SEND_SELECTORS = ("[data-testid='send-button']", "button[aria-label^='Send']")
USER_MESSAGE_SELECTOR = "[data-message-author-role='user']"
LOGIN_SELECTORS = ("[data-testid='login-button']", "button:has-text('Log in')")


class ChatGPTError(Exception):
    """``kind`` is one of: needs_login, page_changed, not_sent, timeout, unavailable, busy."""

    def __init__(self, kind: str, message: str) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message


class ChatGPTDriver(Protocol):
    def submit(self, prompt: str) -> None:
        """Returns only once the prompt is visible as sent; raises ChatGPTError otherwise."""


def _normalise(text: str) -> str:
    return " ".join(text.split())


class PlaywrightChatGPT:
    """Owns one Edge window. Playwright's sync objects must stay on one thread, so every
    request is handed to a dedicated worker thread."""

    def __init__(self, profile_dir: Path = PROFILE_DIR, channel: str = "msedge",
                 load_timeout_s: float = 30, verify_timeout_s: float = 20) -> None:
        self.profile_dir = profile_dir
        self.channel = channel
        self.load_timeout_ms = int(load_timeout_s * 1000)
        self.verify_timeout_ms = int(verify_timeout_s * 1000)
        self._jobs: queue.Queue[tuple[str, Future]] = queue.Queue()
        self._busy = threading.Lock()
        self._thread: threading.Thread | None = None
        self._pw = None
        self._ctx = None

    # --- public, called from the agent's request threads ------------------------------

    def submit(self, prompt: str) -> None:
        if not self._busy.acquire(blocking=False):
            raise ChatGPTError("busy", "Vaani is already sending a prompt to ChatGPT. Try again in a moment.")
        try:
            self._start_worker()
            done: Future = Future()
            self._jobs.put((prompt, done))
            timeout = (self.load_timeout_ms + self.verify_timeout_ms) / 1000 + 15
            try:
                done.result(timeout=timeout)
            except TimeoutError as exc:
                raise ChatGPTError("timeout", "ChatGPT took too long. Check the browser window on your laptop.") from exc
        finally:
            self._busy.release()

    # --- worker thread -------------------------------------------------------------------

    def _start_worker(self) -> None:
        if self._thread is None or not self._thread.is_alive():
            self._thread = threading.Thread(target=self._run, name="vaani-chatgpt", daemon=True)
            self._thread.start()

    def _run(self) -> None:
        while True:
            prompt, done = self._jobs.get()
            try:
                try:
                    self._submit(prompt)
                except ChatGPTError:
                    raise
                except Exception:  # noqa: BLE001 - the user may have closed the window; retry once fresh
                    self._close()
                    self._submit(prompt)
                done.set_result(None)
            except ChatGPTError as exc:
                done.set_exception(exc)
            except Exception as exc:  # noqa: BLE001
                self._close()
                done.set_exception(ChatGPTError("unavailable", f"Couldn't control the browser ({type(exc).__name__})."))

    def _page(self):
        if self._ctx is None:
            from playwright.sync_api import sync_playwright

            self.profile_dir.mkdir(parents=True, exist_ok=True)
            self._pw = sync_playwright().start()
            self._ctx = self._pw.chromium.launch_persistent_context(
                str(self.profile_dir), channel=self.channel, headless=False, no_viewport=True,
                args=["--start-maximized"],
            )
        pages = [p for p in self._ctx.pages if not p.is_closed()]
        return pages[0] if pages else self._ctx.new_page()

    def _close(self) -> None:
        for closer in (lambda: self._ctx and self._ctx.close(), lambda: self._pw and self._pw.stop()):
            try:
                closer()
            except Exception:  # noqa: BLE001
                pass
        self._ctx = None
        self._pw = None

    def _find_first(self, page, selectors, timeout_ms: int):
        """First visible element among selectors, polling until timeout."""
        step = 500
        waited = 0
        while waited <= timeout_ms:
            for sel in selectors:
                loc = page.locator(sel)
                for i in range(min(loc.count(), 5)):
                    el = loc.nth(i)
                    if el.is_visible():
                        return el
            page.wait_for_timeout(step)
            waited += step
        return None

    def _submit(self, prompt: str) -> None:
        page = self._page()
        page.bring_to_front()
        page.goto(CHATGPT_URL, wait_until="domcontentloaded", timeout=self.load_timeout_ms)  # new chat
        composer = self._find_first(page, COMPOSER_SELECTORS, self.load_timeout_ms)
        if composer is None:
            if self._find_first(page, LOGIN_SELECTORS, 0) is not None:
                raise ChatGPTError("needs_login", "Sign in to ChatGPT in the Vaani browser window on your laptop, then try again.")
            raise ChatGPTError("page_changed", "ChatGPT's page changed and Vaani couldn't find the message box.")

        before = page.locator(USER_MESSAGE_SELECTOR).count()
        url_before = page.url
        composer.click()
        composer.fill(prompt)
        send = self._find_first(page, SEND_SELECTORS, 3000)
        if send is not None and send.is_enabled():
            send.click()
        else:
            composer.press("Enter")

        head = _normalise(prompt)[:60]
        waited = 0
        while waited <= self.verify_timeout_ms:
            if self._sent(page, composer, head, before, url_before):
                return
            page.wait_for_timeout(500)
            waited += 500
        if self._find_first(page, LOGIN_SELECTORS, 0) is not None:
            raise ChatGPTError("needs_login", "ChatGPT asked you to sign in. Sign in in the Vaani browser window on your laptop.")
        raise ChatGPTError("not_sent", "Vaani typed the prompt but couldn't confirm ChatGPT sent it. Check the browser window.")

    @staticmethod
    def _sent(page, composer, head: str, before: int, url_before: str) -> bool:
        """Evidence the prompt left the composer and became part of a conversation.
        Signed-in pages mark user messages; the signed-out page doesn't, so also accept the
        composer emptying while the prompt shows on the page or a conversation URL opens."""
        msgs = page.locator(USER_MESSAGE_SELECTOR)
        n = msgs.count()
        if n > before and head in _normalise(msgs.nth(n - 1).inner_text()):
            return True
        try:
            remaining = composer.input_value() if composer.evaluate("e => e.tagName") == "TEXTAREA" else composer.inner_text()
        except Exception:  # noqa: BLE001 - composer re-rendered; fall through to page checks
            remaining = ""
        if _normalise(remaining):
            return False  # still in the box: not sent
        shown = head in _normalise(page.evaluate("() => document.body.innerText"))
        return shown or page.url.rstrip("/") != url_before.rstrip("/")


def default_driver() -> ChatGPTDriver | None:
    try:
        import playwright  # noqa: F401
    except ImportError:
        return None
    return PlaywrightChatGPT()
