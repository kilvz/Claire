## Why

#314: when a user installed Mika in an emulator and controlled it through a bridge program via the official CLI, they hit two kinds of version mismatch whose errors are insufficient to localize the problem: for an old App lacking the `task_recovery` capability, the new CLI only says "Update the phone app to submit a recoverable request" — it does not name the missing capability, does not report the phone's current version (`discover()` has clearly already got `server_version` from `GET /api/v1`), and does not mention the immediately usable workaround "drop `--request-id` and submit as usual"; the steer / export / task_files gates likewise only have the one vague line "update the phone app". Maintainer triage (TRIAGE-2026-11-18 §2.3) has scoped it: only make these two mismatches produce **clear errors**, without touching the emulator scenario itself.

## What Changes

- When the CLI's four capability gates (`--request-id`→`task_recovery`, `steer`→`task_steer`, `export`→`run_export`, attachments/downloads→`task_files`) trigger `incompatible_api`, the error copy is unified as "phone Mika version (when known) + missing capability name + actionable remedy"; the remedy for `--request-id` must state that the parameter can be dropped and submission proceed as usual.
- The error JSON (`--json` output and the stderr for exit code 2) gains structured fields `capability` and `server_version` (when known), so bridge programs can auto-downgrade from them (the #314 reporter worked around it precisely by branching on the error code).
- The `incompatible_api` row of the error table in `companions/mika/SKILL.md` is updated to paired guidance (capability → update the phone App / or drop the parameter and retry).
- The old-CLI-does-not-recognize-`--request-id` (argparse rejects it) direction: released old CLIs cannot be retrofitted, so it is only documented in the SKILL.md paired guidance, with no code change.

## Capabilities

### New Capabilities
- `cli-capability-errors`: the error contract when the phone App lacks capabilities — must be diagnosable (including phone version and capability name), actionable (giving a remedy), and provide structured fields for machine callers.

### Modified Capabilities

(None — `openspec/specs/` has no main spec yet; this change establishes the spec for this capability for the first time.)

## Impact

- `companions/mika/scripts/mika_cli.py`: new `incompatible(info, capability, remedy)` helper; replaces the `CliError` construction at the 4 gates (`run_with_files`, the steer/export branches of `execute`, `require_files`). The `discover()` 404 / api_version mismatch paths keep their original copy (there is no server_version to report at that point).
- `companions/mika/tests/test_mika_cli.py`: the 3 existing `incompatible_api` assertions gain copy and structured-field checks; a new export gate copy assertion.
- `companions/mika/SKILL.md`: 1 row of the error table + 1 sentence in the `--request-id` section.
- No App-side (Kotlin) changes: the API already broadcasts capabilities correctly and old APKs cannot be retrofitted; the happy path taken by `RemoteCliContractTest` is unaffected.
