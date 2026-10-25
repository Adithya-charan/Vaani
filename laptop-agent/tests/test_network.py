from __future__ import annotations

import logging
import socket

import pytest

from app.config import ConfigError, parse_hosts, parse_networks
from app.main import bind_sockets
from tests.conftest import AUTH


@pytest.mark.parametrize("ip", ["192.168.0.5", "10.1.2.3", "172.20.0.9", "100.101.102.103", "127.0.0.1",
                                "::1", "fd7a:115c:a1e0::1234", "::ffff:192.168.0.5"])
def test_private_and_tailscale_clients_allowed(make_client, ip):
    assert make_client(client_ip=ip).get("/health").status_code == 200


@pytest.mark.parametrize("ip", ["8.8.8.8", "100.128.0.1", "172.32.0.1", "2001:db8::1", "testclient", ""])
def test_other_clients_forbidden_even_with_token(make_client, launcher, ip):
    client = make_client(client_ip=ip)
    assert client.get("/health").status_code == 403
    r = client.post("/commands", json={"command": "open calculator"}, headers=AUTH)
    assert r.status_code == 403
    assert launcher.calls == []


def test_parse_hosts():
    assert parse_hosts(None) == ("127.0.0.1",)
    assert parse_hosts(" 192.168.0.7 , 100.90.1.2 ") == ("192.168.0.7", "100.90.1.2")
    with pytest.raises(ConfigError):
        parse_hosts("my-laptop.local")
    with pytest.raises(ConfigError):
        parse_hosts("0.0.0.0:8765")


def test_parse_networks():
    nets = parse_networks("192.168.0.0/24, 100.64.0.0/10")
    assert [str(n) for n in nets] == ["192.168.0.0/24", "100.64.0.0/10"]
    assert len(parse_networks(None)) == 7
    with pytest.raises(ConfigError):
        parse_networks("not-a-network")


def test_bind_skips_addresses_that_are_not_up():
    log = logging.getLogger("test")
    # 192.0.2.1 is TEST-NET-1: never assigned to a local interface.
    sockets = bind_sockets(("127.0.0.1", "192.0.2.1"), 0, log)
    try:
        assert len(sockets) == 1
        assert sockets[0].getsockname()[0] == "127.0.0.1"
        assert sockets[0].family == socket.AF_INET
    finally:
        for s in sockets:
            s.close()
