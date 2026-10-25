"""FastAPI application factory."""

from __future__ import annotations

import logging

from fastapi import FastAPI

from app.api.commands import router as commands_router
from app.config import Settings, load_settings
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
    return app


def run() -> None:
    import uvicorn

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    settings = load_settings()
    log = logging.getLogger("vaani")
    log.info("Starting with %r", settings)
    if settings.host not in ("127.0.0.1", "localhost", "::1"):
        log.warning("Listening on %s. Make sure this is a private interface (Wi-Fi LAN or Tailscale) "
                    "and that the Windows firewall limits who can reach port %d.", settings.host, settings.port)
    uvicorn.run(create_app(settings), host=settings.host, port=settings.port, log_level="info")
