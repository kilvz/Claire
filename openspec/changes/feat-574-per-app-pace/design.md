# Design: per-app operating pace overrides (#574)

## Context

Current chain (explore conclusion):

```
设置 (Settings) › 干活时 (During work) › 操作节奏 (Operating pace) — radio: 快/标准/像人 (Fast/Standard/Human-like) → FeatureFlags.operatingPace() (prefs "operating_pace")
  └─ TaskRun.kt:699  pace = evalHooks.operatingPace ?: the settings value
       ├─ pace.appPacing() → an AppPacing instance (FAST→Off; STANDARD→the two normal limit sets; HUMAN→all CAREFUL limits)
       │    └─ PhoneCapability → AppDwell(pacing, pace=lambda)
       │         pace lambda = PlacePace.of(catalog.appPace(pkg))  ← App knowledge APP.md `pace: careful`
       │         CAREFUL → careful limits (dwell longer, read by character count, 24-screen cap, slower typing)
       └─ pace.typing(placePace) → TypingPacing
```

Pain points: the careful list (8 built-in apps) is invisible and unchangeable to the user; with the global 「快」 (Fast) setting, `AppPacing.Off` makes the careful determination ineffective as well; a single app cannot be slowed down or sped up independently.

Precedents: `AppAccessStore` (JSON file + StateFlow) is the precedent for per-app settings storage; `AppAccessScreen` is the precedent for a per-app list page (`launchableApps` lists apps + search + ListRow/MikaDialog); `SettingsScreen` routes sub-pages with the `SettingsPage` enum.

## Goals / Non-Goals

**Goals:**
- Each app can independently be set to 快/标准/像人 (Fast/Standard/Human-like); apps without an override follow the global setting; the built-in careful list becomes the initial content of the list (visible, editable and deletable).
- The phone's dwell, typing and screen-count limit are all resolved from the "effective level"; eval hooks take priority and are deterministic.
- Changes are concentrated in the core pace engine's inputs and the app's settings/wiring; the web side (sitePace) is untouched.

**Non-Goals:**
- No finer dimensions by task type/time of day/network environment (the issue explicitly says these can come later).
- No changes to web PagePacing/site careful determination.
- No changes to the pace of the gestures themselves (GesturePacing is human-like in all three levels and is orthogonal to this setting).

## Decisions

### D1: Effective-level resolution — "override value ?: global", snapshotted at the same time as the global

Resolved once at task start: `effective(pkg) = overrides[pkg] ?: global`. Consistent with the global level's "takes effect from the next task", changing settings mid-task does not affect the current run. When the eval hook sets a level, overrides are treated as empty (determinism).

### D2: All three levels' semantics go through per-app resolution, and the AppPacing instance no longer morphs by global level

The instance is uniformly `AppPacing.human(random)` (both normal limits + careful limits), and the global level only feeds into `effective()`:
- effective=FAST → that app does not dwell (the engine's off), typing is Instant, and screens are not counted or capped;
- effective=STANDARD → PlacePace.NORMAL (normal limits);
- effective=HUMAN → PlacePace.CAREFUL (careful limits + read time + slower typing).

When everyone is FAST (global FAST and no overrides), the `AppPacing.Off` fast path is kept, with behavior identical to today.
The alternative (keeping the instance morphing in `OperatingPace.appPacing()`) was rejected: global HUMAN + a single app overridden to STANDARD cannot be expressed with one set of instance limits.

### D3: Decouple the screen-count limit from "slow" — the limit only follows "overridden to Human-like"

Current state: with global HUMAN, all apps use CAREFUL limits, but only knowledge-careful apps have their screen count capped. If effective=HUMAN were uniformly mapped to PlacePace.CAREFUL, global HUMAN would suddenly put all apps under the 24-screen cap (a behavior regression). So on the core side, "pace speed" and "whether to cap screens" are split into two inputs:
- Pace: `AppTempo` (a new three-state FAST/NORMAL/CAREFUL, phone domain only; `PlacePace` stays two-state and unchanged, and the web side keeps using it);
- Screen cap: `capped: (String) -> Boolean`, supplied by the app layer as `overrides[pkg] == HUMAN`.

After seeding, capped comes only from user overrides, and the 8 built-in apps are preset to HUMAN → behavior matches today's under STANDARD; deleting a preset means no cap (consistent with "deleting = follow global").

### D4: Storage uses SharedPreferences (a JSON string), seeded lazily

`FeatureFlags` adds `app_pace_overrides` (`{"pkg":"HUMAN",...}` JSON) to the same prefs file. On the first read (when the value is empty), `GuideCatalog.carefulApps()` (a new query) is written into it and returned, and the knowledge is no longer queried at runtime afterwards. The list is expected to be <20 items, so prefs is enough; no AppAccessStore-style file storage is done (that one has a complex classifications structure, which is absent here). Writes use `commit()` (consistent with the existing FeatureFlags style).

### D5: Core wiring stays compatible with default behavior

The default values of the per-app pace parameters of `TaskTools`/`PhoneCapability` are still resolved from app knowledge (`AppTempo.of(skills?.appPace(pkg))`, with capped from the same source), so the default paths of core unit tests/CLI are unchanged; `TaskRun` passes the per-app resolution results explicitly. The `AppDwell.pace` parameter type changes from `(String) -> PlacePace` to `(String?) -> AppTempo` (FAST is expressed inside the engine, so a separate off lambda is no longer needed).

### D6: UI — a settings sub-page + an add dialog

`SettingsPage` gains `AppPace`, entered from a new entry under the operating pace group in `DuringWorkScreen`. The sub-page reuses the AppAccessScreen shape: an override ListRow (label + level; tapping it pops up the four choices Fast/Standard/Human-like/Delete), and 「添加」 (Add) opens a MikaDialog (launchableApps + search, excluding already overridden items). New strings are added to all 11 language directories.

## Risks / Trade-offs

- [A careful app newly added to app knowledge no longer automatically enters existing users' lists] → The issue author explicitly accepts this ("there are no longer exceptions users cannot override"); new installs still seed the latest list.
- [Hand-written/corrupted prefs JSON fails to parse] → A parse failure is treated as empty (the next read reseeds), with no crash.
- [The AppDwell/AppPacing signature change affects existing unit tests] → Update the case semantics in AppPacingTest/AppDwellTest accordingly (tempo/capped), with the full core test suite as the gate.
- [M3 RadioRow is not tappable via adb on some ROMs (a known pitfall in AGENTS)] → Device verification relies on writing prefs directly + task behavior evidence, supplemented by UI screenshots.

## Migration Plan

No migration of existing data: no key in prefs means a first seed. Rollback = revert the commit; the extra key in prefs is not read by anyone, with no side effects.

## Open Questions

None — the issue already gives an acceptable simplified scope, and this design covers its full requirements (three levels, add/edit/delete, built-in presets).
