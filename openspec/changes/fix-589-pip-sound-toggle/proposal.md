## Why

The sound button in the top-left corner of the PiP window (AgentPip) is a "one-shot restore sound": after a task auto-mutes, the user can tap once to let the current App's sound out, but tapping again has no reverse effect — the sound keeps playing and the only way to stop it is to close the whole PiP window and wait for the task to wrap up (issue #589, #588 is a duplicate). The root cause is that once `AppMuter.unmute()` puts the key into the `unmuted` set, there is no "mute again" entry point anywhere in the pipeline.

## What Changes

- `AppMuter` gains the symmetric user action `muteAgain(packageName, user)`: when the user explicitly presses it, mute directly through `AudioMute.mute` (not going through the auto-mute `wanted`/main-screen-visibility decision), and move the key from `unmuted` back to `muted`; also expose the `unmuted` set as a `StateFlow` so the button can show the real state.
- `TaskRun` turns the overlay's `unmute()` into a toggle that switches based on the current state: when the target App's sound has already been restored by the user, take the "mute again" path; otherwise keep the existing "restore sound"; the takeover path goes through `TakeoverPort` in the off direction of `setSound(pkg, false)`. Add an outward `StateFlow` for the PiP sound state (target App + unmuted records combined).
- `AgentPip`'s sound button switches icon and label following the real state (`Restore sound` / `Mute`), and toggles on tap; when there is no state information it keeps showing "Restore sound" as today.
- Add the `pip_mute` string and sync it to the 8 language directories alongside the existing `pip_unmute`.
- Unit tests: `AppMuterTest` gains coverage of `muteAgain` semantics (mutes directly, returns to automatic management, the wrap-up restoreAll still restores, records are not lost on failure).

Not changed: the auto-mute decision rules (`wanted`, main-screen visibility, packages not to touch); the "mute opened apps" toggle in settings; the restoration responsibility of the wrap-up `restoreAll`; the routing logic of the takeover/virtual-display paths.

## Capabilities

### New Capabilities
- `pip-sound-toggle`: the two-way toggle behavior of the PiP sound button — both the button action and the displayed state are based on the real records of the task mute ledger.

### Modified Capabilities

(None — the existing auto-mute requirement of `AppMuter` is unchanged; only an explicit user reverse action is added.)

## Impact

- `app/src/main/java/com/wangzan101/mika/task/AppMuter.kt`: add `muteAgain` and the `userUnmuted` StateFlow.
- `app/src/main/java/com/wangzan101/mika/task/TaskRun.kt`: `unmute()` becomes a toggle, and a new PiP sound state flow is added.
- `app/src/main/java/com/wangzan101/mika/takeover/Takeover.kt` / `TakeoverPort`: add the off direction to the takeover path.
- `app/src/main/java/com/wangzan101/mika/ui/`: `ChatViewModel` (actions and state), `ChatScreen`/`MainActivity` (wiring), `AgentPip.kt` (stateful button).
- `app/src/main/res/values*/strings_chat.xml`: add `pip_mute`.
- `app/src/test/java/com/wangzan101/mika/task/AppMuterTest.kt`: new test cases.
- Risk: the reverse mute is still per package (another instance of the same package on the main screen loses sound as well) — the same mechanism as the existing auto-mute, with a unified restore at wrap-up; explicit user intent takes priority, symmetric with `unmute`.
