"""Agent configuration, read from environment variables (and a local .env file)."""

from __future__ import annotations

import ipaddress
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path

from dotenv import load_dotenv

DEFAULT_FOLDERS = ("Documents", "Desktop", "Downloads")
MIN_TOKEN_LENGTH = 32


class ConfigError(Exception):
    pass


# Home networks, Tailscale (IPv4 CGNAT range and IPv6 ULA) and loopback.
DEFAULT_ALLOWED_CLIENTS = (
    "127.0.0.0/8", "::1/128",
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
    "100.64.0.0/10", "fd7a:115c:a1e0::/48",
)


def parse_networks(raw: str | None) -> tuple[ipaddress.IPv4Network | ipaddress.IPv6Network, ...]:
    entries = [e.strip() for e in raw.split(",") if e.strip()] if raw else list(DEFAULT_ALLOWED_CLIENTS)
    try:
        return tuple(ipaddress.ip_network(e, strict=False) for e in entries)
    except ValueError as exc:
        raise ConfigError(f"Invalid VAANI_ALLOWED_CLIENTS entry: {exc}") from exc


def parse_hosts(raw: str | None) -> tuple[str, ...]:
    hosts = tuple(h.strip() for h in (raw or "127.0.0.1").split(",") if h.strip())
    for h in hosts:
        try:
            ipaddress.ip_address(h)
        except ValueError as exc:
            raise ConfigError(f"VAANI_HOST entries must be IP addresses, got {h!r}") from exc
    return hosts


@dataclass(frozen=True)
class Settings:
    auth_token: str
    approved_folders: dict[str, Path] = field(default_factory=dict)
    hosts: tuple[str, ...] = ("127.0.0.1",)
    port: int = 8765
    allowed_clients: tuple[ipaddress.IPv4Network | ipaddress.IPv6Network, ...] = field(
        default_factory=lambda: parse_networks(None)
    )
    enable_docs: bool = False
    groq_api_key: str | None = None
    groq_model: str = "qwen/qwen3.8-27b"
    sarvam_api_key: str | None = None
    sarvam_stt_model: str = "saaras:v4"
    sarvam_tts_model: str = "bulbul:v4-flash"
    telegram_bot_token: str | None = None
    telegram_recipients: dict[str, int] = field(default_factory=dict)

    def __repr__(self) -> str:  # never leak secrets through repr/logging
        return (
            f"Settings(hosts={list(self.hosts)}, port={self.port}, "
            f"approved_folders={list(self.approved_folders)}, "
            f"groq={'set' if self.groq_api_key else 'unset'}, "
            f"sarvam={'set' if self.sarvam_api_key else 'unset'}, "
            f"telegram={'set' if self.telegram_bot_token else 'unset'}, "
            f"telegram_recipients={list(self.telegram_recipients)})"
        )


# Windows Known Folder IDs, so "Desktop" resolves to wherever Windows (or OneDrive) put it.
_KNOWN_FOLDER_IDS = {
    "desktop": "B4BFCC3A-DB2C-424C-B029-7FE99A87C641",
    "documents": "FDD39AD0-238F-46AF-ADB4-6C85480369C7",
    "downloads": "374DE290-123F-4565-9164-39C4925E467B",
    "pictures": "33E28130-4E1E-4676-835A-98395C3BC3BB",
    "music": "4BD8D571-6D19-48D3-BE97-422220080E43",
    "videos": "18989B1D-99B5-455B-841C-AB7C74E4DDFC",
}


def _known_folder(name: str) -> Path | None:
    folder_id = _KNOWN_FOLDER_IDS.get(name.lower())
    if folder_id is None or sys.platform != "win32":
        return None
    import ctypes
    import uuid

    class GUID(ctypes.Structure):
        _fields_ = [("Data1", ctypes.c_uint32), ("Data2", ctypes.c_uint16),
                    ("Data3", ctypes.c_uint16), ("Data4", ctypes.c_ubyte * 8)]

    u = uuid.UUID(folder_id)
    guid = GUID(u.fields[0], u.fields[1], u.fields[2], (ctypes.c_ubyte * 8).from_buffer_copy(u.bytes[8:]))
    out = ctypes.c_void_p()
    if ctypes.windll.shell32.SHGetKnownFolderPath(ctypes.byref(guid), 0, None, ctypes.byref(out)) != 0:
        return None
    try:
        return Path(ctypes.wstring_at(out.value))
    finally:
        ctypes.windll.ole32.CoTaskMemFree(out)


def _find_user_folder(name: str) -> Path | None:
    known = _known_folder(name)
    if known is not None and known.is_dir():
        return known
    home = Path.home()
    for candidate in (home / name, home / "OneDrive" / name):
        if candidate.is_dir():
            return candidate
    return None


def parse_approved_folders(raw: str | None) -> dict[str, Path]:
    """Parse ``VAANI_APPROVED_FOLDERS``.

    Entries are comma-separated. Each is either a well-known folder name in the
    user's profile (``Documents``) or ``Alias=C:\\absolute\\path``.
    Folders that do not exist are skipped.
    """
    entries = [e.strip() for e in (raw or ",".join(DEFAULT_FOLDERS)).split(",") if e.strip()]
    folders: dict[str, Path] = {}
    for entry in entries:
        if "=" in entry:
            alias, _, path_str = entry.partition("=")
            alias, path = alias.strip(), Path(path_str.strip())
            if not path.is_absolute():
                raise ConfigError(f"Approved folder '{alias}' must be an absolute path.")
            path = path if path.is_dir() else None
        else:
            alias, path = entry, _find_user_folder(entry)
        if not alias.replace("_", "").isalnum():
            raise ConfigError(f"Invalid approved folder alias: {alias!r}")
        if path is not None:
            folders[alias] = path.resolve()
    return folders


def load_settings(env_file: Path | None = None) -> Settings:
    load_dotenv(env_file or Path(__file__).resolve().parent.parent / ".env")
    token = os.getenv("VAANI_AUTH_TOKEN", "")
    if len(token) < MIN_TOKEN_LENGTH:
        raise ConfigError(
            f"VAANI_AUTH_TOKEN must be set to a random value of at least {MIN_TOKEN_LENGTH} characters."
        )
    return Settings(
        auth_token=token,
        approved_folders=parse_approved_folders(os.getenv("VAANI_APPROVED_FOLDERS")),
        hosts=parse_hosts(os.getenv("VAANI_HOST")),
        port=int(os.getenv("VAANI_PORT", "8765")),
        allowed_clients=parse_networks(os.getenv("VAANI_ALLOWED_CLIENTS")),
        enable_docs=os.getenv("VAANI_ENABLE_DOCS", "").lower() in ("1", "true", "yes"),
        groq_api_key=os.getenv("GROQ_API_KEY") or None,
        groq_model=os.getenv("GROQ_MODEL", "qwen/qwen3.8-27b"),
        sarvam_api_key=os.getenv("SARVAM_API_KEY") or None,
        sarvam_stt_model=os.getenv("SARVAM_STT_MODEL", "saaras:v4"),
        sarvam_tts_model=os.getenv("SARVAM_TTS_MODEL", "bulbul:v4-flash"),
        telegram_bot_token=os.getenv("TELEGRAM_BOT_TOKEN") or None,
        telegram_recipients=_recipients(os.getenv("TELEGRAM_RECIPIENTS")),
    )


def _recipients(raw: str | None) -> dict[str, int]:
    from app.services.telegram import parse_recipients

    try:
        return parse_recipients(raw)
    except ValueError as exc:
        raise ConfigError(str(exc)) from exc
