# Tasks: LockState polling self-healing (#638)

## 1. Implementation

- [x] 1.1 `DeviceStateWatcher.kt`: change the `LockState` polling guard from `last.interactive && last.locked` to `last.locked`;
      when a subscription starts (watched turns true), call `refresh()` once immediately before entering the polling loop
- [x] 1.2 Rewrite the comments on `LockState` / `Reading`: remove the old 「熄屏时不读，等 SCREEN_ON」 (do not read while the screen is off; wait for SCREEN_ON) convention,
      state 「锁着就重读、广播只当加速信号」 (re-read while locked; broadcasts only accelerate), and reference #638 (consistent with the comment style of #590/#627)

## 2. Tests

- [x] 2.1 Rework `LockStateTest.unlockSeenWithoutAnotherBroadcast`: read while the screen is off too;
      change the assertion to 「广播全丢时轮询看到亮屏→解锁两步、gate 放行」 (with all broadcasts lost, polling sees the two steps screen-on → unlocked and the gate lets it through)
- [x] 2.2 Add `LockStateTest.screenOnAndUnlockSeenWithoutAnyBroadcast`:
      starting from screen-off locked, without calling refresh() even once, the gate lets it through within two ticks (minimal reproduction of #638)
- [x] 2.3 Add `LockStateTest.staleLockedHealsOnSubscribe`: re-read as soon as the subscription starts, correcting the stale 「锁着」 (locked) on the spot
- [x] 2.4 Confirm the existing cases do not regress: `unlockSeenWhenKeyguardClearsBeforeDevice`, `noReadsWithoutSubscribers`

## 3. Verification and delivery

- [x] 3.1 Full local unit test run: `./gradlew :core:test :core:cliTest :app:testDebugUnitTest` all green
- [x] 3.2 Build the debug package and install it on a real device: regression-check that the lock-screen pause path still works normally (screen off mid-task → the 「等你解锁手机」 (waiting for you to unlock the phone) banner appears),
      capture screenshots into evidence/
- [x] 3.3 Persist the evidence into evidence/ (timeline + screenshots), stating that automatic recovery after unlock is covered by unit tests
      (on this machine adb cannot unlock the PIN lock, so end-to-end unlock is impossible; stated honestly in the PR)
- [ ] 3.4 Commit, push the fork, open a PR linking #638, with the body containing the root cause, the fix, the test results and the limitations
