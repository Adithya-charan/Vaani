"""Packages one approved folder into a temporary ZIP for sending.

Only regular files inside the selected folder are included: links and junctions are skipped
(never followed), every file is re-checked to resolve inside the folder, and size and count
are capped before and after compression.
"""

from __future__ import annotations

import os
import shutil
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

TEMP_ROOT = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "Vaani" / "tmp"
MAX_FILES = 5_000
MAX_INPUT_BYTES = 500 * 1024 * 1024


class ZipError(Exception):
    def __init__(self, kind: str, message: str) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message


@dataclass
class Archive:
    path: Path
    size: int
    files: int
    skipped: list[str] = field(default_factory=list)  # relative paths not included

    def cleanup(self) -> str | None:
        """Deletes the temporary archive; returns an error description instead of raising."""
        try:
            shutil.rmtree(self.path.parent)
        except OSError as exc:
            return type(exc).__name__
        return None


def _is_link(path: Path) -> bool:
    try:
        return path.is_symlink() or path.is_junction()
    except OSError:
        return True


def make_zip(folder: Path, temp_root: Path = TEMP_ROOT, max_files: int = MAX_FILES,
             max_input_bytes: int = MAX_INPUT_BYTES, max_zip_bytes: int | None = None) -> Archive:
    folder = folder.resolve(strict=True)
    if not folder.is_dir():
        raise ZipError("not_a_folder", "Only folders can be sent as a ZIP.")

    files: list[tuple[Path, str]] = []
    skipped: list[str] = []
    total = 0
    for dirpath, dirnames, filenames in os.walk(folder, followlinks=False):
        here = Path(dirpath)
        # Don't descend into links/junctions.
        for d in list(dirnames):
            if _is_link(here / d):
                skipped.append((here / d).relative_to(folder).as_posix() + "/")
                dirnames.remove(d)
        for name in filenames:
            p = here / name
            rel = p.relative_to(folder).as_posix()
            if _is_link(p):
                skipped.append(rel)
                continue
            try:
                resolved = p.resolve(strict=True)
                size = resolved.stat().st_size
            except OSError:
                skipped.append(rel)
                continue
            if not resolved.is_relative_to(folder) or not resolved.is_file():
                skipped.append(rel)
                continue
            files.append((resolved, rel))
            total += size
            if len(files) > max_files:
                raise ZipError("too_many_files", f"That folder has more than {max_files} files.")
            if total > max_input_bytes:
                raise ZipError("too_large", f"That folder is larger than {max_input_bytes // (1024 * 1024)} MB.")

    if not files:
        raise ZipError("empty", "That folder has no files that can be sent.")

    temp_root.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(temp_root).free < total + 50 * 1024 * 1024:
        raise ZipError("no_space", "Not enough free disk space on the laptop to build the ZIP.")

    work = Path(tempfile.mkdtemp(prefix="zip-", dir=temp_root))
    target = work / f"{folder.name}.zip"
    archive = Archive(target, 0, 0, skipped)
    try:
        with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            for path, rel in files:
                try:
                    zf.write(path, arcname=f"{folder.name}/{rel}")
                    archive.files += 1
                except OSError:  # unreadable or locked
                    skipped.append(rel)
        if archive.files == 0:
            raise ZipError("empty", "None of the files in that folder could be read.")
        archive.size = target.stat().st_size
        if max_zip_bytes is not None and archive.size > max_zip_bytes:
            raise ZipError("too_large", f"The ZIP is larger than {max_zip_bytes // (1024 * 1024)} MB.")
    except (ZipError, OSError, zipfile.BadZipFile) as exc:
        archive.cleanup()
        if isinstance(exc, ZipError):
            raise
        raise ZipError("archive_failed", "Couldn't build the ZIP on the laptop.") from None
    return archive
