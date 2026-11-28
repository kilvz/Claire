# Functional-area integration design for 19 open PRs

Date: 2026-11-25
Baseline: `main` / `7ff1703f6`
Integration branch: `integration/pr-batch-2026-11-25`

## 1. Goals and principles

This work is not about stacking the diffs of 19 PRs one after another, but about organizing them into feature commits that are semantically complete, architecturally consistent, and independently reviewable and revertible. The final merge must satisfy all of the following:

- Complete functionality: normal paths, failure paths, lifecycle, persistence, compatibility and recovery form a closed loop.
- Clear architecture: follow the current main layering and directories; do not restore old package names, old entry points or superseded architectures.
- Extensible: establish explicit interfaces and policies where two or more PRs intersect; do not spread one provider's, system version's or UI's special-case logic into global assumptions.
- Verifiable: write a test that reproduces the problem first, then implement; run the relevant tests for each group, and finally run the full CI and the necessary device acceptance.
- Revertible: keep separate commits per functional area; do not squash 19 features into one indivisible giant commit.
- Generic Android: standard APIs are the main path, adapt through public capability detection, and do not introduce brand-private target implementations.

Out of scope: #847, #846, #836, #818, #722, #677, #308, #294, #277; do not refactor unrelated modules; do not build a large new framework for the sake of "architectural beauty".

## 2. Integration approach

### Chosen approach: functional-area integration branch

Create an isolated worktree and integration branch from the latest main. Each functional area goes through "semantic design → regression tests → full implementation → relevant tests → independent code review" and is then committed to the integration branch. After all features are done, run full verification, then merge into main through a merge node that preserves the feature commits.

Do not modify contributor forks directly, and do not click Merge on each original PR. Original PRs stay as they are until the integrated version is merged; after the merge, annotate each with its corresponding feature commit and close it as integrated. Every feature commit uses `Co-authored-by` to preserve the attribution of the original authors whose code was actually absorbed.

### Approaches not chosen

- Fix and merge PR by PR: repeated rebases at the same intersection easily carry temporary compromises into main.
- Squash everything into one commit: the boundaries for review, attribution and rollback are too poor.
- Merge first, refactor later: this leaves intermediate versions in a known-defective state, contradicting this effort's goal.

## 3. Overall dependencies and commit boundaries

The order is as follows; independent groups may investigate and prepare tests in parallel, but commits enter the integration branch in this order:

1. #826: title and stop-summary language.
2. #297: HTML conversation export.
3. #306: per-conversation override of the main model/group.
4. #827, #830, #854: settings and generic buttons / recent tasks / search, kept as three separate feature commits.
5. #859: capsule edge-docked wake.
6. #829, #849: system TTS engine and voice, then extend OpenAI-compatible speech and self-hosted ASR.
7. #856, #852, #853: lock-screen credentials, screen-off execution, plan UI.
8. #806: Light per-call tool calling. (#808 was closed on 2026-11-27; no periodic compaction or fixed input cap, see §4.8.)
9. #804: full pause/resume lifecycle.
10. #803, #811: call background standby, then dial-pad and history interactions.
11. #327: Feishu multi-window channel.
12. #266: ADB reconnection and read-only screen reading; decide whether to keep two commits based on actual coupling.

#306 before #806 avoids reworking model routing and conversation overrides on the old structure; #856 before #852/#853 ensures lock-screen classification has only one set of semantics; #829 before #849 ensures voice configuration and voice selection are based on the same catalog model.

## 4. Functional-area designs

### 4.1 Title and stop summary (#826)

- Language is passed into the core as an explicit parameter from the app locale, without passing the device default language off as the user's language.
- The compatibility path for `null`/`system` is clearly defined; a fake language tag must not be constructed from a sample text.
- Title length is handled by writing system, keeping the existing cleaning, timeout and fallback.
- Stop-summary header localization is centralized in one function, covering full-width punctuation, existing translations and Chinese unchanged.
- Acceptance: language instruction, fallback, empty title, mixed scripts, tests for each supported language and the prompt golden.

