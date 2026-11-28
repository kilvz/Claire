# Evidence timeline (real device: Huawei Mate X2, TET-AN50, API 31, 2026-11-21)

| Time | Action | Result |
|---|---|---|
| 17:32 | `./gradlew :app:assembleDebug` builds the debug package containing the changes | BUILD SUCCESSFUL |
| 17:33 | Pre-install check: the latest run record is 2026-11-20 (no active tasks), a screenshot confirms the foreground is the empty chat home screen | Safe, OK to proceed |
| 17:33 | `adb install -r app-debug.apk` | Success; the first launch shows the "Language preference" guide, tapping "Not now" keeps the existing settings |
| 17:34 | sidebar › Settings › Task runs | The new row "Per-task sandbox time limit" appears after "Max runtime for scheduled tasks", with the default value **30 minutes** (screenshot 01) |
| 17:34 | Tap that row | Dialog title + description copy + four radio tiers: 30 / 60 / 120 / 240 minutes (screenshot 02) |
| 17:35 | Select "240 minutes" | The row value is written back as **240 minutes** (screenshot 03); `run-as … cat shared_prefs/feature_flags.xml` shows `sandbox_budget_limit_minutes" value="240"` |
| 17:36 | Select "30 minutes" back | prefs returns to `value="30"`, the device is restored to its default state, exit settings |

Default behavior being unchanged and the guard math (rejection timing on the default/custom tiers, minute count in the message, capTimeout clamping) are covered by `SandboxGuardTest` (core, 5 cases) and `FeatureFlagsSandboxBudgetTest` (app, 1 case); the four full local test groups are below.

## Local tests (2026-11-21)

- `./gradlew :core:test`: all green (including the 5 new SandboxGuardTest cases)
- `./gradlew :core:cliTest`: OK
- `python3 -m unittest discover -s tools/i18n`: 12 cases OK; `python3 tools/i18n/translations.py check`: 3718 resources / 12 languages pass
- `python3 -m unittest discover -s tools -p 'test_pull_logs.py'`: OK
- `./gradlew :app:testDebugUnitTest`: all green (in the first round TerminalHubTest.deletingProjectClosesSharedProjectTerminal had a one-off 15s timeout flake; rerunning it alone and rerunning the whole group were both green, unrelated to this change — that case's 2s→15s budget is a timing-sensitive item the maintainer just adjusted in 3b513b1e)
- `./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin` (the composition of the CI app group): passes
- `./gradlew :core:relayTest` (Go group): BUILD SUCCESSFUL

## Device test cases

Not run (this change does not involve device test cases; instrumentation on this machine is unusable because the accessibility binding hangs, see AGENTS.md — this ROM state was verified over two consecutive rounds, it only clears after a reboot and the PIN lock makes adb unable to unlock it).
