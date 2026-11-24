## ADDED Requirements

### Requirement: The PiP sound button toggles in both directions

After the PiP window is expanded, the sound button MUST handle both directions — "restore sound" and "mute again": when the target App's sound has already been restored by the user, pressing it mutes the App again (reusing the task mute ledger, with the wrap-up restoring as usual); otherwise pressing it restores the sound (current behavior). The button's icon and label MUST follow the real state: "Mute" when restored, "Restore sound" when not restored.

#### Scenario: pressing again after restoring mutes it back
- **WHEN** the user has restored the sound of the target App that the task auto-muted, through the PiP window, and then presses the sound button once more
- **THEN** that App is muted again and the button returns to "Restore sound"

#### Scenario: the wrap-up still restores after a re-mute
- **WHEN** the user mutes again the sound that had been restored, and the task then wraps up normally
- **THEN** the unified restore at wrap-up puts that App's sound back to the original mode from before the task started

#### Scenario: a failed re-mute can be retried
- **WHEN** "Mute" is pressed but the mute operation fails (e.g. the shell is temporarily unavailable)
- **THEN** the button stays in the "Mute" state, pressing again retries, and the wrap-up fallback restore is unaffected

#### Scenario: the takeover path toggles too
- **WHEN** the task is in the takeover state ("handed over to the user") and the user restores the sound from the PiP window and then presses once more
- **THEN** the taken-over App is muted again as well, and the button state matches the virtual-display path

#### Scenario: the state is shown truthfully
- **WHEN** the sound of the PiP window's current target App has not been restored by the user (including never muted and auto-muted)
- **THEN** the button shows "Restore sound", the same as before the change; pressing once restores and the button flips to "Mute"
