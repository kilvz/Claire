# Front desk implementation: handoff notes (2026-11-23, second handoff)

For the agent taking over. You can continue from these notes without rereading earlier conversations. The previous handoff (Task 3 halfway) has been completed, and this version is the current state.

## 1. Where and how far

- **Working directory**: `/Users/bolyn/coding-projects/Claire/.claude/worktrees/front-desk` (git worktree, branch `front-desk`, branched from local main `2e33e150b`). All work happens here. **Do not touch main, other branches or other worktrees; do not push or merge** unless the user asks in person.
- **Three files to read** (all committed on this branch):
  - Design (highest authority): `docs/design/specs/2026-11-23-front-desk-design.md`
  - Implementation plan (11 tasks + inserted 3b): `docs/design/plans/2026-11-23-front-desk.md`
  - Chat-page prototype (user has approved v2 + the in-progress bar variant above the input field): `docs/design/prototypes/2026-11-23-front-desk-chat.html`
- **Progress ledger** (most important; git-ignored, only on disk): `.superpowers/sdd/2026-11-23-front-desk/progress.md`. It contains the pre-flight table, all rulings (Ruling A–K), the commit range of each task and deferred minor issues. **Trust the ledger + `git log`**; do not redo completed tasks from memory.
- **Current state** (HEAD = `c4ac312e2` when this was written):

| Task | Status | Commits |
|---|---|---|
| 1 Move the conversational brain into core | ✅ Done, review passed | `4e44b27cf` |
| 2 Task-event classification + report policy | ✅ Done, review passed | `6fcb6813b` |
| 3 Dispatch tools + executor | ✅ Done, review passed | `ea95da0ef` |
| 3b Front desk only talks and dispatches (user change, lookup tools removed) | ✅ Done, review passed | `455cb0c19`…`677a7ae66` |
| 4 Per-conversation resident FrontDesk | ✅ Done, review passed | `3db7ef9ff` |
| 5 Background receives front-desk notes | ✅ Done, review passed | `1727a6f81` |
| 6: App-layer wiring (passed after 1 fix round) | ✅ Done, review passed | `cef4d456f`…`37fb910c3` |
| 7 Front-desk prompts + model choice | ✅ Done, review passed | `c4ac312e2` |
| 8 Voice call connected to the front desk | ⏸ **The implementer was dispatched but had not touched the working tree when stopped** | Start point `c4ac312e2` |
| 9 Chat page goes through the front desk (prototype gate open) | Not started | — |
| 10 The front desk in AI phone calls | Not started | — |
| 11 Full regression + device verification | Not started | — |

**Task 8 leftovers**: shortly after the implementer (opus) was dispatched the user called a stop, and the controller terminated it directly. **When terminated it was still reading code; the working tree was clean with no work in progress** (verified). **First thing on taking over: dispatch a new Task 8 implementer** (opus, BASE = HEAD at the time), following the prompt style in section 2 and carrying the rulings in section 3 (Ruling B/F/G/H/I are mandatory). Task 8's brief is already generated: `.superpowers/sdd/2026-11-23-front-desk/task-8-brief.md`; the report goes to `task-8-report.md` in the same directory.

## 2. Execution approach (user's choice: subagent-driven)

Use the superpowers `subagent-driven-development` skill. For each task: dispatch an implementation subagent → dispatch a review subagent → send any Critical/Important findings back to the original implementer to fix → after it passes, update the ledger → next. Do not stop between tasks to ask the user.

**Scripts** (skill directory `/Users/bolyn/.claude/plugins/cache/superpowers-dev/superpowers/7.0.2/skills/subagent-driven-development/scripts/`, run from the worktree root):
- Generate the task brief: `bash <dir>/task-brief $PWD/docs/design/plans/2026-11-23-front-desk.md N` → writes `task-N-brief.md` (**briefs for 8/9/10/11 are already generated**, directly in `.superpowers/sdd/2026-11-23-front-desk/`)
- Generate the review package: `bash <dir>/review-package $PWD/docs/design/plans/2026-11-23-front-desk.md <BASE> HEAD`. BASE is the HEAD recorded before dispatching the implementer; **do not use HEAD~1**

**Shared files already prepared** (in `.superpowers/sdd/2026-11-23-front-desk/`; have the subagent read them when dispatching so the prompt can be very short):
- `implementer-rules.md`: implementer rules + project rules (work only in this worktree, localize every language copy, test commands, etc.)
- `reviewer-rules.md`: review method and output format
- `global-constraints.md`: the global constraints from the plan

**Model**: always use **`opus`** (Ruling D: the `sonnet` alias returns 404 on this account).

**Verification**: pure core tasks run `./gradlew :core:test`; tasks touching app (8/9/10/11 all) run the full `./gradlew :app:testDebugUnitTest` (about 20 minutes). **Do not run bare `:app:test`** (OOM). Known local load flakiness can be ignored: `TerminalHubTest` 15s timeout, `McpServerStoreTest.cancelDuringConnectClosesWhatConnected` (when the diff does not touch the corresponding area).

## 3. Settled rulings (carry them when dispatching later tasks)

