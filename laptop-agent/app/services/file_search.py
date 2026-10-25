"""File search restricted to approved root folders.

Only names and safe metadata are returned, never file contents. Symlinks and
junctions are not followed, so the walk cannot escape an approved root.
"""

from __future__ import annotations

import os
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SKIP_DIRS = {"node_modules", "__pycache__", "$recycle.bin", "appdata"}


class FolderNotApproved(Exception):
    pass


def _squash(text: str) -> str:
    return re.sub(r"[\s_\-.]+", "", text.lower())


class FileSearcher:
    def __init__(
        self,
        roots: dict[str, Path],
        max_results: int = 20,
        max_depth: int = 6,
        max_scanned: int = 50_000,
        time_limit: float = 5.0,
    ) -> None:
        self.roots = {alias: Path(p).resolve() for alias, p in roots.items()}
        self.max_results = max_results
        self.max_depth = max_depth
        self.max_scanned = max_scanned
        self.time_limit = time_limit

    def resolve_roots(self, folder: str | None) -> dict[str, Path]:
        if folder is None:
            return self.roots
        for alias, path in self.roots.items():
            if alias.lower() == folder.lower():
                return {alias: path}
        raise FolderNotApproved(folder)

    def search(
        self,
        query: str,
        folder: str | None = None,
        extension: str | None = None,
        newest_first: bool = False,
    ) -> dict[str, Any]:
        roots = self.resolve_roots(folder)
        tokens = [_squash(t) for t in query.split() if _squash(t)]
        ext = f".{extension.lower()}" if extension else None
        deadline = time.monotonic() + self.time_limit
        matches: list[dict[str, Any]] = []
        scanned = 0
        truncated = False

        for alias, root in roots.items():
            stack: list[tuple[Path, int]] = [(root, 0)]
            while stack:
                directory, depth = stack.pop()
                try:
                    with os.scandir(directory) as it:
                        entries = list(it)
                except OSError:
                    continue
                for entry in entries:
                    scanned += 1
                    if scanned > self.max_scanned or time.monotonic() > deadline:
                        truncated = True
                        break
                    if entry.name.startswith(".") or entry.is_symlink() or entry.is_junction():
                        continue
                    try:
                        is_dir = entry.is_dir(follow_symlinks=False)
                    except OSError:
                        continue
                    if is_dir and depth + 1 < self.max_depth and entry.name.lower() not in SKIP_DIRS:
                        stack.append((Path(entry.path), depth + 1))
                    if self._matches(entry.name, is_dir, tokens, ext):
                        result = self._describe(entry, alias, root, is_dir)
                        if result:
                            matches.append(result)
                if truncated:
                    break
            if truncated:
                break

        if newest_first:
            matches.sort(key=lambda m: m["modified"], reverse=True)
        else:
            matches.sort(key=lambda m: (len(m["name"]), m["path"].lower()))
        return {
            "results": matches[: self.max_results],
            "total_matches": len(matches),
            "truncated": truncated or len(matches) > self.max_results,
            "searched": list(roots),
        }

    @staticmethod
    def _matches(name: str, is_dir: bool, tokens: list[str], ext: str | None) -> bool:
        if ext and (is_dir or not name.lower().endswith(ext)):
            return False
        squashed = _squash(name)
        return all(t in squashed for t in tokens)

    @staticmethod
    def _describe(entry: os.DirEntry[str], alias: str, root: Path, is_dir: bool) -> dict[str, Any] | None:
        path = Path(entry.path).resolve()
        if not path.is_relative_to(root):  # defence in depth
            return None
        try:
            stat = entry.stat(follow_symlinks=False)
        except OSError:
            return None
        return {
            "name": entry.name,
            "path": f"{alias}/{path.relative_to(root).as_posix()}",
            "type": "folder" if is_dir else "file",
            "size": None if is_dir else stat.st_size,
            "modified": datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc).isoformat(timespec="seconds"),
        }
