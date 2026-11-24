#!/usr/bin/env python3
"""Build the example plugin into two zips:

- dist/com.example.feishu-skill-0.1.0.zip: the skill-only version P1a can install directly (manifest.json)
- dist/com.example.feishu-full-0.2.0.zip: the tools version for after P1b lands (manifest.full.json renamed to manifest.json)

Standard library only; zip entries always use `/`; non-plugin files such as README, build.py and manifest.full.json are not packed.
"""

import argparse
import pathlib
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent


def add(zf, source, arcname):
    if not source.is_file():
        raise SystemExit("missing file: {}".format(source))
    zf.write(source, arcname)


def files_under(folder):
    """Plugin contents: skip __pycache__ and .pyc, which are not part of the package."""
    for f in sorted((ROOT / folder).rglob("*")):
        if f.is_file() and "__pycache__" not in f.parts and f.suffix != ".pyc":
            yield f


def build_skill(out_dir):
    out = out_dir / "com.example.feishu-skill-0.1.0.zip"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        add(zf, ROOT / "manifest.json", "manifest.json")
        for f in files_under("skills"):
            add(zf, f, f.relative_to(ROOT).as_posix())
    return out


def build_full(out_dir):
    out = out_dir / "com.example.feishu-full-0.2.0.zip"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        add(zf, ROOT / "manifest.full.json", "manifest.json")
        for folder in ("skills", "scripts"):
            for f in files_under(folder):
                add(zf, f, f.relative_to(ROOT).as_posix())
    return out


def main():
    parser = argparse.ArgumentParser(description="build Feishu plugin zips")
    parser.add_argument("--out", default=str(ROOT / "dist"), help="output directory")
    parser.add_argument("--only", choices=["skill", "full"], help="build just one variant")
    args = parser.parse_args()
    out_dir = pathlib.Path(args.out).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    built = []
    if args.only in (None, "skill"):
        built.append(build_skill(out_dir))
    if args.only in (None, "full"):
        built.append(build_full(out_dir))
    for path in built:
        print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
