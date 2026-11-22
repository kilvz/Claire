# Claire versioning

Claire follows the same versioning scheme as Kai-custom: `a.b.c` with a
recorded base version.

- **Claire version:** 1.0.0 (versionCode 52)
- **Base version:** upstream Mika 0.1.50 (commit `9c7aabd8`)

## Bump rules

- Minor fix: bump `c` — 1.0.0 → 1.0.1
- Feature add / major fix: bump `b` — 1.0.0 → 1.1.0
- Rebase onto a new upstream Mika (or rebuild): bump `a`, reset `b`/`c` — 1.x → 2.0.0
- `versionCode` in `app/build.gradle.kts` increments on every release
  (it continues upstream's numbering so the APK installs over Mika 0.1.50).

## Releases

A release is cut by pushing a tag `v<version>` (e.g. `v1.0.0`).
`.github/workflows/release.yml` then builds the debug APK on GitHub
Actions and publishes it as a GitHub Release with the APK attached
(`Claire-<version>.apk` + SHA256SUMS.txt). The APK is debug-signed, so
it installs alongside nothing — it is the `com.wangzan101.mika` package
and installs over Mika / earlier Claire builds.
