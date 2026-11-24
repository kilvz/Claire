# Make the per-task sandbox time limit an adjustable level in settings (#774)

## Why

Issue #774: when users run time-consuming sandbox tasks (installing dependencies, compiling, data processing), the cumulative sandbox commands in one task are limited to a hardcoded 30 minutes (`SandboxGuard.SANDBOX_BUDGET_MS`), after which further commands are refused, and users have no way to loosen it. Settings already has precedents for "user-adjustable limits" of the same kind (task step limit, scheduled task duration, token budget), so the sandbox time limit can simply be opened up as a level in the same pattern.

Of the issue's three requests, this change only does "budget time": **sandbox space** is actually the phone's free physical storage (a 500 MB minimum remaining threshold), with no software knob to adjust; the **upload size limit** lives in the computer connection's relay/RemoteTaskFiles path (200 MB), which is a different chain and does not belong in the same change.

## What Changes

- 设置 (Settings) › 干活时 (During work) gains 「每个任务的沙箱时间上限」 (Sandbox time limit per task): levels 30 (default, consistent with the current state) / 60 / 120 / 240 minutes, a single-choice dialog, taking effect from the next task (same semantics as the step limit).
- `FeatureFlags` gains a storage key and read/write (only values within the levels are recognized; anything else counts as the default 30).
- Wiring: both the main task's and the sub-tasks' `toolDispatcher` in `TaskRun` pass the value → `SandboxCapability(budgetMs)` → `SandboxGuard(budgetMs)` (the constructor parameter already existed, it was just never assigned from outside).
- The exhaustion message no longer hardcodes "30 minutes"; it outputs the number of minutes dynamically from the actual budget.
- New strings and translations for all languages; core/app unit tests.

## Capabilities

### New Capabilities

- `sandbox-time-budget`: the cap on cumulative sandbox command duration per task — default value, user-adjustable levels, when it takes effect, and exhaustion behavior.

### Modified Capabilities

(No existing spec directories; not applicable.)

## Impact

- core: `SandboxGuard` (dynamic message), `SandboxCapability`/`TaskTools.toolDispatcher` (pass-through parameters, defaults unchanged).
- app: `FeatureFlags` (new key), `TaskRun` (two wiring points), `SettingsScreen`/`SettingsSubPages` (one settings row + a level dialog), `strings_settings.xml` × all languages.
- Unchanged behavior: the default is still 30 minutes; the 10-minute per-command cap (`timeoutSec` 1..600) is unchanged; repeated failures of the same command being judged a loop (sandbox_loop) is unchanged; terminals the user opens themselves are not bound by the budget.
