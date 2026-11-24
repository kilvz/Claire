# Design: LockState polling self-healing (#638)

## Context

`DeviceStateWatcher` provides a `StateFlow<Boolean>` for the main screen being "not operable (screen off or locked)":
when a broadcast (SCREEN_OFF / SCREEN_ON / USER_PRESENT) arrives it re-reads once;
when someone is subscribed and the snapshot is "screen on and locked" it re-reads every 500ms (introduced for #590/#627: when unlocking, USER_PRESENT arrives before the lock screen goes away, and no broadcast follows afterwards, so only polling can fill the gap). `TaskGate` checks it before tools that change the screen; if locked, it pauses as "waiting for you to unlock the phone" and suspends on `locked.first { !it }` awaiting automatic recovery.

The gap exposed by #638: after the screen goes off, the snapshot is `interactive=false`, so the polling guard `last.interactive && last.locked` is always false — polling stops, and recovery depends entirely on SCREEN_ON / USER_PRESENT. On HyperOS / Android 17, when a broadcast is dropped, `locked` stays true forever; on that ROM a transient false report from `isDeviceLocked` is likewise left uncorrected.

## Goals / Non-Goals

**Goals:**
- When all broadcasts are lost (not a single SCREEN_ON / USER_PRESENT arrives), the task can still continue automatically within about one polling period after the screen is on and unlocked;
- A transient `isDeviceLocked` false report no longer causes a permanent pause (the next read showing unlocked restores it);
- Do not change the pause decision semantics or the pause/resume wording, and do not touch other gate reasons.

**Non-Goals:**
- Do not solve the ROM lying case where "`isDeviceLocked` keeps returning true while already unlocked" (that needs another arbitration layer, left to maintainers);
- Do not add a manual "continue" button to the LOCKED pause (unnecessary with polling self-healing; and it touches gate semantics);
- Do not change the polling period or add screen-off frequency reduction (500ms × one binder read, happening only during a pause, not worth complicating).

## Decisions

1. **Guard changed to "poll while locked"**: `if (last.locked) refresh()`. Screen off (`!interactive`) also counts as locked, so polling keeps running while the screen is off — exactly what self-healing needs: after a missed SCREEN_ON, the first poll that reads `interactive=true` brings the snapshot back to reality. Extra reads under a real lock are harmless; the existing behavior of restoring immediately on reading unlocked is unchanged.
2. **Re-read immediately when a subscription starts**: `collectLatest { watched -> if (watched) refresh(); while … }`. A wait hanging on the gate taking up to 500ms for the first self-heal is wasteful; read once at the moment of subscription so the stale state is calibrated on the spot. (With no subscribers it still never reads once, and the `noReadsWithoutSubscribers` case stays.)
3. **Rewrite the comments**: delete the old convention "don't read while the screen is off, wait for SCREEN_ON" and replace it with "re-read while locked, broadcasts are only an acceleration signal", write #638 into the comments, consistent with the existing comment style of #590/#627.
4. **Write tests against behavior**, not polling implementation details:
   - Rework `unlockSeenWithoutAnotherBroadcast`: also read while the screen is off (delete the "no read while screen off" assertion), and observe screen-on + unlock through polling when all broadcasts are lost;
   - Add `screenOnAndUnlockSeenWithoutAnyBroadcast`: start from screen-off and locked, change only the system state, never call `refresh()`, and have the gate release within two ticks — the minimal reproduction of #638;
   - Add `staleStateHealsOnSubscribe`: re-read on subscription start, so the stuck stale value is corrected immediately.

## Risks / Trade-offs

- **Continuous binder reads while the screen is off**: one `isDeviceLocked` every 500ms, occurring only when "someone is waiting on this state" (= a task paused waiting for unlock); compared with the user loss of being stuck for an hour, the cost is negligible.
- **Under a real lock the pause is unchanged and recovery is earlier**: the only outward behavioral change is for the better; if the system still briefly reports locked after the screen comes on (the window where USER_PRESENT precedes the lock screen going away), polling waits as before for `deviceLocked` to become false and will not release early (the `unlockSeenWhenKeyguardClearsBeforeDevice` case continues to cover it).
- **Snapshot concurrency**: `last` is `@Volatile`, and the read-modify-write between the polling and broadcast/subscription threads is still "last write wins", consistent with today; all write paths read the real system value, so no source writes a hardcoded value.
