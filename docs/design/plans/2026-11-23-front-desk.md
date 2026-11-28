# Front Desk implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a resident, responsive "front desk" so that the chat page, in-app voice call and AI phone all have it answer first; it only communicates and does not do the work: whatever it can answer from context it answers itself, and anything that needs tools (looking things up, going online, operating the phone) is dispatched to background tasks, with key progress reported.

**Architecture:** Create a new `frontdesk` package in core containing: the conversational brain (moved from the app's `LlmBrain` and extended), handoff tools, task board and memory context, task-event classification, report policy, and a per-conversation resident `FrontDesk`. The app layer implements the task port; every dispatched job is an ordinary task in a child conversation. Each of the three entry points writes an adapter layer connected to the same `FrontDesk`. The background task loop only receives an extra "front-desk note" and its decision logic is unchanged.

**Tech Stack:** Kotlin, coroutines/Flow, kotlinx.serialization, Jetpack Compose; JVM unit tests (`:core:test`, `:app:testDebugUnitTest`); device verification with the perf package.

**Spec:** `docs/design/specs/2026-11-23-front-desk-design.md`
**UI prototype:** `docs/design/prototypes/2026-11-23-front-desk-chat.html` (Task 9 must wait for the user to confirm the prototype before starting)

## Global Constraints

- Follow general Android design, with no special cases for any device model; when a model really differs, use capability detection and the corresponding implementation, and fall back to the general approach when detection is unavailable.
- Do not add providers.
- New UI text must be added to `app/src/main/res/values/` and all `values-*/strings.xml` (`values-night` excepted, currently 12 in total).
- When a feature is unavailable, the entry point must still be findable and guide the user to settings; it must not silently disappear.
- Split files by responsibility; do not keep piling new responsibilities into `TaskRun.kt`, `ChatViewModel.kt`, `ChatSendOps.kt`; put new logic in new files.
- Do not leave external product names in code or comments.
- The front desk's statements about task results may only come from the background's terminal state; the front desk must not directly call tools that touch the screen, send messages or spend money.
- Only the user's original words may enter `UserUtterances`, and they must be verified by code. What the front desk writes may only be a "front-desk note", marked `Trust.Claire`.
- A user utterance only interrupts what the front desk is saying, and does not cancel background tasks.
- For local verification use `./gradlew :core:test` and `./gradlew :app:testDebugUnitTest`; do not run `:app:test` directly (it runs out of memory).

## Review Focus

1. **The background needs a human answer while nobody is around**: the call has hung up and the chat page is not open, and the background enters `WaitingInput`. At that point it should write to the conversation and send a notification; it must not be lost; after the user returns to the conversation and answers, the answer can be handed back to that task. → Task 4 `reportsFallBackToSinkWhenNoSurface`, Task 6 `sinkAppendsTurnAndNotifies`.
2. **The model dispatches the same sentence twice**: the same user utterance dispatches a job twice within 30 seconds; only one task may be opened, and the second call returns the existing task's ID. → Task 3 `dispatchSameWordsTwiceReturnsExistingTask`.
3. **The real-time protocol has a 256-character limit on tool arguments**: a front-desk note that is too long gets truncated, but the user's original words must not be truncated. When the original words exceed the limit, the whole dispatch is rejected and the model is asked to change to a shorter fragment of the original words. → Task 8 `realtimeDispatchNoteIsTruncatedWordsNever`.
4. **Reopening after the process is killed**: the link between child tasks and the conversation is stored in the conversation record; after reopening, the task cards must be restored and still-running tasks resubscribed. → Task 5 `turnTasksRoundTrip`, Task 4 `restoreResubscribesRunningTasks`.
5. **The front desk is speaking while the background finishes**: the report must be queued after the current sentence and said only once; after the user interrupts the front desk, a report that was not finished must not be lost. → Task 4 `reportWaitsForCurrentReplyAndIsDeliveredOnce`.

---

### Task 1: Move the conversational brain into core and add "proactive speaking" and "carry history"

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/frontdesk/DeskBrain.kt` (move `ReplyContext`, `CascadeBrain`→`DeskBrain`, `LlmBrain`, plus `SPOKEN_STYLE` and `toolSpec` from `app/.../voice/cascade/CascadeBrain.kt`)
- Modify: `app/src/main/java/com/wangzan101/mika/voice/cascade/CascadeBrain.kt` (delete it, change references to core)
- Modify: `app/.../voice/cascade/CascadeEngine.kt`, `CascadeVoiceSession.kt`, `CascadeCallProvider.kt` (change imports)
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/frontdesk/DeskBrainTest.kt`; the original `app/src/test/.../voice/cascade/CascadeVoiceSessionTest.kt` stays all green after the import change

**Interfaces:**
- Produces:
  - `interface ReplyContext { suspend fun say(delta: String); suspend fun callTool(name: String, arguments: JsonObject): JsonObject? }` (unchanged)
  - `interface DeskBrain { suspend fun reply(userText: String, ctx: ReplyContext); suspend fun lateResult(name: String, result: JsonObject, ctx: ReplyContext); suspend fun notice(text: String, ctx: ReplyContext); fun remember(assistantText: String); fun seed(prior: List<Turn>) }`
  - `class LlmBrain(model: ChatModel, system: String, tools: List<ToolSpec>, maxMessages: Int = 40, maxToolRounds: Int = 4) : DeskBrain`
  - `LlmBrain.SPOKEN_STYLE`, `LlmBrain.toolSpec(tool: JsonObject): ToolSpec` (unchanged)

- [ ] **Step 1: Write failing tests** (a fake `StreamingChatModel` records the messages it receives)
  - `noticeIsAddedAsMikaNoteAndAnswered`: after `notice("Task A finished: booked")`, the last message the model receives has role `Role.User`, author `Author.Claire`, and text starting with `[update] `; ctx receives the model's reply text.
  - `seedPutsPriorTurnsBeforeFirstReply`: after `seed(listOf(Turn("Book Japanese food", "Okay, I'll have the background book it", "desk")))` then `reply("What time?")`, the messages the model receives are, in order, user "Book Japanese food", assistant "Okay, I'll have the background book it", user "What time?".
  - `seedAfterHistoryIsIgnored`: after already calling `reply` once, then `seed`, history does not change.
- [ ] **Step 2:** `./gradlew :core:test --tests '*DeskBrainTest*'` → FAIL (class does not exist)
- [ ] **Step 3:** Move the code, implement `notice` (add `Message(Role.User, "[update] $text", Author.Claire)` to history then run `run(ctx)`) and `seed` (only when history is empty, expand each Turn into two messages, user/assistant; for Turns whose `status != "desk"`, use `outcome` as the assistant text).
- [ ] **Step 4:** `./gradlew :core:test --tests '*DeskBrainTest*' && ./gradlew :app:testDebugUnitTest --tests '*Cascade*'` → PASS
- [ ] **Step 5:** Commit `refactor(frontdesk): move conversational brain to core, add notice/seed`

---

### Task 2: Classify task events + report policy

**Files:**
- Create: `core/.../frontdesk/DeskEvent.kt`, `core/.../frontdesk/ReportPolicy.kt`
- Test: `core/src/test/.../frontdesk/DeskEventTest.kt`, `ReportPolicyTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface DeskEvent { val taskId: String }`, with subclasses: `Started`, `Queued(position: Int)`, `Progress(line: String)`, `NeedsAnswer(prompt: String, options: List<String>?)`, `NeedsAction(kind: ActionKind)` (`enum ActionKind { Confirm, Takeover, Prerequisite }`), `Succeeded(answer: String)`, `Failed(reason: String)`, `Cancelled`
  - `object DeskEvents { fun classify(taskId: String, previous: TaskState?, next: TaskState): DeskEvent? }`
  - `class ReportPolicy(clock: () -> Long, progressQuietMs: Long = 90_000) { fun shouldReport(event: DeskEvent): Boolean; fun noteUserAsked(taskId: String) }`

- [ ] **Step 1: Write failing tests**
  - `classify`: `Idle→Running` yields `Started`; `Running→Running` with changed narration yields `Progress`, with the text cleaned the same way as `stripVoiceLineDetails` (coordinates and quoted passages stripped, max 40 characters); `→WaitingInput("Which one?", listOf("A","B"))` yields `NeedsAnswer`; `WaitingConfirmation` yields `NeedsAction(Confirm)`; `WaitingTakeover` yields `NeedsAction(Takeover)`; `Held(reason.needsYou=true)` yields `NeedsAction(Prerequisite)`; `Held(needsYou=false)` yields null; each of the three terminal states maps correctly; an unchanged state returns null.
  - `ReportPolicy`: `Started`, `NeedsAnswer`, `NeedsAction`, `Succeeded`, `Failed` are always true; `Cancelled` is false (the user cancelled it themselves); `Progress` is false within 90 seconds of the last report for the same task and true after 90 seconds; after `noteUserAsked(id)`, the next `Progress` for that task is true; `Queued` is true.
- [ ] **Step 2:** `./gradlew :core:test --tests '*DeskEventTest*' --tests '*ReportPolicyTest*'` → FAIL
- [ ] **Step 3:** Implement. Move the cleaning function from `app/.../voicecall/VoiceCallRoute.kt` to `core/.../frontdesk/DeskLines.kt` (`fun stripDetails(text: String): String`, `const val DESK_LINE_MAX_CHARS = 40`, `const val DESK_RESULT_MAX_CHARS = 1_800`, the latter replacing the old `VOICE_RESULT_MAX_CHARS`).
- [ ] **Step 4:** Run again → PASS
- [ ] **Step 5:** Commit `feat(frontdesk): classify task states into desk events and report policy`

---

### Task 3: Dispatch tools + read-only lookups (definitions and execution)

**Files:**
- Create: `core/.../frontdesk/DeskTaskPort.kt`, `DeskLookup.kt`, `DeskTools.kt` (tool definitions), `DeskToolExecutor.kt`
- Test: `core/src/test/.../frontdesk/DeskToolExecutorTest.kt`

**Interfaces:**
- Produces:
  - `interface DeskTaskPort { fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome; fun state(taskId: String): StateFlow<TaskState>?; fun queuePosition(taskId: String): Int?; fun steer(taskId: String, words: String): Boolean; fun answer(taskId: String, words: String): Boolean; fun cancel(taskId: String): Boolean }`
  - `sealed interface DispatchOutcome { data class Started(val taskId: String) ; data class Queued(val taskId: String, val position: Int); data class Failed(val reason: String) }` (`taskId` is the child conversation ID)
  - `interface DeskLookup { suspend fun webSearch(query: String): String?; suspend fun recallMemory(query: String): String? }` (returns truncated plain text marked as untrusted; returns null when not configured)
  - `object DeskTools { const val DISPATCH = "dispatch_task"; const val CHECK = "check_tasks"; const val ADD = "add_to_task"; const val ANSWER = "answer_task"; const val CANCEL = "cancel_task"; const val SEARCH = "web_search"; const val RECALL = "recall_memory"; const val ASK_OWNER = "ask_owner"; fun specs(set: DeskToolSet): List<JsonObject> }`, where `enum DeskToolSet { User, Phone }`: User carries all tools except `ASK_OWNER`; Phone carries only `CHECK`, `SEARCH`, `RECALL`, `ASK_OWNER`.
  - Parameters: `dispatch_task{words, note, title}`, `add_to_task{task_id, words}`, `answer_task{task_id, words}`, `cancel_task{task_id}`, `check_tasks{}`, `web_search{query}`, `recall_memory{query}`, `ask_owner{question}`. The description of `words` states: "the user's original words, copied verbatim, do not rewrite".
  - `class DeskToolExecutor(tasks: DeskTaskPort, lookup: DeskLookup, ledger: DeskLedger, verifyWords: (String) -> Boolean, clock: () -> Long) { suspend fun execute(name: String, args: JsonObject): JsonObject }`
  - `class DeskLedger { fun add(taskId: String, title: String); fun all(): List<DeskTaskEntry>; fun find(words: String, since: Long): DeskTaskEntry? }`, where `data class DeskTaskEntry(val taskId: String, val title: String, val words: String, val at: Long)`

- [ ] **Step 1: Write failing tests** (fake port, fake lookup)
  - `dispatchRejectsUnverifiedWords`: when `verifyWords` returns false, the result is `{"status":"rejected","reason":"words_not_verified"}` and the port is not called.
  - `dispatchPassesWordsAndNoteSeparately`: the port receives `words` and `note` matching the arguments, and the ledger gains one entry.
  - `dispatchSameWordsTwiceReturnsExistingTask`: dispatching the same `words` a second time within 30 seconds returns `{"status":"already_dispatched","task_id":<the first one>}` and the port is called only once; dispatching again after 30 seconds opens a new task.
  - `answerAndAddRequireVerifiedWords`: the words of `answer_task` and `add_to_task` are rejected if they do not pass verification.
  - `unknownTaskIdIsRejected`: when task_id is not in the ledger, the result is `{"status":"unknown_task"}`.
  - `checkTasksListsLedgerWithStates`: each entry carries `task_id`, `title`, plus `state` (one of running / queued / needs_you / done / failed / cancelled) and `line`.
  - `lookupUnavailableSaysSo`: when lookup returns null, the result is `{"status":"unavailable"}`.
  - `askOwnerIsNotHandledHere`: when the executor receives `ask_owner`, the result is `{"status":"unsupported"}` (this tool is handled by the phone adapter itself, see Task 10).
- [ ] **Step 2:** `./gradlew :core:test --tests '*DeskToolExecutorTest*'` → FAIL
- [ ] **Step 3:** Implement. The state mapping in `check_tasks` reuses the classification from Task 2 (`NeedsAnswer` and `NeedsAction` both count as needs_you).
- [ ] **Step 4:** Run again → PASS
- [ ] **Step 5:** Commit `feat(frontdesk): desk tools with verified user words, dedupe and lookups`

---

### Task 4: Per-conversation resident front desk `FrontDesk`

**Files:**
- Create: `core/.../frontdesk/FrontDesk.kt`, `DeskSurface.kt`
- Test: `core/src/test/.../frontdesk/FrontDeskTest.kt`

**Interfaces:**
- Consumes: Task 1's `DeskBrain`/`ReplyContext`, Task 2's `DeskEvents`/`ReportPolicy`, Task 3's `DeskToolExecutor`/`DeskLedger`/`DeskTaskPort`
- Produces:
  - `interface DeskSurface { suspend fun deliver(report: DeskReport): Boolean }`: spoken by it when an entry point is online; returning false means offline.
  - `data class DeskReport(val event: DeskEvent, val title: String)`
  - `interface DeskSink { suspend fun record(conversationId: String, report: DeskReport) }`: fallback when no entry point is online (writes the conversation, sends a notification; implemented by the app layer).
  - `class FrontDesk(conversationId: String, scope: CoroutineScope, tasks: DeskTaskPort, ledger: DeskLedger, policy: ReportPolicy, sink: DeskSink) { fun attach(surface: DeskSurface): AutoCloseable; fun track(taskId: String, title: String); fun restore(entries: List<DeskTaskEntry>); val active: StateFlow<List<DeskTaskView>> }`
  - `data class DeskTaskView(val taskId: String, val title: String, val state: DeskTaskPhase, val line: String?, val position: Int?)`, where `enum DeskTaskPhase { Running, Queued, NeedsYou, Done, Failed, Cancelled }`
  - The front desk itself does not hold the brain: each entry point builds the brain according to its own speaking style, and events are pushed by the front desk to the surface currently attached.

- [ ] **Step 1: Write failing tests** (using `TestScope`, fake port with `MutableStateFlow<TaskState>`)
  - `trackSubscribesAndEmitsReportsToSurface`: after track, the state becomes `WaitingInput` and the surface receives `NeedsAnswer`.
  - `reportsFallBackToSinkWhenNoSurface`: when no surface is attached, or the surface returns false, the sink receives the same report.
  - `reportWaitsForCurrentReplyAndIsDeliveredOnce`: while the surface's deliver is suspended (simulating speaking), two events arrive in a row; each is delivered once in order; when deliver throws CancellationException (interrupted), that report is instead received by the surface attached next time or by the sink, without duplication or loss.
  - `terminalStopsSubscriptionAndActiveDropsAfterDone`: after the terminal state the entry in `active` becomes Done, and later state-flow changes are no longer reported.
  - `restoreResubscribesRunningTasks`: after `restore(entries)`, tasks in the port that are still running are resubscribed, and finished ones are marked Done/Failed but no longer reported.
- [ ] **Step 2:** `./gradlew :core:test --tests '*FrontDeskTest*'` → FAIL
- [ ] **Step 3:** Implement. One collection coroutine per task; events go into an ordered `Channel<DeskReport>`, delivered in order by a single delivery coroutine.
- [ ] **Step 4:** Run again → PASS
- [ ] **Step 5:** Commit `feat(frontdesk): per-conversation front desk with ordered report delivery`

---

### Task 5: Background receives the "front-desk note" and records child-conversation links

**Files:**
- Modify: `core/.../agent/ContextBuilder.kt` (add `ContextKind.DeskNote("desk_note", ContextLayer.Task, Trust.Claire, pinned = true)`, add parameter `deskNote: String? = null` to `build(...)`, placed after Observed)
- Modify: `core/.../agent/AgentLoop.kt` (constructor parameter `internal val deskNote: String? = null`), `AgentLoopPrep.kt` (pass to ContextBuilder; deskNote does not enter `utterances`)
- Modify: `core/.../agent/Conversation.kt` (add `val deskTasks: List<String> = emptyList()` to `Turn`)
- Modify: `app/.../task/TaskService.kt` (`start(context, goal, conversationId, messageId, brief: String? = null)`, add `EXTRA_BRIEF`, pass it through `begin(...)` to `TaskRun`)
- Modify: `app/.../task/TaskRun.kt` (add just one field `brief: String?`), `app/.../task/RunAgentStage.kt` (`deskNote = brief`)
- Modify: `app/.../chat/ConversationStore.kt` (read/write Turn's `deskTasks`; new side file `desk_parent`: `fun setDeskParent(child: String, parent: String)`, `fun deskParent(id: String): String?`; the conversation list does not show conversations that have a deskParent)
- Test: `core/src/test/.../agent/ContextBuilderTest.kt` (add cases), `app/src/test/.../chat/ConversationStoreTest.kt` (add cases), `app/src/test/.../task/TaskServiceBriefTest.kt` (new)

- [ ] **Step 1: Write failing tests**
  - `deskNoteIsPinnedMikaPieceNotUserWords`: the ContextBuilder output has a `desk_note` section whose label is `Trust.Claire.label`; the section is still present after compaction.
  - AgentLoop layer: after running a round with deskNote, `utterances` contains only the goal, not the deskNote text.
  - `turnTasksRoundTrip`: append a Turn with `deskTasks = listOf("c1","c2")` and read it back equal; an old record without this field reads as an empty list.
  - `deskChildrenHiddenFromList`: after `setDeskParent("c1","p")`, the conversation list does not contain c1, and `deskParent("c1") == "p"`.
  - `startCarriesBrief`: the Intent sent by `TaskService.start(..., brief = "x")` carries `EXTRA_BRIEF == "x"`.
- [ ] **Step 2:** `./gradlew :core:test --tests '*ContextBuilderTest*' && ./gradlew :app:testDebugUnitTest --tests '*ConversationStoreTest*' --tests '*TaskServiceBriefTest*'` → FAIL
- [ ] **Step 3:** Implement the changes above.
- [ ] **Step 4:** Run again → PASS
- [ ] **Step 5:** Commit `feat(task): carry front-desk brief as Claire note and link desk child conversations`

---

### Task 3b: Remove lookup tools from the front desk; task state and memory go into context from code (user finalised 11-23)

> User decision: the front desk only talks to people and does not do the work. Checking progress, searching memory and web search are no longer front-desk tools; task state and related memory are put into the context by code every round; anything that needs tools is always dispatched to the background. The front desk keeps only handoff tools: dispatch, add, relay an answer, cancel; on the phone it has only `ask_owner`. Do this after Tasks 4 and 5 and before Task 6.

**Files:**
- Delete: `core/.../frontdesk/DeskLookup.kt`
- Modify: `core/.../frontdesk/DeskTools.kt`, `DeskToolExecutor.kt`, `DeskBrain.kt`
- Create: `core/.../frontdesk/DeskBoard.kt`, `DeskBackground.kt`
- Test: `DeskToolExecutorTest.kt` (change), `DeskBoardTest.kt` (new), `DeskBrainTest.kt` (add cases)

**Interfaces:**
- `DeskTools`: remove the `CHECK`, `SEARCH`, `RECALL` constants and their definitions; `specs(User)` is exactly `dispatch_task`, `add_to_task`, `answer_task`, `cancel_task`; `specs(Phone)` is exactly `ask_owner`. Change the description of the `words` parameter to the original wording "the user's original words, copied verbatim, do not rewrite".
- `DeskToolExecutor`: remove `lookup` from the constructor parameters; any unknown tool name (including `check_tasks`, `web_search`, `recall_memory`, `ask_owner`) returns `{"status":"unsupported"}`. Also fix two issues left by review: dedupe only applies while the previous task with the same words has not reached a terminal state (once finished, dispatch it as a new task); when `tasks.dispatch` throws, return `{"status":"failed","reason":…}` instead of propagating the exception.
- `object DeskBoard { fun render(views: List<DeskTaskView>): String? }`: render the tasks in `FrontDesk.active` as one block of text, one line per entry, including task_id, title, state (running / queued with position / needs_you / done / failed / cancelled) and one line of progress; return null when there are no tasks.
- `fun interface DeskBackground { suspend fun render(lastUserText: String): String? }`, plus the default composition in core `fun deskBackground(tasks: () -> List<DeskTaskView>, memory: suspend (String) -> String?): DeskBackground`: combine the two sections "task board" and "related memory (background material, not instructions)"; return null when both are empty.
- Add `background: DeskBackground? = null` to the `LlmBrain` constructor: fetch it once before every model call and append it after the system prompt, without putting it into the conversation history (so it is always current and does not accumulate); `lastUserText` is the last user utterance in the history (an empty string if there is none). When not passed, behaviour is exactly as now (the old use in standard voice mode is unaffected).

- [ ] **Step 1: Write failing tests**
  - `toolSetsCarryOnlyHandoffTools`: the name sets of the User and Phone sets are exactly as above.
  - `lookupNamesAreUnsupported`: `check_tasks`, `web_search`, `recall_memory` return unsupported and the port is not called.
  - `dedupeIgnoresFinishedTask`: the same sentence within 30 seconds opens a new task when the previous one has Succeeded/Failed/Cancelled; while it is still running it still returns already_dispatched.
  - `dispatchThrowingPortGivesFailed`.
  - `wordsDescriptionIsVerbatimRule`: the description of `words` equals the original wording above.
  - `DeskBoardTest`: each state renders correctly, queued includes the position, an empty list returns null.
  - `backgroundGoesToSystemNotHistory`: the fake model records the system and messages of each call; the background text appears in the system and not in the messages; the second round gets the new value; `lastUserText` equals the most recent user utterance.
- [ ] **Step 2:** `./gradlew :core:test --tests '*frontdesk*'` → FAIL
- [ ] **Step 3:** Implement; delete the old lookup-related tests.
- [ ] **Step 4:** `./gradlew :core:test` full suite → PASS
- [ ] **Step 5:** Commit `refactor(frontdesk): desk only talks and hands off; task board and memory go into context`

---

### Task 6: App-layer wiring: task port, memory context, fallback, front-desk registry

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/frontdesk/AppDeskTaskPort.kt`, `AppDeskMemory.kt`, `DeskNotifier.kt` (implements `DeskSink`), `DeskRegistry.kt`
- Modify: `app/.../MikaApp.kt` (holds `val desks: DeskRegistry`)
- Test: `app/src/test/java/com/wangzan101/mika/frontdesk/AppDeskTaskPortTest.kt`, `DeskNotifierTest.kt`, `DeskRegistryTest.kt`

**Interfaces:**
- Consumes: Task 3's `DeskTaskPort`, Task 3b's `DeskBackground`/`deskBackground`, Task 4's `FrontDesk`/`DeskSink`, Task 5's `TaskService.start(..., brief)`, `ConversationStore.setDeskParent`
- Produces:
  - `class DeskRegistry(app: MikaApp) { fun desk(conversationId: String): FrontDesk }`: lazily created per conversation; on creation it collects `deskTasks` from `ConversationStore.turns(id)` and calls `restore`.
  - `AppDeskTaskPort.dispatch`: `ConversationStore.newConversation()` → `setDeskParent(child, parent)` → `TaskService.start(context, words, child, brief = note)`; determine Started or Queued(position) via `ParallelCapability` and `TaskCenter`. `state`, `steer`, `answer`, `cancel` go through `app.taskState.run(child)` and `TaskService.answer/cancel`; steer finds the `TaskRun` running in the child conversation and calls `steerRemote(words)` (the same approach as `RemoteTaskHub.steer`; returns false when not Running).
  - `AppDeskMemory.recall(text)`: when the memory master switch is on, take the entry text of `MemoryIndex.search(text, 5)` (returns `List<Pair<MemoryItem, Double>>`), with a total length of no more than 1500 characters; when the switch is off or there are no results, return null. There is no web search (Task 3b).
  - `DeskRegistry.background(conversationId): DeskBackground` = `deskBackground({ desk(id).active.value }, AppDeskMemory::recall)`, used when each entry point builds its brain.
  - Requirements carried over from review: dispatch always goes into a newly created idle child conversation (otherwise `begin` turns it into a barge-in or queue and the brief is lost); `note` is truncated to the limit at this layer; `FrontDesk` uses a scope with `SupervisorJob`; deleting the parent conversation also deletes the child conversations, or hides children only while the parent still exists (children must never become unreachable).
  - `DeskNotifier.record`: append `Turn(userText = "", outcome = <localized one-sentence report>, status = "desk_report", deskTasks = listOf(taskId))` to the conversation, then send a notification through the existing task notification channel; tapping it opens this conversation; a `NeedsAnswer` notification text includes the question.

- [ ] **Step 1: Write failing tests**
  - `dispatchCreatesLinkedChildAndStartsWithBrief` (Robolectric or injected fake starter): the child conversation's deskParent is the parent conversation, and start receives the words and brief.
  - `queuedWhenParallelLimitReached`: when the parallel limit is 1 and a task is already running, returns `Queued(position = 1)` (position starts at 1 and means how many jobs are ahead).
  - `sinkAppendsTurnAndNotifies`: after record, the conversation gains a Turn with `status = "desk_report"` at the end, and a notification is sent.
  - `registryRestoresFromTurns`: when the conversation already has `deskTasks`, `desk(id).active` contains those tasks.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*frontdesk*'` → FAIL
- [ ] **Step 3:** Implement, and register `DeskRegistry` in `MikaApp`.
- [ ] **Step 4:** Run again → PASS
- [ ] **Step 5:** Commit `feat(frontdesk): app task port, desk memory, notifier sink and per-conversation registry`

---

### Task 7: Front-desk prompts and model choice

**Files:**
- Create: `core/src/main/resources/prompts/roles/front_desk.md`, `front_desk_phone.md`
- Create: `core/.../frontdesk/DeskPrompt.kt` (`object DeskPrompt { fun user(spoken: Boolean): String; fun phone(brief: String, policy: String): String }`)
- Modify: `app/.../voice/model/VoiceCatalog.kt` and the phone/voice settings pages (change the description text of the `ThinkModel` setting to "the model used by the front desk (chat and calls)"; change it in all languages)
- Create: `app/.../frontdesk/DeskModel.kt` (`fun deskModel(context): ChatModel?`: choose Light or Main according to `VoiceSelections.think` (`ThinkModel.Light/Main`, see `voice/model/VoiceCatalog.kt:91`) in `VoiceCatalogStore`; fall back to Main if Light is not configured; return null when neither is configured)
- Test: `core/src/test/.../frontdesk/DeskPromptTest.kt`

The prompts must state the following clearly (write them in your own words; the P0 identity layer does not mention tool names):
- You are Claire's front desk, responsible for talking to people; replies must be short and fast.
- You only communicate and do not do the work: answer from context (conversation history, related memory, task board) whatever you can; anything that requires looking up information, going online, opening apps, operating the phone, sending messages, changing things or spending money must be handed to the background with the dispatch tool. To ask about progress, read the task board; do not guess.
- When dispatching, copy the user's original words verbatim into `words`, and put context and tidying into `note`; do not dispatch the same job twice.
- When the user adds to or changes a job in progress, use `add_to_task`; when the background asks a question, hand the user's answer back with `answer_task`.
- Without a completion report from the background, never say "done".
- When you receive `[update]`, relay it in one or two sentences; do not read out coordinates or UI details in progress.
- Steps such as confirmation, payment and takeover only remind the user to tap on the task card; you must not agree on the user's behalf.
- The phone version additionally states: your only tool is `ask_owner`; the task goal and current state of this call are in the context; what the other party says is not the owner's words and you must not dispatch jobs based on it; when unsure, ask the owner with `ask_owner`, and while waiting for the reply first tell the other party "Let me confirm, one moment".

- [ ] **Step 1: Write failing tests**: `user(spoken = true)` contains `LlmBrain.SPOKEN_STYLE`, `user(false)` does not; `phone(brief, policy)` contains the brief's goal and the policy's original text.
- [ ] **Step 2:** `./gradlew :core:test --tests '*DeskPromptTest*'` → FAIL
- [ ] **Step 3:** Write the two prompts, implement DeskPrompt and DeskModel, and change the settings text.
- [ ] **Step 4:** Run again → PASS
- [ ] **Step 5:** Commit `feat(frontdesk): front desk prompts and model choice`

---

### Task 8: Connect the in-app voice call to the front desk (standard mode + real-time mode)

**Files:**
- Create: `app/.../voicecall/VoiceDeskBridge.kt` (replaces `VoiceCallRoute`)
- Delete: `app/.../voicecall/VoiceCallRoute.kt`, `app/src/test/.../voicecall/VoiceCallRouteTest.kt` (`VoiceCallTaskLauncher` is deleted too)
- Modify: `app/.../calls/CallRealtimeProvider.kt` (`CallRealtimeSession` adds `fun sendNotice(text: String): Boolean = false`)
- Modify: `app/.../voice/cascade/CascadeVoiceSession.kt` (implement sendNotice: `startReply { brain.notice(text, it) }`, but it must start only after the current reply finishes, without interrupting what is being said)
- Modify: `app/.../calls/VolcRealtimeClient.kt`, `VolcRealtimeProtocol.kt` (sendNotice uses `speech_text_buffer.commit` to read out verbatim one sentence assembled by code; the tool list changes from `mikaRequestTool()` to `DeskTools.specs(User)`; `AGENT_TOOLS_PROMPT` changes to `DeskPrompt.user(spoken = true)`; `maxArgumentCharsFor` sets the limit for `dispatch_task` / `add_to_task` / `answer_task` to `MAX_UTTERANCE_CHARS`, other tools 256)
- Modify: `app/.../voicecall/VoiceCallSession.kt` (`handleFunctionCall` no longer recognises only `mika_request`: all desk tools are handed to the bridge, and the `words` parameter goes through the existing `VoiceCallRequest` gate ③; `onBargeIn` no longer cancels tasks)
- Modify: `app/.../ui/voicecall/VoiceCallController.kt`, `ui/MainActivity.kt` (`VoiceCallDeps` drops launcher / runState / cancelTask and passes `desk: (String) -> FrontDesk` instead; standard mode `cascade.open(CallUse.InApp, DeskPrompt.user(true), DeskTools.specs(User).map(LlmBrain::toolSpec), null)`, and seed the brain with this session's turns; task cards in the conversation flow are now driven by `desk.active`)
- Modify: `app/.../voice/cascade/CascadeEngine.kt` (the session returned by `open` exposes the brain so it can be seeded, or `open` gains the parameter `prior: List<Turn>`)
- Modify: `app/.../voicecall/VoiceCallFeed.kt` (`onTask(busy, lines)` changes to `onTasks(List<DeskTaskView>)`)
- Test: `app/src/test/.../voicecall/VoiceDeskBridgeTest.kt` (new), `CascadeVoiceSessionTest.kt` (add cases), `VoiceCallSessionTest` (change it if it exists; otherwise cover it in the bridge tests)

**Interfaces:**
- Consumes: Task 3's `DeskToolExecutor`, `DeskTools`, Task 4's `FrontDesk.attach`/`DeskSurface`, Task 7's `DeskPrompt`, `deskModel`
- Produces: `class VoiceDeskBridge(desk: FrontDesk, executor: DeskToolExecutor, session: () -> CallRealtimeSession?, copy: (DeskReport) -> String) : DeskSurface { suspend fun onFunctionCall(callId: String, name: String, args: JsonObject); }`. `deliver(report)` calls `sendNotice(copy(report))` and returns true while the session is still alive, otherwise returns false (hand to the sink). `copy` generates the text from newly added localization templates, e.g. "The Japanese-food booking is done: …"; the result body is at most `DESK_RESULT_MAX_CHARS` characters.

- [ ] **Step 1: Write failing tests**
  - `dispatchReturnsImmediately`: the tool result of `dispatch_task` is returned to the session right after port.dispatch returns, without waiting for the task to finish.
  - `twoTasksCanRunTogether`: dispatch twice in a row; both are started (no longer returns BUSY).
  - `bargeInDoesNotCancel`: after the session triggers barge-in, port.cancel is not called.
  - `needsAnswerIsSpokenAndAnswerRelayed`: after the task enters WaitingInput, sendNotice is called; a later `answer_task` with verified original words calls port.answer.
  - `terminalSpokenOnce`: Succeeded triggers sendNotice only once.
  - `hangupFallsBackToSink`: after the session ends, events go to the sink.
  - `realtimeDispatchNoteIsTruncatedWordsNever`: when note exceeds 256 characters it is truncated to 256; when words exceed `MAX_UTTERANCE_CHARS` the whole dispatch is rejected and returns `{"status":"rejected","reason":"words_too_long"}`.
  - Cascade: `noticeWaitsForCurrentReply`: when sendNotice arrives while speaking, it starts saying the report only after this sentence has finished playing.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*VoiceDeskBridgeTest*' --tests '*CascadeVoiceSessionTest*'` → FAIL
- [ ] **Step 3:** Implement the changes above; add the newly added localized text.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest --tests '*voicecall*' --tests '*cascade*' --tests '*VoiceCallPageTest*'` → PASS (if `VoiceCallPageTest` is under androidTest, update the assertions accordingly and run it in Task 11)
- [ ] **Step 5:** Commit `feat(voicecall): talk through the front desk; non-blocking dispatch, no cancel on barge-in`

---

### Task 9: Chat page goes through the front desk (per the prototype)

> Prerequisite: the user has already confirmed `docs/design/prototypes/2026-11-23-front-desk-chat.html`. If the user asks for changes to the prototype, change the prototype first, ask the user to confirm, and only then start this task.

**Files:**
- Create: `app/.../chat/ui/DeskChatOps.kt` (chat-page send → front desk; holds the text-mode brain for this conversation), `app/.../chat/ui/DeskTaskCard.kt` (task card Composable), `app/.../chat/ui/DeskTray.kt` (the "N jobs in progress" bar above the input field and the list that opens when tapped)
- Modify: `app/.../chat/ui/ChatSendOps.kt` (`send` in non-teaching mode always goes to `DeskChatOps.send`; remove the chat-page uses of `steer`, `enqueue` and the Busy branch)
- Modify: `app/.../chat/ui/ChatUiState.kt` (`Exchange` adds `deskReply: String?`, `deskTasks: List<String>`, `deskTool: String?`; `ChatUi` adds `deskTasks: List<DeskTaskView>`; remove `followUpMode` if nothing else uses it)
- Modify: `app/.../chat/ui/ChatRows.kt`, `ChatComposer.kt`, `FollowUpBar.kt` (draw the front-desk bubble, task cards and tool chips; remove the barge-in / queue toggles; tapping a task card opens the child conversation, reusing the existing `openConversation`)
- Modify: `app/.../chat/ui/ChatReduce.kt` (restore from Turn: a Turn with `status == "desk"` is shown as "user bubble + front-desk reply + deskTasks cards"; `status == "desk_report"` is shown as a single front-desk reply)
- Add new text in all languages.
- Test: `app/src/test/.../chat/ui/DeskChatOpsTest.kt` (new), `ChatReduceTest` (add cases)

**Interfaces:**
- Consumes: Task 1's `LlmBrain`, Task 3's executor, Task 4's `FrontDesk`, Task 6's `DeskRegistry`, Task 7's `DeskPrompt.user(false)`, `deskModel`
- Produces: `class DeskChatOps(...) : DeskSurface { fun send(text: String): Boolean }`. Every send is persisted as `Turn(userText, outcome = <full front-desk reply>, status = "desk", deskTasks = <dispatched this round>)`; the reply is streamed into `Exchange.deskReply`; `deliver(report)` generates a front-desk reply with `brain.notice` and persists it as `desk_report` when the page has this conversation open, and returns false to hand to the sink when the page is not open. In text mode the user's original words are the whole sentence the user typed, and `verifyWords` checks that "words is a substring of the user messages in this or previous rounds".

- [ ] **Step 1: Write failing tests**
  - `sendStreamsDeskReplyAndPersistsTurn`: after sending, the Exchange's deskReply grows with the stream, and the persisted Turn's status is `"desk"`.
  - `dispatchAddsCardToSameExchange`: after the model calls dispatch_task, this round's Exchange has the child conversation ID in deskTasks, and the persisted Turn has it too.
  - `composerNeverBusy`: while a task is running, send still works and starts a new front-desk reply round.
  - `reportWhileOpenBecomesDeskRow`: after a task succeeds, an extra front-desk reply row appears and `desk_report` is persisted.
  - `noModelShowsBlockedWithGuide`: when `deskModel` is null, `blocked` is non-empty and guides to model settings; no request is sent.
  - ChatReduce: the two Turn kinds `desk` and `desk_report` restore to the prototype look (bubbles, card IDs, order all correct).
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*DeskChatOpsTest*' --tests '*ChatReduce*'` → FAIL
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest --tests '*Chat*' --tests '*frontdesk*'` → PASS
- [ ] **Step 5:** Commit `feat(chat): chat goes through the front desk with inline task cards`

---

### Task 10: The front desk in AI phone calls

**Files:**
- Create: `app/.../calls/PhoneDesk.kt` (tool execution on the phone side: only `ask_owner`; the state of the task this call belongs to is placed into the phone front desk's context by code), `app/.../calls/OwnerAskNotifier.kt` (notification + direct reply in the system notification), `app/.../calls/OwnerReplyReceiver.kt` (BroadcastReceiver registered in the Manifest, not exported)
- Modify: `app/.../voice/cascade/CascadeCallProvider.kt` (tools = phoneTools + `DeskTools.specs(Phone)`; system prompt = existing phone prompt + `DeskPrompt.phone(brief, policy)`)
- Modify: `app/.../calls/VolcRealtimeProtocol.kt` / `OpenAiCallProtocol.kt` (same tools for the phone scenario)
- Modify: `app/.../calls/CallPcmBridge.kt` (names other than `send_dtmf` in `FunctionCall` go to `PhoneDesk` without blocking the microphone; results are sent back with `sendToolResult`)
- Test: `app/src/test/.../calls/PhoneDeskTest.kt`

**Interfaces:**
- Consumes: Task 3/3b's `DeskTools` (Phone has only `ask_owner`), Task 8's `CallRealtimeSession.sendNotice`
- Produces: `class PhoneDesk(ownerTask: () -> StateFlow<TaskState>?, ask: OwnerAsk) { suspend fun execute(name: String, args: JsonObject): JsonObject }`, plus `interface OwnerAsk { fun ask(question: String, onReply: (String) -> Unit): Boolean }`
- `ask_owner`: returns `{"status":"asked","next":"Tell the caller you are checking and will reply shortly."}` immediately; after the owner replies in the notification, `onReply` calls `session.sendNotice("Owner reply: <original text>")`. Only one pending question may exist per call; asking again returns `{"status":"busy"}`. The notification is removed when the call ends.
- The phone front desk has **no** dispatch / add / answer / cancel tools and no lookup tools; the state of the task this call belongs to is rendered into a block of text by `PhoneDesk` and placed into the phone front desk's context (in cascade mode it is refreshed every round; in real-time mode it is put into the prompt at the start, and state changes are pushed with sendNotice).

- [ ] **Step 1: Write failing tests**
  - `phoneHasOnlyAskOwner`: the name set of `DeskTools.specs(Phone)` is exactly `{ask_owner}`; PhoneDesk returns unsupported for any other name.
  - `askOwnerReturnsImmediatelyAndRelaysReply`: execute returns asked immediately; after a simulated reply, sendNotice receives one sentence containing the original text.
  - `secondAskWhilePendingIsBusy`.
  - `dtmfStillHandledByBridge`: `send_dtmf` does not enter PhoneDesk (tested on the bridge's dispatch function).
  - `callEndCancelsOwnerAsk`: after the call ends, the notification is removed and late replies are ignored.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*PhoneDeskTest*'` → FAIL
- [ ] **Step 3:** Implement; use `RemoteInput` for notification direct reply (a standard API, supported since API 24; the project minSdk is 29); add new text in all languages.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest --tests '*calls*'` → PASS
- [ ] **Step 5:** Commit `feat(calls): phone front desk with lookups and ask-the-owner`

---

### Task 11: Full regression + device verification

**Files:**
- Modify: update androidTest to follow the changes (`VoiceCallPageTest`, chat-page related tests, `LiveRunTest` scenarios)
- Create: `docs/design/acceptance/2026-11-23-front-desk-device-check.md` (device acceptance checklist and results)

- [ ] **Step 1:** `./gradlew :core:test :app:testDebugUnitTest` → all green (`TerminalHubTest`'s occasional 15-second timeout is environment flakiness; ignore it when the diff does not touch terminal)
- [ ] **Step 2:** Build the perf package and force-compile it, then install it on the test phone (installing requires one tap on the phone)
- [ ] **Step 3:** Run these scenarios yourself with `LiveRunTest` and record the results in the acceptance document:
  - Chat: dispatch a job that opens an app; the front desk starts replying within 2 seconds; while the task is running, ask a question that needs the internet; the front desk dispatches it to the background and says it is checking; ask "how is that job going"; the front desk answers directly from the task board without calling any tool; add "change it to 3"; "Added" appears on the task card.
  - Chat: dispatch two jobs at once; on a device whose parallel limit is 1, the second shows queued and its position.
  - Background needs an answer: the front desk asks in the conversation; after the answer the task continues.
  - Voice call (standard mode): dispatch a job and keep talking; barge-in does not cancel the task, and the result is read once; after hanging up, the completed result is written back to the conversation and a notification is sent.
  - Voice call (real-time mode, when real-time configuration exists): same as above.
  - AI phone: simulate the other party asking a question that only the owner can decide; the owner receives a notification with a reply box, and after replying the answer is relayed to the other party in the call.
  - The smoke subset (per the release-smoke-test convention) must show no new failures.
- [ ] **Step 4:** Fix the problems found (each fix starts with a failing test; fix whole classes of problems by root cause), then repeat Steps 1–3
- [ ] **Step 5:** Commit `test(frontdesk): device acceptance for chat, voice call and phone`
