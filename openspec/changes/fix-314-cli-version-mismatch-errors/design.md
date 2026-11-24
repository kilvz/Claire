## Context

The CLI (`companions/mika/scripts/mika_cli.py`, pure Python standard library) discovers phone capabilities via `GET /api/v1`: `server_version` + `capabilities` (`tasks` / `task_recovery` / `task_steer` / `run_export` / `task_files`…). Four gates throw `CliError("incompatible_api", …)` when a capability is missing, but the copy is just the single line "update the phone app" without structured fields — bridging users in #314 can only blindly guess the downgrade path from the error code.

Constraints: **do not touch the emulator scenario itself** (scoped by maintainer triage); do not touch the App side (old APKs cannot be retrofitted, and the new App's capabilities broadcast is already correct); the CLI targets agents/bridges, so the existing conventions — errors in English, stdout carrying only JSON — stay unchanged.

## Goals / Non-Goals

- Goals: errors from the four gates are diagnosable (version + capability name) and actionable (a remedy sentence); the `--json` error object carries structured `capability` / `server_version` fields; the SKILL.md error table is updated to match.
- Non-Goals: no exit-code changes (gate failures stay = 2); no CLI/App version lookup table in code (the capabilities broadcast is the contract, version numbers are only for humans); no argparse behavior changes (the old CLI rejecting `--request-id` cannot be retrofitted, so it is only documented in SKILL.md).

## Decisions

1. **A single helper `incompatible(info, capability, remedy)`** builds all four errors uniformly instead of inlining the concatenation at each site — guaranteeing none of the three elements "version (when known) + capability name + remedy" is missing. `server_version` is written into the copy and the field only when it is a non-empty string.
2. **Remedy sentences differ by operation**: for the `--request-id` scenario, write "you can drop this parameter and submit as usual" (the task will run, just losing disconnect recovery); steer/export/files have no parameter to drop, so they only point to "update Mika on the phone". These come from the two ways out the #314 reporter actually needed.
3. **Structured fields go through `CliError(details)`** (an existing mechanism: `**exc.details` is merged into the error object), so bridges need zero text parsing. Field names use `capability` / `server_version` directly, matching the API broadcast field names.
4. **The two paths in `discover()` — the 404 and the api_version mismatch — are left untouched**: at that point server_version is unavailable, and the existing copy ("install a build with CLI support") is already the correct guidance; adding more would only be hollow.
5. **Test assertions use the existing fake server** (`api(respond)` + `DISCOVERY`, whose `server_version` is `"test"`): assert the copy contains the capability name, `"test"`, and the key remedy words; assert the `error.capability` / `error.server_version` field values. Do not assert whole sentences (avoiding brittle copy tests).

## Risks / Trade-offs

- [No one reviews the copy wording] → keep the same style as existing English errors (imperative, short sentences), and attach real output samples in the PR body for the maintainer to see.
- [New fields in the `--json` output may alarm callers with strict schemas] → only new keys are added, existing keys are untouched; the error object already has precedent for details-based extension (`http_status`, `task_id`).
- [SKILL.md is documentation distributed with the skill] → change only 1 row of the error table and 1 sentence in the `--request-id` section, without restructuring.

## Migration Plan

A pure single-file CLI change, stateless, with no protocol change; neither the App nor old CLIs need to act. Rollback = revert the single commit.

## Open Questions

None.
