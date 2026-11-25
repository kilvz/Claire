# Personal assistant mode (first version)

A fixed entry point sits in the sidebar above “新对话” (New conversation) and opens a long-lived personal main conversation. “启动时打开个人助手” (Open personal assistant on startup) can be enabled in Settings. Chat uses the main model from the model settings, and task execution uses Claire's approvals, takeover, resource queuing and run recovery. Proactive contact is left for later.

## Messages and execution

- `core/personal/PersonalAgent.kt` is a testable coordinator that uses the existing ChatModel protocol. Model calls come from the host model source and record usage as usual.
- The main chat uses a separate inbox queue and does not take up a TaskCenter execution slot. New text is recorded into the main conversation immediately, and the next batch is consumed once the current reply has been handled; continuous input does not automatically become interruptions of a background task.
- The model provides delegate_task, revise_task, cancel_task, remember, recall_history. It delegates only for explicit action requests; it revises the corresponding task only for explicit additions; ordinary discussion stays as chat.
- Before delegating, it saves the task and a separate conversationId, then calls TaskService. Background tasks may queue due to resource limits, while the main chat can still reply.
- Revision and stop only accept taskIds registered by the personal assistant. The main chat does not directly get device tools. Takeover, confirmation and answers are handled through the task card, which opens the original task page.
- Repeated delegation in the same turn is deduplicated, and a failed start clearly shows failure; a process restart does not automatically replay actions that may already have run.
- PersonalRuntime recovers progress and results from task state and the original conversation results. Terminal state and the delivery flag are persisted in the same write, so repeated refreshes do not send results twice. A delayed closing statement updates the same message.

## Continuity and storage

`files/personal/main-v1.json` uses Android AtomicFile for atomic writes, saving the original messages, task correspondences, summaries and coverage boundaries. After reopening or a restart it still uses this main conversation, and summaries do not delete the original text. When reading fails, the original file is kept as a backup and a notice is shown.

Longer history is organized into rolling summaries, keeping a recent range for picking up the conversation, and history is limited by the model's context budget. recall_history searches the full original text by keyword. Opening the chat jumps straight to the latest message and renders older messages on demand. The first read still parses the whole local record file.

Long-term facts and preferences use the existing encrypted memory store and respect the master switch. Writing is only allowed when the user explicitly asks to remember something, with the real original source attached, and it still goes through sensitive-information filtering. Model settings and memory management reuse the existing pages.

After the process is killed, an unfinished main chat reply is marked as interrupted the next time it is opened, and turns with actions are not automatically retried. Task execution uses the existing recovery mechanism; no promise of always being online or resuming mid-way through device operations.

## Native UI

- PersonalAgentScreen uses the floating avatar and the existing chat input box, supporting push-to-talk and keyboard sending. Assistant replies use the existing Markdown rendering, with table support.
- Tapping the avatar opens the tasks, memory and skills popover. Execution progress is concentrated below the avatar; tapping the progress opens task details.
- When executing a phone task, the phone screen is shown to the right of the avatar. Tap to enlarge; the progress bar at the bottom is at the far right by default. Drag left to review, and drag back to the far right or tap “回到实时” (Back to live) to keep following the task. Finished tasks can be opened for playback from task details.
- Playback uses a low-frame-rate JPEG sequence with a sampling interval of at least 500 milliseconds. Files are stored in the app's private directory, and the total for all playbacks is capped at 100 MB. The oldest non-recording playbacks are cleaned up first.
- Route.Personal and the fixed sidebar entry; the existing standalone conversation is kept.
- Task details enter the original task page to view the full process, approve or take over; going back to the personal assistant is still the original main conversation.
- Stop targets the selected task only; other execution controls follow the states the task page actually supports.
- The new UI copy is mainly Chinese; multilingual copy and streaming display can be polished later.

## Verification

Core tests cover: ordinary chat and history continuity, still being able to chat after delegation, revision ownership, deduplication of repeated delegation, rejection of unknown taskIds, continuous input, no replay after restart, honest errors with no model, stop not falsely reporting success, summaries not deleting the original text, and the closing statement not being delivered twice.

There are also regression tests for ChatViewModel, the playback store, preview and the draggable timeline. Scripted-model tests, emulator interaction and real-model real-device experience are accepted separately.
