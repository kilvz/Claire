#!/usr/bin/env python3
"""Pack each plugin under plugins/ into an installable zip (manifest.json at the zip root; skills/scripts/docs carried over as they are)."""
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.join(ROOT, "dist")

# Not part of the package: Python bytecode caches and records Finder leaves in directories.
SKIP_DIRS = ("__pycache__",)
SKIP_FILES = (".DS_Store",)


def entry_name(rel: str) -> str:
    """Turn a relative path into a zip entry name, always with `/`.

    On Windows os.path.relpath returns backslashes, and the app's installer treats entries containing backslashes (or colons) as
    unsafe paths and rejects the whole package (PluginInstaller.checkName), so replace them before writing the zip.
    """
    return rel.replace("\\", "/")


def plugin_entries(src: str):
    """Yield (zip entry name, disk path) one by one; __pycache__, .pyc and .DS_Store are excluded."""
    for dirpath, dirnames, filenames in os.walk(src):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for fn in sorted(filenames):
            if fn.endswith(".pyc") or fn in SKIP_FILES:
                continue
            full = os.path.join(dirpath, fn)
            yield entry_name(os.path.relpath(full, src)), full


def build() -> int:
    os.makedirs(DIST, exist_ok=True)
    plugins = [d for d in os.listdir(ROOT)
               if os.path.isdir(os.path.join(ROOT, d)) and not d.startswith(".") and d not in ("dist", "examples")]
    for p in sorted(plugins):
        src = os.path.join(ROOT, p)
        manifest_path = os.path.join(src, "manifest.json")
        if not os.path.isfile(manifest_path):
            print(f"skip {p}: no manifest.json", file=sys.stderr)
            continue
        with open(manifest_path, encoding="utf-8") as f:
            m = json.load(f)
        out = os.path.join(DIST, f"{m['id']}-{m['version']}.zip")
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
            for arcname, full in plugin_entries(src):
                zf.write(full, arcname)
        print(f"built {os.path.relpath(out, ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(build())
