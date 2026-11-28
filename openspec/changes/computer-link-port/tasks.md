## 1. Settings and runtime

- [x] 1.1 `McpSettings`: `port` becomes mutable + `setPort()` (1024–65535 validation, persisted), app unit tests (save/out-of-range/default read, compatibility with old manual values)
- [x] 1.2 `McpRuntime.setPort()`: write the setting + `writeUsbScript()` + `apply()`; `sync()` rebinds when `boundPort != settings.port`
- [x] 1.3 The "Listen port" row in the `McpScreen` advanced section + dialog (default 8765 shortcut + numeric input validation), wiring `ComputerAccessUi.onPortChange`

## 2. Copy and documentation

- [x] 2.1 Strings ×10 (port row, dialog title/description/range hint/restore default/apply)
- [x] 2.2 Add one sentence to docs/mcp.md (or the corresponding section) that the port can be changed

## 3. Verification

- [x] 3.1 `./gradlew :core:test :core:cliTest :app:testDebugUnitTest` all green; i18n check and updateReadme confirmed
- [x] 3.2 On device: change the port to 9123 → verify the whole chain (script/`mcp-usb.json`/notification/`curl :9123`) → restore 8765 and verify again; evidence in `evidence/`
- [ ] 3.3 Commit, push the fork, open a PR linking #392
