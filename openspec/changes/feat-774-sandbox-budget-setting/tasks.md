## 1. core: guard and pass-through

- [x] 1.1 The `SandboxGuard.exhausted()` message now emits the minute count dynamically from `budgetMs`; added `SandboxGuardTest`: under default and custom budgets, no rejection before the limit and rejection once reached (error code, usedMs, message containing the actual minute count), and `capTimeout` clamped to the remaining time without touching legal requests
- [x] 1.2 `SandboxCapability` gains a `budgetMs: Long = SANDBOX_BUDGET_MS` constructor parameter used in `guard()`; `TaskTools.toolDispatcher` gains a `sandboxBudgetMs` parameter passed through (default unchanged, zero changes for other callers)
- [x] 1.3 `./gradlew :core:test` all green

## 2. app: storage and wiring

- [x] 2.1 `FeatureFlags` adds `sandboxBudgetMinutes()`/`setSandboxBudgetMinutes()` (tiers 30/60/120/240, 0/invalid = default 30); added `FeatureFlagsSandboxBudgetTest`: default, tier round-trip, invalid value falling back
- [x] 2.2 Both `toolDispatcher` calls in `TaskRun`, for the main task and the subtask, pass `sandboxBudgetMs`

## 3. app: settings entry point and copy

- [x] 3.1 `SettingsSubPages.DuringWorkScreen` gains parameters, a `ListRow` (showing the current tier's minute count) and a `SandboxBudgetDialog` (RadioRow tiers, no "Unlimited" option); `SettingsScreen` wires up state and callbacks
- [x] 3.2 `strings_settings.xml` (values + all language directories) adds `settings_sandbox_budget`, `settings_sandbox_budget_sub`, the plurals `settings_sandbox_budget_minutes_n`; `python3 tools/i18n/translations.py check` passes
- [x] 3.3 `./gradlew :app:testDebugUnitTest` all green

## 4. Verification and delivery

- [x] 4.1 Full local run: `:core:test :core:cliTest :app:testDebugUnitTest` + the two i18n checks in the cli group
- [x] 4.2 On-device smoke test: default tier behavior unchanged + setting 240 takes effect for new tasks (evidence from logcat/run records, stored in evidence/)
- [ ] 4.3 `openspec validate`; branch, commit (Chinese commit message), PR (references #774, with test results and evidence notes)
