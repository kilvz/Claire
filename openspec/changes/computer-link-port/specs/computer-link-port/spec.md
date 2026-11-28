## ADDED Requirements

### Requirement: Users can customize the computer connection's listening port
Settings › Connect computer › Advanced SHALL provide a "Listen port" edit entry, allowing the port to be changed to an integer between 1024–65535 (the default 8765 can be restored with one tap). Invalid input SHALL be blocked inside the dialog and not written to storage.

#### Scenario: Change the port and it takes effect immediately
- **WHEN** the computer connection (USB or network, either one) is on and the user changes the port from 8765 to 9123
- **THEN** the service listens again on the new port; `connect-computer.sh` is rewritten to forward 9123; the USB connection info file `mcp-usb.json` is republished (`port`/`api_url`/`forward` all carry the new port); the foreground service notification shows 9123

#### Scenario: Change the port while not listening
- **WHEN** the computer connection is off and the user changes the port
- **THEN** the setting is saved and the script is rewritten with the new port; the next time the connection is turned on it listens on the new port directly

#### Scenario: Invalid port rejected
- **WHEN** the user enters 80 or 70000 or a non-numeric value in the dialog
- **THEN** the apply button is disabled and the range is shown; the setting is not persisted

### Requirement: Port reading is compatible with existing behavior
Without customization everything SHALL stay as it is: default 8765; a valid `port` field already written into `mcp.json` (previously editable only by hand) SHALL continue to be read and listened on.

#### Scenario: Custom port in an old settings file
- **WHEN** `"port": 9123` has already been written by hand in `mcp.json`
- **THEN** the UI shows 9123 and the service listens on 9123, the same as before the change
