# Make the per-task sandbox time limit an adjustable level in settings (#774)

## Context

`SandboxGuard` (core/cap/sandbox) accumulates the `durationMs` of each sandbox command from the start of a task; once `SANDBOX_BUDGET_MS = 30 minutes` is exceeded, `exhausted()` returns a rejection, and `capTimeout()` compresses a single command's `timeoutSec` to the remaining time. The constructor already has a `budgetMs` parameter (JVM tests have used small budgets), but the app side never passed it, so it is always the default. Settings already has three precedents of the same kind: the task step limit (custom number), the scheduled task duration (fixed levels 15/30/60/120, null = no limit), and the token budget — the level-dialog pattern is taken from "scheduled task duration" (`TriggerRunLimitDialog`: RadioRow lists the levels).

## Goals / Non-Goals

**Goals:**
- The user can pick one of 30/60/120/240 minutes as the cap on cumulative sandbox command time per task, defaulting to 30 (consistent with the current state), taking effect from the next task.
- The minutes in the exhaustion message match the actual budget (no longer hardcoded to 30).
- Sub-tasks (ChildAgent) get a budget at the same level as the main task (each accumulates independently, consistent with their own independent step budgets).

**Non-Goals:**
- No "unlimited" level: the duration guard is a backstop against battery drain/heat, repeated failures of the same command are covered by the separate `sandbox_loop` guard, and loops are not handled by the time cap.
- No changes to the 1..600 second `timeoutSec` validation for a single command.
- No changes to "sandbox space" (free physical storage + the 500 MB threshold) or "upload size" (200 MB on the relay path) — the issue's other two requests, handled separately.
- Not exposed in SelfConfig (`settings_set`): similar limits such as scheduled task duration are not exposed to the model either.

## Decisions

- **Levels 30/60/120/240, stored as minutes (Int), 0 = unset and therefore the default 30.** Same style as `TRIGGER_RUN_MINUTES`: only values within the levels are recognized, and an invalid stored value counts as unset. The 240 cap is enough to cover long jobs that install dependencies + compile, without making the guard a mere formality.
- **core takes `Long` milliseconds; the app side converts.** `FeatureFlags.sandboxBudgetMinutes(): Int` is used by the UI/storage; when wiring, `TaskRun` passes `* 60_000L` to `toolDispatcher(sandboxBudgetMs = …)` (a new parameter, defaulting to `SANDBOX_BUDGET_MS`, so core unit tests and other callers need zero changes).
- **`SandboxCapability` gains a `budgetMs` constructor parameter**, with `guard() = SandboxGuard(budgetMs)`; the `SANDBOX_BUDGET_MS` constant remains as the default value and conversion basis.
- **Dynamically generate the exhaustion message**: `"Sandbox commands in this task have used their ${budgetMs / 60_000} minutes. …"`, in English for the model, differing from the existing copy only in the number of minutes.
- **The settings entry goes on the "During work" page**, immediately after "Maximum scheduled task run time" (both are task-duration-type limits), reusing `MikaIcons.Clock`, `RadioRow` and `trPlural`.

## Risks / Trade-offs

- A 240-minute foreground task drains the battery and produces heat — the user chose it and bears the cost, and not offering "unlimited" is already the minimal compromise; the default level is unchanged, so existing users' behavior does not change at all.
- Sub-tasks each having a full budget could theoretically add up to 240×N of sandbox time — sub-tasks are orchestrated by the main task and each has its own step budget, and this is already the case today (30 minutes each), so the ratio is unchanged.
- New strings must be synced across 11 language directories, with the `tools/i18n` check as the backstop.
