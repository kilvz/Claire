## ADDED Requirements

### Requirement: Task answers render output reference cards

The task's final answer (`Done`), when it contains `/workspace` references, and the hand-off after stopping
(`Cancelled`'s summary) SHALL render reference cards below the answer text (photos laid out in a grid, files
drawn as cards), through the same rendering entry as interjection replies (`FinishedReply`); opening SHALL go
through the file gate to read the conversation workspace, and when it cannot be read, show that the file does
not exist.

#### Scenario: A task answer with encoded references
- **WHEN** the task's final answer contains `![图](/workspace/%E6%8A%A5%E5%91%8A.png)` and
  `[my report.csv](/workspace/my%20report.csv)`
- **THEN** a blue image thumbnail and a file card named `my report.csv` render below the answer; tapping the
  file card opens `my report.csv` in the workspace through the file gate

#### Scenario: The hand-off after stopping carries references
- **WHEN** the hand-off (summary) written after the task is stopped contains `/workspace` Markdown references
- **THEN** reference cards render below the hand-off in the same way

### Requirement: Output references are decoded from percent escapes

Before rendering, the `/workspace` link/image destinations extracted from answers SHALL be leniently decoded
from URI percent escapes: valid `%HH` sequences are decoded as UTF-8; invalid escape sequences and a literal
`+` SHALL be kept as-is; after decoding the path SHALL still go through out-of-bounds filtering (references that
contain `..` or leave the workspace are dropped).

#### Scenario: A space written as %20
- **WHEN** the model reply contains `[报告](/workspace/my%20report.csv)`
- **THEN** the reference card shows the file name `my report.csv`, and opening reads
  `my report.csv` in the workspace through the file gate

#### Scenario: A Chinese file name is URL-encoded
- **WHEN** the model reply contains `![图](/workspace/%E6%8A%A5%E5%91%8A.png)`
- **THEN** the parsed reference path is `/workspace/报告.png`, rendered as an image thumbnail

#### Scenario: A literal plus is not treated as a space
- **WHEN** the reference target is `/workspace/a+b.png`
- **THEN** the parsed path is still `/workspace/a+b.png`

#### Scenario: An invalid percent sequence is kept as-is
- **WHEN** the reference target is `/workspace/100%.csv` (`%.c` is not a valid escape)
- **THEN** the parsed path is still `/workspace/100%.csv`, and the reference is not dropped because decoding
  failed

#### Scenario: Out-of-bounds filtering still applies after decoding
- **WHEN** the reference target contains `..` after decoding or points outside the workspace
- **THEN** that reference is not rendered as a card
