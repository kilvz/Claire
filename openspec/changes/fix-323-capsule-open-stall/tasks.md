## 1. Expand-path guard

- [x] 1.1 `CapsuleCardContent.kt`: add `runOverlayOpen(reduceMotion, animate)` (`withTimeoutOrNull((CARD_IN_MS * scale) + OPEN_GRACE_MS)`, with scale taken from MotionDurationScale and `coerceAtMost(10f)`), and bring the whole open branch of `OverlayMotion` (the SETTLE wait, `opened()`, `animateTo`) under the guard; on timeout `snapTo(1f)` and call `opened()` as a make-up, with `openedCalled` guaranteeing exactly one call; the animations-off branch behavior is unchanged
- [x] 1.2 Confirm that the side window `CapsuleOverlay` (always `open = true`) benefits automatically through the same composable, with no call-site changes

## 2. Unit tests

- [x] 2.1 `OverlayMotionTest` adds the four `runOverlayOpen` scenarios: normal completion without the fallback, reduceMotion returning directly, missing frames (`awaitCancellation`) settling on timeout, and slow-motion multiplier conversion (an extreme multiplier is capped at 10x)

## 3. On-device evidence (Huawei Mate X2 / HarmonyOS 4.2)

- [ ] 3.1 The existing `CapsuleWindowDeviceTest.closedCardLeavesNoPixelsBehind` passes after the change (collapse residual-frame regression) — **blocked**: on this machine the accessibility binding wedges after instrumentation force-stops the app (the binding hangs and no longer retries; a reboot would solve it but the PIN lock cannot be cleared via adb), so both device cases never reach their assertions; see evidence.md
- [ ] 3.2 `CapsuleWindowDeviceTest` gains expand-state geometry assertions: after tapping the card open, the `capsule-card` node bounds are on-screen with symmetric left/right margins (centering tolerance), and the height is not below the expected lower bound for that tier; run on a real device with a screenshot kept — the case is written and compiles; blocked on this machine as above, awaiting a device with a working accessibility environment
- [x] 3.3 Evidence goes into `evidence/`: timeline + screenshots (whether they ship with the PR is up to the user) — the timeline and unit test results are in place; on-device screenshots are missing because the case could not be executed
- [x] 3.4 (Found during implementation) Fix the test-infrastructure bug where `DeviceSupport.rebindAccessibility()` only recognized the full component name and did not match the short form written by the App itself (`PermissionCommands.accessibilityService`), causing rebinds to be skipped

## 4. Build and wrap-up

- [x] 4.1 `./gradlew :app:testDebugUnitTest` (full suite) and `:app:assembleDebug` pass
- [x] 4.2 Commit (Chinese commit message), push to the fork, open a PR linked to #323 with the measured evidence on the first screen; state honestly that the original frame stall could not be reproduced and that the guard is proven by unit tests and the code path (PR #621)
