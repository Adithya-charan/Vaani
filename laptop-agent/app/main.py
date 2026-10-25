"""FastAPI application factory."""

from __future__ import annotations

import logging
import socket

from fastapi import FastAPI

from app.api.commands import router as commands_router
from app.config import Settings, load_settings
from app.security.network import ClientNetworkMiddleware
from app.services.ai_planner import AIPlanner, GroqPlanner
from app.services.command_executor import CommandExecutor, Launcher, default_launcher
from app.services.file_search import FileSearcher


def create_app(
    settings: Settings | None = None,
    launcher: Launcher = default_launcher,
    planner: AIPlanner | None = None,
    use_configured_planner: bool = True,
) -> FastAPI:
    settings = settings or load_settings()
    if planner is None and use_configured_planner and settings.groq_api_key:
        planner = GroqPlanner(settings.groq_api_key, settings.groq_model, list(settings.approved_folders))

    docs = settings.enable_docs
    app = FastAPI(
        title="Vaani laptop agent",
        docs_url="/docs" if docs else None,
        redoc_url=None,
        openapi_url="/openapi.json" if docs else None,
    )
    app.state.settings = settings
    app.state.planner = planner
    app.state.executor = CommandExecutor(
        searcher=FileSearcher(settings.approved_folders),
        launcher=launcher,
        ai_enabled=planner is not None,
    )

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok", "service": "vaani-laptop-agent"}

    app.include_router(commands_router)
    app.add_middleware(ClientNetworkMiddleware, allowed=settings.allowed_clients)
    return app


def bind_sockets(hosts: tuple[str, ...], port: int, log: logging.Logger) -> list[socket.socket]:
    """Bind one listening socket per address. Addresses that are not up (e.g. Tailscale
    disconnected, laptop on another Wi-Fi) are skipped instead of stopping the agent."""
    sockets = []
    for host in hosts:
        family = socket.AF_INET6 if ":" in host else socket.AF_INET
        sock = socket.socket(family, socket.SOCK_STREAM)
        try:
            sock.bind((host, port))
        except OSError as exc:
            sock.close()
            log.warning("Not listening on %s:%d (%s). Is that network connected?", host, port, exc.strerror or exc)
            continue
        sockets.append(sock)
        log.info("Listening on %s:%d", host, port)
    return sockets


def run() -> None:
    import uvicorn

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    settings = load_settings()
    log = logging.getLogger("vaani")
    log.info("Starting with %r", settings)
    sockets = bind_sockets(settings.hosts, settings.port, log)
    if not sockets:
        raise SystemExit("Vaani agent could not listen on any address in VAANI_HOST.")
    server = uvicorn.Server(uvicorn.Config(create_app(settings), log_level="info"))
    server.run(sockets=sockets)
