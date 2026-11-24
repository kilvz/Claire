## 1. CLI error implementation

- [x] 1.1 `mika_cli.py` gains `incompatible(info, capability, remedy)`: takes `server_version` from `info` (when a non-empty string) and builds copy containing the capability name + version (when known) + remedy, `CliError("incompatible_api", …, capability=…, server_version=…)`
- [x] 1.2 The four gates switch to this helper: `run_with_files` explicit `--request-id` (remedy = drop the parameter and submit as usual), the steer branch of `execute`, the export branch, and `require_files`
- [x] 1.3 The `discover()` 404 / api_version mismatch paths stay as-is, confirmed no regression

## 2. Tests

- [x] 2.1 `test_explicit_request_id_never_downgrades_on_an_old_phone` gains assertions: the copy contains `task_recovery`, `"test"` (the DISCOVERY version), `without --request-id`; the `error.capability`/`error.server_version` fields are correct
- [x] 2.2 `test_steer_requires_capability_and_never_falls_back_to_run` gains assertions: the copy contains `task_steer` and `error.capability` is correct
- [x] 2.3 New export gate assertion (hooked into the first section of the existing `test_export_requires_capability_and_unknown_id_is_explicit`): the copy contains `run_export`
- [x] 2.4 New/extended scenario without `server_version`: the field and version phrase do not appear, and the guidance is still present
- [x] 2.5 Run the full CLI test suite for `companions/mika` (including the existing `RemoteCliContractTest`, covered by cliTest)

## 3. Docs and verification

- [x] 3.1 `companions/mika/SKILL.md`: the `incompatible_api` row of the error table becomes paired guidance; the `--request-id` section gains a sentence "when the phone reports incompatible_api you can drop the parameter and submit as usual"
- [x] 3.2 Run the CI four-group counterparts locally: `python3 -m unittest discover -s companions/mika`, `:core:test`, `:core:cliTest` (cliTest covers the real CLI × real service contract)
- [ ] 3.3 Open the branch `fix/314-cli-version-mismatch-errors`, commit in Chinese, PR referencing #314, body with real measured error output samples
