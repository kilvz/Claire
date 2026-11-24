## Why

#323 (Honor 30 Pro+ / HarmonyOS 4.2): the card expanded by tapping the floating ball stops half-open — the picture is shrunk and biased toward the docked edge ("the opened app screen is not full screen"); tapping the × in the top-right corner shrinks the whole card toward the docked edge yet leaves half of it on screen; the capsule at the side still draws behind the residual card, and tapping it goes back to half-open. The three symptoms cycle, and the reporter confirmed on v0.1.37 that they still persist. The collapse path already has three layers of stall protection (`runOverlayClose` frame-stall timeout, the 97053397 wrap-up watchdog, ce037717 forcing invisibility after closing + an on-device pixel-comparison test), but **the expand path has no equivalent guard at all**: in `OverlayMotion`, both `repeat(SETTLE_FRAMES) { withFrameNanos {} }` and `animateTo(1f)` hang on the frame clock, so as soon as the overlay stops receiving frames it suspends forever — `opened()` is not executed, `cardOut` is not set (the capsule at the side keeps drawing), the window visibility state machine is stuck in opening, and the card stops at an intermediate scale value.

## What Changes

- Add a frame-stall guard `runOverlayOpen`, symmetric with the collapse path, to `OverlayMotion`'s expand path: the timeout is converted by `MotionDurationScale`, and on a frame stall it falls back to `snapTo(1f)` and makes the `opened()` call, guaranteeing the state machine (`cardOut`, `syncWindowVisibility`) always settles.
- The same `SETTLE_FRAMES` wait in the side window (`CapsuleOverlay`, always `open = true`) is brought under the guard as well: the capsule will not fail to appear forever because of a frame stall.
- Unit tests mirror `OverlayMotionTest`: four scenario classes — normal animation, animations off, missing-frame timeout, system slow-motion multiplier.
- On-device evidence (Huawei Mate X2 / HarmonyOS 4.2, the same OS family as the reporting device): run the existing `CapsuleWindowDeviceTest.closedCardLeavesNoPixelsBehind` (collapse residual-frame regression) and add expand-state geometry assertions — the card's visible size reaches the full size of the scale tier, it is horizontally centered, and there is no edge clipping (the cross-section of point 1 of #323).

## Capabilities

### New Capabilities
- `capsule-overlay-motion`: frame-stall protection for the floating capsule overlay animation (card expand/collapse, side-window appearance) — both the expand and collapse paths must settle to a terminal state within a finite time, and a stalled frame clock must not leave the state machine stuck in the middle.

### Modified Capabilities

(None — `openspec/specs/` still has no main spec; this change establishes the first spec for this capability.)

## Impact

- `app/src/main/java/com/wangzan101/mika/capsule/CapsuleCardContent.kt` (`OverlayMotion`, new `runOverlayOpen`)
- `app/src/test/java/com/wangzan101/mika/capsule/OverlayMotionTest.kt` (new test cases)
- `app/src/androidTest/java/com/wangzan101/mika/capsule/CapsuleWindowDeviceTest.kt` (new expand-state geometry assertions; used for on-device evidence)
- No changes to docking geometry, no touching of the `CardDocking`/`CapsuleDocking` pure functions, and no involvement of other overlay behavior.
