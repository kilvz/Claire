# fix-323-capsule-open-stall evidence timeline (2026-11-14)

## Code and unit tests (local machine)

- `OverlayMotionTest`: 8/8 passing (the 4 collapse scenarios are pre-existing; the 4 expand scenarios are new in this change:
  `reducedMotionSkipsTheOpenAnimation`, `normalAnimationFinishesBeforeOpenTimeout`,
  `missingFramesCannotKeepTheOverlayOpeningForever`, `openSystemSlowMotionGetsEnoughTimeToFinish`).
  Result file: `overlay-motion-unit-tests.xml`.
- `./gradlew :app:testDebugUnitTest :core:test`: all passing (BUILD SUCCESSFUL).
- `./gradlew :app:compileDebugAndroidTestKotlin`, `:app:assembleDebug`: passing.

## Real device (Huawei Mate X2 / TET-AN50 / HarmonyOS 4.2 / API 31, USB)

Goal: run `CapsuleWindowDeviceTest` (collapse residual-frame regression + the newly added expand geometry assertions). **It could not be executed; the environment was blocked**, recorded faithfully:

1. First `connectedDebugAndroidTest` (class filter CapsuleWindowDeviceTest): both cases failed with
   `IllegalStateException: Mika accessibility service is not enabled` (DeviceSupport.kt:50).
   Consistent with DeviceSupport's documentation: instrumentation force-stops the App at startup, the accessibility service is recorded as crashed and no longer rebinds automatically, and normally the "detach then reattach" in `rebindAccessibility()` should trigger a rebind.
2. Manual rebind attempts (all ineffective):
   - writing `settings put secure enabled_accessibility_services` back — note the first attempt mistakenly used the short form
     `com.wangzan101.mika/.a11y.MikaAccessibilityService`, later changed to the full form
     `com.wangzan101.mika/com.wangzan101.mika.a11y.MikaAccessibilityService` (DeviceSupport matches the full form as a string);
   - detach → force-stop → reattach; a full off/on cycle; cycles with longer intervals (5s/15s);
   - going through the system Settings UI (screenshots + visual-model navigation): Accessibility → Downloaded services → Claire (status "Service stopped") →
     the "Use Claire" switch → the system confirmation dialog "Allow Claire to use accessibility services?" → Allow.
3. The system state stayed at `Bound services:{} / Binding services:{com.wangzan101.mika/...MikaAccessibilityService}`, with
   `Crashed services:{}` empty; the App process was alive (`dumpsys activity services` shows no ServiceRecord for that service,
   i.e. the binding never reached the application side), and logcat showed no sign of retries.
4. Assessment of recovery options: a reboot would very likely clear the pending binding, but this machine has a PIN lock that adb cannot
   clear (known in AGENTS.md), and a reboot would lock out subsequent sessions, so it was not done.
5. The connected tests were re-run three times during this period and failed the same way at obtaining an instance from service() (an environment issue, not introduced by this change —
   the first failure occurred on a freshly installed package before any source change was installed; and the change does not touch the accessibility/service path).
6. Along the way a test-infrastructure bug was located and fixed (included in this PR): the App's own recovery logic
   (`PermissionCommands.accessibilityService` in `core/device/shell/RecoveryPermissions.kt`) writes
   `enabled_accessibility_services` using the short form (`package/.a11y.MikaAccessibilityService`), whereas
   `DeviceSupport.rebindAccessibility()` only matched the full form as a string — so on any machine where the App's auto-repair had run,
   the rebind was silently skipped (local SPMN logs confirm the App rewrites it at startup; the maintainer also mentioned in 41468b39 that "this pixel test has not yet been run
   in the required accessibility environment", presumably for the same reason). DeviceSupport now recognizes both forms (and strips both on removal).
   The pending-binding problem on this machine is independent of that bug: after the fix the matching works and the rebind cycle keeps running, but the binding pending on the system side is still not cleared,
   and the test still never reaches its assertions on this machine.

Conclusion: this machine's real device does not meet the environment requirements for running this device test (the accessibility binding wedges after instrumentation force-stops the app, and the ROM no longer retries). The device tests (including the new `openedCardReachesFullCenteredGeometry`) need to be executed on a device where accessibility can rebind normally — upstream CI/maintainer devices have been running CapsuleWindowDeviceTest all along (51bd6d8f and a0c1ea33 each fixed that test), so please rely on their results. The PR body states this honestly.

## Appendix: original #323 symptoms and their code-side counterparts

- Reporter (Honor 30 Pro+ / HarmonyOS 4.2, v0.1.33–0.1.37): the app picture expanded by tapping the floating ball is incomplete (shrunk, biased toward the docked edge);
  tapping × shifts the whole thing right leaving half; the capsule hides behind the residual card, and tapping it returns to half-open — a loop.
- Code counterparts: the `OverlayMotion` collapse path has a timeout fallback (`runOverlayClose`) + watchdog (97053397) + forced invisibility after closing
  (ce037717) + pixel regression; the expand path's `repeat(SETTLE_FRAMES){ withFrameNanos{} }` and `animateTo(1f)`
  have no protection at all, so a frame stall means being permanently stuck at the intermediate value, and `opened()` (→`cardOut`, window visibility sync) is not executed while the side-window capsule keeps drawing
  — all three symptoms can be explained uniformly by "the expand stuck in an intermediate state" (shrinking against the docked edge ≈ an intermediate scale value with transformOrigin pointing at the docked edge).
- This fix does not depend on reproduction: the guard is proven by the unit test's `awaitCancellation` (frame stall) scenario, and the behavioral impact is only the snapTo(1f) fallback after the timeout,
  whose terminal state matches a natural animation completion.
