# #589 on-device verification timeline (Huawei Mate X2, HarmonyOS 4.2, API 31)

## Environment

- Build branch `fix/589-pip-sound-toggle` @ 143e6576 (22 files, +171/−25, including the whole fix); the debug package was installed on the real device.
- The task was submitted through the USB connection channel `POST /api/v1/tasks`: **open Huawei Music and play the daily recommended songs, keep playing without stopping** (run `28846183-…`).
- The sound toggle was triggered through a debug-only temporary broadcast calling `TaskRun.togglePipSound()` (the same entry point as the pip button; the temporary code was removed after verification and is not in the PR).

**Why a broadcast instead of tapping the button**: while the task operates the App on the virtual display, the system input focus is held by the virtual display (`mFocusedWindow=com.huawei.music`, display 94), so `adb shell input tap` is all routed into the virtual display and the overlay on the main-screen Mika window receives no injected input; `input` has no `--display` parameter (API 31), and `sendevent` is rejected by the ROM (Permission denied). This is an environment limitation of this device and unrelated to the fix itself; the button tap chain (`AgentPip` → `ChatViewModel.togglePipSound` → `togglePipSoundIn` → `TaskRun.togglePipSound`) is covered by `ChatViewModelTest`.

## appops timeline (PLAY_AUDIO of com.huawei.music)

| Time | Action | appops | Conclusion |
| --- | --- | --- | --- |
| T0 | Task opens Huawei Music (auto-mute takes effect) | `ignore` | Precondition holds |
| T1 | Trigger the sound toggle (1st time, should restore) | `Default mode: allow` | Restored ✓ |
| T2 | Trigger the sound toggle (2nd time, should mute again) | `ignore` | **Reverse mute ✓** |
| T3 | Trigger the sound toggle (3rd time, should restore again) | `Default mode: allow` | Repeatable ✓ |
| T4 | Cancel the task, wrap-up `restoreAll` | `Default mode: allow` | Wrap-up restore ✓ |

logcat showed no `could not restore/mute again` failure logs throughout.

Another independent task (`c2d97c75-…` opening Huawei Music and staying on the home page) likewise verified T0 auto-mute (`ignore`) and T4 wrap-up restore (`allow`).

## Unit tests

- `AppMuterTest` adds 4 cases: re-muting after a restore returns to automatic management while the wrap-up still restores; muting even when visible on the main screen (explicit intent takes priority); a failed re-mute keeps the restored state for a retry; the `userUnmuted` flow updates with the actions.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` all green; `:core:test` all green (core untouched).

## Screenshots (kept locally, not published with the PR)

- `mika-pip2.png`: the expanded PiP window while the task is running, with the "Restore sound" button in the top-left corner (behavior before the change: one tap restores, another tap does nothing).
- `mika-tk2.png`: the preview auto-expanded while waiting for takeover (that layout has no control buttons and was used for orientation during verification).
- `evidence-running.png`: main-screen state during verification.