### 4.2 HTML export (#297)

- Port only the genuine P2 increment and wire it into the current Export registry, snapshot and asset pipeline.
- The HTML backend keeps default escaping, the URL scheme allowlist and strict CSP; WebView disables JS, file/content access and DOM storage by default.
- Preview navigation, the AssetLoader root directory, cancellation and destruction cleanup are handled centrally, without copying the old P1.
- Acceptance: malicious Markdown/URL/file names, attachments, cancellation, destruction, the selected snapshot and all current languages.

### 4.3 Conversation-level model override (#306)

- The conversation settings store is the only persistence source for conversation overrides; absent means follow the global setting.
- `TaskEnvironment` generates an immutable snapshot when a run starts; changes during a run affect only the next task.
- A branched conversation copies the source conversation's override snapshot when created and does not stay linked to the source conversation afterwards; scheduled and channel tasks read the current override of their own conversation at each start.
- Light/Vision's SameAsMain points to the main model actually in effect for that conversation, rather than bypassing the conversation override and falling back to the global main model; when the override is deleted or invalid, fall back to global.
- Acceptance: new conversation, switching mid-run, reopening, branching, scheduled, override deletion and the model chip.

### 4.4 Settings and buttons (#827, #830, #854)

- #827 prefers local layout to solve capsule and card button problems; when the global button component really must change, keep the existing default visual contract and enable long-text capability through an explicit parameter.
- #830 uses the public `ActivityManager.AppTask.setExcludeFromRecents`; settings writes, system calls and UI state must agree, and failure must not silently show success. Reapply on start and restore.
- #854 search entries are a stable data model; matching, ordering, highlighting and navigation are separated; register only pages that can really be opened, and follow existing visibility when a capability is unavailable.
- The three features keep separate commits; do not mix search, recent tasks and capsule styling into one "settings improvement".
- Acceptance: Vietnamese and all languages, long text, large fonts, small screens, deep navigation, scroll position on back, recent-task hide/restore.

### 4.5 Capsule wake (#859)

- The capsule's appearance identity includes the run identity, rather than comparing only visual fields such as Running/Failed.
- Before and after screen-off, when the service instance is replaced, and when task A ends while task B starts, collapsed/expanded state is decided by the same state machine.
- Test cleanup and preference restoration do not affect each other; a shell cleanup failure must not pollute later tests.
- Acceptance: same appearance with different runs, auto-hide when docked to the edge, wake, service recreation and opacity restore.

### 4.6 Voice engine, voice and endpoints (#829, #849)

- Engine package name, voice name, recognition language and model ID are different types; do not use one global string to imply several states.
- System TTS engine selection is injected through the existing Voice catalog/adapter; engine enumeration and voice queries have timeouts, release and default-engine fallback.
- Voice setting results must be checked; model switching and clearing the voice execute in transactional order: the corresponding voice may be changed only after the model switch succeeds, and on failure the old model and old voice are kept.
- OpenAI-compatible endpoints support http/https, an explicit base path and non-default ports; default ports are normalized in comparisons and IDs. query, fragment and userinfo are always rejected, and their semantics are not guessed. Configs already saved legally keep their original ID and credential mapping; invalid configs that must be corrected use an explicit, testable migration, without silently pointing the key at another endpoint.
- STT/TTS/Realtime each store their model and voice configuration, and adapters read by capability, without spreading Realtime restrictions to ordinary TTS.
- New providers still undergo the project's standard trust review; this group mainly extends existing protocols and does not introduce third-party aggregation platforms.
- Acceptance: engine failure, voice failure, model-switch write failure, HTTP on a LAN, addresses with paths/ports, key reading, recognition language, resource release and on-device synthesis.

### 4.7 Lock screen, screen-off execution and plans (#856, #852, #853)

