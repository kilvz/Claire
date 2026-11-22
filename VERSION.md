# Claire versioning

Claire follows the same versioning scheme as Kai-custom: `a.b.c` with a
recorded base version.

- **Claire version:** 1.2.1 (versionCode 55)
- **Base version:** upstream Mika 0.1.50 (commit `9c7aabd8`)
- **Package:** `com.kilv.claire` since 1.1.0 — installs alongside Mika
  (1.0.0 was still `com.wangzan101.mika` and could not install next
  to it).

## Bump rules

- Minor fix: bump `c` — 1.0.0 → 1.0.1
- Feature add / major fix: bump `b` — 1.0.0 → 1.1.0
- Rebase onto a new upstream Mika (or rebuild): bump `a`, reset `b`/`c` — 1.x → 2.0.0
- `versionCode` in `app/build.gradle.kts` increments on every release
  (it continues upstream's numbering).

## Releases

- 1.0.0: first release (OrcaRouter, English by default).
- 1.1.0: own applicationId `com.kilv.claire` + app label Claire, so
  it installs alongside Mika instead of conflicting with it.
- 1.2.0: full Claire identity — the app calls itself Claire
  everywhere (settings, captions, prompts, phone-call greetings),
  the in-app updater checks `kilvz/Claire` releases, the author
  points at Kilv (`@KilZT`, his WeChat QR), and its own folders are
  `Pictures/Claire`, `Download/Claire`, `Music/Claire`.
- 1.2.1: release-signed with Claire's permanent keystore (GitHub
  secret `ANDROID_KEYSTORE_BASE64`, local backup
  `~/workspace/keystores/claire-release.keystore`). 1.2.0 was
  debug-signed and could not install over 1.1.0 — GitHub runners
  generate a fresh debug key per run, so the signatures never
  matched. From 1.2.1 on, releases install over earlier
  `com.kilv.claire` builds; 1.1.0 still needs one manual uninstall.

A release is cut by pushing a tag `v<version>` (e.g. `v1.1.0`).
`.github/workflows/release.yml` then builds the release APK on GitHub
Actions and publishes it as a GitHub Release with the APK attached
(`Claire-<version>.apk` + SHA256SUMS.txt). The APK is release-signed
with the permanent key. Since 1.1.0 its package is `com.kilv.claire`, so it installs
alongside Mika — and alongside Claire 1.0.0, which was still
`com.wangzan101.mika` (uninstall that one first if it is on the
phone). It installs over earlier `com.kilv.claire` builds from 1.2.1
onward (1.1.0 was debug-signed with a throwaway CI key and needs one
manual uninstall first).
