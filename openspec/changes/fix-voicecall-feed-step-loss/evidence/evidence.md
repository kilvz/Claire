# fix-voicecall-feed-step-loss evidence timeline

Date: 2026-11-23. Device not connected (USB unplugged); this round of verification was JVM unit-test level, with no device walkthrough.

## Locating the defect (code review)

- Defect introduced: 8bd0d661 "voice call page revamped as a conversation feed" (merged 2026-11-23, not released).
- `VoiceCallFeed.onTask` (lines 44–70 before the change): when `lines != taskBaseline`, it appended only `lines.lastOrNull()`
  as one step. When multiple lines of new state accumulated between two callbacks, all but the last line were lost.
- Trigger mechanism: `VoiceCallRoute.lines` is a StateFlow; `VoiceCallController:312` collects
  `combine(route.busy, route.lines)` in `callScope` on `Dispatchers.Default`. StateFlow
  merges and drops intermediate values for collectors that cannot keep up (per the official docs); during a call, audio/protocol/state collection competes in the same pool,
  and after the `combine` collector coroutine misses a `_lines` update within a rescheduling window, one `onTask` callback carries multiple lines.
- Contract basis: the `VoiceCallFeed` class documentation "Do work: … add a step for each new status line that appears afterwards".

## Red-green comparison (JVM unit tests, :app:testDebugUnitTest --tests VoiceCallFeedTest)

The new tests were first run against the **old implementation** (`git stash` the VoiceCallFeed.kt fix):

```
VoiceCallFeedTest > front eviction in the rolling window keeps the diff on new lines FAILED
VoiceCallFeedTest > steps are not lost when several status lines land in one update FAILED
8 tests completed, 2 failed
```

After restoring the fix, full rerun:

```
BUILD SUCCESSFUL in 6m 15s
```

test-results XML confirms all 8 cases ran (including 3 new ones):
partial user speech…/ a repeated update with no new lines adds no step / the feed keeps only
the latest items / blank text is ignored / a task card collects new status lines… / front
eviction in the rolling window… / steps are not lost when several status lines land in one
update / assistant draft grows in one bubble…

## Full verification

- `./gradlew :core:test :core:cliTest :app:testDebugUnitTest` → BUILD SUCCESSFUL (6m 5s).
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (1m 30s, artifact 153,099,186 bytes).
- relayTest (Go) and the i18n/python checks were not run locally, left to PR CI; this change does not touch the string or protocol layers, so they are
  expected to be irrelevant.

## Not done

- Device call walkthrough (no device). Step card rendering with `steps.forEachIndexed` has no per-line animation dependency, and the UI behavior
  change is only "lines appear at the same time when one callback carries multiple lines", with no interaction-surface change.

## 2026-11-25 rebase port (after the upstream refactor)

### Events

- PR #822 is based on a40f6a98; upstream on 2026-11-24 (bbf2872d/17a30645/19c8ff89) refactored VoiceCallFeed into the
  `onTasks(List<DeskTaskView>)` model, and the PR became dirty (kkkaqi's same-topic PR #833 is also dirty).
- Rebased onto origin/main (6d364993), VoiceCallFeed.kt conflict: the old API `onTask(busy, lines)` no longer exists.

### Residual defect characterization (code review)

- New model `steps(view) = (view.recent + listOfNotNull(view.line)).distinct()`: a single snapshot with multiple lines can already be
  fully collected; but the `recent` window has only 3 entries (`DESK_RECENT_LINES`), so when the task produces
  more than 3 distinct progress lines between two `desk.active` collections, the lines that scroll out of the window no longer appear in any collected snapshot and are permanently lost from the card.
- `DeskTaskView.steps`'s full history (≤60 steps) is maintained item by item by `FrontDesk.updateView` and carried with every snapshot,
  but VoiceCallFeed did not use it.

### Fix

- `steps(view)` changed to `(view.steps.map { it.text } + view.recent + listOfNotNull(view.line)).distinct()`;
  removed the old model's `newSince` diff; the openspec planning artifacts (proposal/design/tasks/spec) were updated to the new approach in sync.

### Verification

- `./gradlew :app:testDebugUnitTest --tests '*VoiceCallFeedTest*'` → BUILD SUCCESSFUL (9 cases all green,
  including 3 new ported ones: `a step that scrolled out of the recent window is still kept from the full history`,
  `a task first seen mid-run seeds its card with the steps it already has`,
  `a repeated update with no new lines adds no step`).
- Full `:core:test :core:cliTest :app:testDebugUnitTest`: 3 failures — `StringResourcesTest.otherLanguagesMatchEnglish`
  (values-ja missing a key), `VolcRealtimeAgentToolsTest.aNoticeEndsSpokenOnlyWhenItsResponseFinishes`,
  `DocsConsistencyTest.docsMentionOnlyThingsThatExist` (architecture.md mentions a NeedStatusCard that does not exist).
  All three also fail in a clean origin/main (6d364993) workspace, and upstream main's own check-runs also show
  three failures (app/cli/core) — all are main already red, unrelated to this branch (this branch's diff only touches VoiceCallFeed,
  its tests, and the openspec docs).
- `:core:cliTest` rerun alone → BUILD SUCCESSFUL.

### Device

- Not done (this round is a conflict-resolution port; the change semantics are the same area as the first round: extra backfilled step lines on the card, with the render path unchanged).