- Credential recognition stays a pure function with conservative classification: PIN, password, SIM PIN and pattern are separate; the resource ID is only one piece of evidence, and "a numeric keypad is nearby" does not override a declared text password type.
- `TaskGate` clearly distinguishes the lock-screen policy for phone and web/sandbox: web/sandbox tasks may start and continue while the screen is locked/off; phone tasks must satisfy the unlock policy first, otherwise wait for the user rather than blindly operating after the screen locks.
- For a non-secure lock screen, wake/dismiss only when the user has authorized unattended unlock; PIN/password require valid credentials and the corresponding authorization, with bounded retries after failure and then waiting for the user; pattern, SIM PIN and unclassifiable credential screens are always handed back to the user, without automatic attempts.
- CPU wake uses an owned lease: tasks, background workflows and alarm dispatch each hold one, released when all are finished; keeping a web page must not unintentionally extend a task's CPU lease.
- Accessibility late binding and wake/dismiss retries use a unified bounded wait, instead of piling up ad-hoc delays in each place.
- The plan editor's draft is the single source of truth: a hidden condition must not equal off but still be saved; relative time uses the now at tap time; 12/24-hour format, time zone and year crossing use the same formatting policy.
- The credential Dialog's own window must set the secure flag; toggle failures must be visible; a new plan chat can still open before it is materialized.
- Acceptance: PIN/password/SIM, lock-screen web/phone, Doze, process recreation, wake-lock release, credential screenshot prevention, hidden conditions, relative time, year-crossing time zones and real instrumentation.

### 4.8 Light tool calling (#806); no periodic compaction or fixed input cap (#808 closed)

- Forced tool calling and the output cap are per-call options, not placed on the whole Light client; plain-text Light calls are unaffected.
- Providers explicitly declare forced-tool/max-token capability and the degradation when unsupported; 400/422 fallback does not swallow real errors.
- Retries merge the two usages; the `task_progress` fake call and the first real action are verified separately; privileged/final-summary/interjection review routing stays consistent.
- Decision on 2026-11-27: do not do #808's periodic compaction every 15 steps, and do not add a unified 100k input cap; #808 is closed. Reason: periodically rewriting history changes the request prefix and invalidates the provider's prompt cache; Claire keeps the request prefix append-only and compacts by size only when approaching the context window, and this behavior is unchanged. The existing folding by round count (`compactionEveryRounds`) and size-triggered compaction stay as they are.
- Recompute the prompt transforms that need to change on every real request; overflow retry and ordinary retry do not share stale state.
- Acceptance: all providers, unsupported forced tools, plain-text Light, retry usage and combination with #306.

### 4.9 Pause and resume (#804)

- Persisted paused is the only authoritative state; the current engine state is only a projection; Shizuku, watchdog, repair, pairing and notifications all read the same policy.
- Recheck paused at every asynchronous handoff point, including connections that passed the startup guard but have not yet been handed to Binder.
- Pause covers running tasks, helpers, accessibility, wireless debugging and automatic restore; resume keeps pairing material and does not turn pause into unbinding.
- While paused, cancel side-effect tasks that are executing and queued; new scheduled triggers are recorded as not executed due to pause and are not caught up after resume. New channel messages return an explicit paused state and do not form a hidden queue that executes automatically after resume. Resume only reopens subsequent new work.
- UI copy promises only the capabilities actually paused, and does not describe a lifecycle pause as bank-grade security isolation.
- Acceptance: pausing during connection, late handoff, transport switching, process restart, accessibility and wireless debugging restore.
- Requests from the computer side (CLI / MCP / HTTP) that would operate the phone uniformly return `UNAVAILABLE` with a pause explanation through `PauseFlag.refuseRemote()`; the conversation page and floating capsule (including the front desk model) block and notify at the send site.
- TODO: the desktop companion (`companions/`) is currently read-only (`CompanionApi` has GET only), so there is no write entry to block; when a write entry is added later it must go through `refuseRemote()`, so the desktop also receives the same pause explanation instead of failing silently.

### 4.10 Calls (#803, #811)

