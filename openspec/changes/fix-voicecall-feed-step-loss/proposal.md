## Why

The work step card on the call page can lose steps. The upstream 8bd0d661 revamp's `VoiceCallFeed.onTask(busy, lines)` added only the
**last line** of `lines` to the card on each callback, while the producer delivers through a StateFlow and the collector merges and drops intermediate values when it cannot keep up,
so of the multiple new status lines accumulated between two callbacks only the last line reached the card (this change's first round fixed it with a "baseline diff").

On 2026-11-24 upstream (bbf2872d/17a30645/19c8ff89) refactored this area as a whole: `VoiceCallFeed` changed to
`onTasks(views: List<DeskTaskView>)`, and the card steps take `(view.recent + view.line).distinct()` and then merge them with
the steps already on the card, deduplicated. After the refactor **a single snapshot with multiple new status lines can all reach the card**, but a residual gap remains:
`recent` is a rolling window of only 3 entries (`DESK_RECENT_LINES`), so if the task produces
more than 3 distinct progress lines between two `desk.active` collections, the intermediate steps that scroll out of the window are still lost; while each `DeskTaskView` snapshot's own **full-run
`steps` history** (at most `DESK_STEPS_MAX`=60 steps, accumulated item by item by `FrontDesk.updateView`) is instead not
used by `VoiceCallFeed`.

## What Changes

`VoiceCallFeed`'s card step source changes from "`recent + line` (a ≤4-line window)" to "the snapshot's full-run `steps` text
+ `recent + line` fallback": the complete history carried by any snapshot makes the card steps immune to collector merging — no matter how many intermediate
snapshots are dropped, the next snapshot received still carries the full run. Paired with this, the old API's unit tests are ported to the new `onTasks` API, adding coverage for:
intermediate steps scrolled out of the window are still backfilled from the full history, a task first appearing on a card carries its existing progress, and repeated snapshots produce no steps.

## Capabilities

### New Capabilities

- `voicecall-task-steps`: the status-line collection contract for the work step card on the voice call page — every newly appearing
  status line in a snapshot must become a step (all lines in a single snapshot are collected, lines scrolled out of the recent window are backfilled from the full-run steps, and no new lines
  produce no steps).

### Modified Capabilities

(None — `openspec/specs/` currently has no voicecall spec; this fix establishes a contract as a new capability.)
