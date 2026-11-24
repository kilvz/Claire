## ADDED Requirements

### Requirement: Per-task sandbox time limit is user-configurable

The cumulative runtime limit for sandbox commands within a task SHALL be chosen by the user in settings from fixed tiers (30, 60, 120, 240 minutes), defaulting to 30 minutes (consistent with existing behavior). The setting takes effect when a task starts; in-progress tasks are unaffected by changes. When a value outside the tiers appears in storage, it SHALL be treated as the default 30 minutes.

#### Scenario: Default matches current behavior

- **WHEN** the user has never set a sandbox time limit
- **THEN** once sandbox commands in a task accumulate 30 minutes they are refused further use, matching behavior before the setting was introduced

#### Scenario: Raising the tier takes effect on the next task

- **WHEN** task A is in progress and the user changes the limit from 30 minutes to 120 minutes
- **THEN** task A is still refused at 30 minutes; task B, started afterwards, runs at 120 minutes

#### Scenario: An invalid stored value falls back to the default

- **WHEN** the stored sandbox time limit is written as a value outside the tiers (e.g. 45 or a negative number)
- **THEN** it is read as 30 minutes and the settings page shows the default tier

### Requirement: The exhaustion message reflects the actual limit

The message returned to the model when sandbox time is exhausted SHALL state the actually configured minute count and MUST NOT hardcode 30 minutes.

#### Scenario: Message for the 120-minute tier

- **WHEN** sandbox commands in a task with a 120-minute limit accumulate 120 minutes
- **THEN** the tool returns `sandbox_budget_exhausted` and the message says 120 minutes

### Requirement: Subtasks use the same tier

A subtask's (child agent's) sandbox time limit SHALL use the same tier as the main task that spawned it, each accumulating independently without consuming the other's allowance.

#### Scenario: Subtasks accumulate independently

- **WHEN** the main task has used 25 minutes of sandbox time on the 60-minute tier and then spawns a subtask
- **THEN** that subtask accumulates from 0 and is only refused at 60 minutes; the main task continues accumulating against its own 60 minutes
