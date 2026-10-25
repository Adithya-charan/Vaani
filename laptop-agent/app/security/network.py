"""Reject requests from outside the allowed client networks, before auth runs.

Defence in depth: even if the agent is reachable from an unexpected network
(e.g. a public Wi-Fi with a broad firewall rule), only home-network and
Tailscale peers get any response.
"""

from __future__ import annotations

import ipaddress
import logging
from typing import Iterable

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

log = logging.getLogger("vaani.security")


class ClientNetworkMiddleware:
    def __init__(self, app: ASGIApp, allowed: Iterable[ipaddress.IPv4Network | ipaddress.IPv6Network]) -> None:
        self.app = app
        self.allowed = tuple(allowed)

    def _is_allowed(self, host: str | None) -> bool:
        if not host:
            return False
        try:
            addr = ipaddress.ip_address(host)
        except ValueError:
            return False
        if isinstance(addr, ipaddress.IPv6Address) and addr.ipv4_mapped:
            addr = addr.ipv4_mapped
        return any(addr in net for net in self.allowed if net.version == addr.version)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] in ("http", "websocket"):
            client = scope.get("client")
            host = client[0] if client else None
            if not self._is_allowed(host):
                log.warning("rejected request from non-allowed network %s", host)
                await JSONResponse({"detail": "Forbidden."}, status_code=403)(scope, receive, send)
                return
        await self.app(scope, receive, send)
