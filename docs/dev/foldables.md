# Foldable adaptation

The main interface and the standalone file viewer lay out according to the window's available space and the separating fold reported by Jetpack WindowManager. When the window is not divided by a fold and a single column is used, the system bars and keyboard are still handled by the original page.

| Window | Layout |
| --- | --- |
| Available width under 640dp or height under 480dp | Use the original single-column page and chat drawer |
| Available width ≥ 640dp, height ≥ 480dp | Conversation list on the left, chat or the scheduled task, memory and skills, or browser selected in the sidebar on the right. The middle line is draggable, the left side at least 280dp, the right side at least 320dp |
| One vertical separating hinge, at least 280dp and 360dp on the left and right respectively, height ≥ 480dp | Use the hinge as a fixed divider, including a zero-width fold |
| Horizontal half-fold, multiple hinges, or one side of the hinge too narrow | Lay out within the largest complete area |

**Operation screen.** Two-column chat puts the running screen on the left. The menu switches back to the conversation list, and 「看画面」 (View screen) in the list restores the preview. The phone's virtual screen adjusts to the preview's available space, changing size, density and the Surface receiving the picture on the same displayId. The long side is at most 2048px, the preview rotates with the app's orientation and scales proportionally so the short side is at most 720px. A newly created ordinary background screen uses the main screen's size, with the preview's short side at most 360px. Web pages follow the browser's own viewport strategy.

**Control.** Tap the top status, or tap the screen during automatic operation for the first time, to request takeover. After the current action finishes, taps, swipes, back and input within the screen are sent to this run's target. Tap the top status again to hand back, and Mika observes again before continuing. Old coordinates are rejected when the size changes. After switching to a narrow screen, the floating small window is used, and a task already taken over can also be handed back from the chat's 「继续」 (Continue).

**Regression verification.** JVM cases cover size breakpoints, hinges, coordinate conversion and mutual exclusion of control. `SidebarDestinationsDeviceTest` on an unfolded real device covers sidebar page switching and switching between narrow and wide windows, and `InlineScreenControlDeviceTest` verifies dragging the split, keeping the displayId, tap input and handing back on a real virtual screen. The latter case requires a connected phone assistant and accessibility service, and the input verification uses the native pages bundled with the test package.

**Physical posture acceptance.** `PhysicalFoldDeviceTest` is skipped by default and runs only when `physicalFold=true` is explicitly passed. It applies to devices whose physical posture `dumpsys device_state` names `OPENED`, `CLOSED`, `HALF_OPENED`. First unfold in landscape, then follow the prompts in the `adb logcat -s PhysicalFoldCheck` log to close the screen and unlock the cover screen, hold a half-fold at about 90°, and unfold again in landscape; wait at most three minutes for each posture. The case keeps an unsent draft, checks the cover screen's single column, that the title and input field do not cross the hinge when half-folded, and that two columns are restored after unfolding. It reads the physical state and rejects state overrides; it does not call simulated fold commands. Screenshots are written to `fold-verification/` in the app's external files directory.

```sh
adb shell am instrument -w -r --no-window-animation \
  -e physicalFold true \
  -e class com.wangzan101.mika.ui.PhysicalFoldDeviceTest \
  com.wangzan101.mika.test/androidx.test.runner.AndroidJUnitRunner
```
