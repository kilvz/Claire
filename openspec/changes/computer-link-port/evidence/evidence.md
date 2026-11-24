# Verification evidence for computer-link-port (issue #392)

Branch `feat/392-computer-link-port` (based on main `8d61573e`).

## Unit tests (all green)

- `:core:test :core:cliTest :app:testDebugUnitTest` pass; the i18n check (2187 resources × 10 languages) and
  `updateReadme` (no diff) pass.
- New test cases (`McpSettingsTest`):
  - `portChangePersistsAndReloads`: changing to 9123 persists, takes effect on reload, and does not affect the switches
  - `portOutsideRangeIsRejected`: 1023/65536/0/-1 throw IllegalArgumentException and are not persisted
  - `handEditedPortInFileStillRead`: a port manually written into mcp.json before the change is still read from (compatibility with existing data)

## On-device verification (Huawei Mate X2, TET-AN50, 2026-11-08)

- Package: `:app:assemblePerf` arm64 perf build, over-installed with `adb install -r` (0.1.38 → this branch).
- Throughout the verification the USB computer connection was on (bootstrapped through `connect-computer.sh`), i.e. the hot path.

| # | Step | Result | Evidence |
| --- | --- | --- | --- |
| 1 | Baseline: bootstrap the connection with the default port | `mcp-usb.json` port 8765; after `adb forward tcp:8765`, `/api/v1/tasks` returns normally | — |
| 2 | 设置 (Settings) › 连接电脑 (Connect computer) › 高级设置 (Advanced settings) | The new 「监听端口」 (Listen port) row shows 8765, with the footnote 「端口 8765。手机重启后 USB 访问会关闭。」 (Port 8765. USB access will be turned off after the phone restarts.) | 01-port-row-default.png |
| 3 | Tap the row to open the dialog | A 「默认（8765）」 (Default (8765)) shortcut + a custom input field (digits only, 5-digit limit), with complete explanatory copy | 02-port-dialog.png |
| 4 | Apply the custom 9123 | The row value changes to 9123; the content of `connect-computer.sh` becomes `tcp:9123`; `port`/`api_url`/`forward` in `mcp-usb.json` are all 9123; connections on the old port 8765 are refused (the hot rebind is done); the API works after `adb forward tcp:9123`; the foreground service notification reads 「经 USB 连接,端口 9123(只在本机监听)」 (Connected via USB, port 9123 (listening on this device only)) | 03-port-changed.png |
| 5 | Restore 「默认（8765）」 (Default (8765)) | The row value returns to 8765, the script/info/notification all return to 8765, and the API is usable again | 04-port-restored.png |

- At one point curl on 8765 stopped working; it was traced to the computer side's `adb forward` being lost after the phone's screen locked and unlocked (`adb forward --list` showed only 9123), while the phone-side listener was still there (loopback 8765 in `/proc/net/tcp6`) — not an app problem; rebuilding the forward restores it. Recorded here to avoid a misdiagnosis.
- The screenshots are all of Mika's own settings pages, with no personal information; each was checked by eye.
- After verification the USB connection was turned off and all adb forwards were cleared, leaving no state changes on the phone.
