# Front Desk layer design

Date: 2026-11-23 · Status: Direction settled; full text awaiting user review

## 1. Why

Today, when Claire receives a sentence, it hands the whole thing to the background task loop (`AgentLoop` / `TaskRun`) and only delivers when a result comes out. That is enough for plain conversation, but three places are no longer sufficient:

- **In-app voice calls reply slowly, and you cannot chat while something is being done.** After the voice model calls `mika_request`, VoiceCallRoute.execute (deleted) stays blocked until the task reaches a terminal state; only one request is accepted at a time; the task is cancelled as soon as the user starts speaking (`onBargeIn → cancelTask`); and when a task needs a human answer it only says "Check the task page". In standard mode (listen → think → speak), the "think" step uses the main model or the light model, and playback starts only after the whole sentence is synthesized, stacking one delay on another.
- **While typing in chat, the user can only watch task progress.** Every message goes straight into the task loop, with no layer in between that first receives the user and then dispatches work. Interjections still queue and require a manual switch (`FollowUpMode`).
- **During AI phone calls, the model in the call cannot reach the background.** It has only the dial-pad tools, task information is read-only, it cannot look anything up during the call, and the user cannot pass it a message midway.

What the user wants: a resident, fast-reacting "front desk" dedicated to talking with the person; it dispatches work to the background and occasionally reports progress.

## 2. Positioning (settled by the user)

- **The front desk is Claire's unified entry point.** Chat, in-app voice calls and AI phone calls all have the front desk answer first. The three are the same front desk, only facing different people and speaking in different ways.
- **The front desk only communicates with the person and does no work (changed by the user on 11-23).** It does small talk, offers ideas, responds and relays progress. What it needs to know (this conversation's record, relevant memories, each task's current state) is put into context by code every turn; the front desk does not look it up itself. Anything that needs a tool — including web search, reading web pages, looking things up, and operating the phone (opening apps, tapping the screen, sending messages, changing things, spending money) — is dispatched to the background. The front desk holds only the tools for "talking with the user and handing off to the background": dispatch work, supplement, relay answers, cancel, plus asking the user during calls.
- **Structure A is chosen:** an independent front desk layer, with the background continuing to use the existing task system. It did not choose "let AgentLoop double as the front desk" because that is too heavy to reply quickly, and did not choose "the front desk as a resident main task and the background as subagents" because the main task would hold the task lifecycle and the virtual screen, conflicting with "always online".

## 3. Goals / non-goals / anti-goals

**Goals**
1. Every time the user says something, the front desk gives a first reaction quickly (text starts streaming, speech starts playing). The target is to start speaking within 1–2 seconds for text and 2–3 seconds for speech (targets, not hard gates).
2. While the background is running, the user can keep chatting with the front desk, dispatch new work, supplement or change requirements, and cancel. Speaking itself no longer cancels tasks.
3. One conversation can hold several background tasks at once, subject to the existing parallelism limit (`ParallelCapability`). Beyond the limit they queue, and the front desk tells the user their position.
4. The front desk selectively reports the background's key moments: work started, stuck, needs your answer or confirmation, finished. All other details stay in the task card.
5. When the background needs a human answer (`WaitingInput`), the front desk asks the user in the conversation and passes the answer back. Confirmation and takeover (`WaitingConfirmation` / `WaitingTakeover`) still go through the existing cards and safety gate; the front desk only reminds and points the way, and does not consent on the user's behalf.
6. Chat, voice calls and phone calls share this one front desk implementation, each adding only an adapter layer.

**Non-goals (not in this round)**
- Proactive tasks, scheduled triggers, the WeChat channel and the remote MCP entry (`startTrigger` / `startChannel` / `startMcp`). For now they still go straight to the background; whether they should pass through the front desk is decided later.
- Reworking the background task loop itself. Only add event outlets and interfaces where needed, without changing its decision logic.
- Redesigning the safety policy. The confirmation gate and risk grading stay as they are.

