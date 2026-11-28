# Project experience

| Mechanism | Applicable signal | Verification scope |
| --- | --- | --- |
| [Task goal counting and action records](patterns/task-goal-progress.md) | A cross-app task stuck at 0/N, or a whole run with actions but no score | Two runs distinguish a missing initial checklist from later missed reporting; the script covers backfill, counting and wrap-up |
| [Associating playback frames with actual actions](patterns/task-playback-frames.md) | A click followed immediately by Back, multiple actions in the same round, markers appearing on the result frame | Event-to-frame association has unit tests; the original #348 was verified on one phone |
| [Compose device tests fail during input initialization](patterns/espresso-input-initialization.md) | Before the test case touches the UI, Espresso reports that `InputManager.getInstance` does not exist | Reproduced #644 on an SDK 37 phone; both test cases pass after upgrading the test dependency |
| [The denominator of the cache hit rate](patterns/cache-report-coverage.md) | Mixed providers, missing cache fields, old records that only have cumulative totals | Mixed reporting is covered by unit tests; the scripted session display was also verified on one phone |
| [Live notifications and chat-page task progress](patterns/promoted-task-notifications.md) | No capsule on the home screen, it keeps showing "Running", or the task text has advanced while the count is still 0 | System display verified on an Android 16 device; on that device it stayed at "Address lookup" after switching apps; same-name interference and AI segment stalls were reproduced in unit tests |
| [Progress checklist treated by the model as a tool call](patterns/progress-as-tool-call.md) | `task_progress · not successful` appears in the first round while subsequent phone operations continue | Device joint testing captured 4 calls with the same name and the checklist updated successfully; the script covers invalid parameters and parallel calls |
