# Design: customizable computer connection port (issue #392)

## Context

`McpSettings` (mcp.json) already supports `port` (1024–65535, default 8765) when reading, but there is no write entry point; at runtime the whole chain is dynamic:
- `McpRuntime.startServer` creates a `RemoteHost` with `settings.port` (a bind failure throws → `McpServerState.Failed`)
- `sync()` publishes `mcp-usb.json` with the actual bound port while USB is on (`McpUsb.info(port,…)`; the `forward` command comes from the same source)
- `writeUsbScript()` generates `connect-computer.sh` with `settings.port` (the content contains the port and is rewritten only when it changes)
- The CLI reads the port from `mcp-usb.json` on each call, so no change is needed
- The `McpService` notification text includes the port, and `onStartCommand` rebuilds it every time

## Goals / Non-Goals

**Goals:** The port can be changed in the advanced section and takes effect immediately on save (rebind/rewrite the script/republish/update the notification); existing manual configurations remain fully compatible.

**Non-Goals:** The CLI/Skill side, the relay gateway (relay), and automatic retry on another port when the port is taken (just error and display it, the same as now).

## Decisions

1. **`McpSettings.setPort(port: Int)`, with `port` changed from `val` to `@Volatile private var` + getter.**
   The read validation rule (1024–65535) is used as-is for the setter: nothing is silently corrected beyond `require` — the range is stopped first in the UI dialog, with the storage layer as the backstop. `save()` already writes the `port` field, so no format change is needed.

2. **Rebinding on a port change lives in `sync()`: `host != null && (hostListen != want || host!!.boundPort != settings.port)` triggers `stopListener()`.**
   `RemoteHost` binds the exact port (throwing if it is taken), and `boundPort` is the actual listening port, which can serve as the basis for change detection. No extra hostPort field is introduced.

3. **The entry point `McpRuntime.setPort(port)` = `settings.setPort` + `writeUsbScript()` + `apply()`.**
   `apply()` follows the switches' existing path: if either one is on → `ensureRunning()` restarts the service → `onStartCommand` rebuilds the notification + `sync()` (rebind + republish info); if both are off → only store the setting and rewrite the script. `writeUsbScript` is idempotent (nothing is written to disk if the content has not changed).

4. **The UI goes in the "Advanced" section (which currently has only the two switches, USB/network, and a port footnote).**
   Upgrade the port footnote into a tappable "Listen port" row + dialog: a "Default 8765" preset + a numeric input (up to 5 digits, digits only), with the apply button enabled only for 1024–65535. `ComputerAccessUi` gains `onPortChange: (Int) -> Unit` passed down to the lower layer, isomorphic to the other callbacks.

## Risks / Trade-offs

- [Changing the port while running disconnects sessions already connected to the computer] → That is exactly the point of changing the port (moving to an available port); `mcp-usb.json` is republished + the script shows the new `forward` command, so the computer side simply reconnects; the disruption is on par with rotating the token, and smaller.
- [A brief moment of unavailability during the hot rebind] → Toggling the switches already stops/starts the service through the same path; on a bind failure `McpServerState.Failed` still shows the reason as usual, and the user can change the port again.
- [Range validation in two places (UI + require)] → The UI handles the experience, the storage layer is the backstop, and the rule comes from the same constant (1024..65535 already on the read side).

## Migration Plan

Purely additive: default behavior is unchanged; users who have manually written `port` are unaffected. Rolling back just reverts the code, and the old code can also read a valid port field written into mcp.json.
