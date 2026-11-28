# Chat page reply redesign implementation plan

Prototype: `docs/design/prototypes/2026-11-24-chat-page-hifi.html` (approved by the user on 11-24).

Goal: Several replies after one sentence (the front desk's acknowledgement, the completed result, and legacy answers) are all drawn as the same light-grey block, with the full result written out; in-progress jobs no longer insert cards into the conversation, and progress is shown in the bar above the input field; when something needs your action there is one question card in the conversation; the virtual-screen mini window also follows the work dispatched by the front desk.

## A few decisions from the user

- The mini window covers the question card: keep it as is (draggable, closeable).
- No "view process" under a result; reach it from ⋯ at the top right → "Jobs in this conversation".
- Two jobs with screens at the same time: the mini window shows the one that moved most recently; when more than one job is in progress, the window states which one, and when there is only one it does not.
- "Call settings" also goes in ⋯ at the top right (the entry in Settings stays).

## A. How replies look (`chat/ui`)

1. Front-desk replies (`DeskReply`), report rows and legacy answers (`StatusCard`'s completed state) all use the same grey block: `colors.answer` + `MikaShapes.AnswerBubble`, with the full body in Markdown; short ones hug the text width and long ones fill the width.
2. Remove "Claire · time · tokens" (`TurnMeta`) above an answer and the row of buttons at the end (`ReplyActions`); the long-press menu stays as before (copy, quote, select text, branch).
3. Replies under the same sentence are spaced 8 apart; when two adjacent rounds are more than 5 minutes apart, a time line goes between them (today "14:02", earlier "Yesterday 21:30" / date).

## B. Full results, less paraphrasing by the front desk

1. `DeskEvent.Succeeded.answer` is no longer truncated; only the copy shown to the front-desk model is truncated (`DESK_RESULT_MAX_CHARS`).
2. When the chat page is open, a completed result does not go through the front-desk model and is written verbatim as one report line (`desk_report`). A failed job is still spoken by the front desk (reason + next step). Needing your action is not announced in a separate sentence; a question card appears in the conversation.
3. Fallback when the page is not open (`DeskNotifier`): start, progress and needing-your-action no longer write a line into the conversation; completion writes the original text, and failure writes one sentence with the reason; notifications stay as before.
4. `front_desk.md`: when chatting by text, no longer limit it to "one or two sentences" — answer questions that can be answered directly in full; brevity for voice is handled by `SPOKEN_STYLE`.

## C. Progress bar and question cards

1. Add to `DeskTaskView`: `goal` (the original words handed to the background), `startedAt`, `steps` (one sentence per step + time, at most `DESK_STEPS_MAX` entries), `need` (what is being asked, the options, which kind of action is needed), `updatedAt`. The clock is injected by `FrontDesk`. `recent` is left for the call page.
2. Change `DeskTray` to match the prototype: collapsed, one line (status dot, name, current step, elapsed time, `+N`, arrow); expanded, one section per job (name, phase capsule, original words, vertical-line steps and elapsed time; at the bottom "Stop" and "View detailed process ›").
3. The conversation no longer draws `DeskTaskCard`. Each job that needs your action gets a `NeedCard` at the end of the conversation: either it asks you to choose (question + options; tapping answers this job directly) or asks you to take over/confirm ("Go handle it" opens this job's conversation, "Stop this job").

## D. The mini window follows front-desk jobs

When the conversation the front desk is handling has no task of its own: among jobs that are in progress (running / needing your action) and have a screen, take the one that moved most recently and have the service display it (the screen follows); the mini window draws green/orange according to its phase; when more than one job is in progress, the window shows the job name. Zoom and close state are remembered per page, separately per job.

## E. ⋯ at the top right

Replace the pencil at the top right of the chat page with ⋯: New conversation, Call history (tap one to jump to the start of that call), Jobs in this conversation (list every job the front desk dispatched; tap to open its run record, or its conversation if there is no record), Call settings. Remove ⋯ from the call page (call history is reached from the chat page).

## F. Wrap-up

Strings for all languages; `./gradlew :core:test :app:testDebugUnitTest --continue`, `:app:compileDebugAndroidTestKotlin`; update the front-desk and chat-page documentation under `docs/dev`; on a device, dispatch only harmless jobs and take a look; commit to main without pushing.