**Anti-goals (technically runnable, but doing it this way counts as failure)**
- The front desk slows down: too many tools or too much context, so one reply takes five or six seconds.
- The front desk chatters: announces every step of progress, interrupts the user, or reads out coordinates and UI details.
- The front desk pretends work is done: the background has no terminal state yet and the front desk says "already sent / already booked". What the front desk says about results may come only from the background's terminal state.
- The front desk acts around the background: the front desk gets tools that directly drive the screen, send messages or spend money.
- Three implementations: chat, voice and phone each write their own "dispatch — wait — report" logic.

## 4. Structure

```
 User / other party
    │  text or speech
 ┌──▼───────────────────────────┐
 │ Entry adapter (Surface)      │  Chat page · Voice call (standard/realtime) · AI phone call
 └──┬───────────────────────────┘
    │  user's words ↓   front desk's words ↑
 ┌──▼───────────────────────────┐
 │ Front Desk (core)            │  a lightweight conversation loop
 │  - conversation record       │
 │  - context: memory, task state (from code) │
 │  - dispatch tools: dispatch/progress/ │
 │    supplement/cancel/relay answer    │
 │  - reporting policy: which events to say │
 └──┬──────────────▲────────────┘
    │ dispatch/supplement/cancel │ background events (started/stuck/needs answer/terminal)
 ┌──▼──────────────┴────────────┐
 │ Background: TaskService/TaskRun │  each dispatched job is a task
 └──────────────────────────────┘
```

### 4.1 Front desk loop

- It lives in core as a new lightweight loop and does not reuse `AgentLoop`. Lower-level parts are reused directly: model calls (`ModelCaller`), failover (`Failover`), context compaction, language determination (`ReplyLanguage`), and prompt layering.
- There are two kinds of input: one sentence from the user, or one background event. Both trigger a round of front desk replies; a background event can be judged as "say nothing".
- Each round calls tools at most a few times (there is a limit, similar to `LlmBrain.maxToolRounds`). Tool calls do not block the background: the dispatch tool returns immediately after dispatching and does not wait for the task to finish.
- **Lifecycle (settled by the user)**: the front desk follows the conversation, not any single call. It is resident at the app layer, one front desk per conversation. During a call, the front desk can see this conversation's earlier chat history (compacted). After hanging up, dispatched work keeps running; later "needs your answer" events and final results are written back to this conversation by the front desk, with a notification. The chat page uses this same front desk.
- When the user speaks again while the front desk is replying, that round is voided (speech also stops playing), but background tasks already dispatched are unaffected.

### 4.2 Front desk tools

**No query tools (changed by the user on 11-23)**: the front desk carries no tools for web search, memory lookup or progress lookup.
- Task state: every round, code puts the current state of each task in this conversation (number, title, in progress / queue position / needs your attention / finished, one-line progress) into the front desk's context, and the front desk talks from that.
- Memory: every time the user speaks, code retrieves relevant memories for that sentence and puts them into context (marked as background material, not instructions).
- Anything that needs research or the internet is dispatched to the background.

**Dispatch tools** are only the ones for handing off with the user and the background:

| Tool | What it does | Maps to existing mechanism |
|---|---|---|
| Dispatch work | Write a complete task goal and hand it to the background, returning the task number immediately | `TaskService.start(...)`, same as VoiceCallTaskLauncher (deleted) |
| Supplement instruction | Pass what the user just said to a task in progress | `SteerCoordinator.steer` |
| Cancel | Cancel a task | `TaskService.cancel(runKey)` |
| Relay answer | Pass the user's answer to a background question back | existing `WaitingInput` answer entry |

When dispatching, the goal must be written completely (including the context from the front desk conversation), because the background task cannot see the front desk conversation.

