# Tasks: per-app operating pace overrides (#574)

## 1. core: pace engine supports per-app three states with the screen cap split out

- [x] 1.1 Add `AppTempo` (FAST/NORMAL/CAREFUL, parsed by `of("careful")`) in `core/cap/phone`; change the pace parameter of `AppPacing.noteAction`/`capReached` to tempo + a capped determination (FAST does not record dwell; only capped apps count/limit screens), keeping the Off fast path
- [x] 1.2 Change `AppDwell`'s `pace: (String) -> PlacePace` to `tempo: (String?) -> AppTempo` + `capped: (String) -> Boolean`; for FAST, `before` neither waits nor gates
- [x] 1.3 `PhoneCapability`/`TaskTools` pass the new parameters through, with defaults still resolved from app knowledge (`AppTempo.of(skills?.appPace(pkg))`, capped from the same source)
- [x] 1.4 `OperatingPace` gains a tempo-based typing-pace helper (FAST→Instant, CAREFUL→CAREFUL typing); `GuideCatalog` gains `carefulApps()` (the list of package names of built-in apps with pace=careful)
- [x] 1.5 core unit tests: AppPacing/AppDwell — FAST does not wait, CAREFUL+capped counts and limits screens, NORMAL does not count screens; GuideCatalog.carefulApps returns the 8 built-in package names; rewrite existing cases affected by the signature change

## 2. app: override storage and task wiring

- [x] 2.1 `FeatureFlags` gains `appPaceOverrides()` (prefs JSON; when empty, seed `GuideCatalog.carefulApps()`→HUMAN and persist) and `setAppPace(pkg, OperatingPace?)`; a parse failure is treated as empty and reseeded
- [x] 2.2 Add pure-function resolution `effective(pkg)=overrides[pkg]?:global`, `capped(pkg)=overrides[pkg]==HUMAN`, and the all-FAST determination (shared by unit tests and TaskRun)
- [x] 2.3 Three wiring points in `TaskRun`: `appPacing` (all FAST→Off, otherwise human(random)), `appPace` (tempo/capped by resolution), `typing` (by effective); when the eval hook sets a level, overrides are treated as empty
- [x] 2.4 app unit tests: each branch of the resolution functions (override/global/cap/all-fast), the first seed persist and the corruption fallback

## 3. UI: settings entry and per-app sub-page

- [x] 3.1 `SettingsPage` gains the `AppPace` route; `DuringWorkScreen` gains a 「按应用设置」 (Per-app settings) entry row under the operating pace group
- [x] 3.2 `AppPaceScreen`: an override list (label + level), a four-choice dialog (快/标准/像人/删除 — Fast/Standard/Human-like/Delete), and an add dialog (launchableApps + search, excluding already overridden items)
- [x] 3.3 Add the new strings to values and all 11 language directories

## 4. Verification and wrap-up

- [x] 4.1 Full local test run: `./gradlew :core:test :core:cliTest :app:testDebugUnitTest`
- [x] 4.2 Device verification: with global Fast + one app overridden to Human-like, that app's step intervals are noticeably longer while the rest stay fast; tasks under changed levels are not wrongly hit by the screen cap; evidence goes into evidence/
- [x] 4.3 Commit (with a Chinese commit message), push the fork, open a PR linking #574, and wait for CI to pass
