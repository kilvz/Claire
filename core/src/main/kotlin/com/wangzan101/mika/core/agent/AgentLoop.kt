package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.run.trace.RunTrace
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.safety.guard.GuardVerdict
import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.screen.observation.tag
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.PromptVersion
import com.wangzan101.mika.core.tool.parseArgs
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.OutwardPacing
import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.action.FollowUp
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.Presented
import com.wangzan101.mika.core.action.Refusals
import com.wangzan101.mika.core.device.access.AppAccessDenied
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.safety.gate.ConfirmationGate
import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.learn.knowledge.ChannelFacts
import com.wangzan101.mika.core.learn.knowledge.KnowledgeStore
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.memory.ProfileEntry
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.proactive.ObservedContext
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.RunEventBus
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.Interaction
import com.wangzan101.mika.core.run.LocalInteraction
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.model.usage.RunUsageTally
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.HandBackOutcome
import com.wangzan101.mika.core.tool.Outcome
import com.wangzan101.mika.core.tool.AskPort
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.learn.replay.RouteMatcher
import com.wangzan101.mika.core.screen.map.ScreenMapStore
import com.wangzan101.mika.core.safety.verify.Verifier
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.ZoneId
import com.wangzan101.mika.core.screen.user.UserScreenSource

/** The extra look taken after a batch finishes: the content and the screen at the same moment. */
internal fun batchViewNote(content: String) = Feedback.format("batch_view_note", "screen" to content)

/** After the progress block is extracted, the remaining text stays as one assistant message; the calls and their private data are kept as-is. */
internal fun cleanReplyParts(parts: List<Part>, text: String): List<Part> {
    var textWritten = false
    return parts.mapNotNull { part ->
        if (part !is Part.Text) part else if (textWritten) null else {
            textWritten = true
            Part.Text(text).takeIf { text.isNotEmpty() }
        }
    }
}

/**
 * Writing or changing Claire's own memory does not first ask "is this a discussion or an action". That judgement governs whether to
 * read or touch apps and websites; these two tools only touch Claire's own memory, and each has its own validation (quotations must
 * come from the user's words in this turn, and edits/deletions must be explicitly requested in those words, see MemoryTools); changes
 * can be undone on the result card.
 * If they were left to that judgement, sentences like "summarize this into memory" or "make those entries more concise" were all judged
 * as discussion on real devices: the calls were dropped, the model was asked again without tools, and it could only write the calls as
 * text, ending the task with "tool call not recognised".
 * Pure questions like "if I asked you to delete that entry, what would you do" are rejected by memory_edit itself (see onlyAsking in MemoryEdit).
 */
private val MEMORY_WRITE_VERBS = setOf(Verb.MemorySave, Verb.MemoryEdit)
private val MEMORY_VERBS = setOf(Verb.MemorySearch, Verb.MemorySave, Verb.MemoryEdit)
/** Local memory, skill material and earlier turns' records can still be consulted when discussing history; these calls never open an app or a website. */
private val DISCUSSION_LOOKUP_VERBS = setOf(Verb.MemorySearch, Verb.KnowledgeSearch, Verb.KnowledgeRead, Verb.SkillSearch, Verb.SkillRead, Verb.HistoryRead, Verb.NoticesRead)

/**
 * Drives the "model ↔ tools" loop and only does scheduling: ask the model ([ModelCaller]) → hand to the execution entry
 * point ([ActionExecutor]) → decide reminders, advice and the ending by policy ([TurnPolicy], [StopRules], [StuckAdvice],
 * [NoticePolicy]) → write into the history.
 * Endings: a reply without tool calls is the final answer to the user (see [AnswerReview] for verification; sources are
 * found in code from the evidence ledger);
 * text written alongside calls is progress narration ([TaskState.Running.narration]). Cannot-do, need-to-ask and
 * user-must-do-it go through end_not_done, ask_user and hand_to_user respectively. Only an empty reply (or one truncated by
 * the output limit) triggers a reminder, and only once.
 * Each round executes only the first tool call, and writes pass through [gate] first (suspended when a capability's
 * precondition is not met, e.g. the phone is locked); guard verdicts go into the guard field of the tool result.
 * Before gate suspends, setWaiting reports the reason (the capability-provided [TaskState.Held]), and null is passed once
 * allowed through; the state is shown externally while waiting and Running is restored afterwards.
 * The run is handed to [sink] as [RunEvent]s (already redacted, large fields clipped), and saved screens are handed over via
 * [RunSink.media].
 */
