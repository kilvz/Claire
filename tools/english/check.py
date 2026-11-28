#!/usr/bin/env python3
"""Fail when Chinese text appears where this repository must be English.

English is the only product language for prompts, UI text, docs and code comments.
Chinese is allowed only in localization resources, legacy/vendor data, eval tasks,
tests, and the matching tables that read Chinese screens, speech and messages.
Those exceptions are listed in ``allowlist.txt`` (glob patterns, one per line).

Rules:
  * Comments in ``app/src/main``, ``core/src/main`` and ``core/src/chatgptCheck``
    must be English even inside allowlisted files.
  * Code outside the allowlist must not contain Chinese; allowlisted files may
    keep Chinese inside string literals and regular expressions.
  * Prompts, docs, OpenSpec changes, build files, native/AIDL sources, the
    manifest and root documentation must contain no Chinese at all.

Run from anywhere: ``python3 tools/english/check.py``.
"""

from __future__ import annotations

import fnmatch
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HAN = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
COMMENT_START = re.compile(r"^\s*(?://|/\*|\*|<!--)")
TRAILING_COMMENT = re.compile(r"(^|\s)//")
SKIP_DIRS = {
    ".git", ".gradle", ".kotlin", ".idea", "build", "node_modules",
    "dist", "out", "__pycache__",
}

# Files whose Chinese is functional data: vendor names, legacy formats,
# localized output and the tables that recognize Chinese screens/speech.
# Populated in allowlist.txt; tests and localization resources are skipped below.
def load_allowlist() -> list[str]:
    path = os.path.join(ROOT, "tools", "english", "allowlist.txt")
    if not os.path.isfile(path):
        return []
    patterns = []
    for line in open(path, encoding="utf-8"):
        line = line.split("#", 1)[0].strip()
        if line:
            patterns.append(line)
    return patterns


def is_allowlisted(rel: str, patterns: list[str]) -> bool:
    return any(fnmatch.fnmatchcase(rel, pat) for pat in patterns)


def is_comment(line: str) -> bool:
    return bool(COMMENT_START.match(line) or TRAILING_COMMENT.search(line))


def scan_mode(rel: str) -> str | None:
    """Return ``all`` (no Chinese at all), ``code`` (comment rule + allowlist) or None (skip)."""
    if rel.startswith(("app/src/main/assets/", "app/src/main/res/values")):
        return None
    if rel.startswith(("app/src/test/", "core/src/test/", "app/src/androidTest/",
                       "core/src/testFixtures/", "core/src/test/resources/")):
        return None
    if rel.startswith(("companions/", "eval/", "plugins/", "tools/")) and not rel.startswith("tools/english/"):
        return None
    if rel.endswith((".kt", ".java")) and rel.startswith(
        ("app/src/main/", "core/src/main/", "core/src/chatgptCheck/")
    ):
        return "code"
    if rel.startswith("core/src/main/resources/prompts/"):
        return "all"
    if rel.startswith("docs/") and rel.endswith(".md"):
        return "all"
    if rel.startswith("openspec/") and rel.endswith((".md", ".txt")):
        return "all"
    if rel in ("README.md", "README.en.md"):
        return "all"
    if rel.startswith(".github/") and rel.endswith((".md", ".yml")):
        return "all"
    if rel.endswith(".gradle.kts") or rel in ("gradle.properties", ".gitignore", ".gitattributes"):
        return "all"
    if rel.startswith(("app/src/main/cpp/", "app/src/main/aidl/")):
        return "all"
    if rel == "app/src/main/AndroidManifest.xml" or rel.startswith("app/src/main/res/xml/"):
        return "all"
    if rel.startswith("app/libs/") and rel.endswith(".md"):
        return "all"
    return None


def tracked_files() -> list[str]:
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, ROOT).replace(os.sep, "/")
            yield rel


def main() -> int:
    patterns = load_allowlist()
    only = [a.rstrip("/").replace(os.sep, "/") for a in sys.argv[1:]]
    violations: list[str] = []
    for rel in tracked_files():
        if only and not any(rel == p or rel.startswith(p + "/") for p in only):
            continue
        mode = scan_mode(rel)
        if mode is None:
            continue
        try:
            text = open(os.path.join(ROOT, rel), encoding="utf-8").read()
        except (UnicodeDecodeError, OSError):
            continue
        for number, line in enumerate(text.splitlines(), 1):
            if not HAN.search(line):
                continue
            if mode == "all" or (mode == "code" and (is_comment(line) or not is_allowlisted(rel, patterns))):
                violations.append(f"{rel}:{number}: {line.strip()[:160]}")

    if violations:
        print("Chinese text found where this repo must be English:", file=sys.stderr)
        print("\n".join(violations), file=sys.stderr)
        print(
            "\nTranslate it, or add the file to tools/english/allowlist.txt when the "
            "Chinese is functional data (localization, legacy/vendor values, or a "
            "matching table for Chinese screens, speech or messages).",
            file=sys.stderr,
        )
        return 1
    print("English-only checks passed: no Chinese outside localization data, tests and allowlisted data files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