The ledger has the original text; here are the ones later tasks will use:
- **Ruling A**: `DeskBrain.seed(prior)` only takes effect when history is empty; each Turn expands to user (userText non-empty) + assistant (outcome non-empty), regardless of status.
- **Ruling B**: the cleaning function is in core `DeskLines.stripDetails`; `VoiceCallRoute` calls it, and when Task 8 deletes `VoiceCallRoute`, confirm no other caller is left stranded. The result limit constant is `DESK_RESULT_MAX_CHARS = 1_800`.
- **Ruling C**: the prototype is approved (v2 + the in-progress bar variant above the input field, 11-23 18:03), **Task 9's gate is open**; follow the prototype directly.
- **Ruling E**: tasks that only change core do not run the full app suite.
- **Ruling F** (actual Task 3 interfaces): `DeskLedger.add(entry: DeskTaskEntry)`; the `DeskToolExecutor` constructor takes `conversationId: String` + `onDispatched: (DeskTaskEntry) -> Unit = {}`; `DISPATCH_DEDUPE_MS = 30_000L`; tool result shapes are fixed (`started{task_id}` / `queued{task_id, position}` / `failed{reason}` / `ok` / `not_accepted` / `rejected{reason}` / `unknown_task` / `already_dispatched{task_id}` / `unavailable` / `unsupported`).
- **Ruling G**: `FrontDesk.track(taskId, title)` does not write the ledger (the executor writes it); app wiring uses `onDispatched = { desk.track(it.taskId, it.title) }`.
- **Ruling H**: an interrupted report goes to the next attached surface, otherwise straight to the sink (write to the conversation, do not read it out again) — `FrontDesk.deliver` already behaves this way; the bridge's `deliver` returning false triggers it.
- **Ruling I (user change, 11-23)**: the front desk only talks and dispatches. User tools = dispatch/add/answer/cancel; Phone tools = only ask_owner; lookup tools (check_tasks/web_search/recall_memory) have been deleted, and the task board + memory are put into the context by code through DeskBackground. **No task may bring the lookup tools back.**
- **Ruling J**: the background block is a temporary trailing message (system stays byte-stable to hit the cache); memory is looked up once per user sentence; the task board carries recent ≤3 lines of progress for in-progress / awaiting-answer tasks.
- **Ruling K**: the model adapter has an opt-in no-cache message marker; move the cache point before the no-cache block; default requests stay byte-identical.
- **Task 6 ruling**: desk steer goes through local `run.steer`, not the plan's `steerRemote` (that is MCP-only).
- Check the real code signatures before dispatching each task; do not copy the plan text verbatim.
- Dispatch order: **`8 → 10 → 9 → 11 → whole-branch final review`**.

## 4. Key points for the remaining tasks

- **Task 8 (voice call connects to the front desk)**: delete `VoiceCallRoute`/`VoiceCallTaskLauncher` and their tests; create `VoiceDeskBridge`; `CallRealtimeSession.sendNotice`; both cascade + real-time (Volc); `onBargeIn` no longer cancels tasks; conversation-flow task cards are driven by `desk.active`. Two deferred items in the ledger should be verified along the way in this task: desk_report row rendering, and the sink notification throttled by `ReportPolicy` (do not create a second throttle in the bridge).
- **Task 9 (chat page goes through the front desk)**: follow the prototype; the gate is open (Ruling C).
- **Task 10 (AI phone front desk)**: `PhoneDesk` has only `ask_owner`; the owner's reply goes through the notification `RemoteInput` (minSdk 29 satisfies API 24+); `OwnerReplyReceiver` is registered in the Manifest and not exported.
- **Task 11 (device)**: build the perf package (`./gradlew :app:assemblePerf`; the release build is this variant, signed with debug), install on the test phone (installing requires one tap on the phone, **possible only with the user next to the phone**), force AOT with `adb shell cmd package compile -f -m speed com.wangzan101.mika` (note: `cmd compile` does not exist on this phone; use `cmd package compile`). The test phone is a HONOR ANP-AN00 (A94VUT2C28001694, Android 14). Run scenarios yourself with `LiveRunTest`. If the user is not around, finish everything that can run automatically and mark the device items in the acceptance document as "awaiting device".
- **Whole-branch final review**: after all tasks are done, follow the skill's `references/final-review.md` and use the strongest model. All `minor (deferred)` items in the ledger go to the final review for triage.
- When done, use `superpowers:finishing-a-development-branch` to ask the user how to merge; **do not merge into main yourself**.

## 5. Other notes

- The main repository `/Users/bolyn/coding-projects/Claire` still has untracked old copies of these three documents from before the worktree was created (`docs/superpowers/{specs,plans,prototypes}/2026-11-23-front-desk*`). The official versions are on the branch (moved to `docs/design/` along with main's directory layering); delete the old copies at merge time and leave them alone for now.
- main's perf package (0.1.51, versionCode 52, `a85b8a7be`) is already installed on the test phone for the user to try and is unrelated to this branch.
- Project rules are in `CLAUDE.md` at the repository root and the user memory directory `/Users/bolyn/.claude/projects/-Users-bolyn-coding-projects-Claire/memory/MEMORY.md` (generic Android, do not add niche providers, fix by root cause, prototype new UI first, etc.).
- After finishing each task, append a line to the ledger: `Task N: complete (commits a..b, review clean)`; record minor issues as `Task N: minor (deferred): …`.
- Use Chinese for code and comments, following the surrounding style; write commit messages as given in the task brief.
