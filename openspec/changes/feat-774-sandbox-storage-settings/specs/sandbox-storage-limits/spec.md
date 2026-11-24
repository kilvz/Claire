## ADDED Requirements

### Requirement: Single uploaded file size limit is user-configurable

The size limit for a single file the user imports into the sandbox workspace (chat attachments, device file imports) SHALL be chosen by the user in settings from fixed tiers (200, 512, 1024, 2048 MiB), defaulting to 200 MiB (consistent with existing behavior). The tiers SHALL constrain only the two entry points of chat attachments and device file imports (SAF-granted directories, phone assistant), and each import SHALL read the current tier fresh; WeChat images and files sent with tasks from a computer SHALL keep fixed limits and are unaffected by this setting. When a value outside the tiers appears in storage, it SHALL be treated as the default 200 MiB. The device free-space safety line (500 MiB) SHALL be unaffected by this setting and always in force.

#### Scenario: Default matches current behavior

- **WHEN** the user has never set a single-file limit and attaches a 300 MiB file to chat
- **THEN** the file is refused for import, with the same failure reason and message as before the setting was introduced

#### Scenario: Raising the tier allows larger files

- **WHEN** the user raises the single-file limit to the 1024 MiB tier and then attaches the same 300 MiB file to chat
- **THEN** the file is imported successfully into the workspace inbox/

#### Scenario: An invalid stored value falls back to the default

- **WHEN** the stored single-file limit is written as a value outside the tiers (e.g. 300)
- **THEN** it is read as 200 MiB and the settings page shows the default tier

### Requirement: Workspace space limit is user-configurable

The space limit of a single workspace (the conversation sandbox directory) SHALL be chosen by the user in settings from fixed tiers (4, 8, 16, 32 GB), defaulting to 4 GB (consistent with existing behavior). The headroom checks for chat attachments and device file imports SHALL use the current tier; the headroom checks for WeChat images and files sent with tasks from a computer SHALL keep the fixed 4 GB. When a value outside the tiers appears in storage, it SHALL be treated as the default 4 GB.

#### Scenario: Default matches current behavior

- **WHEN** the user has never set a workspace limit and a workspace already occupies more than 4 GB
- **THEN** new files are no longer imported into that workspace, a message says the workspace has reached its limit, and behavior matches before the setting was introduced

#### Scenario: Raising the tier allows imports to continue

- **WHEN** the workspace occupies 4.2 GB and the user raises the limit to the 8 GB tier
- **THEN** new files can continue to be imported into that workspace until usage exceeds 8 GB

#### Scenario: An invalid stored value falls back to the default

- **WHEN** the stored workspace limit is written as a value outside the tiers (e.g. 6)
- **THEN** it is read as 4 GB and the settings page shows the default tier

### Requirement: Failure messages reflect the actual limit

The failure message for a single file exceeding the limit SHALL state the actually configured limit value and MUST NOT hardcode 200 MiB.

#### Scenario: Out-of-scope entry points are unaffected by tiers

- **WHEN** after the single-file limit is raised to the 1024 MiB tier, the user sends a 201 MiB task attachment file from a computer
- **THEN** the computer transfer path still refuses at the fixed 200 MiB, WeChat images still follow the protocol layer's fixed 10 MiB limit, and behavior matches before the setting was introduced

#### Scenario: Message for the 512 MiB tier

- **WHEN** the limit is the 512 MiB tier and the user attaches a 600 MiB file
- **THEN** the failure message in chat for that file states 512 MiB (or an equivalent readable capacity), not 200 MiB
