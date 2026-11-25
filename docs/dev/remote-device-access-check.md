# Remote wake and unlock verification

Date: 2026-10-31. Based on main commit `65e82c0d01ac0f016db76cde4c76cb9a3c03183c` (0.1.16).

## Scope and layering

- On the phone side, remote wake/unlock is off by default, and credentials can only be configured locally on the computer connection settings page.
- `core/device/DeviceAccess.kt` handles the serial preparation flow and actual state verification; platform input and recognition are provided by the Android adapter.
- `app/settings/DeviceAccessSettings.kt` uses a separate Keystore key with encrypted atomic storage, keeps non-secret state, and does not cache decrypted credentials.
- `McpDeviceTools` provides status, wake and unlock interfaces, rejects all parameters, and reuses the original MCP authentication; it does not enter the model tool catalog or task history.
- `TaskGate` keeps its original logic, letting things through automatically after a real unlock. Device management on the main screen is independent of the Agent's virtual screen.
- The settings page uses the existing grouping, row, button and input components, with Chinese and English resources corresponding; old credentials are not restored or shown, and the no-screenshot flag is set while editing.

## Automated verification

Build environment: JDK 17, Android SDK 36, the repository's existing Gradle/AGP/Kotlin versions.

```sh
./gradlew :core:test :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Final result: 2416 items in core, 977 items in app JVM, no failures, no errors; 2 pre-existing tests in core were skipped. Covers dependency direction, documentation consistency, Chinese and English resources, existing regressions and new feature tests.

New verification covers: off by default, no assistant, first-unlock boundary, idempotent wake, command succeeding but the screen not actually turning on, lock screen with no credentials, PIN/password flow, erasing after input, persistent pause after wrong credentials, concurrent calls, cancel, manual unlock/revoking permission while waiting, encrypted storage and key loss, credential update invalidating old requests, MCP parameter/auth restrictions, the stock lock screen's self-drawn PIN control and exclusion of SIM/unknown controls.

## Android 16 test device

Verified through a separate Claire Dev (application ID `com.kilv.claire.dev`, a debug build with the application ID changed locally, not part of this change), with the original release and its data preserved. The development build's USB MCP uses a separate port 18765; the original model key and other private data were not copied.

The user configured the real lock-screen credentials only on the phone; the test scripts did not read or output any credentials. The records contain only the following non-secret state:

| Phase | interactive | keyguard_showing | device_locked | ready |
| --- | --- | --- | --- | --- |
| Start | true | false | false | true |
| Screen off and locked | false | true | true | false |
| Remote wake returns awake | true | true | true | false |
| Remote PIN unlock returns unlocked | true | false | false | true |

The persistent placeholder was observed before submission and cleared after success, proving the credential submission path was actually exercised rather than only an already-unlocked state being detected.

The stock Android lock screen's `pinEntry` is a self-drawn `com.android.keyguard.PasswordTextView` that does not declare editable. The recognition rule allows it by the stock ID, class name and password flag, and otherwise only accepts edit boxes with the password flag and a matching input type, with regression tests; there was no blind tapping by coordinates and no reading of control text.

`DeviceAccessSectionTest` passed on a real device: confirming by re-entering, save conditions, and not showing the input value after saving. The test only used simulated data in a separate cache and did not touch the real configuration. The first time, the test page did not come to the foreground; after terminating it, it was rerun from the development build in the foreground, with the result OK (1 test). The test package was removed after verification.

## Explicitly unverified parts

- The real-device verification used the user's existing PIN; the phone's lock-screen type was not changed. So passwordless/swipe and password scenarios are covered by automated tests but have not been separately switched and verified on that device.
- The password supports 4–64 printable ASCII characters, and the PIN is 4–16 digits; patterns, SIM PINs and unknown vendor credential pages are not supported.
- Starting the existing Claire MCP before the first unlock after a reboot is not supported.
- The real-device record above predates the change "credential keystrokes are injected only into the SystemUI window" (`ShellService.injectTo`); after that change, PIN unlock needs to be rerun on a real device, and you need to verify that when other windows sit above the lock screen it returns `input_failed` and does not input.
- No wrong PIN was deliberately submitted to the real device; stopping retries after failure is checked by automated tests.
- The original task payment/confirmation policy was not changed, and no model was configured or external app task executed for verification.

Phone usage steps are in [computer connection documentation](../mcp.md).
