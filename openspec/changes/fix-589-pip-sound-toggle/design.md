## Context

The existing pipeline for picture-in-picture (PiP) sound: a task auto-mutes (`AppMuter`, appops muting by "package + user", with the original mode recorded in a persistent ledger) → the user taps "Restore sound" in the PiP window (`ChatViewModel.unmutePip` → `ChatActions.unmuteIn` → `TaskRun.unmute()` → the `restorePipSound()` route) → during takeover it goes through `Takeover.unmute()` → `TakeoverPort.unmute` → `AppMuter.unmute()`; otherwise it goes to the virtual-display target App → `AppMuter.unmute()`. The latter puts the key into the `unmuted` set (this task no longer auto-mutes it), and the whole pipeline has no reverse operation. `AudioMute.mute()` itself is a safe re-mute entry point: when the key is not in the ledger it first records the current (already restored) original mode, so the wrap-up `restoreAll` can still restore it as before.

## Goals / Non-Goals

**Goals:**
- The PiP sound button can be both turned on and off: after the sound has been restored, pressing it once more can mute the target App again, with the icon/label following the real state.
- The virtual-display path and the takeover path behave the same.
- The reverse mute reuses the existing ledger mechanism, and task wrap-up still restores the sound (the recovery logic for a process killed mid-way is covered as well).

**Non-Goals:**
- Do not change the auto-mute decision (`wanted`, main-screen visibility, "packages not to touch") or the settings toggle.
- Do not do fine-grained control per media stream/volume; muting stays per package (the same mechanism as today).
- Do not change the core `TaskState`/main-loop semantics.

## Decisions

1. **The state source is `AppMuter`'s `unmuted` set, exposed as a `StateFlow<Set<String>>`** — it is the real ledger of "the user explicitly restored the sound", and the button state flips based on it, continuing the principle of "not faking switch states unrelated to real audio". The alternative "query the actual appops mode at render time" was rejected: asynchronous shell calls, no reactive refresh, and the target App switches frequently.

2. **`AppMuter.muteAgain()` calls `audio.mute(key)` directly, without going through the `wanted()`/main-screen-visibility decision** — symmetric with `unmute()` directly calling `audio.restore(key)`: both are explicit user intent and take priority over heuristics. On success the key moves from `unmuted` back to `muted` (back under automatic management); on failure it stays in `unmuted` (the button keeps "Mute" for a retry, with the wrap-up `restoreAll` as the fallback) — symmetric with `unmute()`'s failure semantics.

3. **`TaskRun.unmute()` becomes the toggle `togglePipSound()`**: if the current target key ∈ `unmuted` → take the "mute again" path, otherwise keep the existing "restore sound". The takeover path adds `muteAgain(packageName)` on `TakeoverPort` (TaskRun's anonymous implementation calls `p.audio.muteAgain` directly), and `Takeover` adds the symmetric `muteBack()`; do not borrow `setSound(pkg, false)` — its current implementation maps to `opened()`, which in the takeover scenario gets rejected by the "no muting while visible on the main screen" decision and wrongly reports "may make sound".

4. **State wiring follows the `frames`/`pipInput` side-channel StateFlow pattern**: inside `TaskRun`, `combine(target App flow, userUnmuted flow)` yields `pipSoundOn: StateFlow<Boolean>`, exposed through TaskService (following the task being displayed); ChatScreen receives it as a parameter and `AgentPip` receives it as `soundOn: Boolean`. Do not put it into `PipUi`/`TaskState` — that would leak the appops muting concept into the core published state.

5. **With no record the button stays at "Restore sound"**: when auto-mute is off and the target has never been muted, the key ∉ `unmuted`, and the button shows the same as today; pressing it once enters `unmuted` (current behavior), after which the reverse works. Do not additionally distinguish the "never muted" form, to avoid introducing a second switch state.

## Risks / Trade-offs

- [The re-mute is still per package, so another copy of the same package visible on the main screen loses sound too] → Same mechanism as the existing auto-mute; it only happens when the user explicitly presses, and the wrap-up/restore fallback restores it, so the risk window is only during the task.
- [`unmuted` lives only in `AppMuter`'s memory and is lost after a process restart, with the button reverting to "Restore sound"] → After a restart the task also goes through the recovery flow that rebuilds the mute state, consistent with today; no persistence is added (the current `unmuted` is not persisted either).
- [State briefly lags when the target App switches quickly] → `combine` recomputes on both reactive flows as soon as the switch happens; no debouncing is added.
- [Muting again during takeover affects the sound of the main-screen App in the user's hands] → That is exactly what the user asks for by pressing the button; the label says "Mute" explicitly.

## Migration Plan

A purely additive behavior change, no data migration. Rollback is just reverting the code; the re-mute records in the ledger are cleaned up as usual by the wrap-up `restoreAll` / startup recovery, leaving no dirty state.
