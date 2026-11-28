## Context

Chain after the upstream 2026-11-24 refactor (bbf2872d/17a30645/19c8ff89):

- `FrontDesk.updateView` (`core/.../frontdesk/FrontDesk.kt`) maintains, item by item from the task state flow,
  `views: StateFlow<List<DeskTaskView>>`: each task's `recent` is the latest 3 distinct progress lines
  (`DESK_RECENT_LINES`=3, evicted by scrolling), and `steps` is every step taken over the whole run
  (`DeskStep(text, atMs)`, at most `DESK_STEPS_MAX`=60, appended at the tail).
- `VoiceCallController` in `callScope` runs `desk.active.collect { feed.onTasks(it) }` —
  when the StateFlow collector cannot keep up, intermediate snapshots are merged and dropped.
- `VoiceCallFeed.onTasks`/`withTask` (`VoiceCallFeed.kt`): the card steps take
  `steps(view) = (view.recent + listOfNotNull(view.line)).distinct()` and merge them with the steps already on the card,
  deduplicated by `!in`. A single snapshot with multiple lines is fully collected; but the `recent` window has only 3 entries, so when the task produces
  more than 3 distinct progress lines between two collections, the lines that scrolled out of the window no longer appear in any collected snapshot and are permanently lost from the card —
  even though each snapshot's `steps` field always carries the full history.

## Goals / Non-Goals

**Goals:**
- Card steps are immune to "the collector merging and dropping snapshots": they follow the snapshot's own full `steps`, so no matter how many
  intermediate snapshots are dropped, the card steps match the front desk ledger.
- When a task first appears on a card (already running when the call starts, or its first snapshot is collected only after dispatch), the card directly carries all
  existing progress.
- Keep existing behavior: multiple lines in a single snapshot's `recent + line` still all go onto the card; repeated snapshots produce no steps;
  the `ignored` baseline semantics for finished old tasks not going onto the card are unchanged.

**Non-Goals:**
- Do not change `FrontDesk`'s `recent`/`steps` maintenance (the window behavior is the front desk's display contract; the card side using full history
  is enough to be immune to merging).
- Do not change `VoiceCallController`'s collection wiring, do not add a count limit to the step card, do not change the rendering style.

## Decisions

1. **The step source becomes "full steps first, recent + line as fallback".**
   `steps(view) = (view.steps.map { it.text } + view.recent + listOfNotNull(view.line)).distinct()`.
   `distinct()` keeps first-occurrence order, and `steps` itself is appended in occurrence order, so the order is naturally correct; `recent + line`
   remains as a fallback covering shapes `steps` does not record (e.g. `line` in non-moving stages). The downstream `withTask`'s
   `card.steps + seen.filter { it !in card.steps }` dedup-merge logic is unchanged.
2. **No diffing on the card side.** The old baseline diff was designed for "snapshots carry only the window"; once snapshots carry full history,
   the diff degrades into redundant state (and still needs to handle baseline invalidation), while a direct full dedup-merge is simpler and stronger.
3. **The 60-step cap on `DeskTaskView.steps` is acceptable.** For extremely long tasks over 60 steps, dropping the oldest steps is the front desk
   ledger's own trade-off; the card just needs to stay consistent with the ledger.

## Risks / Trade-offs

- A card that carries full steps the first time it appears: an extremely long task has a full card the moment it opens (the old implementation carried ≤4 lines). This is
  a direct corollary of "do not lose steps", and the UI shows only the latest two steps, so a full card has no visual burden.
- If `view.steps` is later given different semantics upstream (e.g. changed to a summary), the `recent + line` fallback remains, and behavior degrades to
  the status quo, not worse.
