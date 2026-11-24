# On-device walkthrough evidence: sandbox storage tier settings (follow-up to #774)

Device: Huawei Mate X2 (TET-AN50, API 31); package: app-debug.apk (built from this branch, based on main 3ad11d66).
Pre-walkthrough check: the phone is unlocked, Mika is on the home project list, no tasks are running (screenshot from 2026-11-22 07:28 left in /tmp and not committed).

## Timeline (2026-11-22 07:29–07:32)

| Time | Step | Result |
|---|---|---|
| 07:29 | `adb install -r app-debug.apk`, then go to 设置 (Settings) › 任务运行 (Task runs) | Two new settings rows appear: 「单个上传文件上限 = 200 MB」 (single upload file limit = 200 MB) and 「单个工作区上限 = 4 GB」 (single workspace limit = 4 GB), matching the default tier → `01-defaults-200mb-4gb.png` |
| 07:30 | Tap 「单个上传文件上限」 (single upload file limit) | The dialog shows four tiers 200/512/1024/2048 MB, with the subtitle stating the scope and default value → `02-attachment-dialog-tiers.png` |
| 07:30 | Select 1024 MB | The row value is written back as 「1024 MB」 → `03-attachment-1024mb-set.png` |
| 07:31 | Tap 「单个工作区上限」 (single workspace limit) | The dialog shows four tiers 4/8/16/32 GB → `04-workspace-dialog-tiers.png` |
| 07:31 | Select 16 GB | The row value is written back as 「16 GB」 → `05-workspace-16gb-set.png` |
| 07:31 | `run-as … cat shared_prefs/feature_flags.xml` | `attachment_file_limit_mb=1024` and `workspace_limit_gb=16` are persisted |
| 07:32 | Restore the two rows to 200 MB / 4 GB respectively | Both row values and prefs return to the defaults (`attachment_file_limit_mb=200`, `workspace_limit_gb=4`) → `06-restored-defaults.png` |

## Conclusion

- Zero change to default behavior: when unset, the two rows show 200 MB / 4 GB, matching the existing hardcoded values.
- The full chain works: tier selection, row value echo, prefs persistence, and restoring the defaults (the row value echo is itself behavioral evidence that the app reads prefs, not dependent on reading values from screenshots).
- The device is the user's daily driver; no tasks ran during the walkthrough and no other apps were touched.

Local tests: `:core:test` `:core:cliTest` `:app:testDebugUnitTest` `relayTest` (Go) all green;
`python3 -m unittest discover -s tools/i18n` and `translations.py check` (3916 resources ×12 languages),
`test_pull_logs.py`, `:app:assembleDebug`, `:app:compileDebugAndroidTestKotlin` pass.
Device test cases (connectedDebugAndroidTest) were, as usual, not run (the accessibility binding hangs on this machine's ROM, see AGENTS.md).
