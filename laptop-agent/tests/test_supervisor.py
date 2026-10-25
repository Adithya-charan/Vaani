from __future__ import annotations

import sys

import pytest

from app import supervisor as sv


def test_usable_hosts_keeps_present_addresses_in_order():
    # 192.0.2.1 (TEST-NET-1) is never assigned locally.
    assert sv.usable_hosts(("192.0.2.1", "127.0.0.1")) == ("127.0.0.1",)
    assert sv.usable_hosts(()) == ()


def test_backoff_grows_and_resets_after_stable_run():
    assert sv.next_backoff(2, ran_for=1) == 4
    assert sv.next_backoff(40, ran_for=1) == sv.MAX_BACKOFF
    assert sv.next_backoff(40, ran_for=sv.STABLE_RUN + 1) == 2


def test_rotate_if_large(tmp_path):
    log = tmp_path / "agent.log"
    log.write_bytes(b"x" * 20)
    sv.rotate_if_large(log, limit=100)
    assert log.exists()
    sv.rotate_if_large(log, limit=10)
    assert not log.exists()
    assert (tmp_path / "agent.log.1").read_bytes() == b"x" * 20


def test_health_url_prefers_loopback():
    assert sv.health_url(("192.168.0.7", "127.0.0.1"), 8765) == "http://127.0.0.1:8765/health"
    assert sv.health_url(("100.106.183.88",), 8765) == "http://100.106.183.88:8765/health"
    assert sv.health_url(("fd7a::1",), 8765) == "http://[fd7a::1]:8765/health"


def test_is_healthy_false_when_nothing_listens():
    assert sv.is_healthy("http://127.0.0.1:9/health", timeout=1) is False


@pytest.mark.skipif(sys.platform != "win32", reason="msvcrt file locks")
def test_single_instance(tmp_path):
    first = sv.SingleInstance(tmp_path / "s.lock")
    assert first.acquire() is True
    assert sv.SingleInstance(tmp_path / "s.lock").acquire() is False
