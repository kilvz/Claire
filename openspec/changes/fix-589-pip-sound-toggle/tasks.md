## 1. Mute ledger (AppMuter)

- [x] 1.1 `AppMuter` gains `muteAgain(packageName, user): Boolean` (directly `audio.mute`, the key moves from `unmuted` back to `muted` and is cleared out of `early`; on failure it stays in `unmuted` for a retry), and the `unmuted` set is exposed as a `StateFlow<Set<String>>` (updated on `unmute`/`muteAgain`)
- [x] 1.2 `AppMuterTest` gains cases: re-muting after a restore returns the key to automatic management, the ledger is still restored by `restoreAll` after a re-mute, a failed re-mute keeps the retry, and the `userUnmuted` flow updates with the actions

## 2. Task and takeover routing (TaskRun / Takeover)

- [x] 2.1 `TakeoverPort` gains `muteAgain(packageName)` (TaskRun's anonymous implementation calls `p.audio.muteAgain` directly), and `Takeover` gains the symmetric `muteBack(): Boolean`
- [x] 2.2 `TaskRun.unmute()` becomes `togglePipSound()`: if the target key ∈ `unmuted` → take "mute again" (through `Takeover.muteBack()` during takeover, otherwise `p.audio.muteAgain`), otherwise keep the existing restore path
- [x] 2.3 `TaskRun` exposes `pipSoundOn: StateFlow<Boolean>` (`combine` of the target App flow and the `userUnmuted` flow; during takeover the taken-over package is authoritative), and TaskService provides it outward for the task being displayed

## 3. PiP UI and strings

- [x] 3.1 `AgentPip` sound button made stateful: a `soundOn: Boolean` parameter; when already restored the icon/label is "Mute" (`pip_mute`), and tapping calls `onToggleSound`; ChatViewModel/ChatScreen/MainActivity are wired the same way as `frames`/`pipInput`
- [x] 3.2 Add the `pip_mute` string and sync it to the 8 language directories (values, zh, b+zh+Hant, ko, fa, in, ar, pt)

## 4. Verification

- [x] 4.1 `./gradlew :app:testDebugUnitTest` (the new AppMuter cases plus the existing suite all green) + `:app:assembleDebug` build
- [x] 4.2 On-device verification: task auto-mutes → PiP restores sound → press again to mute back (appops/dumpsys evidence) → wrap-up restores; screenshots and timeline kept in `openspec/changes/fix-589-pip-sound-toggle/evidence/`