class AgentLoop(
    internal val model: ChatModel,
    internal val tools: ToolDispatcher,
    internal val basePrompt: String,
    internal val maxSteps: Int = DEFAULT_MAX_STEPS,
    internal val gate: suspend (setWaiting: (TaskState?) -> Unit) -> Unit = {},
    internal val knowledge: KnowledgeStore? = null,
    /** Each app's screen map (page structure); not injected when null. Given along with APP.md when entering an app that has one. */
    internal val screenMaps: ScreenMapStore? = null,
    sink: RunSink = RunSink.NONE,
    /** Installed apps (package name, display name), used only to pick reference material by the task text; treated as an empty list when unavailable. */
    internal val installedApps: suspend () -> List<Pair<String, String>> = { emptyList() },
    /** The independent, risk-triggered verification asked for before the final answer is handed over (see [AnswerReview]); when null, only the literal check is done (the product path must provide one). */
    internal val verifier: Verifier? = null,
    /** The confirmation gate for payments; autonomous paths pass null and do not offer request_confirmation to the model either. */
    internal val confirmations: ConfirmationGate? = ConfirmationGate(),
    /** Ask / autonomous (when switched to autonomous mid-task, it applies from the next step on); asks by default. */
    internal val approvals: ApprovalSettings = ApprovalSettings.ASK,
    /** How long to wait for the user to answer a confirmation, treated as not approved on timeout; when null, wait forever (tests). */
    confirmTimeoutMs: Long? = null,
    /** Claire's own package name: write operations acting on it are not performed. */
    internal val selfPackage: String? = null,
    /** How long to wait for the user to answer a follow-up question (ask_user and pre-start ambiguity questions); timing uses the coroutine dispatcher's clock, with virtual time in tests. */
    inputTimeoutMs: Long = DEFAULT_INPUT_TIMEOUT_MS,
    /** The facts used to choose a channel; when given, an "available channels" line is injected at the start of the task. */
    internal val channelFacts: (suspend () -> ChannelFacts)? = null,
    /** Speed-up switches; all off by default. */
    val flags: SpeedFlags = SpeedFlags.OFF,
    /** The clock used for event times and durations; replaced with a fake clock in tests. */
    internal val clock: () -> Long = System::currentTimeMillis,
    /** Division of labor between the big and small models; used only when flags.lightSteps is on. */
    router: ModelRouter? = null,
    /** An independent intent judgement when the UI conversation is about to use tools for the first time; null keeps the original behavior of evaluations and other entry points. */
    internal val intentJudge: ChatModel? = null,
    /** An optional typed intent judgement; when it is unsure or fails, intentJudge is still asked, and it does not replace the main model or the execution gate. */
    intentDecision: com.wangzan101.mika.core.model.decision.DecisionModel? = null,
    /** Route matching for experience replay; used only when flags.replay is on. */
    routes: RouteMatcher? = null,
    /** The skill catalog (including personal skills); at task start all skills are listed, plus an index of a few pieces of app/site knowledge chosen for the goal. No index when null or when [recommendSkills] is off. */
    internal val skills: GuideCatalog? = null,
    internal val recommendSkills: Boolean = true,
    /** Memory; when null (or the master switch is off), nothing is retrieved, nothing is injected, and no memory_* tool is offered. */
    internal val memory: MemoryStore? = null,
    /** The local time in the runtime state is written in this time zone. */
    internal val zone: ZoneId = ZoneId.systemDefault(),
    /** Retries when a model call hits a transient failure (rate limiting, 5xx, network). */
    retry: RetryPolicy = RetryPolicy(),
    /** Asked once before a reply without calls is accepted as the final answer (see [BeforeAccept]); accepted as usual by default. Not asked on the last round after the step limit is used up. */
    internal val beforeAccept: suspend (answer: String) -> BeforeAccept = { BeforeAccept.Accept },
    internal val interaction: Interaction = LocalInteraction,
    internal val profileSnapshot: List<ProfileEntry> = emptyList(),
    internal val autonomous: Boolean = false,
    /** The block the proactive capability accumulated from notifications that qualifies for carrying into this task (see [com.wangzan101.mika.core.proactive.ObservedContext]); null when there is none. */
    internal val observed: String? = null,
    /** Extra context the front desk attaches when dispatching (background and tidying); it enters the context as a note from Claire, not as the user's words ([utterances]). Null when absent. */
    internal val deskNote: String? = null,
    /** The interactive UI turns on the visible text stream; evaluation and background calls keep the original request format. */
    internal val streamReplies: Boolean = false,
    /** Screens the user sent in this conversation (see [UserScreenTurns]); when absent, the line carrying the screen is given to the model as-is. */
    internal val userScreens: UserScreenSource? = null,
    /** Evaluations may keep the old final verification; for the interactive product the outcome is observed during execution and the finish only assembles sources. */
    internal val reviewFinalAnswer: Boolean = true,
    /** The interval between two consecutive outward actions (sending, submitting) within the same website or app; no wait by default. */
    internal val outwardPacing: OutwardPacing = OutwardPacing.Off,
    /** The fallback chain for the main model; [modelChainProviderIds] is parallel to the main model and the fallback models. */
    internal val modelFallbacks: List<ChatModel> = emptyList(),
    internal val modelChainProviderIds: List<String?> = emptyList(),
    internal val trace: RunTrace? = null,
    /** Plugin prompt hook: the assembled system prompt passes through it before being sent to the model; sent as-is when null. */
    internal val promptTransform: (suspend (String) -> String)? = null,
    /** The persona used in this round (name · revision); written to the run record only, not part of prompt assembly. Null when there is no persona. */
    internal val personaLabel: String? = null,
    internal val stages: TaskStages? = null,
) {
    init {
        require(maxSteps > 0) { "maxSteps must be positive" }
        // Targets and anchors are captured before write operations only when replay is on
        tools.captureTargets = flags.replay
        tools.observeMcpCalls(::showMcpCall)
    }

    /** Run record: written in causal order (see [RecordOrder]). */
    val events = RunEventBus().also { bus ->
        bus.subscribe(sink)
        trace?.let { bus.subscribe(it) }
    }
    internal val sink = RecordOrder(events).also { order ->
        // Every observation recorded in the evidence ledger goes into the run record as-is (right after the result of the call that read it)
        tools.observer = { record { order.observed(RunEvent.Observed.of(it)) } }
    }

    internal val caller = ModelCaller(model, tools, router, flags, retry, ::emit, ::now, autonomous, trace, modelFallbacks, modelChainProviderIds)
    internal val intentGate = intentJudge?.let {
        IntentGate(it, model.takeUnless { main -> main === it }, intentDecision) { detail ->
            emit(RunEvent.State(now(), "jev_intent", detail))
        }
    }
    internal val routeMatcher = routes
    /** This task's context builder (envelope, budget, reference material already given). */
    internal var ctx = ContextBuilder()
    /** Screens the user sent within this task (created at the opening). */
    internal var screens: UserScreenTurns? = null
    /** How this task's own words look when given to the model (rewritten into a block when a screen is carried, see [UserScreenTurns]); null before the run starts. Judging the outcome and extracting memory read from it. */
    @Volatile var taskText: String? = null
        internal set
    /**
     * The characters the user typed in their most recent sentence (the opening words or a steer), see [ownWords]; when a steer typed nothing (only a screen sent, only text selected), the previous sentence's are kept.
     * Language hints and the answer language check use it; memory and skill quotations collect [UserWords.said] (which also recognises words of the user's own that they quoted).
     */
    internal var latestWords = ""

    internal val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    val state: StateFlow<TaskState> = _state.asStateFlow()

    @Volatile internal var usedMemory: List<String> = emptyList()
    /** The IDs of the entries listed in this task's memory block; read after the task ends. */
    val usedMemoryIds: List<String> get() = usedMemory

    /** Whether this task started with the "obtained from notifications" block; read after the task ends (if it did, memory is not extracted automatically — known without reading the run record). */
    val carriedObserved: Boolean get() = observed != null

    /** The capability groups actually used in this task (recorded into this turn and pre-expanded at the start of the next turn); read after the task ends. */
    val usedGroups: Set<String> get() = tools.used

    /** What the user said in this task (used for quotation validation by memory_save and skill_save); collected when available, in memory only, cleared at the start of each task. */
    internal val utterances get() = tools.utterances

    internal val user = UserExchange(tools, confirmations, confirmTimeoutMs, inputTimeoutMs, { utterances }, { _state.value = it }, ::emit, ::now)
    /** Per-call consent for its own tools: reuses the path that waits for the user's answer, waiting inside the same tool call rather than splitting it into two steps. */
    internal val askPort = object : AskPort {
        override suspend fun awaitAnswer(prompt: String, options: List<String>?) = user.awaitAnswer(prompt, options)
    }
    init { tools.ctx.ask = askPort }
    internal val replay = routes?.let { RouteReplay(it, tools, ::awaitGate, { r -> gateState = r; _state.value = r }, ::emit, clock) }

    @Volatile internal var job: Job? = null
    /** The interface state to restore after waiting out a gate such as the lock screen or being taken away (the current step, batch or replay). */
    @Volatile internal var gateState: TaskState = TaskState.Idle
    @Volatile internal var cancelRequested = false
    /** The step whose request was withdrawn because of a steer; streaming text that arrives late afterwards is no longer shown. */
    @Volatile internal var interruptedStep = 0
    /** What the user said in earlier turns of this conversation, given only to the verifier so it understands follow-ups; contains nothing from the working model. */
    internal var earlierGoals: List<String> = emptyList()
    /** The outcome of this run; route counting needs it after the outcome is judged ([noteOutcome]). */
    @Volatile internal var lastEnd: TaskState? = null
    internal val review = AnswerReview(tools, verifier, ::emit, ::now)
    /** The image IDs saved at each step of this task (step → IDs); the answer's sources attach images according to it. */
    internal val media = mutableMapOf<Int, MutableList<Int>>()
    /** This task's answer has already been sent back once because the verification did not match; if it still does not match, it is handed over marked as unconfirmed, without sending it back again. */
    internal var gapsNudged = false
    internal var provenance = RunProvenance()
    val activeAsk: Delivery.Ask? get() = user.activeAsk

    /** New entry point: steers and control within the same conversation come in through [inbox]; the old entry point keeps using the same execution path. */
    suspend fun run(spec: RunSpec, inbox: RunInbox): TaskState {
        suspend fun start() = runInternal(spec.goal, spec.priorTurns, inbox, spec.provenance, spec.threadId, spec.tokenBudget)
        // The app installs a shared tally around the whole run and subtasks reuse it; when going straight through the core entry point, this is what makes RunSpec's budget take effect.
        if (spec.tokenBudget == null || currentCoroutineContext()[RunUsageTally] != null) return start()
        return withContext(RunUsageTally(spec.tokenBudget)) { start() }
    }

    suspend fun run(task: String, priorTurns: List<Turn> = emptyList()): TaskState =
        runInternal(task, priorTurns, null, RunProvenance(), "local")

    internal suspend fun runInternal(
        task: String, priorTurns: List<Turn>, inbox: RunInbox?, runProvenance: RunProvenance, threadId: String,
        tokenBudget: Long? = null,
    ): TaskState {
        cancelRequested = false
        provenance = runProvenance
        user.begin(threadId, runProvenance, interaction)
        inbox?.bind(object : RunInbox.Handler {
            override suspend fun answer(text: String, askId: String?) = user.answerInput(text, askId)
            override suspend fun confirm(choice: ConfirmChoice, askId: String?) = user.answerConfirmation(choice, askId)
            override suspend fun handBack(result: HandBack, askId: String?) = user.resumeAfterTakeover(result, askId)
            override fun cancel() = this@AgentLoop.cancel()
        })
        caller.reset(basePrompt)
        caller.promptTransform = promptTransform
        // Consent is only valid within a single task
        confirmations?.reset()
        utterances?.clear()
        emit(RunEvent.TaskStarted(now(), Redactor.text(task), model.name,
            origin = runProvenance.origin.takeIf { it != RunOrigin.Ui }?.name?.lowercase(),
            stepLimit = maxSteps.takeIf { it != Int.MAX_VALUE }, tokenBudget = tokenBudget))
        // The persona is recorded after TaskStarted, so the timeline reads "who was used at the start"; nothing is written when there is no persona
        personaLabel?.let { emit(RunEvent.State(now(), "persona", it)) }
        var end: TaskState? = null
        replay?.reset()
        lastEnd = null
        try {
            end = runLoop(task, priorTurns, inbox)
            lastEnd = end
            return end
        } catch (e: CancellationException) {
            end = TaskState.Cancelled
            throw e
        } finally {
            emit(ended(end))
            inbox?.close()
        }
    }

    internal suspend fun runLoop(task: String, priorTurns: List<Turn>, inbox: RunInbox?): TaskState {
        val end = try {
            coroutineScope {
                // The steer signal is installed in the context of the whole run: waits that lose nothing when withdrawn end early because of it (see waitOrSteer)
                val body = async(inbox?.signal ?: EmptyCoroutineContext) { loop(task, priorTurns, inbox) }
                job = body
                if (cancelRequested) body.cancel()
                body.await()
            }
        } catch (e: CancellationException) {
            if (!cancelRequested) throw e
            TaskState.Cancelled
        } finally {
            job = null
            user.clear()
            confirmations?.reset()
        }
        _state.value = end
        return end
    }

    /** Pass through [gate] before a write operation: the waiting reason is shown externally while suspended, and [gateState] is restored afterwards. */
    internal suspend fun awaitGate() {
        try {
            gate { waiting ->
                _state.value = waiting ?: gateState
                if (waiting != null) emit(waitingEvent(waiting))
            }
        } finally {
            _state.value = gateState
        }
    }

    /**
     * Called after the handed-over answer's outcome has been judged ([OutcomeJudge]): a replay route that ran to completion counts one success only when it succeeded ([LearnGate]).
     * Failed and cancelled tasks are neither judged nor need this call; without the call no success is recorded.
     */
    suspend fun noteOutcome(outcome: RunOutcome) {
        val end = lastEnd ?: return
        replay?.noteResult(end, outcome)
    }

    /** The outcome of handing back for takeover; the outcome is given to the model as the tool result of hand_to_user. */
    suspend fun resumeAfterTakeover(result: HandBack) = user.resumeAfterTakeover(result)

    /** The old hand-back form: always counts as complete; when [note] is [USER_FINISHED_NOTE] it means the user said they had already done it themselves. */
    suspend fun resumeAfterTakeover(note: String) =
        resumeAfterTakeover(HandBack(HandBackOutcome.complete, userFinished = note == USER_FINISHED_NOTE))

    /** Answer a follow-up question; returns false when no question is waiting (already timed out or already answered), and the UI then tells the user the answer came too late. */
    suspend fun answerInput(text: String): Boolean = user.answerInput(text)

    fun cancelInput(askId: String): Boolean = user.cancelInput(askId)

    suspend fun answerConfirmation(approved: Boolean) = answerConfirmation(if (approved) ConfirmChoice.Once else ConfirmChoice.Deny)

    suspend fun answerConfirmation(choice: ConfirmChoice) = user.answerConfirmation(choice)

    fun cancel() {
        cancelRequested = true
        job?.cancel()
    }

    internal data class LoopOpening(
        val task: String,
        val priorTurns: List<Turn>,
        val screen: UserScreenTurns.Rendered?,
        val context: Message?,
        val memoryMessage: String?,
    )

    internal data class LoopHistory(val messages: MutableList<Message>, val labels: Map<String, String>)

    /** The tool list currently given to the model and its system prompt: they switch together when a new group is expanded, and stay exactly the same otherwise. */
    internal inner class ToolOffer {
        private var openGroups = tools.expanded
        private val extra = listOfNotNull(BatchRunner.spec(tools.batchable()).takeIf { flags.batchSteps })
        var current = emptyList<ToolSpec>()
            internal set

        private fun keep(spec: ToolSpec): Boolean {
            val verb = tools.verb(spec.name)
            return (confirmations != null || verb != Verb.RequestConfirmation) && (memory != null || verb !in MEMORY_VERBS)
        }

        fun recordPromptVersion(): List<String> {
            // The prompt version is computed as if every group were expanded: the same prompt version does not differ by which groups happen to be expanded this time
            val everything = tools.offeredNow(extra, all = true).filter(::keep)
            emit(RunEvent.PromptVersion(now(), PromptVersion.of(SystemPrompt.assemble(basePrompt, tools.promptSections(everything, all = true)), everything)))
            return everything.map { it.name }
        }

        // Before each model call: when new groups were expanded, the tools and system prompt change with them; otherwise nothing changes at all
        fun refresh() {
            val offer = tools.offeredNow(extra).filter(::keep)
            if (offer == current) return
            current = offer
            caller.systemPrompt = SystemPrompt.assemble(basePrompt, tools.promptSections(current))
            (tools.expanded - openGroups).forEach { emit(RunEvent.State(now(), "tools_opened", it)) }
            openGroups = tools.expanded
        }
    }

    internal suspend fun loop(raw: String, rawPrior: List<Turn>, inbox: RunInbox?): TaskState {
        val opening = prepareOpening(raw, rawPrior)
        val task = opening.task
        // Pre-expanded at the opening: the groups used in the previous turn, and those this sentence will certainly need (see ToolDispatcher.preload)
        tools.preload(task, opening.priorTurns.lastOrNull()?.groups.orEmpty()).forEach { emit(RunEvent.State(now(), "tools_preloaded", it)) }
        val offer = ToolOffer()
        val allToolNames = offer.recordPromptVersion()
        offer.refresh()
        val guards = OperationGuards(maxSteps)
        val recorder = ActionRecorder(sink, clock, saveScreen = { id, png -> sink.media(id, png) })
        val executor = ActionExecutor(tools, guards, confirmations, gate = ::awaitGate, recorder = recorder, offScreen = tools.offScreenGuard(), approvals = approvals,
            provenance = provenance,
            selfPackage = selfPackage, autonomous = autonomous, pacing = outwardPacing, onScreenAction = stages?.let { it::action })
        media.clear()
        gapsNudged = false
        interruptedStep = 0
        // All context other than the user's own words is generated through it: one envelope, one budget
        ctx = ContextBuilder(zone = zone)
        val (history, labels) = prepareHistory(opening)
        return LoopSession(opening, offer, allToolNames, guards, recorder, executor, history, labels, inbox).run()
    }

    /** State shared by the model turns and tool execution within one run; each suspending phase manages its own temporaries. */
    internal inner class LoopSession(
        private val opening: LoopOpening,
        private val offer: ToolOffer,
        private val allToolNames: List<String>,
        private val guards: OperationGuards,
        private val recorder: ActionRecorder,
        private val executor: ActionExecutor,
        private val history: MutableList<Message>,
        private val labels: Map<String, String>,
        private val inbox: RunInbox?,
    ) {
        private val task = opening.task
        private var note = ""
        private var emptyNudged = false
        private var progressOnlyTurns = 0
        // There is material to verify only if tools were called; handing payment to the user is like letting an outward action through, and the answer must ask for a separate verification
        private var acted = false
        private var paymentHandedOver = false
        // The user answered a follow-up and nothing else has been done yet: now the answer is due, so remind once to do as told (see [owedFollowUp])
        private var answered: String? = null
        private var followUpNudged = false
        private var resultReadNudged = false
        private var languageNudged = false
        // A reply whose tool call leaked as plain text has just been sent back once (see [LeakedToolCall]); normally calling a tool resets it
        private var leakNudged = false
        private fun facts() = ReviewFacts(acted, paymentHandedOver || recorder.outwardActs > 0, recorder.lastChangeMark?.let { !tools.readSince(it) } == true)
        // On the web, knowledge is looked up by domain only on the first visit to each site
        private val visitedSites = mutableSetOf<String>()

        // Where the task is right now (app, site, …); provided by the capability side from each call's result
        private var place: Place? = null
        private var stuck = StuckState()
        private var lastProgressVersion = guards.progressVersion
        private fun stuckAdvice(verdicts: List<GuardVerdict>, typed: String?): String? {
            val version = guards.progressVersion
            val (next, advice) = StuckAdvice.next(stuck, StuckInput(verdicts, typed, place?.name, version != lastProgressVersion))
            lastProgressVersion = version
            stuck = next
            return advice
        }
        private val track = StepTrack()
        private val eachLoop = EachLoopDetector()
        private val progress = TaskProgressTracker(::now, ::emit)
        private var firstToolStep: Int? = null
        private val newData = NewDataTracker()
        private var intent: IntentGate.Intent? = null
        private var intentAssessed = false
        // Judged only once per run; when it cannot be judged (the call fails, or the reply is not one of the three words) it is null and the main model's own decision stands
        private suspend fun assessIntent(): IntentGate.Intent? {
            intentAssessed = true
            // Only a screen was sent and nothing was said: there are no words to judge, so guessing intent from the screen is left to the main model
            val result = if (opening.screen != null && opening.screen.words.isEmpty()) null else intentGate?.assess(task, opening.priorTurns)
            emit(RunEvent.State(now(), "intent_gate", result?.name ?: "unavailable"))
            return result
        }
        private fun acceptProgress(parsed: CompletionPlanBlock.Extracted, step: Int) = progress.acceptParsed(parsed, step)

        suspend fun run(): TaskState {
            replayOpening()
            for (step in 1..maxSteps) {
                val end = runStep(step)
                if (end != null) return end
            }
            appendSteers(history, inbox)
            offer.refresh()
            return finalTurn(task, history, offer.current, maxSteps + 1, labels, place, recorder, facts(), inbox, progress)
        }

        private suspend fun replayOpening() {
            // Before the first model call: if a suitable route exists, follow it first without calling the model; whether it finishes or does not match, control returns to the model
            // Routes only record what is done on the phone: tasks that explicitly call for the web are not replayed
            if (flags.replay && replay != null && !task.trimStart().startsWith(WEB_CHANNEL_PREFIX)) {
                val reviewRoute = provenance.origin == RunOrigin.Ui && intentGate != null && routeMatcher?.candidates(task)?.isNotEmpty() == true
                if (reviewRoute) intent = assessIntent()
                if (!reviewRoute || intent == IntentGate.Intent.Read || intent == IntentGate.Intent.Act) {
                    replay.run(task, history, executor, ctx, track)?.let { place = it; acted = true }
                }
            }
        }

        private suspend fun runStep(step: Int): TaskState? {
            appendSteers(history, inbox)
            if (maxSteps != Int.MAX_VALUE) TurnPolicy.beforeStep(TurnSnapshot(step, maxSteps))?.let { history += ctx.notice(it.source, it.text) }
            appendRuntimeState(history, place, labels, step, progress.pending(step, afterFirstTool = firstToolStep == step - 1))
            _state.value = TaskState.Running(step, note, progress = progress.state(step))
            offer.refresh()
            var asked = now()
            tools.manualControl?.awaitAutomatic()
            val controlVersion = tools.manualControl?.version
            val routed = try {
                routedOrRedirect(inbox, history, offer.current, track, step, { asked = it }, previewFor(step))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return StopRules.modelFailed(e)
            }
            if (routed == null) {
                keepInterrupted(history, inbox, step)
                return null
            }
            tools.manualControl?.awaitAutomatic()
            if (controlVersion != tools.manualControl?.version) {
                history += ctx.notice("manual_control", "The screen changed. Read the current screen and reconsider the next step; the earlier proposed action was not executed.")
                return null
            }
            var (reply, used) = routed
            val proposed = reply.parts.firstActionCall()
            if (provenance.origin == RunOrigin.Ui && intentGate != null && proposed != null &&
                tools.verb(proposed.name).let { it !in DISCUSSION_LOOKUP_VERBS && it !in MEMORY_WRITE_VERBS }) {
                if (!intentAssessed) intent = assessIntent()
                if (intent == IntentGate.Intent.Discuss) {
                    // The proposed call has not been written into the history and has not been executed either; ask again, giving only the opportunity for a direct answer.
                    val discussion = history + ctx.notice("conversation_intent",
                        "The user is discussing or reviewing this time. Answer this question directly, without reading or operating apps, websites or other outside content.")
                    val direct = try { caller.askRouted(discussion, emptyList(), step, onText = previewFor(step)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { return StopRules.modelFailed(e) }
                    reply = direct.first
                    used = direct.second
                }
            }
            val rawText = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
            val parsed = CompletionPlanBlock.extract(rawText)
            parsed.stage?.let { stages?.reported(it) }
            acceptProgress(parsed, step)
            val text = parsed.text
            val cleanParts = cleanReplyParts(reply.parts, text)
            history += Message(Role.Assistant, cleanParts, Author.Mika).also { caller.noteReply(it, used, reply.usage) }
            val calls = ReplyCalls(reply.parts) { acceptProgress(it, step) }
            note = text.ifEmpty { calls.action?.name ?: note }
            val tool = calls.action?.name
            val reason = calls.action?.let { tools.callReason(it.name, parseArgs(it.argsJson)) }
            val retryWaitMs = caller.lastRetryWaitMs
            val repliedAtMs = now()
            val latencyMs = repliedAtMs - asked - retryWaitMs
            // Text written alongside a call is progress narration; the live line in the UI uses it first
            val narration = text.takeIf { !calls.isEmpty && it.isNotEmpty() }
            _state.value = TaskState.Running(step, note, tool, progress = progress.state(step), reason = reason, narration = narration,
                preview = text.takeIf { streamReplies && calls.isEmpty && it.isNotEmpty() })
            // A reply carrying calls is recorded after execution: whether the entry point reports a password field needs to be known
            fun recordReply(argsJson: String?) = emit(RunEvent.ModelReply(
                now(), step, RunEvent.clip(Redactor.text(text)), tool, argsJson,
                reply.usage?.inputTokens, reply.usage?.outputTokens, reply.usage?.cachedInputTokens, latencyMs,
                if (caller.recordRoles) used.name else null, if (caller.recordRoles) caller.roleOf(used) else null, retryWaitMs = retryWaitMs,
                group = caller.groupOf(used),
                repliedAtMs = repliedAtMs,
            ))

            val first = calls.action
            return if (first == null) {
                handleAnswer(step, calls, text, reply.stopReason, ::recordReply)
            } else {
                handleTool(step, calls, first, reason, narration, ::recordReply)
            }
        }

        /** A reply without calls: progress narration, the final answer, and the checks before it is handed over. */
        private suspend fun handleAnswer(
            step: Int,
            calls: ReplyCalls,
            text: String,
            stopReason: StopReason,
            recordReply: (String?) -> Unit,
        ): TaskState? {
            if (!calls.isEmpty) {
                recordReply(null)
                history += calls.results()
                if (++progressOnlyTurns >= 3) return StopRules.noAnswer()
                return null
            }
            track.after(null, ok = false, guard = false, changed = null, recovery = false, app = null)
            recordReply(null)
            // When the model gives the final answer, take in any in-flight user steer first, then decide whether to hand the answer over.
            if (keepFinished(history, inbox)) return null
            // An empty reply, or a half-sentence truncated by the output limit, is not an answer: remind once, and end if it happens again
            if (text.isEmpty() || stopReason == StopReason.MaxTokens) {
                val n = TurnPolicy.afterEmpty(emptyNudged, truncated = text.isNotEmpty()) ?: return StopRules.noAnswer()
                emptyNudged = true
                history += ctx.notice(n.source, n.text)
                return null
            }
            emptyNudged = false
            // The tool call leaked as plain text verbatim (the server did not parse it as a call): not an answer. Send it back once so it is called again through the tool interface;
            // if it leaks again right after, end, and do not hand the raw markers to the user
            val leaked = LeakedToolCall.find(text, allToolNames)
            if (leaked != null) {
                if (leakNudged) return StopRules.toolCallAsText()
                leakNudged = true
                emit(RunEvent.State(now(), "tool_call_as_text", RunEvent.clip(leaked)))
                TurnPolicy.leakedToolCall(leaked).let { history += ctx.notice(it.source, it.text) }
                return null
            }
            // The user just approved something or answered something, and then nothing was done: remind once to do it or to explain why not; afterwards verify as usual
            val owed = owedFollowUp(answered).takeIf { !followUpNudged }
            if (owed != null) {
                followUpNudged = true
                TurnPolicy.followUp(owed).let { history += ctx.notice(it.source, it.text) }
                return null
            }
            val before = beforeAccept(text)
            if (before is BeforeAccept.Continue) {
                history += ctx.notice(Feedback["before_accept_source"], before.note)
                return null
            }
            val currentFacts = facts()
            if (!reviewFinalAnswer && currentFacts.unreadChange && !resultReadNudged) {
                resultReadNudged = true
                history += ctx.notice("operation_result", "The result of the last action has not been read yet. Look at the current screen first to confirm the actual state, then answer; if it cannot be read, say so plainly.")
                return null
            }
            // The body of the answer is not in the language of the user's latest sentence: send it back once for a full rewrite; if it is still wrong after the rewrite, hand it over anyway
            if (!languageNudged) {
                val mismatch = ReplyLanguage.mismatch(latestWords, text)
                if (mismatch != null) {
                    languageNudged = true
                    TurnPolicy.language(mismatch).let { history += ctx.notice(it.source, it.text) }
                    return null
                }
            }
            // A reply without calls is the final answer; the product path only assembles sources.
            val result = conclude(task, text, step, currentFacts, history)
            if (result != null) {
                // Another steer arrived before handover (during verification or source assembly): do not finish, carry on with it; if there is none, close the inbox so that later messages become the next task
                if (inbox?.closeUnlessSteered() == false) { keepFinished(history, inbox); return null }
                return result
            }
            return null
        }

        /** A reply carrying calls: batch steps, confirmation gates, single-step execution and the wrap-up decision after execution. */
        private suspend fun handleTool(
            step: Int,
            calls: ReplyCalls,
            first: Part.ToolCall,
            reason: String?,
            narration: String?,
            recordReply: (String?) -> Unit,
        ): TaskState? {
            emptyNudged = false
            leakNudged = false
            progressOnlyTurns = 0
            acted = true
            if (firstToolStep == null) firstToolStep = step
            // Something else was done after the answer: that answer now counts as followed up on
            answered = null

            if (stages != null && tools.verb(first.name) !in setOf(Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser)) {
                stages.planIfNeeded(latestWords, intentJudge ?: model)
            }
            trace?.toolStarted(step, first.name, first.id, now())
            val args = parseArgs(first.argsJson)

            // The step the user just approved must be done on its own: steps in a batch carry no consent, so mixing it in would be stopped by the gate, and the consent could also be consumed or invalidated by other steps
            if (((flags.batchSteps && first.name == DO_STEPS_TOOL) || tools.composite(first.name)) && confirmations?.hasGrant == true) {
                val content = buildJsonObject {
                    put("ok", false); put("error", "approved_step_first"); put("message", APPROVED_STEP_FIRST); put("recoverable", true)
                }
                history += calls.results(Part.ToolResult(first.id, content.toString(), isError = true))
                recordReply(if (first.name == DO_STEPS_TOOL) BatchRunner.redactArgs(first.argsJson) else Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                recorder.result(step, first.name, tools.verb(first.name), content, JsonObject(emptyMap()), null, null, 0, "model", first.id)
                track.after(tools.verb(first.name), ok = false, guard = false, changed = null, recovery = false, app = null)
                return null
            }
            if (flags.batchSteps && first.name == DO_STEPS_TOOL) {
                val end = batch(first, calls, history, offer.current, guards, executor, track, eachLoop, step, note, first.name, reason, narration, recordReply = recordReply,
                    app = { place = it }, steerWaiting = { inbox?.hasSteers() == true },
                    noteVerdicts = { s, sub, v ->
                        // Counts toward stuck reminders and repeated input of the same text just like a single step
                        val typed = StuckAdvice.typedText(tools.verb(s.tool), parseArgs(s.argsJson), sub?.content)
                        stuckAdvice(v, typed)
                    })
                if (end != null) return end
                if (progress.plan.complete) return finalTurn(task, history, offer.current, step + 1, labels, place, recorder, facts(), inbox, progress, Closeout.Completed)
                return null
            }

            val verb = tools.verb(first.name)
            val offScreen = tools.offScreen(first.name)
            val running = TaskState.Running(step, note, first.name, progress = progress.state(step), reason = reason, narration = narration)
            gateState = running
            var advice: String? = null
            var waitsForUser = false
            val action = executor.run(first, Origin.Model(step), object : FollowUp {
                override suspend fun resolve(exec: ToolExecution): ToolExecution? {
                    var e = exec
                    (e.outcome as? Outcome.Confirm)?.let { e = user.confirm(it, step, running) }
                    // Waits for the user (follow-up, takeover): record this reply before waiting, so that a task cancelled while waiting still leaves it in the run record
                    waitsForUser = e.outcome is Outcome.Input || e.outcome is Outcome.Takeover
                    if (waitsForUser) recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                    // The follow-up answer (or its timeout) is this call's tool result, keeping call and result paired
                    (e.outcome as? Outcome.Input)?.let { o ->
                        e = user.input(o, running)
                        if (e.content["ok"] == JsonPrimitive(true)) answered = o.prompt
                    }
                    // Wait for the hand-back; the hand-back's outcome is this call's tool result, and cancelling the task ends things here
                    (e.outcome as? Outcome.Takeover)?.let { e = user.takeover(it, running) ?: return null }
                    return e
                }

                override suspend fun present(exec: ToolExecution, verdicts: List<GuardVerdict>): Presented {
                    val e = exec
                    val a = stuckAdvice(verdicts, StuckAdvice.typedText(verb, args, e.content))
                    advice = a
                    val content = if (verdicts.isEmpty() && advice == null) e.content
                    else JsonObject(e.content +
                        (if (verdicts.isEmpty()) emptyMap() else mapOf("guard" to JsonArray(verdicts.map { it.toJson() }))) +
                        (advice?.let { mapOf("advice" to JsonPrimitive(it)) } ?: emptyMap()))
                    val isError = (e.content["ok"] as? JsonPrimitive)?.booleanOrNull == false
                    history += calls.results(Part.ToolResult(first.id, ScreenElementTable.text(content, flags.tablesAtBirth && !isError), isError, e.observations.map { it.tag }))
                    // Record the original text only when the entry point explicitly says "not a password field"
                    val secret = (e.content["secret"] as? JsonPrimitive)?.booleanOrNull != false
                    if (!waitsForUser) recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secret))
                    return Presented(e, content)
                }

                override fun refused(content: JsonObject) {
                    history += calls.results(Part.ToolResult(first.id, content.toString(), isError = true))
                    recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                }

                // A step that is off-screen and needs asking (a root command that changes things): ask on the spot with the same confirmation card as payments, and the declaration is this step itself
                override suspend fun approve(request: ConfirmationRequest): ToolExecution =
                    user.confirm(Outcome.Confirm(request, request.app), step, running)
            })
            val ran = when (action) {
                is ActionOutcome.Cancelled -> return TaskState.Cancelled
                is ActionOutcome.Refused -> {
                    guards.stop?.let {
                        recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                        return StopRules.guard(it)
                    }
                    // App permission denial has already been given to the model as a tool result by the execution entry point: do not end the task, let the model try another route or explain to the user
                    if (AppAccessDenied.isAppAccessCode(action.code) || action.code == com.wangzan101.mika.core.run.ManualControl.CHANGED) {
                        track.after(tools.verb(first.name), ok = false, guard = false, changed = null, recovery = false, app = null)
                        return null
                    }
                    // A model's single step can only be stopped by a guard, blocked by the confirmation gate, or not done because it acts on Claire itself; any other refusal means the execution entry point and this code disagree
                    check(action.hit != null || action.code == Refusals.OWN_APP) { "unexpected refusal ${action.code} for ${first.name}" }
                    // Blocked by the confirmation gate: not counted toward the failure circuit breaker, handed to the model to go through the confirmation flow
                    track.after(tools.verb(first.name), ok = false, guard = action.before.isNotEmpty(), changed = null, recovery = false, app = null)
                    return null
                }
                is ActionOutcome.Ran -> action
            }
            val exec = ran.exec
            val noNewData = newData.after(verb, exec)
            if (noNewData) emit(RunEvent.State(now(), "no_new_data", NewDataTracker.LIMIT.toString()))
            if (verb == Verb.HandToUser && (args["reason"] as? JsonPrimitive)?.contentOrNull == PAYMENT) paymentHandedOver = true
            val isError = (exec.content["ok"] as? JsonPrimitive)?.booleanOrNull == false
            noteResultEvents(verb, exec.content)
            exec.place?.let { place = it }
            track.after(tools.verb(first.name), !isError, ran.verdicts.isNotEmpty() || advice != null, exec.changed, false, exec.place?.name)
            if (eachLoop.feed(verb, !isError)) {
                history += ctx.notice(EACH_LOOP_SOURCE, Feedback.format("loop.repeating_items", "count" to EachLoopDetector.REPEATS))
                track.markRecovery()
            }
            val picture = ran.picture
            val shotId = ran.shotId
            if (picture != null && shotId != null) {
                record { sink.media(shotId, picture) }
                media.getOrPut(step) { mutableListOf() } += shotId
                emit(RunEvent.Media(now(), shotId, step, RunEvent.placeLabel(place)))
            }

            val stopNow = guards.stop ?: executor.offScreenStop
            val end = StopRules.afterAction(exec.outcome, stopNow)
            if (end != null) {
                // The model itself said it could not do it (end_not_done) and the user has just added something: do not finish, carry on with that message; a guard stop still ends the task
                if (stopNow == null && exec.outcome is Outcome.Failure && inbox?.closeUnlessSteered() == false) {
                    appendSteers(history, inbox)
                    return null
                }
                return end
            }

            if (exec.images.isNotEmpty()) {
                // The image files that were read are not screen images, so no screenshotId is attached
                history += Message(Role.User, listOf(Part.Text(NoticePolicy.imageNote(verb, offScreen))) +
                    exec.images.mapIndexed { i, img -> tools.modelImage(img, if (offScreen) null else exec.imageIds.getOrNull(i)) }, Author.Mika)
            }
            for (lookup in NoticePolicy.knowledgeAfter(verb, exec.place, !isError)) when (lookup) {
                is KnowledgeLookup.App -> injectKnowledge(history, knowledge?.byPackage(lookup.pkg), lookup.pkg)
                // The same piece of knowledge is never injected twice
                is KnowledgeLookup.Site -> if (visitedSites.add(lookup.host)) injectKnowledge(history, knowledge?.byDomain(lookup.host), null)
            }
            if (progress.plan.complete) return finalTurn(task, history, offer.current, step + 1, labels, place, recorder, facts(), inbox, progress, Closeout.Completed)
            if (noNewData) return finalTurn(task, history, offer.current, step + 1, labels, place, recorder, facts(), inbox, progress, Closeout.NoNewData)
            return null
        }
    }

    companion object {
        const val STEER_MARKER = "<user_interjection>"
        const val DEFAULT_MAX_STEPS = 60
        /** A normal task has no fixed step cutoff; an explicit step budget is used only for evaluations and subtasks with a specified limit. */
        const val UNLIMITED_STEPS = Int.MAX_VALUE
        /** The description of this step shown in the UI while verification is in progress. */
        const val VERIFYING_NOTE = "Verifying the result"
        val APPROVED_STEP_FIRST: String get() = Feedback["approved_step_first"]
        /** The places that still did not match after the answer was sent back once; the outcome description starts this way (the UI marks them individually via [TaskState.Succeeded.unconfirmed]). */
        const val UNCONFIRMED_NOTE = "Unconfirmed: "
        /** The reason when hand_to_user hands payment to the user; payment is irreversible, so the answer must ask for a separate verification. */
        internal const val PAYMENT = "payment"
        /** The source label of the repeated item-by-item work reminder. */
        internal const val EACH_LOOP_SOURCE = "each loop"
        const val DEFAULT_INPUT_TIMEOUT_MS = 15 * 60_000L
        /** The note in the tool result after handing back for takeover (UI tests recognise it by this). */
        val HANDBACK_NOTE: String get() = UserExchange.HANDBACK_NOTE

        /** The user clicked "I'll do it myself". */
        val USER_FINISHED_NOTE: String get() = Feedback["user_finished_note"]
    }

    internal fun now() = clock()

    internal fun detail(s: String) = RunEvent.clip(Redactor.text(s))

    internal inline fun record(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A failed record does not affect the task
        }
    }

    internal fun emit(e: RunEvent) = record { sink.event(e) }

    internal fun ended(end: TaskState?): RunEvent.TaskEnded = when (end) {
        // Verified but not confirmable (including those marked unconfirmed): recorded as unverified, the same wording as evaluations and the conversation record
        is TaskState.Succeeded -> RunEvent.TaskEnded(now(), if (end.doubtful) "unverified" else "succeeded", Redactor.text(end.answer))
        is TaskState.Failed -> RunEvent.TaskEnded(now(), "failed", Redactor.text(end.reason), end.kind.name.lowercase())
        TaskState.Cancelled -> RunEvent.TaskEnded(now(), "cancelled", "已取消")
        else -> RunEvent.TaskEnded(now(), "error", "The task was interrupted unexpectedly")
    }

    internal fun waitingEvent(s: TaskState): RunEvent.State = when (s) {
        is TaskState.Held -> RunEvent.State(now(), s.reason.code, detail(s.reason.why))
        else -> RunEvent.State(now(), s::class.simpleName.orEmpty(), "")
    }
}

/** How the user says to use the web (also prepended to the goal when an evaluation forces the web channel). */
private const val WEB_CHANNEL_PREFIX = "用网页"
