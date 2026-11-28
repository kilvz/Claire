# Customizable computer connection port (issue #392)

## Why

On some phones a system service occupies port 8765, and Claire's computer connection (MCP/CLI) becomes unusable when binding fails (issue #392). The settings store `McpSettings` actually already supports a `port` field (validated 1024–65535 on read, default 8765), and the whole runtime chain (service binding, `mcp-usb.json` connection info, `connect-computer.sh`, the `adb forward` hint, the CLI reading the port dynamically) already goes through this value — the only thing missing is a write entry point: no setter and no settings UI. This last link just needs to be added.

## What Changes

- Settings › Connect computer › Advanced gains a "Listen port" row: tap to edit in a dialog (1024–65535, with a shortcut item to restore the default 8765); it takes effect on save.
- After saving: a listening service hot-rebinds to the new port (for both USB and network modes); `connect-computer.sh` is rewritten with the new port; `mcp-usb.json` is republished while USB is on; the port in the foreground service notification is updated in sync.
- Port validation follows the existing rule on the read side (1024–65535); invalid input is blocked in the dialog and is not persisted.
- Documentation (docs/mcp.md etc.) gains one sentence noting that the port can be changed in settings.

Not doing: CLI/Skill-side changes (the `mika` command already reads the port dynamically from `mcp-usb.json`); the relay gateway (relay, deployed on the public network, unrelated to the phone's local port).

## Capabilities

### New Capabilities
- `computer-link-port`: setting the computer connection listening port, hot rebinding, and the linked script/info publishing.

### Modified Capabilities

(No existing specs.)

## Impact

- `:app`
  - `settings/McpSettings.kt`: `port` becomes writable (`setPort`, range validation, persisted)
  - `mcp/McpRuntime.kt`: `setPort()` (write the setting + rewrite the USB script + `apply()`); `sync()` rebinds the listener when the port changes
  - `mcp/ui/McpScreen.kt`: the "Listen port" row in the advanced section + the edit dialog (`ComputerAccessUi` gains `onPortChange`)
  - `res/values*/strings_mcp*.xml` (10 languages)
- `docs/mcp.md` (or the corresponding section of computer.md): one sentence of explanation
- Unit tests: McpSettings port storage/validation (app); if `McpUsb` already has port pass-through tests, add an assertion that the script contains the new port (core)
