# #638 lock-screen pause stuck — verification evidence

## Unit tests (proof of the fix)

`./gradlew :core:test :core:cliTest :app:testDebugUnitTest` all green (2026-11-16).
`LockStateTest` all 5 cases pass, including:

- `screenOnAndUnlockSeenWithoutAnyBroadcast` (new, minimal reproduction of #638): starting from screen-off locked,
  not a single SCREEN_ON / USER_PRESENT arrives, polling still reads while the screen is off (`reads > dark`),
  and after the system state changes to screen-on + unlocked, the gate lets it through within two ticks. Old code failed at the first step of this case
  (the screen-off snapshot has interactive=false, which blocked polling, so reads did not grow).
- `staleLockedHealsOnSubscribe` (new): re-read immediately when the subscription starts, and a missed unlock does not wait for the first 500ms.
- `unlockSeenWithoutAnotherBroadcast` (reworked): removed the "do not read while the screen is off" assertion; when USER_PRESENT arrives first
  but the device is still locked, polling continues until deviceLocked=false and only then lets it through.
- `unlockSeenWhenKeyguardClearsBeforeDevice`, `noReadsWithoutSubscribers` (existing, no regression).

## Device regression (proof of no regression)

Huawei Mate X2 (TET-AN50, HarmonyOS 4.2 / API 31), this branch's debug package, real model glm-5.3-flash,
task injected through the "computer connection" API (run 20261116-090428-bdn5xo):

1. The phone screen was off when the task was created (power-button screen-off, before the PIN lock delay) → about 10 seconds later the task API state was `paused`
   — the screen-off pause path via "locked" works normally (`!interactive` decision, untouched by this change).
2. Power-button screen-on → the task API state returned to `running`, with no manual intervention;
   the `dumpsys window` focus window was `com.android.settings.HWSettings` — after resuming, the task really kept operating the phone
   (the first few steps of events.jsonl were opening settings). **Screen-off pause → screen-on auto-continue, the chain is complete.**
3. After that the task repeatedly retried the "return to home" step (navigation bar lookup failed, shell command rejected), which is a model capability issue,
   unrelated to this change; it was cancelled after the verification goal was reached (`run-090428-events.jsonl` keeps the full record).

Limitations: this machine has a PIN lock that adb cannot clear, so the end-to-end chain "lock screen → enter PIN to unlock → auto-continue" cannot be completed on this machine
(this ROM's brief screen-off did not trigger the PIN lock; what was measured above is the screen-off/screen-on variant); that scenario is deterministically covered by
`screenOnAndUnlockSeenWithoutAnyBroadcast` with a virtual clock (it recovers even when all broadcasts are lost).

Screenshot `01-task-running-after-resume.png`: the chat UI while running after resume (judge status by the API/event stream).

## Process notes

- During verification we found and incidentally fixed a pitfall in this machine's test environment: when experimenting by swapping the App's providers.json, the app rewrites
  provider-keys.json while running and discards provider keys that are not in the current config — **swapping providers must back up/restore
  provider-keys.json together**, otherwise the real model channel reports "API Key invalid".
  (This pitfall is unrelated to this PR, and has been recorded in the local AGENTS.md process.)