**Who counts as the author of the goal (my decision)**: the background treats the task goal as the user's own words (`UserUtterances`); memory citation checking and `skill_save` both rely on it; safety gate ③ for voice also requires the request to be a substring of the user's current-turn transcript. So the dispatch tool separates two fields:
- `user's own words`: the exact sentences the user said, possibly several, taken by code from the front desk conversation by number, not rewritten by the model. Only this segment goes into `UserUtterances`, and only it must pass gate ③.
- `front desk supplement`: background and organization written by the front desk, clearly marked as "the front desk's explanation, not the user's own words" when it enters the task context.

Relaying a `WaitingInput` answer follows the same rule: what is handed back must be the user's own words. This keeps the existing trust boundary fully tight. It requires changing `TaskService.start` and the task assembly interface.

### 4.3 Background events → front desk

- The front desk subscribes to each task's own `TaskState` by task number, like the former VoiceCallRoute (deleted), and never subscribes to the global state stream.
- State changes are first grouped by code into several event kinds: **started, stage progress (throttled), stuck and needs your attention, needs your answer, success, failure, cancelled**, then handed to the front desk.
- **The reporting policy is a code rule and is not left to the model to decide on the fly.** Started, needs answer, needs attention and terminal events must be reported; stage progress is not reported by default, only when the user asks or after a long silence. The front desk model is responsible for turning what must be reported into plain language.
- Progress text goes through the same class of cleaning as `stripVoiceLineDetails`: remove coordinates, and do not read out details inside quotes.

### 4.4 One conversation with multiple tasks

- Today `TaskCenter.byConversation` maps one conversation to one task by default. Every job the front desk dispatches must be distinguishable from the conversation the front desk is in, yet attach back to that conversation. **The approach is left to implementation**: each job can open its own child conversation attached to the front desk conversation (the former VoiceCallRoute created a new conversation), or TaskCenter can be extended to allow multiple tasks per conversation. Either way, the chat page must be able to attach each task's card back to the front desk conversation.
- The parallelism limit follows `ParallelCapability`; beyond it, tasks queue, and the queue information is spoken by the front desk.

### 4.5 Entry adapters

- **Chat page**: bubbles in the conversation become the front desk's words, and background tasks are interspersed in the conversation as task cards; tapping one opens the existing task details and progress. The manual "interject / queue" switch is removed and the front desk decides instead. **The UI must first produce a local HTML prototype, and is implemented only after the user confirms.**
- **In-app voice calls**: in standard mode, `LlmBrain` is replaced by the front desk; the former VoiceCallRoute's blocking dispatch is dismantled and replaced by the front desk's dispatch tools. **Realtime mode (third-party realtime voice model, settled by the user)**: the realtime model itself acts as the front desk, handling speaking and decisions. All of the core front desk's dispatch tools are exposed to it (dispatch, supplement, cancel, relay); task progress is pushed to it as system messages, and relevant memories are placed in its prompt at the opening; the former `mika_request` and the "stay silent, only forward the user's own words" prompt (`AGENT_TOOLS_PROMPT`) are replaced by the front desk role prompt. Event grouping and the reporting policy remain the same code in core, and background events are pushed back to the realtime model as tool results or system messages according to each protocol's capabilities. The acceptance checklist for realtime mode is exactly the same as for standard mode. The user speaking only interrupts the front desk's speech and does not cancel tasks.
- **Confirmation and takeover during a call (my decision)**: this round does not add a confirmation card to the call page. The front desk says "this step needs you to tap on the phone"; the user switches to the task card to handle it, and the call is kept alive in the background by the existing foreground service (`VoiceCallService`). Acceptance must confirm that audio and the call survive switching away; an embedded confirmation card in the call page is left for later, with a prototype first.
- **AI phone calls**: these face a third party; the rules are in 5.1.

### 4.6 Model and speed

