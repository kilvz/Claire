# WeChat proactive result push + local task unlock opt-in design

Date: 2026-11-21
Status: awaiting approval
Research source: in-session research notes (a close reading of the two feature branches, including the guardrail list and the defect list; the notes are not committed, and every conclusion has landed in this spec)

## Intent and why

Two small, worthwhile features, both hooked onto the existing foundation:

1. **WeChat proactive result push**: the WeChat channel delivery layer (binding, Outbox, dedupe) has long been ready; the only thing missing is a tool entry point on the model side — when the user says explicitly inside a task "send it to my WeChat when it's done", the model can push the result proactively to the bound owner's WeChat.
2. **Local task unlock opt-in**: unlock authorization is currently available only to scheduled/WeChat-sourced tasks; local (UI-initiated) tasks have no authorization path. In the fork, about 70% of comparable work (relock obligation, guard notifications, interaction counting) was merged into main today in a better form (TaskRelock with a WAIT_IDLE grace period), so what is really left to add is only ~90 lines.

## Goals

- F1: when a task is Ui-sourced and the initial task text carries an explicit "send it to my WeChat" intent, the model can use `wechat_send_owner(text)` to push the text to the currently bound owner's private chat; when the conditions are not met, the tool does not appear in the supply at all.
- F2: add a default-off "Run tasks while locked" switch in Settings, independent of the existing "remote task unlock" switch; once on, Ui/trigger tasks can use the local encrypted credential to wake the screen and unlock.
- All new strings in 11 languages; prompt golden samples regenerated in sync.

## Non-goals

- Not letting the model choose the recipient (the delivery target is always the currently bound owner's private chat).
- No image/file push (the fork only has text too).
- Not changing the existing semantics of the remote unlock switch; the two switches do not affect each other.
- No automatic retry after a failed unlock (credential safety: stop on failure, hand over to a human).

## Anti-goals

- Credentials stored in plaintext in any form, displayed, or injected on retry.
- A push tool that appears without explicit intent (to stop the model from sending things out on its own).
- Duplicate delivery (summary dedupe; when the delivery result is uncertain, never resend automatically).
- Reinventing the TaskRelock/relock system just merged today — it must be reused.

## Constraints

- Generic AOSP; no brand special cases; code/comments/docs carry no trace of external projects.
- Tool descriptions live in `core/src/main/resources/prompts/tools/`; golden samples are regenerated with `-PupdateSnapshots` (including `*PromptLayoutTest*`).
- Every new string present in all 11 languages.

## Design

### F1 WeChat proactive result push

- **New tool `wechat_send_owner(text)`** (new core file `WechatOwnerCapability.kt` + `prompts/tools/wechat_send_owner.md`): Risk.Write, and the tool description states clearly that "delivered successfully = accepted on the WeChat side, which does not mean read; the same text is not sent twice within one run".
- **Existence gating (not a runtime check)**: only when ① the task source is Ui (excluding scheduled/subtask/WeChat sources) and ② the initial task text passes the "explicit owner-directed send" gate does the tool enter the supply. The gate: the fork version only has a Chinese mode (English tasks never match, which is as good as broken for non-Chinese users) and its length window swallows long sentences — **our version has at least a bilingual Chinese/English mode and does no length truncation** (my decision, fixing the fork's defect).
- **Sending side** (new app file `WechatOwnerSender.kt`): re-validate the binding at send time (enabled + account + owner context token + an existing private chat thread); if the binding changes mid-run → unavailable, and report it truthfully. Outbox summary dedupe guarantees idempotency; before sending, add a TOCTOU `accepts` re-check (main already has it on the image path but not on the text path, so it is filled in here as well).
- **Touch points**: `AgentTool`/`TaskTools` registration, `WechatStore` (boundOwnerPeer query), `WechatChannel`, `TaskRun` (supply gate), `ChatViewModel` (the facts passed to the task), `strings_tools.xml` ×11.
- Size: ~200 lines of new code + ~160 lines of tests.

### F2 Local task unlock opt-in

- **Configuration path**: `DeviceAccessConfig/DeviceAccessStore` gains `localEnabled` (default false), independent of the remote switch with no cross-effects; `DeviceAccess.unlockLocal` goes through the same encrypted credential store (revision check, never displayed).
- **Authorization decision**: `taskUnlockAuthorization` gains a Ui branch — Ui/trigger tasks get wake-and-unlock authorization when the switch is on; trigger tasks need only either of the two switches (the UI copy must make the relationship between the two switches clear).
- **Guards (all reusing the system merged today)**: before unlocking, accessibility must be running and the guard notification must be sendable (otherwise the relock obligation cannot hold, and the unlock is refused); permissions are re-checked before every step, and a mid-run revocation aborts before credential entry; `beginLocalAttempt` writes a "blocked" marker before entering the credential — after a failure/cancellation/crash it never retries automatically; only unlocks performed by us carry the relock obligation (if the user had not locked the phone in the first place, the guard is removed immediately, with no false alarm — the fork has a false-positive defect here, while our `clearUnlockGuardIfSafe` behavior is right, so keep it); relock waits for all tasks to finish, and if the user touches the main screen it yields and waits for idle (the fork alarms on the slightest touch, while our WAIT_IDLE is strictly better, so keep it).
- **Settings UI**: an independent switch + explanatory copy; guidance strings for failure/interruption (5 keys ×11 languages; the relock strings already exist).
- **Behavior change (must be explained in the release notes)**: once Ui tasks have an authorization path, an unlock failure changes from "task failed" to "wait for the user to unlock manually" (through recoverUnlockFailure).
- Size: ~90 lines changed + ~120 lines of tests.

## Verification

- JVM: `:core:test :app:testDebugUnitTest` all green (including the new tests: positive/negative gate cases in Chinese and English, existence gating for Ui, dedupe not resending, the localEnabled authorization matrix, unlock failure handing over to a human).
- Golden samples: `*PromptSurface*` + `*PromptLayoutTest*` regenerated.
- Real device (needs the user present): ① "send me the XX result on WeChat" end to end; ② switch on → a local task on the lock screen wakes the screen and runs → relocks automatically when finished; ③ switch off → the local task does not unlock. Real-device results go back into this document.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| The model abuses push to bother the owner | Existence gating (no explicit intent, no tool) + a fixed, non-selectable target + dedupe |
| Credential leak / retry injection | Write the blocked marker before entry, stop on failure; reuse the existing encrypted store, no new path |
| Explicit intent in languages other than Chinese and English goes unmatched | The gate pattern table is extensible; an unmatched intent means the tool does not appear (the conservative direction, never a false enable) |
| The two switches are confusing | The settings page copy states the relationship; trigger tasks need only either one |
