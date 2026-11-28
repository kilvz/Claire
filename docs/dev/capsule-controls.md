This capsule-controls and small-screen change is based on the user's fork of main (188ffe4), with the entry point still at 「设置 › 悬浮胶囊」 (Settings › Floating capsule).

## How to use

1. **贴边半圆**：先在胶囊设置打开「贴边自动隐藏」（默认关闭）。普通图标静置 3 秒后缩为贴边半圆，并采用现有闲置透明度（默认 40%）；点按半圆先恢复完整图标，再点可展开卡片。半圆和一颗共用同一块窗口（只是整块挪到屏幕外、只露半圆），换状态时只挪窗口不改尺寸；收起和点开照常放动效，只有从半圆拖出来这一下直接到位、从第一帧就跟手。开启该设置后，唤醒/解锁屏幕、切出 Mika 或退出全屏重新出现时默认直接保持贴边半圆与闲置透明度（仅在需要用户处理，或隐藏期间状态、件数变了、换成另一件事时展开完整图标）。新事项需要用户处理、展开的一行提示、打开的卡片、语音或拖动期间保持完整显示。关闭该设置会立即恢复完整胶囊。
2. **system-toggle / 快捷设置**：在胶囊设置打开「允许添加到快捷设置」，Android 才会把名为「Mika 胶囊」的磁贴列入控制中心可编辑项；关闭后移除这项注册。用户仍需在 Android 控制中心的「编辑」里自行放入磁贴。点磁贴可关闭或恢复胶囊，恢复之前的「一直显示 / 有任务时显示」模式；长按磁贴直接打开 Mika，而不是系统应用详情页。锁屏时先交给系统解锁。手机操作 / 无障碍未设置好或 Mika 处于完全退出状态时，引导用户打开 Mika，不自动授权或启动任务。
3. **全屏自动隐藏**：在胶囊设置打开「全屏时隐藏胶囊」，默认关闭。前台主屏应用覆盖屏幕且状态栏不可见时隐藏胶囊；退出全屏恢复。任务和通知继续运行。读取失败、分屏、普通 edge-to-edge 页面仍显示胶囊。退出全屏或关闭该选项可以恢复；快捷设置磁贴也可直接关闭胶囊。全屏自动隐藏优先于半圆显示规则，需要处理事项时仍可通过通知进入 Mika。
4. **展开小画面缩放**：对话页的小画面展开后支持双指捏合；右下角的「− / + / ↺」也可缩小、放大或恢复默认大小。倍率限制为 65–180%，实际窗口还受可用屏幕范围限制，整张画面按比例显示。收起后仍使用原小画面尺寸，再展开时保留当前倍率。缩放只调整预览窗口，不改变后台屏分辨率，也不向后台应用注入手势。

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
