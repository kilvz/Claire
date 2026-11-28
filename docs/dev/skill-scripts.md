# Sandbox scripts

Skills and website knowledge can carry scripts, placed under `scripts/` in their own directories. After packaging, these directories appear in the sandbox under `/opt/mika/skills/`,
and are run in the sandbox by `run_bundled_script`. Arguments:

- `source`: the directory the script is in, i.e. the `scriptSource` given in `knowledge_read` and `skill_read` results, e.g. `skills/documents`;
- `script`: the script path within the directory, e.g. `scripts/extract_pdf.py`;
- `args`: argument array (at most 50), passed to the script as-is, without going through a shell;
- `timeoutSec`: time limit, 1–600 seconds, default 60.

The interpreter is chosen by extension: `.py` uses python3, `.mjs` and `.js` use node, `.sh` uses sh (`ScriptManifest.interpreterFor`).

There is currently one script in the repository:

| Script | Location | What it does | What it needs |
|---|---|---|---|
| `scripts/extract_pdf.py` | `skills/documents/` | Extracts text from text-based PDFs, optionally by page, viewing metadata, writing to a file; scanned documents are not handled | Alpine package `py3-pypdf` |

All the above locations are under `core/src/main/resources/`. Scripts all accept `--help`, exit non-zero on error, and write a one-line reason to stderr.

## `scripts/manifest.json`

Each directory with scripts has a `scripts/manifest.json`, an array with one entry per script:

```json
[
  {"file": "scripts/extract_pdf.py", "runnable": true, "needs": ["apk:py3-pypdf"]}
]
```

- `file`: the script's path relative to that directory; entries that are not a relative path within the directory are dropped.
- `runnable`: whether it can run in the sandbox. A script with `false` only serves to say what is missing, and `run_bundled_script` refuses it.
- `reason`: optional, the reason given when `runnable` is `false`.
- `needs`: dependency list. `apk:<package>` means this Alpine package is required (must be in `SANDBOX_PACKAGES`); `net` means network access is required.
- `network`: whether network access is needed. When it is `true` and "Allow scripts to use the network" under Settings → Advanced is off (off by default), `run_bundled_script` refuses to run and prompts you to turn it on.

Parsing and the "can it run right now" judgment are in `core/.../knowledge/ScriptManifest.kt`; whether the manifest, the scripts mentioned in the docs and the script files all line up is checked by `BundledScriptsManifestTest`.

## Runtime environment

The sandbox is based on Alpine 3.22; `SANDBOX_PACKAGES` (`core/.../cap/sandbox/SandboxPackages.kt`) lists the packages bundled with the APK: python3, nodejs, py3-pillow, py3-pypdf, tzdata. Among them py3-pypdf is for the PDF text-extraction script; py3-pillow is not used by any built-in skill script right now, but the package is kept for now; tzdata lets commands handle local time by the phone's current time zone.
These packages are installed at build time into the runtime bundled with the APK (`tools/sandbox/build-runtime.py`, see `tools/sandbox/README.md`); when the packages change, the runtime version changes with them, and the phone unpacks it again automatically on startup.