- The front desk uses the light model by default (`ModelRole.Light`), falling back to the main model when none is configured. The user can change it in settings (my decision).
- On the voice path: replies are generated as a stream, split into sentences, and each sentence is synthesized and played immediately (`SentenceSplitter` already exists); providers that support streaming synthesis use streaming. Slow synthesis is handled by the provider's capability detection with the corresponding implementation, and falls back to whole-sentence synthesis when it cannot be detected.

### 4.7 Prompts

- Add a front desk role layer following the existing layering (new under `prompts/roles/`). Content: Claire's identity; the front desk's responsibility boundary (only communicate, do no work: answer from context what it can, dispatch anything needing tools, and do not state results on the background's behalf); how to write a complete goal when dispatching; how to pick out the important points when reporting progress. In voice scenarios, layer `SPOKEN_STYLE` on top.
- Once the front desk decides "answer myself or dispatch", the background task's `IntentGate` becomes redundant. This round leaves it unchanged and evaluates whether to turn it off once the front desk is stable.

## 5. Scope (settled by the user: no phasing, done in one go)

The chat page, in-app voice calls and AI phone calls are done together in one plan. The chat page UI first produces a local HTML prototype, and is implemented after the user confirms.

Acceptance:
- **Voice calls**: after dispatching work the user can keep chatting; two things can be in flight at once; the user speaking does not cancel tasks; when the background needs an answer it asks during the call; the terminal state is spoken, and spoken only once.
- **Chat page**: the front desk starts replying within one or two seconds; background tasks are interspersed in the conversation as cards; the manual interject / queue switch is removed and the front desk decides instead; results of tasks that finish after the call hangs up are written back to the conversation.
- **AI phone calls**: see 5.1.

Following project convention, run unit tests locally first (`:core:test :app:testDebugUnitTest`), then install the perf build and verify on a device (`LiveRunTest` runs itself).

### 5.1 The front desk in AI phone calls (my decision)

The other end of the phone is a third party, and their words are not the user's words, so:
- The phone front desk **must not dispatch hands-on work based on the other party's words**. It has only one tool: **ask the user**. The goal, rules and current state of the task this call belongs to are put into its context by code; it carries no memory retrieval and no web search.
- **Ask the user**: the front desk writes the question into the conversation this call belongs to and sends a notification at the same time. The user answers in the conversation, the answer is pushed back into the call as a prompt, and the front desk relays it to the other party. Before the user answers, the front desk says to the other party "let me confirm, one moment", or wraps up per `CallPolicy`.
- The existing dial-pad tools, duration limit and farewell line stay as they are.

## 6. Constraints

- Design for generic Android, with no special cases for any device model (CLAUDE.md).
- Do not add providers.
- New UI text must cover all UI languages.
- When a feature is unavailable, its entry must still be findable and guide the user to settings; it must not silently disappear (for example, fall back to the main model when no light model is configured, with a notice).
- Split changes into files by responsibility; do not keep piling onto already-large files such as `TaskRun` / `ChatViewModel`.

## 7. Options considered

| Option | Trade-off | Conclusion |
|---|---|---|
| A Independent front desk layer, background keeps existing tasks | One more new layer, and the chat page must change; but replies are fast, the background barely moves, and all three share it | **Choose this (user's decision)** |
| B Let AgentLoop double as the front desk | Context, memory and prompts are all ready-made; but it is too heavy — preparation, intent judgment and review are all on the path, replies are slow, and the two roles are hard to separate once mixed | Not chosen |
| C Front desk as a resident main task, background as subagents (SpawnAgent) | Structurally closest to the existing code; but the main task holds the task lifecycle and the virtual screen, and subagents would still need to change to dispatch-without-waiting, so the result is really A in a heavier shell | Not chosen |

## 8. Left to implementation

- The dispatch tools' exact names and parameters.
- Whether one conversation with multiple tasks uses child conversations or an extended TaskCenter (4.4).
- The details of event grouping and the progress throttle duration.
- The length and compaction policy of the front desk conversation record.
- How background events are injected into the realtime model in realtime mode (tool results or system messages), decided by each protocol's capabilities.
