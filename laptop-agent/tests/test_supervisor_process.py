"""Starts a real agent process through the supervisor's launch path and checks that
stopping it frees the port (the venv launcher + interpreter tree is fully killed)."""

from __future__ import annotations

import os
import socket
import subprocess
import sys
import time

import pytest

from app import supervisor as sv

PORT = 8799


def _port_free(port: int) -> bool:
    with socket.socket() as s:
        try:
            s.bind(("127.0.0.1", port))
        except OSError:
            return False
    return True


@pytest.mark.skipif(sys.platform != "win32", reason="Windows process tree")
def test_kill_tree_frees_the_port():
    if not _port_free(PORT):
        pytest.skip(f"port {PORT} busy")
    env = {**os.environ, "VAANI_HOST": "127.0.0.1", "VAANI_PORT": str(PORT),
           "GROQ_API_KEY": "", "SARVAM_API_KEY": ""}
    child = subprocess.Popen([sv._python_for_child(), "-m", "app"], cwd=sv.AGENT_DIR, env=env,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline and not sv.is_healthy(f"http://127.0.0.1:{PORT}/health", timeout=1):
            time.sleep(0.3)
        assert sv.is_healthy(f"http://127.0.0.1:{PORT}/health")
    finally:
        sv.kill_tree(child)
    time.sleep(1)
    assert _port_free(PORT), "agent still holding the port after kill_tree"
