## ADDED Requirements

### Requirement: The work step card does not lose intermediate status lines

The work step card (`VoiceCallFeed`) on the voice call page MUST put **every newly
appearing** status line in the task snapshot (`DeskTaskView`) into the matching card: when a single snapshot carries multiple new status lines, all of them MUST become steps (order
preserved); lines that scroll out of the snapshot's recent rolling window MUST be backfilled from the snapshot's own full-run `steps` history — that is, no matter
how many intermediate snapshots the collector merges and drops, the card steps MUST match the front desk ledger. When a snapshot brings no new lines it MUST NOT
produce new steps; existing progress carried when a task first appears on a card MUST directly become card steps.

#### Scenario: The collector merges and drops intermediate snapshots, and the window scrolls out intermediate steps
- **WHEN** the task produces several progress lines in a row, more than the recent window capacity (3) between two `desk.active` collections,
  so the intermediate steps have already scrolled out of the recent window
- **THEN** the next collected snapshot backfills all the scrolled-out steps into the card from the full-run `steps`, losing no step

#### Scenario: A task already has progress when it first appears on a card
- **WHEN** the task is already running when the call starts (the snapshot's full-run `steps` already has several steps), and the task first appears on a card
- **THEN** the card opens with the snapshot's full-run steps, not just the most recent few lines

#### Scenario: A single snapshot with multiple new status lines
- **WHEN** one snapshot's `recent`/`line` carries multiple new status lines (a snapshot after StateFlow merging)
- **THEN** all these lines become steps, preserving order

#### Scenario: A snapshot with no new lines produces no steps
- **WHEN** the status lines a snapshot carries have no new lines compared with the steps already on the card (a duplicate notification)
- **THEN** the step card content is unchanged
