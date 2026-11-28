# Operating pace can be set per app (#574)

## Why

"Operating pace" has only one global level (Fast/Standard/Human-like), while app knowledge holds a separate `pace: careful` list (8 built-in apps) that users can neither see nor change: apps with strict risk controls actually get sped up under the global "Fast" setting, and users cannot slow down or speed up a single app on its own. Issue #574 asks to turn this hidden determination into a visible, editable, revocable per-app list, with apps not in the list following the global setting.

## What Changes

- Add per-app operating pace overrides: each app can be set to "Fast / Standard / Human-like" individually, and apps without an override follow the global level; the effective level = override value ?: global (snapshotted at task start, consistent with the existing "takes effect from the next task").
- The 8 apps with built-in `pace: careful` are preset to "Human-like" when the list is first initialized, and the app knowledge's pace is no longer queried at runtime afterwards (deleting a preset returns it to following the global setting, so there are no longer exceptions users cannot override); the pace determination for site knowledge (web) is unchanged.
- Under Settings › During work › Operating pace, add a "Per-app settings" entry: the list shows each app's current level, and items can be added (chosen from installed apps, with search), changed and deleted.
- The core pace engine supports three per-app states: Fast = the app does not dwell, typing is written in one shot, and there is no screen-count cap; Standard/Human-like = the existing normal/careful dwell and typing. The screen-count cap (the page_limit gate) only follows "overridden to Human-like" — global Human-like no longer implicitly caps every app (keeping the current state: only apps that limit browsing pace are capped).
- When the eval hook sets an operating pace, determinism is preserved: per-app overrides are ignored and everything runs at the hook's level.

## Capabilities

### New Capabilities

- `per-app-pace`: resolving the operating pace per app — override storage and initialization, effective-level/cap determination, and the composition rules with the global level and eval hooks.

### Modified Capabilities

(No existing spec directories; not applicable.)

## Impact

- core: the pace input of `AppPacing`/`AppDwell` changes from `PlacePace` to the per-app three-state + separate cap determination (`PlacePace` itself is unchanged, and the web side is unaffected); `PhoneCapability`/`TaskTools` gain the corresponding pass-through parameters; `GuideCatalog` gains a careful-app list query; `OperatingPace` gains a typing-pace helper by level.
- app: new override storage (SharedPreferences), three wiring points in `TaskRun` changed to per-app resolution, a new entry and per-app sub-page in the settings screen, and new strings ×11 languages.
- Behavior change: with the global "Fast" setting, apps overridden to "Human-like" are slowed down (exactly what the issue asks for); a careful app newly added to app knowledge no longer automatically enters existing users' lists (the issue explicitly accepts this).
