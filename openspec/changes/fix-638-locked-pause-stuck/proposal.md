# Proposal: lock-screen pause no longer gets stuck — polling self-heals when broadcasts are lost (#638)

## Why

#638: after a task pauses at 「等你解锁手机」 (waiting for you to unlock the phone), the user turns the screen on, unlocks, and uses the phone normally, yet the pause banner does not disappear for 1 hour, the promised 「解锁后自动继续」 (continue automatically after unlock) does not take effect, and the UI has no 「继续」 (Continue) either, so the only option is to stop and resubmit. The root cause is deterministic:
`DeviceStateWatcher.LockState`'s polling guard re-reads only when the previous snapshot satisfies `interactive && locked`, so after the screen goes off the snapshot stays at interactive=false, and the code comment explicitly stipulates 「熄屏时不读，等 SCREEN_ON」 (do not read while the screen is off; wait for SCREEN_ON) —
recovery rests entirely on the two broadcasts SCREEN_ON / USER_PRESENT; if the ROM (HyperOS / Android 17 etc.) drops either one,
`locked` stays stuck at true forever, and no later event ever triggers a re-read. On that ROM a transient false positive of `isDeviceLocked`
(caught again ~50 seconds after recovery) would likewise get stuck under the status quo, because nothing corrects it.

## What Changes

- The `LockState` polling guard changes from "re-read only when the screen is on and locked" to "re-read whenever locked" (screen off counts as locked):
  as long as the last reading was locked, keep re-reading the system state every 500ms, and recover immediately upon reading unlocked;
- when someone starts subscribing to this state, re-read once immediately (without waiting for the first 500ms), so the stale state heals right away;
- update the class comment and `LockStateTest`: the old 「熄屏时不读，等 SCREEN_ON」 convention is voided,
  and a regression case is added: 「广播全丢（SCREEN_ON / USER_PRESENT 一条不来），亮屏+解锁后任务仍自动继续」 (with all broadcasts lost — not a single SCREEN_ON / USER_PRESENT arrives — the task still continues automatically after the screen is turned on and unlocked).

Unchanged: the pause decision semantics (still `!interactive || deviceLocked`), the pause copy, the unattended unlock path,
and other pause reasons (moved away / assistant disconnected / user operating on the page).

## Capabilities

### New Capabilities
- `device-lock-pause-recovery`: after a task pauses because of screen-off/lock, how recovery is detected and continued automatically (without relying on broadcast delivery).

### Modified Capabilities

(None — this change does not modify any other requirement of any existing spec.)

## Impact

- Code: `app/src/main/java/com/wangzan101/mika/task/DeviceStateWatcher.kt` (only the `LockState` polling and comments).
- Tests: `app/src/test/java/com/wangzan101/mika/task/LockStateTest.kt` (change 1 case, add 2).
- Cost: while the screen is off and someone is waiting, two extra system queries every 500ms (isInteractive is locally cached, isDeviceLocked is one binder call),
  occurring only during 「任务暂停等待解锁」 (task paused waiting for unlock), which is acceptable.
- Risk: under a true lock the pause is delayed by at most one polling period (≤0.5s), with no functional impact; if a ROM's `isDeviceLocked`
  keeps returning true (rather than transiently) even when unlocked, this fix does not address it and the maintainer needs to arbitrate separately — stated honestly in the PR.
