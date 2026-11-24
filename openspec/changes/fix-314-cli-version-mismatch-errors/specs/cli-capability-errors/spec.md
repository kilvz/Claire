## ADDED Requirements

### Requirement: Capability-missing errors must be diagnosable and actionable
When the CLI throws `incompatible_api` because the capabilities broadcast by the Mika App on the phone lack the capability required by the current operation, the error message MUST include all of: the missing capability name (`task_recovery` / `task_steer` / `run_export` / `task_files`), the `server_version` already broadcast by the phone (if and only if `GET /api/v1` has returned that field), and one actionable remedy (update Mika on the phone, or drop the corresponding parameter and proceed as usual).

#### Scenario: an explicit --request-id meets an old App without task_recovery
- **WHEN** `mika task run --request-id UUID` and the capabilities returned by `GET /api/v1` do not include `task_recovery` (while `server_version` is present)
- **THEN** exit with `incompatible_api` (no POST is issued), and the error message includes the capability name `task_recovery`, that `server_version`, and states that `--request-id` can be dropped and the command retried to submit as usual

#### Scenario: steer meets an old App without task_steer
- **WHEN** `mika task steer` and the capabilities do not include `task_steer`
- **THEN** the `incompatible_api` error message includes the capability name `task_steer` and the remedy guidance

#### Scenario: export / attachment download meets the corresponding missing capability
- **WHEN** `mika task export` and there is no `run_export`, or attachments/`download` are involved and there is no `task_files`
- **THEN** the `incompatible_api` error messages include `run_export` / `task_files` respectively, along with the remedy guidance

### Requirement: Error output carries structured capability fields
The JSON output of an `incompatible_api` error (the `--json` stdout and the stderr document on non-zero exit) MUST carry a `capability` field (the missing capability name); when the phone has broadcast a version it MUST carry a `server_version` field, so that bridge programs can degrade automatically based on the fields.

#### Scenario: a bridge program degrades based on the structured fields
- **WHEN** a missing capability triggers `incompatible_api` and the output is `--json`
- **THEN** the output `error` object contains `capability` (and `server_version` when available), with no need to parse free text

### Requirement: Keep explicit guidance when no version can be reported
When `GET /api/v1` returns 404 (an old APK without the task API) or `api_version` does not match, the error message MUST keep the "install a build with CLI support" guidance, and MUST NOT omit the remedy or throw an undefined error just because `server_version` is missing.

#### Scenario: old APK 404
- **WHEN** `GET /api/v1` returns 404
- **THEN** report `incompatible_api` and guide the user to install a build with CLI support, with no version field and no crash