- Background standby uses public JobScheduler/notification/permission mechanisms. The user's enabled state is kept across restarts and temporary permission loss, and can automatically re-arm when permission returns; an explicit stop cancels subsequent automatic restore until the user enables it again. Full exit follows the existing exit semantics and does not silently come back.
- Notification routing only expands the existing settings page and does not build a second background state machine.
- Dial-pad cursor/selection, deletion, clear undo and history filtering are UI state logic and do not change SIM, outbound-call review or the lock-screen outbound-call boundary.
- Acceptance: Doze, restart, permission changes, explicit stop, real dialing, selection backspace, clear undo and filter reset.

### 4.11 Feishu multiple windows (#327)

- Reuse the current `Channel`, Outbox, RunInbox, TaskCenter and persisted deduplication; do not build a second executor.
- Window selection is only routing information; replies, appends and cancels check the owner, window and run number together, and an expired nonce does not act on a new task.
- Other sources such as local/scheduled are read-only; only windows created by Feishu may be controlled by Feishu; payments and takeover still go back to the phone.
- Old-binding migration preserves the original channel ownership: conversations originally created by Feishu migrate to a Feishu-owned default window and keep their selection; local or other-source conversations are read-only and do not gain Feishu control because of binding migration. After the default window is deleted, old buttons and controls are all rejected, and the user must explicitly create or select a window.
- Old-binding migration, card plaintext, private-chat restrictions and not resending when the result is unclear stay explicit.
- Acceptance: multiple windows in parallel/queued, expired buttons, deleting a window, owner boundary, network switching, lock screen, restart and real card callbacks.

### 4.12 ADB reconnection and read-only screen reading (#266)

- The ADB server port, bounded scanning after a stale endpoint, and candidate validation follow the current `core/src/main/kotlin/com/wangzan101/mika/core/device/adb/`, without restoring the old package.
- Read-only screen reading goes through the shell UID and the public capabilities of UiAutomation, masking passwords and limiting nodes and depth, without suppressing the accessibility service.
- The two capabilities share one commit only when the shared connection lifecycle really requires it; otherwise commit them separately.
- Acceptance: non-default server, bounded scan after rejection, USB plug/unplug, virtual display ID, no password leakage and continuous telemetry.

## 5. Testing and review gates

Before each feature commit:

1. Reproduce the original PR's problem with a regression test; first confirm the test fails on the old implementation.
2. Run the affected core/app/CLI/relay tests, resource checks and compilation.
3. Do an independent code review covering correctness, architecture, project rules and unrelated changes; re-review after fixing the problems found.
4. Update the corresponding architecture/user docs and do not keep stale dependency notes.

Before the final merge:

- Run full local verification equivalent to GitHub CI.
- Device tests that can be automated must actually be executed, not just compiled; real-device/external-service acceptance that cannot be executed must be explicitly listed as unverified and must not be claimed as done.
- Without acceptance evidence for the real paths of lock screen, voice, pause, calls, Feishu and ADB, do not merge directly into main.
- In the final review, check the full commit sequence and cumulative diff to confirm no build artifacts, keys, old-directory fallbacks or unrelated formatting were smuggled in.

## 6. Current verification resources and limitations

- One connectable Android device has been detected and can be used for test-build acceptance; cases that actually install or change system settings must first state their impact, use test data, and not read or use real credentials.
- This machine currently has no `go`, so relay builds and tests cannot start for now; that tool must be installed and passing before the merge, and this is not recorded as passed.
- Available credentials for real model services, third-party TTS, SIM/calls and the Feishu test tenant are not yet confirmed. Parts that can be completed with local fake services and instrumentation are done first; real external paths without evidence are explicitly left as unverified, and acceptance conclusions are not faked.

## 7. Merge, attribution and rollback

- Feature commits remain atomic; the commit message explains behavior, architecture and verification, and includes the actual contributors' `Co-authored-by`.
- The final merge uses a merge commit that preserves the feature commits, without a full squash.
- After merging, close the 19 original PRs with comments pointing to the corresponding feature commits; do not close them midway through integration.
- When any feature needs rollback, prefer reverting the corresponding feature commit to avoid affecting other capabilities in the same batch.
- If main moves forward during integration, periodically rebase the integration branch and rerun the affected tests; do not resolve conflicts by force-overwriting main.
