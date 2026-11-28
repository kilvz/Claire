# English-only checks

Claire ships in 13 UI languages, but the source is English: prompts, UI text,
code comments, documentation and release notes are written in English, and the
translations live in `app/src/main/res/values-*` (plus the localized data files).

Chinese is allowed only where it is data the product must read or reproduce:

- localization resources (`app/src/main/res/values-*`, `app/src/main/assets/`);
- legacy and vendor values that must stay byte-identical (old greetings, vendor
  voice names, old run-record markers);
- matching tables that recognize Chinese screens, speech and messages;
- tests, eval tasks, and the separate companion programs under `companions/`.

Everything else is checked by `check.py`:

```bash
python3 tools/english/check.py              # whole repo
python3 tools/english/check.py docs/dev     # one path
```

It fails when it finds Chinese characters in comments anywhere under `app/src/main`
and `core/src/main`, in code outside the allowlist, in prompts, docs, OpenSpec
changes, build files, native/AIDL sources, the manifest or the root documentation.

`allowlist.txt` holds the exceptions for functional data. Keep it narrow and add
a file only with a reason. The checker still requires English comments in
allowlisted files.

Two kinds of files are not scanned at all: localization resources
(`app/src/main/res/values-*`, `app/src/main/assets/`) and historical artifacts
that reproduce what was on screen at the time (run-record evidence under
`openspec/*/evidence/` and the visual mockups under `docs/design/prototypes/`).

## Porting an upstream commit

Every upstream port must leave the repo English-only. After merging or
cherry-picking an upstream change:

1. Translate any new or edited Chinese comments, docs and prompts.
2. Translate new UI strings into all 13 UI languages and run
   `python3 tools/i18n/translations.py check`.
3. Run `python3 tools/english/check.py`.
4. Commit the translation in the same change as the port; do not leave it for a
   follow-up.
