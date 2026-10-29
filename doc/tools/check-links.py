#!/usr/bin/env python3
"""Check relative Markdown links and doc/ path mentions in this repository.

Run from the repository root:

    python3 doc/tools/check-links.py

The script intentionally has no third-party dependencies. It checks:
  * relative Markdown links in every tracked Markdown file;
  * ``doc/... .md`` path mentions in Markdown, Java and Gradle files.

Fragments (``#...``), absolute URLs and mailto links are ignored.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SKIP_DIRS = {"build", ".git", "logs", ".gradle"}
MARKDOWN_LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
DOC_PATH_MENTION = re.compile(r"(?<![\w/])doc/[A-Za-z0-9_./-]+\.md")
TEXT_SUFFIXES = {".md", ".java", ".kts", ".gradle", ".txt"}


def iter_files():
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        yield path


def strip_fragment(target: str) -> str:
    return target.split("#", 1)[0]


def main() -> int:
    errors: list[str] = []

    for path in iter_files():
        if path.suffix not in TEXT_SUFFIXES:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        relative_path = path.relative_to(ROOT)

        if path.suffix == ".md":
            for match in MARKDOWN_LINK.finditer(text):
                target = match.group(1).strip()
                if not target or target.startswith(("#", "http://", "https://", "mailto:")):
                    continue
                clean = strip_fragment(target)
                if not clean:
                    continue
                resolved = (path.parent / clean).resolve()
                if not resolved.exists():
                    errors.append(f"{relative_path}: broken link -> {target}")

        if path.suffix in {".md", ".java", ".kts", ".gradle", ".txt"}:
            for match in DOC_PATH_MENTION.finditer(text):
                target = match.group(0).rstrip(".,;:)")
                if not (ROOT / target).exists():
                    errors.append(f"{relative_path}: missing doc path -> {target}")

    if errors:
        print("Documentation link check failed:")
        for error in errors:
            print(f"  - {error}")
        return 1

    print("Documentation link check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
