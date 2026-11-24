# Translation tools

These commands use only the Python 3.10+ standard library. They need neither a JDK nor an Android SDK and can be run from the repository root.

```sh
python3 tools/i18n/translations.py check
python3 tools/i18n/translations.py export --output translations.csv
python3 -m unittest discover -s tools/i18n -v
```

## Resource checks

`check` compares `string`, `plurals` and `string-array` entries in `strings*.xml` with the default English `values/` folder. It reports missing and extra keys by resource type, allows entries to move between files, and excludes default entries marked `translatable="false"`. It also detects malformed XML, duplicate resources, invalid plural quantities, a missing plural `other` item, array length differences and dropped format arguments.

The placeholder check is intentionally lightweight: it compares the **number** of substitutions in strings, array items and the plural `other` form. A literal percent written as `%%` is text rather than a substitution and is not counted. It does not require a particular argument order and does not compare every plural form. Languages may use different plural categories; for example, Arabic uses six categories and its `one` and `two` forms may omit a numeric argument. Array lengths must match, but plural category sets need not match English.

The Persian catalog has received an initial linguistic review. Review it in context on a device before release, especially security, privacy, payments and irreversible actions.

Language folders are discovered automatically, including legacy Android language codes and BCP 47 folders such as `values-b+zh+Hant`. Configuration overlays such as `values-night` and `values-zh-v31` are ignored. The current repository has **11 UI languages**, including Arabic and Persian with right-to-left text; `check` reports the number it actually discovers.

Exit codes are **0** for success, **1** for reported translation differences and **2** for invalid resources or I/O errors. The `cli (Python)` job in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) runs these tests and this check on Python 3.12.

This tool does **not** replace `app/src/test/.../StringResourcesTest.kt`. The JVM tests remain authoritative for exact placeholder sets where applicable, CLDR plural categories and app language registration. Folder discovery alone cannot prove that a language is registered by the app. Run the JVM tests after editing translations:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.i18n.StringResourcesTest'
```

## Reference CSV

`export` writes UTF-8 CSV with a BOM for spreadsheet applications. Its columns are:

- `key`, `type`: resource name and type.
- `item`: blank for a string, a quantity for a plural, or a zero-based array index.
- `source`: default English file relative to the resource directory.
- `context`: XML comments immediately before the English entry.
- One column per discovered resource folder: `values`, `values-zh`, `values-b+zh+Hant`, then the other languages, including `values-ar`.

Each plural quantity and array item gets its own row. Quantities used only by a translation are included; missing items remain blank rather than being filled with English. Inline XML, Android backslash escapes, placeholders, commas, newlines and right-to-left text are preserved as cell content. The parent directory of `--output` must exist, and an existing file is overwritten.

The CSV is for reading and translation review only, **not for automatic import**. Do not commit generated CSV files. Edit the XML resources, preserve their escaping and formatting arguments, then run both the Python checks and the JVM resource tests.
