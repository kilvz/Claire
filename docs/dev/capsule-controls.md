This capsule-controls and small-screen change is based on the user's fork of main (188ffe4), with the entry point still at 「设置 › 悬浮胶囊」 (Settings › Floating capsule).

## How to use

1. **Edge-attached semicircle**: first enable 「贴边自动隐藏」 (Auto-hide at edge) in capsule settings (off by default). After the normal icon rests for 3 seconds it shrinks into an edge-attached semicircle, using the existing idle opacity (40% by default); tapping the semicircle first restores the full icon, and tapping again expands the card. While there is a new item needing user handling, an expanded one-line notice, an open card, voice, or dragging, it stays fully visible. Turning the setting off immediately restores the full capsule.
2. **system-toggle / Quick Settings**: enable 「允许添加到快捷设置」 (Allow adding to Quick Settings) in capsule settings, and only then will Android list the tile named 「Claire 胶囊」 (Claire Capsule) among the editable items in the Control Center; turning it off removes this registration. The user still needs to add the tile themselves via 「编辑」 (Edit) in the Android Control Center. Tapping the tile can turn the capsule off or restore it, restoring the previous 「一直显示 / 有任务时显示」 (Always show / Show when there are tasks) mode; long-pressing the tile opens Claire directly instead of the system app details page. When locked, it first hands over to the system to unlock. When phone control / accessibility is not set up, or Claire is fully exited, it guides the user to open Claire, and does not automatically grant permissions or start tasks.
3. **Auto-hide in full screen**: enable 「全屏时隐藏胶囊」 (Hide capsule in full screen) in capsule settings, off by default. The capsule is hidden when the foreground main-screen app covers the screen and the status bar is not visible; it is restored when exiting full screen. Tasks and notifications keep running. The capsule is still shown when reading fails, in split screen, and on ordinary edge-to-edge pages. Exiting full screen or turning the option off restores it; the Quick Settings tile can also turn the capsule off directly. Full-screen auto-hide takes precedence over the semicircle display rule, and when something needs handling it can still be reached through the notification.
4. **Expanded small-screen scaling**: after the small screen on the conversation page is expanded, it supports two-finger pinch; the "− / + / ↺" in the lower right can also shrink, enlarge or restore the default size. The scale is limited to 65–180%, the actual window is also limited by the available screen area, and the whole image is displayed proportionally. After collapsing, the original small-screen size is still used, and the current scale is kept when expanding again. Scaling only adjusts the preview window; it does not change the background screen resolution, nor does it inject gestures into background apps.

## Wiring and permission boundaries

- CapsuleTileService is bound only by Android Quick Settings, and the manifest requires BIND_QUICK_SETTINGS_TILE; an ordinary external app cannot toggle the capsule through it. The component is disabled by default in the manifest, and the user's setting switch only registers or unregisters this one component; the system binds it on demand and cancels the state subscription after listening stops, and no permanent foreground service is added.
- The tile and the settings page share FeatureFlags' capsule mode. Turning the tile off only changes the capsule display; it does not stop tasks or change the system switch. Enabling it requires an existing accessibility connection; adding the tile and unlocking are confirmed by the Android system.
- CapsuleEdge decides whether to show the semicircle, and CapsuleEdgeContent actually occupies only the semicircle's width, so the transparent other half does not block the app underneath; tapping the semicircle does not simultaneously trigger the full capsule's tap or screenshot.
- The full-screen check runs only when the user has turned on the option and capsule mode is enabled, reading the main-screen app window bounds and system status bar visibility every 500 ms; it does not read window text, screenshots or app content. Vendors' window Insets behavior may differ, and it does not auto-hide when it cannot be confirmed.
- Auto-hide collapses an expanded capsule card, releases input focus, and cancels capsule recording; unsent text is kept using the existing collapse logic. It can be tapped open again after restoring.
- PipSizing keeps the image ratio and limits the window range. Once a two-finger gesture starts, the remaining single finger does not mistakenly become a drag or a 「需要你」 (Needs you) tap; buttons are provided for users who have trouble using two fingers.

## Local verification

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest
python3 tools/i18n/translations.py check
python3 -m unittest discover -s tools/i18n -v
```

Regression coverage includes the semicircle switch and when it appears, staying visible when the user needs to handle something, safe edge positions, full-screen/split-screen/unknown states, restoring the original capsule mode, live settings changes, scaling bounds and ratios, the tile not being registered by default and the system binding permission.

The device UI cases are CapsuleEdgeContentTest and AgentPipResizeTest, covering semicircle tap-to-restore, the zoom buttons, hiding controls after collapsing, and a pinch not mistakenly triggering takeover. A successful build does not mean these cases have been run on a device.

## On-device acceptance checklist

- After enabling 「允许添加到快捷设置」, 「Claire 胶囊」 appears in the Control Center edit list; after turning it off it is removed from the selectable list. Verify the tile's short-press quick toggle, long-press to open Claire, lock-screen unlock, accessibility disconnect, guidance after a full exit, and display mode restoration.
- Turn 「贴边自动隐藏」 on and off, and verify the left and right edge semicircles and their opacity, that the first tap only restores, and that it collapses again after 3 seconds; the position after dragging the semicircle, rotating the screen and the keyboard popping up; the full capsule is shown when a new item needing handling arrives.
- Full-screen entry and exit for video / games, landscape and portrait, temporary status bar pull-down, split screen, and turning auto-hide on and off; tasks and notifications keep running while the capsule is hidden.
- After expanding the small screen on the conversation page, pinch, continuously zoom in and out, and restore the default size; both ends and the bottom of a tall phone screen and a landscape web page remain visible; scaling does not trigger tap takeover, closing or background screen actions.

## Verification record (2026-11-07)

Core 3,010 tests (2 skipped by default) and Android 1,603 unit tests all passed; 64 CLI Python tests and 1 cross-language contract test passed. The Debug APK and the Android device test APK built successfully. 10 translation tool tests and 2,016 resource checks across 10 UI languages passed.

Used JDK 17, Gradle 8.11.1, Android SDK 36. Local commands added --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process; once dependencies are complete, --offline can be added to reproduce. Logs, XML results and generated summaries are kept in the task worktree's ignored build directory.

No Android device was connected, and dp2 has no usable KVM or Android emulator either. Device interaction cases only finished compiling; the semicircle gesture, tile tapping, system unlock, vendor full-screen detection and the actual scaling effect still need acceptance per the checklist.

## Downloading a test APK from your fork

Each push to the feature branch feat/capsule-controls triggers the Claire Dev APK workflow. First enable the workflow on the fork's Actions page; after a successful build, go to the Artifacts area of that run and download the ZIP named Claire-Dev-<commit>, unzip it and install the APK. The archive also contains SHA256SUMS.txt and is kept for 14 days.

The workflow runs Android unit tests and builds com.kilv.claire.dev with -PdevInstall; the app name is Claire Dev, and it can be installed alongside the original Claire. It does not inherit the original app's model configuration and authorizations; the first time the capsule is enabled in Claire Dev while phone control / accessibility is not yet connected, it opens the Android accessibility settings, and the user must explicitly enable Claire Dev. If the two capsules overlap during testing, you can first turn off the original Claire's capsule. If the build fails, a test report is uploaded separately.

workflow_dispatch can be used to manually choose the build branch, but GitHub only shows the manual run entry for a workflow already registered on the default branch; the first build of this feature branch is triggered directly by push, with no need to merge into main just to run CI.
