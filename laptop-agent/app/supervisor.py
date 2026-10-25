"""Background supervisor for the Vaani agent (``pythonw -m app.supervisor``).

Started at Windows login. It keeps the agent running and in step with the network:
- restarts the agent if it exits or stops answering /health
- restarts it when the set of usable VAANI_HOST addresses changes, e.g. Tailscale
  connecting after login, Wi-Fi changing, or the laptop waking from sleep
  (a listening socket on an address that went away does not come back by itself)
- only one supervisor runs at a time
"""

from __future__ import annotations

import logging
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from logging.handlers import RotatingFileHandler
from pathlib import Path

from app.config import ConfigError, load_settings

AGENT_DIR = Path(__file__).resolve().parent.parent
LOG_DIR = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "Vaani" / "logs"
CHECK_EVERY = 10.0  # seconds between network checks
HEALTH_EVERY = 30.0  # seconds between health checks
HEALTH_FAILURES_BEFORE_RESTART = 3
MAX_BACKOFF = 60.0
STABLE_RUN = 60.0  # a run this long resets the crash backoff
MAX_AGENT_LOG = 5 * 1024 * 1024

log = logging.getLogger("vaani.supervisor")


def usable_hosts(hosts: tuple[str, ...]) -> tuple[str, ...]:
    """Addresses currently assigned to this machine (binding to port 0 succeeds)."""
    usable = []
    for host in hosts:
        family = socket.AF_INET6 if ":" in host else socket.AF_INET
        with socket.socket(family, socket.SOCK_STREAM) as s:
            try:
                s.bind((host, 0))
            except OSError:
                continue
        usable.append(host)
    return tuple(usable)


def next_backoff(current: float, ran_for: float) -> float:
    if ran_for >= STABLE_RUN:
        return 2.0
    return min(current * 2, MAX_BACKOFF)


def rotate_if_large(path: Path, limit: int = MAX_AGENT_LOG) -> None:
    if path.exists() and path.stat().st_size > limit:
        backup = path.with_suffix(path.suffix + ".1")
        backup.unlink(missing_ok=True)
        path.rename(backup)


def health_url(hosts: tuple[str, ...], port: int) -> str:
    host = "127.0.0.1" if "127.0.0.1" in hosts else hosts[0]
    if ":" in host:
        host = f"[{host}]"
    return f"http://{host}:{port}/health"


def is_healthy(url: str, timeout: float = 5.0) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:  # noqa: S310 - local agent only
            return resp.status == 200
    except (urllib.error.URLError, OSError):
        return False


class SingleInstance:
    """Holds an exclusive lock file so a second supervisor exits immediately."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self._fh = None

    def acquire(self) -> bool:
        import msvcrt

        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._fh = open(self.path, "a+")
        try:
            msvcrt.locking(self._fh.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            self._fh.close()
            self._fh = None
            return False
        return True


def _python_for_child() -> str:
    # pythonw.exe has no console; the child needs real stdout/stderr for uvicorn's logs.
    exe = Path(sys.executable)
    console = exe.with_name("python.exe")
    return str(console if console.exists() else exe)


def kill_tree(child: subprocess.Popen) -> None:
    """Stops the agent and its children. A venv's python.exe is a launcher that starts the
    real interpreter as a child, so terminating only the launcher would leave the agent
    running and holding the port."""
    if sys.platform == "win32":
        subprocess.run(["taskkill", "/PID", str(child.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    else:
        child.terminate()
    try:
        child.wait(10)
    except subprocess.TimeoutExpired:
        child.kill()


def run_agent_once(hosts_at_start: tuple[str, ...], all_hosts: tuple[str, ...], port: int) -> str:
    """Runs the agent until it exits or must be restarted. Returns the reason."""
    agent_log = LOG_DIR / "agent.log"
    rotate_if_large(agent_log)
    with open(agent_log, "ab") as out:
        child = subprocess.Popen(
            [_python_for_child(), "-m", "app"],
            cwd=AGENT_DIR,
            stdin=subprocess.DEVNULL,
            stdout=out,
            stderr=subprocess.STDOUT,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        log.info("agent started (pid %s) on %s", child.pid, ", ".join(hosts_at_start))
        url = health_url(hosts_at_start, port)
        last_health = time.monotonic()
        failures = 0
        try:
            while child.poll() is None:
                time.sleep(CHECK_EVERY)
                now_hosts = usable_hosts(all_hosts)
                if now_hosts != hosts_at_start:
                    log.info("network changed: %s -> %s", list(hosts_at_start), list(now_hosts))
                    return "network-changed"
                if time.monotonic() - last_health >= HEALTH_EVERY:
                    last_health = time.monotonic()
                    if is_healthy(url):
                        failures = 0
                    else:
                        failures += 1
                        log.warning("health check failed (%d/%d)", failures, HEALTH_FAILURES_BEFORE_RESTART)
                        if failures >= HEALTH_FAILURES_BEFORE_RESTART:
                            return "unhealthy"
            return f"exited with code {child.returncode}"
        finally:
            if child.poll() is None:
                kill_tree(child)


def main() -> None:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    handler = RotatingFileHandler(LOG_DIR / "supervisor.log", maxBytes=1_000_000, backupCount=2, encoding="utf-8")
    logging.basicConfig(level=logging.INFO, handlers=[handler],
                        format="%(asctime)s %(name)s %(levelname)s %(message)s")

    lock = SingleInstance(LOG_DIR.parent / "supervisor.lock")
    if not lock.acquire():
        log.info("another supervisor is already running; exiting")
        return
    try:
        settings = load_settings()
    except ConfigError as exc:
        log.error("cannot start: %s", exc)
        return

    log.info("supervisor started; configured addresses: %s", list(settings.hosts))
    backoff = 2.0
    while True:
        hosts = usable_hosts(settings.hosts)
        if not hosts:
            log.info("no configured address is available yet; waiting")
            time.sleep(15)
            continue
        started = time.monotonic()
        reason = run_agent_once(hosts, settings.hosts, settings.port)
        ran_for = time.monotonic() - started
        if reason == "network-changed":
            backoff = 2.0
            time.sleep(1)  # let the new address settle
            continue
        backoff = next_backoff(backoff, ran_for)
        log.warning("agent stopped (%s) after %.0fs; restarting in %.0fs", reason, ran_for, backoff)
        time.sleep(backoff)


if __name__ == "__main__":
    main()
