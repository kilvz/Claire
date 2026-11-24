## Context

`OverlayMotion` (`CapsuleCardContent.kt:272-314`) is the enter/exit animation shared by the capsule's two overlay windows: it only animates the scale/alpha of `graphicsLayer`, changing neither layout nor the window frame by frame. The collapse path has been hardened in three rounds: `runOverlayClose` falls back on frame stalls with `withTimeoutOrNull((CARD_OUT_MS * scale) + CLOSE_GRACE_MS)` (MotionDurationScale counted at up to 10x), and after the timeout does `snapTo(0f)` + `closed()`; plus a watchdog (97053397) and explicitly making the overlay root view invisible after closing (ce037717). The expand path (the `open = true` branch) has no timeout at all:

```kotlin
if (shown.value == 0f) repeat(SETTLE_FRAMES) { withFrameNanos { } }   // frame stall → suspend forever
opened()                                                                // cardOut=true, syncWindowVisibility
shown.animateTo(1f, tween(CARD_IN_MS, easing = MikaMotion.Decelerate)) // frame stall → stuck at an intermediate value
```

The card window's `onOpened` callback sets `cardOut` to true (so the side window `CapsuleOverlay` stops drawing that capsule) and syncs window visibility. A frame stall during SETTLE → `opened()` is not executed → the capsule at the side keeps drawing (the "floating ball hidden behind" in #323) and the window state is stuck in opening; a frame stall during animateTo → the card stops at a scale between 0.6 and 1.0, with `transformOrigin` pointing at the docked edge (symptom: incomplete picture, shrunk against the right edge). The side window itself (`CapsuleOverlay`, always `open = true`) has the same SETTLE wait in the same place, so with a frame stall the capsule never appears.

The reporting device is an Honor 30 Pro+ / HarmonyOS 4.2; the local physical device is a Huawei Mate X2 / HarmonyOS 4.2, the same OS family, so behavioral evidence can be gathered (reproducing the frame stall is not required; the state-machine level is provable from code).

## Goals / Non-Goals

**Goals:**
- Stall protection on the expand path at the same level as the collapse path: regardless of whether the frame clock stalls, the open branch of `OverlayMotion` reaches its terminal state within a finite time (`shown = 1f`, `opened()` already called).
- The side window's always-open branch gets the same guard.
- `OverlayMotionTest` mirrors four scenario classes (normal, animations off, missing-frame timeout, slow-motion multiplier).
- On-device evidence: collapse residual-frame regression + complete expand-state geometry (full size, centered, no clipping).

**Non-Goals:**
- Do not change the docking/centering geometry algorithms (`CardDocking`, `CapsuleDocking`).
- Do not change the existing protections on the collapse path, and do not touch window management (the semantics of `syncWindowVisibility`).
- Do not handle interaction races such as "the user taps the capsule again during the close animation" (current behavior stays).
- Do not open a case for other symptoms on the #323 reporter's device (wireless-debugging reconnect churn).

## Decisions

1. **Mirror `runOverlayClose` with `runOverlayOpen`, instead of wrapping the whole `LaunchedEffect` in a timeout.**
   The collapse side's `runOverlayClose(reduceMotion, animate)` already has an established test pattern (`runTest` directly testing the suspend function, `awaitCancellation` simulating a frame stall); the expand side copies that shape: inside `runOverlayOpen(reduceMotion, animate)`, `withTimeoutOrNull((CARD_IN_MS * scale) + OPEN_GRACE_MS)` wraps the whole "SETTLE wait + `opened()` + `animateTo`" sequence. On timeout it returns false, and the call site falls back with `snapTo(1f)` and makes sure `opened()` is called only once as a make-up. The alternative (wrapping the entire LaunchedEffect in withTimeoutOrNull) would also pull in the reduceMotion branch, and the timeout would live at the coroutine-management layer with coarser granularity instead of in a pure function that can be tested.

2. **`opened()` keeps its "before the animation starts" timing; on the timeout fallback it becomes "called after the snap."**
   The normal path order is unchanged (SETTLE → `opened()` → animateTo): `cardOut` is set and window visibility synced before the animation starts, as today. The timeout path does `snapTo(1f)` first and then calls `opened()`: with a frame stall there is no next frame to draw anyway, so the order only affects state — put `shown` in its final place first, then let the state machine settle, so the window is already in its terminal state once frames resume. A `var openedCalled` guarantees it is not called twice (the non-idempotent cost of `syncWindowVisibility` inside `onOpened` should also happen only once).

3. **Timeout length `CARD_IN_MS * scale + OPEN_GRACE_MS`, with `OPEN_GRACE_MS` reusing 500ms.**
   The expand animation is 240ms, the same order as the 180ms collapse; the grace value is the same, and the system slow-motion multiplier is likewise `coerceAtMost(10f)`. The two paths' constants are named independently rather than shared, so that future tuning on one side does not drag in the other.

4. **The side-window branch reuses the same `runOverlayOpen`.**
   `CapsuleOverlay`'s `OverlayMotion(open = true, …, onClosed = {})` shares the composable with the card, so a guard inside `OverlayMotion` automatically benefits both places, with no per-call-site changes. On that branch the timeout fallback shows up as the capsule simply appearing at full size (a snapped head), which is acceptable — it was supposed to be showing all along.

5. **On-device evidence uses the existing pixel test plus new geometry assertions, with no new dependencies.**
   `CapsuleWindowDeviceTest` already has a complete pattern for locating overlay nodes through Mika's own accessibility service and comparing pixels with `UiAutomation.takeScreenshot`. The expand-state geometry assertion follows the same pattern: after tapping the card open, read the `capsule-card` node bounds from the accessibility tree and assert (a) the node is on-screen with symmetric left/right margins (horizontally centered, tolerance converted from density); (b) the visible height reaches the expected range for that scale tier. This assertion guards against "half-open/clipped" regressions like point 1 of #323 and does not depend on reproducing the frame stall.

## Risks / Trade-offs

- [On a real device the expand animation occasionally exceeds 240ms+500ms (heavy load) and is misjudged as a timeout] → The timeout fallback only does `snapTo(1f)`; the terminal state is exactly the same as the animation finishing naturally, and the only difference the user sees is the animation jumping to full size; settling the state machine is the goal anyway.
- [`opened()` is called later on the timeout path than on the normal path, so the side window draws for a moment longer] → Strictly better than the status quo (never settling); a capsule showing a bit longer on the order of 500ms has no functional impact.
- [Cannot reproduce the reporter's frame stall locally] → The guard's value is proven by the unit test's `awaitCancellation` (frame-stall) scenario plus the state-machine code path; on-device evidence covers "the fix does not break normal expand/collapse" and "the expand terminal geometry is correct." The PR states honestly that the original frame stall could not be reproduced.
- [The geometry assertion is fragile across scale tiers/split screen] → The assertion only covers the default tier and the portrait main-screen scenario, consistent with the environment conventions of the existing device tests (`GestureTapTestActivity` + `CapsuleMode.Always`).

## Migration Plan

Merged into main as a single PR, no data migration; rollback = revert the single commit.

## Open Questions

(None — the guard parameters, call sites and test shape have all been settled against the collapse side.)
