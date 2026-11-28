## 1. Fix VoiceCallFeed's step source (first round: baseline diff; after the 2026-11-24 upstream refactor: use full-run steps)

- [x] 1.1 (First round, committed with db9877ff) `VoiceCallFeed.onTask` changed to "longest common suffix diff" to backfill
  all new lines — the API no longer exists after the 2026-11-24 upstream refactor (onTasks/DeskTaskView model).
- [x] 1.2 (Port) `VoiceCallFeed.steps(view)` changed to
  `(view.steps.map { it.text } + view.recent + listOfNotNull(view.line)).distinct()`,
  so card steps follow the snapshot's full-run history and are immune to collector merging; `withTask`'s dedup-merge and the `ignored`
  baseline semantics are unchanged; the old model's `newSince` diff helper is removed.
- [x] 1.3 Update `VoiceCallFeed`'s documentation comments to match the implementation.

## 2. Unit tests (VoiceCallFeedTest, migrated to the onTasks API with the upstream refactor)

- [x] 2.1 Progress produced between two collections exceeds the recent window (3): intermediate steps scrolled out of the window are still
  backfilled from the snapshot's full-run steps (lost before the fix).
- [x] 2.2 When a task first appears on a card the snapshot already carries progress: the card opens directly with the full-run steps (before the fix only recent+line).
- [x] 2.3 Regression: repeated snapshots produce no steps; all lines in a single snapshot are collected and the final-state line also goes onto the card (the upstream
  existing cases continue to pass).

## 3. Verification

- [x] 3.1 (First round) `./gradlew :app:testDebugUnitTest --tests '*VoiceCallFeedTest*'` all green.
- [x] 3.2 (First round) Local full run `:core:test :core:cliTest :app:testDebugUnitTest` passes.
- [x] 3.3 (Port round) Rebase onto the latest main and rerun the above tests; the rebase events and results are appended to
  `evidence/evidence.md`.
