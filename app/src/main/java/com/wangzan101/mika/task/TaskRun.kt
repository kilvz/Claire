package com.wangzan101.mika.task

import android.graphics.Bitmap
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.PhoneViewport
import com.wangzan101.mika.cap.phone.display.PipInput
import com.wangzan101.mika.cap.phone.display.phoneViewport
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.JudgedOutcome
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.agent.OutcomeJudge
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.StopSummary
import com.wangzan101.mika.core.agent.TaskStages
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.run.ChildAgentRunner
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.ResourceScope
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.model.usage.DeepSeekPriceCache
import com.wangzan101.mika.core.model.usage.RunUsageTally
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.record.RunHandle
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.knowledge.FileRouteStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RunApproval
import com.wangzan101.mika.settings.imageModel
import com.wangzan101.mika.settings.ttsModel
import com.wangzan101.mika.settings.videoModel
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.voice.model.settingsProtocol
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Overlay sound-restore routing: only a takeover that really hands the app to the user is handled; the takeover object exists from the moment the task starts,
 * so non-null alone is not a reason to return early. Waiting for takeover, web takeover, or no takeover target at all all fall through to the normal phone audio path.
 */
internal fun restorePipSound(takeover: Takeover?, restorePhone: () -> Unit) {
    if (takeover?.unmute() != true) restorePhone()
}

/** Overlay re-mute routing; the conditions are the same as [restorePipSound]. */
internal fun mutePipSound(takeover: Takeover?, mutePhone: () -> Unit) {
    if (takeover?.muteBack() != true) mutePhone()
}

/** Resolve file-transcription exposure when phone tools are assembled; a stale voice selection only removes this tool. */
internal fun taskTranscribePort(
    origin: RunOrigin,
    stt: com.wangzan101.mika.voice.model.SttSelectionController,
    legacyPort: () -> com.wangzan101.mika.core.cap.transcribe.TranscribePort?,
    migratedPort: () -> com.wangzan101.mika.core.cap.transcribe.TranscribePort,
): com.wangzan101.mika.core.cap.transcribe.TranscribePort? {
    if (origin != RunOrigin.Ui) return null
    if (!stt.migrated) return legacyPort()
    return try {
        if (stt.batchModel() == null) null else migratedPort()
    } catch (_: com.wangzan101.mika.voice.model.VoiceModelError) {
        null
    }
}

private val deepSeekPrices by lazy { DeepSeekPriceCache(ProviderStore.modelHttp) }

/**
 * All state of one task: virtual screen, loop, gate, takeover, mute, watchers. [TaskService] handles only the service lifecycle, intent dispatch and notifications.
 * This task's own flows are mirrored to the service's outward flows only while it is the "shown" task ([service.taskState.shownRun]);
 * writes by other tasks (when parallel), or by an old task replaced by a new one, land only in its own copy and never overwrite the shown one.
 * [scope] is the service's scope: side coroutines hang off it, are not cancelled along with the task coroutine, and are cancelled by [execute] at teardown.
 * [slot] determines its notification IDs and button request codes; when [parallel] is true (the parallelTasks flag) it checks whether another task is using the app before opening it.
 */
class TaskRun(
    val conversationId: String,
    val goal: String,
    internal val service: TaskService,
    private val scope: CoroutineScope,
    val slot: Int = 0,
    val parallel: Boolean = false,
) : ScreenControl {
    override val manualControl = com.wangzan101.mika.core.run.ManualControl()
    internal data class ManualTarget(val port: com.wangzan101.mika.core.screen.uistate.ScreenPort, val identity: String, val width: Int, val height: Int, val inputWidth: Int, val inputHeight: Int)
    private var manualTarget: ManualTarget? = null
    internal var manualTargetNow: (suspend () -> ManualTarget?)? = null

    /** Only opens manual control on the current run's actual screen; no input is offered when a main-screen control already exists or there is no picture. */
    override suspend fun beginScreenControl(): Boolean {
        if (stopPressed || job?.isActive != true || !manualControl.begin()) return false
        return try {
            val target = manualTargetNow?.invoke()
            if (target == null) { manualControl.end(); false }
            else { manualTarget = target; loop?.state?.value?.let(::publish); true }
        } catch (e: CancellationException) { withContext(NonCancellable) { manualControl.end() }; throw e }
        catch (_: Exception) { manualControl.end(); false }
    }

    private suspend fun endScreenControl() {
        manualTarget = null
        manualControl.end()
        loop?.state?.value?.let(::publish)
    }

    override suspend fun continueAfterScreenControl() {
        val waitingTakeover = takeover?.stage?.value == Takeover.Stage.Waiting
        endScreenControl()
        if (waitingTakeover) takeover?.handBack()
    }

    private suspend fun manualInput(width: Int, height: Int, act: suspend (ManualTarget) -> com.wangzan101.mika.core.screen.uistate.ActionResult): Boolean {
        val version = manualControl.version
        return manualControl.userInput {
            if (manualControl.version != version) return@userInput false
            val current = manualTargetNow?.invoke() ?: return@userInput false
            val target = manualTarget ?: return@userInput false
            if (target.identity != current.identity || width != current.width || height != current.height ||
                target.width != width || target.height != height || target.inputWidth != current.inputWidth || target.inputHeight != current.inputHeight) return@userInput false
            try { target.port.checkAppAccess(); act(target) is com.wangzan101.mika.core.screen.uistate.ActionResult.Ok }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        } ?: false
    }

    @Volatile internal var activePhoneDisplay: AgentDisplay? = null
    private var appliedViewport: Pair<Int, PhoneViewport>? = null

    override suspend fun resizeScreen(width: Int, height: Int): Boolean {
        if (width < 64 || height < 64 || job?.isActive != true) return false
        // The mobile/desktop viewport of the web page is managed by the browser itself; here we adjust the background phone screen.
        if (runState._webOpen.value) return false
        val display = activePhoneDisplay ?: return false
        val viewport = phoneViewport(width, height, service.resources.displayMetrics.densityDpi)
        if (appliedViewport == (display.displayId to viewport)) return true
        return manualControl.reconfigure {
            withContext(NonCancellable) {
                if (runState._webOpen.value || job?.isActive != true || activePhoneDisplay !== display || takeover?.handedOver == true) return@withContext false
                if (!display.resize(viewport.width, viewport.height, viewport.dpi)) return@withContext false
                appliedViewport = display.displayId to viewport
                manualTarget = manualTarget?.takeIf { it.identity == "phone:${display.displayId}" }?.copy(
                    width = display.previewWidth, height = display.previewHeight, inputWidth = display.width, inputHeight = display.height)
                // No input based on the old picture is accepted before the first frame of the new Surface; the task stays on the same displayId.
                withTimeoutOrNull(2_000) { display.frames.filterNotNull().first() }
                true
            }
        }
    }

    override suspend fun touchScreen(width: Int, height: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, swipe: Boolean): Boolean {
        if (width <= 0 || height <= 0 || x1 !in 0 until width || x2 !in 0 until width || y1 !in 0 until height || y2 !in 0 until height) return false
        return manualInput(width, height) { target ->
            val start = controlPoint(x1, y1, width, height, target.inputWidth, target.inputHeight)
            val end = controlPoint(x2, y2, width, height, target.inputWidth, target.inputHeight)
            if (swipe) target.port.swipe(start.first, start.second, end.first, end.second, durationMs.coerceIn(50, 2_000))
            else target.port.tapXY(start.first, start.second)
        }
    }

    override suspend fun screenBack(width: Int, height: Int) = manualInput(width, height) { it.port.back() }
    override suspend fun screenText(width: Int, height: Int, text: String) = manualInput(width, height) { it.port.typeText(null, null, text) }
    internal val chipStages = TaskStages {
        try { service.refreshNotifications(this) } catch (e: RuntimeException) {
            // The action already ran. A notification failure must never turn it into a failed tool call.
            Log.w(TAG, "could not refresh stage notification", e)
        }
    }
    internal var provenance = RunProvenance()
    internal var triggerWindowKey: String? = null
    internal var triggerActionGoal: String? = null
    /** The user's own condition text when this runs as a condition check; the conversation and notifications show it instead of the generated check goal [goal]. */
    internal var triggerCondition: String? = null
    internal var triggerThreadId: String? = null
    internal var mcpExternalId: String? = null
    internal var workflowAttemptId: String? = null
    /** Supplementary note attached when the front desk dispatches work; in the task context it is Claire's note, not the user's own words. Null when absent. */
    internal var brief: String? = null
    internal var channelPeer: ChannelPeer? = null
    internal var channelMessageId: String? = null
    /** When the main loop produced the outcome; the time until the result is actually sent is used to locate channel round-trip delay. */
    @Volatile internal var resultReadyAtMs = 0L
        private set
    internal val conditionCheck get() = triggerActionGoal != null
    @Volatile internal var children: ChildAgentRunner? = null
    /** External MCP servers this task connected to; their sessions are closed at the end. */
    @Volatile internal var mcpConnections: com.wangzan101.mika.settings.McpConnections? = null

    /**
     * All external MCP endpoints this task is connected to; this single list is what toolDispatcher gets. When other sources (future extensions, etc.)
     * also provide such endpoints, they are merged here and toolDispatcher gets no extra parameter; whatever each source connects must belong to the task's ResourceScope and be closed together at teardown.
     */
    internal fun mcpEndpoints(): List<com.wangzan101.mika.core.cap.mcp.McpConnection> = buildList {
        mcpConnections?.let { addAll(it.connections) }
    }
    /** The sandbox workspace this task uses (conversations of the same project share `p:<projectId>`; the rest keep the conversation workspace). */
    internal val workspaceKey: WorkspaceKey by lazy { WorkspaceResolver.resolveFor(service, conversationId) }

    /** Its own copy; mirrored to the service's outward copy while shown. [show] copies the whole thing over. */
    /** The model source, evaluation hooks and screen-picture switches provided by the app. */
    internal val env = TaskEnvironment.of(service)
    /** The session ID this conversation is exposed under: every model created for this run (main, lightweight, vision, and side paths such as verification, intent detection and stop summaries) uses it. */
    internal val session = ModelSession.of(conversationId)
    /** Built once at the start of execute; the primary/light/vision models, fallbacks and failure hints all read only this snapshot. */
    @Volatile internal var modelSnapshot: TaskModelSnapshot? = null
        private set
    /** Phone services provided by the App (shared shell connection, "go to Settings" prompts), memory, learned routes, and recovery after an abnormal exit. */
    internal val phoneServices = PhoneServices.of(service)
    internal val memoryRuntime = MemoryRuntime.of(service)
    internal val routeStore = FileRouteStore.shared(service)
    internal val recovery = TaskRecovery.of(service)

    /** This task's UI-visible state (14 Mirrored state flows + publish lock + ended flag). */
    internal val runState = TaskRunState(service, conversationId, env) { service.taskState.shownRun === this }

    /** Mute and other restore records are kept apart by it, so each of several tasks keeps its own. */
    override val key: String = UUID.randomUUID().toString()
    internal val inbox = RunInbox()
    /** Sending, receiving, routing and withdrawing of steers. */
    private val steerCoordinator = SteerCoordinator(
        service, conversationId, runState, inbox,
        loop = { loop }, stopPressed = { stopPressed }, mcpExternalId = { mcpExternalId },
    )
    /** Lifecycle control and user interaction (cancel, takeover, questions, confirmation, teardown). */
    private val runControl = TaskRunControl(
        service, conversationId, scope, runState, inbox,
        loop = { loop }, gate = { gate }, takeover = { takeover },
        children = { children }, job = { job },
        cancelRequested = { cancelRequested }, setCancelRequested = { cancelRequested = it },
        shownRef = { service.taskState.shownRun === this },
        publish = ::publish,
        key = key,
        setStopPressed = { stopPressed = it },
    )
    /** The wrap-up explanation and the learning after teardown. */
    private val runTeardown = RunTeardown(
        service, conversationId, goal, runState,
        kit = { kit }, env = env, memoryRuntime = memoryRuntime, snapshot = { modelSnapshot },
        routeStore = routeStore,
        workflowAttemptId = { workflowAttemptId }, noticeTainted = { noticeTainted }, provenance = { provenance },
    )
    /** Phone-side runtime (overlay sound, mute, target watchers, touch pause on browser pages). */
    internal val phoneRuntime = PhoneRuntime(
        service, conversationId, scope, runState,
        publish = ::publish,
        phone = { phone }, takeover = { takeover }, gate = { gate },
        handoverRef = { handoverRef }, sideScope = { sideScope }, sandbox = { sandbox },
        setMuteFailedFor = { muteFailedFor = it },
        workspaceKey = { workspaceKey },
    )

    val state: StateFlow<TaskState> get() = runState.state
    val runId: StateFlow<String?> get() = runState.runId
    /** Files exported by this task; saved into the conversation with this turn when it ends. */
    val sharedFiles: StateFlow<List<SharedFile>> get() = runState.sharedFiles
    /** Audio generated by this task and left in the workspace; saved into the conversation with this turn when it ends. */
    val audios: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedAudio>> get() = runState.audios
    /** Images generated by this task and left in the workspace; saved into the conversation with this turn when it ends. */
    val photos: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedPhoto>> get() = runState.photos
    val videos: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedVideo>> get() = runState.videos
    val takeoverStage: StateFlow<Takeover.Stage> get() = runState.takeoverStage
    val frames: StateFlow<Bitmap?> get() = runState.frames
    /** The personal assistant's thumbnail accepts only the phone frames this task is allowed to show. */
    val phoneFrames: StateFlow<Bitmap?> get() = runState.phoneFrames
    /** This task's activity so far (what was actually done); the floating capsule takes cards per task, not only for the shown one. */
    val activities: StateFlow<List<RunEvent.Activity>> get() = runState.activities
    /** This task's virtual display ID; the screen is created only when a system command, an event tool, or the first app launch needs it, and it is -1 before that and after release. */
    val displayId: StateFlow<Int> get() = runState.displayId
    /** The direct-input handle for this task's small window; present once the screen exists, back to null after release. */
    val pipInput: StateFlow<PipInput?> get() = runState.pipInput
    /** Whether the sound of this task's small-window target has been restored by the user; the UI uses it to label the button "Restore sound" or "Mute". */
    val pipSoundOn: StateFlow<Boolean> get() = runState.pipSoundOn
    /** Currently using the built-in browser (the tools' UI is a web page); false when not in use and after the task ends. */
    val webOpen: StateFlow<Boolean> get() = runState.webOpen
    /** Steers injected while this task was running and where they went; the floating capsule's conversation card is taken per task. */
    val steers: StateFlow<List<SteerNote>> get() = runState.steers

    /** Make the UI show this task: become the shown task and copy its own values into the service's outward flows. */
    fun show() = synchronized(service.taskState.shownLock) {
        service.taskState.shownRun = this
        runState.copyOut()
    }

    /** The coroutine running [execute], filled in by the service once it has started it. */
    @Volatile var job: Job? = null
        internal set(v) {
            field = v
            if (cancelRequested) v?.cancel()
        }
    @Volatile private var cancelRequested = false
    /** The user pressed stop: steers not yet handed to the model are discarded as well and do not become the next item. */
    @Volatile internal var stopPressed = false
    @Volatile internal var loop: AgentLoop? = null
    val activeAsk: Delivery.Ask? get() = runControl.activeAsk
    @Volatile internal var gate: TaskGate? = null
    @Volatile var takeover: Takeover? = null
        internal set
    @Volatile var muteFailedFor: String? = null
        private set
    @Volatile private var awake: KeepAwake? = null
    /**
     * If tasks are still queued at teardown: take a screen lock on behalf of the successor before releasing its own; the service hands it to the released one during teardown,
     * and that one releases it after taking its own when it starts. If nobody takes over, or it ends before starting, the service releases it. Otherwise releasing here,
     * with the system screen-off time already passed, would turn the screen off on the spot, and the successor could not light it up with the lock, stalling at waiting for unlock as soon as it starts. Releasing repeatedly is not double-counted (see [SharedWakeLock]).
     */
    @Volatile internal var wakeHandoff: WakeLockPort? = null
    /** The shell process is gone, so the virtual screen and the apps on it are gone too; the task cannot go on. */
    @Volatile var shellDied = false
        internal set
    /** The phone side that is connected; overlay sound restore uses it to find the current target app, cleared at teardown. */
    @Volatile internal var phone: PhoneSide? = null
    fun cancel() {
        runControl.cancel()
        if (manualControl.state.value != com.wangzan101.mika.core.run.ManualControl.State.Automatic) {
            scope.launch { manualControl.close() }
        }
    }

    fun resume() {
        if (manualControl.state.value == com.wangzan101.mika.core.run.ManualControl.State.Manual) {
            scope.launch { continueAfterScreenControl() }
            return
        }
        runControl.resume()
    }
    private fun takeoverAction(action: suspend (Takeover) -> Boolean) {
        val current = takeover ?: return
        scope.launch {
            manualControl.end {
                action(current).also { if (it) manualTarget = null }
            }
        }
    }

    fun takeOver(waitKey: Int?) = takeoverAction { it.handOver(waitKey) }
    fun handBack(waitKey: Int?) = takeoverAction { it.handBack(waitKey) }
    fun finishedMyself(waitKey: Int?) = takeoverAction { it.finishedMyself(waitKey) }
    fun skip(waitKey: Int?) = takeoverAction { it.skip(waitKey) }
    fun putAside(waitKey: Int?) = takeoverAction { it.putAside(waitKey) }

    /** Sound toggle on the overlay: press again to mute if the sound was restored, press once to restore if it was not (repeated taps retry the real action); does nothing without a phone side or target app. */
    fun togglePipSound() = phoneRuntime.togglePipSound()

    /** Answer a question; when no longer waiting (answered after the timeout, or already answered) it calls [tooLate] to tell the user this answer was not used. */
    fun answer(text: String, tooLate: () -> Unit) = runControl.answer(text, tooLate)

    /** The channel has already verified the question ID; the answer still goes through the same inbox so the run lifecycle is not bypassed. */
    suspend fun answerFromChannel(text: String, askId: String): Boolean = runControl.answerFromChannel(text, askId)

    /** A new instruction replaces an ordinary question that has not been answered yet; the old task tears down as cancelled and the queued new instruction runs afterwards. */
    fun cancelAsk(askId: String): Boolean = runControl.cancelAsk(askId)

    /** New user words for the same conversation while a task runs, handed to the next model step; during preparation they are queued in the Inbox first. */
    fun steer(text: String, messageId: String = UUID.randomUUID().toString()) = steerCoordinator.steer(text, messageId)

    /** A remote steer belongs only to the current task; it is mutually exclusive with state publishing, and the inbox then atomically checks whether the run already ended or was cancelled. */
    fun steerRemote(text: String): String? = steerCoordinator.steerRemote(text)

    /** Withdraw a steer made on the phone that the model has not taken yet; returns false if already taken, already torn down, or appended from a computer (rejected by the inbox), with its routing unchanged. */
    fun withdrawSteer(messageId: String): Boolean = steerCoordinator.withdrawSteer(messageId)

    /** A phone steer can carry over to the next task; a remote steer can only be used for the current task, so anything unconsumed is discarded, without changing origin or permissions. */
    fun handoffUnconsumed(cancelled: Boolean = stopPressed) = steerCoordinator.handoffUnconsumed(cancelled)

    /** Capability groups this task actually used (phone, web, sandbox); recorded in this turn so the next turn expands them up front. */
    @Volatile var usedGroups: List<String> = emptyList()
        private set

    fun joinedSteers(): List<String> = steerCoordinator.joinedSteers()

    /** One-to-one with [joinedSteers]: the part of the reply that each steer interrupted, an empty string if it interrupted none. */
    fun joinedInterrupted(): List<String> = steerCoordinator.joinedInterrupted()

    /** One-to-one with [joinedSteers]: the part of the reply already finished before each steer was handed to the model, an empty string if none. */
    fun joinedFinished(): List<String> = steerCoordinator.joinedFinished()

    /** The two things the UI follows in run events: what was actually done, and that a steer has been handed to the model. */
    internal val liveEvents = object : RunSink {
        override fun event(e: RunEvent) {
            when {
                e is RunEvent.Activity -> synchronized(service.taskState.shownLock) {
                    val n = synchronized(runState.activityLog) { runState.activityLog += e; runState.activityLog.size }
                    runState._activities.value = runState.logView(n)
                }
                e is RunEvent.State && e.name == "reply_interrupted" -> steerCoordinator.onInterrupted(e.detail)
                e is RunEvent.State && e.name == "reply_finished_before_steer" -> steerCoordinator.onFinished(e.detail)
                e is RunEvent.State && e.name == "steer_received" -> steerCoordinator.onSteerReceived(e.detail)
            }
        }
    }

    /** Notification and card buttons all carry the [key] of the request they belong to; taps on an old notification or card that has already been replaced do not count. */
    fun confirm(choice: ConfirmChoice, key: Int?) = runControl.confirm(choice, key)

    /**
     * The task's final state: written only after [execute] tears down, not through [publish]. Whether or not it is the shown task, the run ID of this run is first recorded
     * under its own conversation ([TaskService.endedRunIds]) so the UI can look it up by conversation when it receives the end state; while shown, [TaskService.endedRunId] is written too.
     */
    fun end(end: TaskState) = runControl.end(end)

    /** Queued because the parallel limit was exceeded; [dequeue] runs when it is released to start. */
    fun queue(position: Int) = runControl.queue(position)
    fun dequeue() = runControl.dequeue()

    /** This task's sandbox: one per task (the export quota is counted per task), with this conversation's workspace. */
    internal val sandbox by lazy { AndroidSandbox(service, SandboxState.get(service), onShared = ::addShared) }

    internal val audioSettings by lazy { com.wangzan101.mika.settings.VoiceSettings.get(service) }

    /** Unmigrated tasks keep the original one-time port decision; only the migrated catalog selection needs to be refreshed dynamically. */
    private val legacyTranscribePort by lazy {
        val voiceModels = com.wangzan101.mika.voice.model.VoiceModelEnvironment.get(service)
        val choice = com.wangzan101.mika.voice.SpeechSettings.get(service).choice.value as? com.wangzan101.mika.voice.SpeechChoice.Provider
            ?: return@lazy null
        ProviderStore.get(service).provider(choice.providerId) ?: return@lazy null
        com.wangzan101.mika.media.AndroidTranscribePort(service, conversationId, choice, voiceModels.stt) { workspaceKey }
    }

    /** The migrated transcription port: both catalog and execution are decided by the current batch STT, and a stale configuration only makes the transcription tool unavailable. */
    internal val transcribePort: com.wangzan101.mika.core.cap.transcribe.TranscribePort?
        get() {
            if (provenance.origin != RunOrigin.Ui) return null
            val voiceModels = com.wangzan101.mika.voice.model.VoiceModelEnvironment.get(service)
            return taskTranscribePort(
                provenance.origin,
                voiceModels.stt,
                legacyPort = { legacyTranscribePort },
                migratedPort = {
                    com.wangzan101.mika.media.AndroidTranscribePort(service, conversationId, null, voiceModels.stt) { workspaceKey }
                },
            )
        }

    /**
     * This task's audio port: created only when the user has configured and enabled a voice model and this task was started on the phone.
     * Scheduled, remote, subtask and condition-check runs do not have it, so the audio tools are not given to the model either.
     */
    internal val imageSettings by lazy { com.wangzan101.mika.settings.ImageModelSettings.get(service) }

    /** This task's image port: created only when the user has configured and enabled an image model and this task was started on the phone. */
    internal val imagePort by lazy {
        val state = imageSettings.state.value
        val choice = state.choice
        if (!state.enabled || choice == null || provenance.origin != RunOrigin.Ui) null
        else imageSettings.capabilities()?.let { caps ->
            com.wangzan101.mika.settings.ProviderStore.get(service).imageModel(choice, caps)?.let { model ->
                com.wangzan101.mika.media.AndroidImagePort(service, conversationId, model, caps, onGenerated = runState::addPhoto, onSaved = runState::addSaved)
            }
        }
    }

    /** Copies an image from the workspace into a content URI other apps can read (for sharing); null when out of bounds, not an image, or too large. */
    internal suspend fun shareWorkspaceImage(path: String): com.wangzan101.mika.media.ShareableFile? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val trimmed = path.trim()
        val prefix = com.wangzan101.mika.core.workspace.SandboxPaths.WORKSPACE + "/"
        if (!trimmed.startsWith(prefix)) return@withContext null
        val rel = trimmed.removePrefix(prefix)
        if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return@withContext null
        val dirs = com.wangzan101.mika.cap.sandbox.SandboxDirs(service)
        val bytes = dirs.tree.shared {
            dirs.gate.openRead(dirs.workspace(com.wangzan101.mika.chat.WorkspaceResolver.resolveFor(service, conversationId)), rel).use { g ->
                if (g.size > 20L * 1024 * 1024) null else g.read(0, g.size.toInt())
            }
        } ?: return@withContext null
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val mime = opts.outMimeType?.takeIf { it.startsWith("image/") } ?: return@withContext null
        com.wangzan101.mika.media.ShareFileExporter(service).save(bytes, mime, trimmed.substringAfterLast('/'))
    }

    /**
     * Copy a video from the workspace into a content URI other apps can read (for sharing), using the same
     * [com.wangzan101.mika.media.ShareFileExporter] as images; it streams the copy instead of reading it all into memory.
     * Null when the range is invalid, the file is not a video, or it is too large.
     */
    internal suspend fun shareWorkspaceVideo(path: String): com.wangzan101.mika.media.ShareableFile? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val trimmed = path.trim()
        val prefix = com.wangzan101.mika.core.workspace.SandboxPaths.WORKSPACE + "/"
        if (!trimmed.startsWith(prefix)) return@withContext null
        val rel = trimmed.removePrefix(prefix)
        if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return@withContext null
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(trimmed.substringAfterLast('.', "").lowercase())
            ?.takeIf { it.startsWith("video/") } ?: return@withContext null
        val dirs = com.wangzan101.mika.cap.sandbox.SandboxDirs(service)
        dirs.tree.shared {
            dirs.gate.openRead(dirs.workspace(com.wangzan101.mika.chat.WorkspaceResolver.resolveFor(service, conversationId)), rel).use { g ->
                if (g.size > com.wangzan101.mika.media.AndroidVideoPort.MAX_BYTES) null
                else com.wangzan101.mika.media.ShareFileExporter(service).save(g.input(), mime, trimmed.substringAfterLast('/'))
            }
        }
    }

    /** This task's video port: only for tasks started on the phone (viewing/saving video only makes sense for the user's own files). */
    internal val videoPort by lazy {
        if (provenance.origin != RunOrigin.Ui) null
        else com.wangzan101.mika.media.AndroidVideoPort(service, conversationId)
    }

    internal val videoSettings by lazy { com.wangzan101.mika.settings.VideoSettings.get(service) }

    /**
     * This task's video generation port: built only when the user enabled video generation, the selected provider can
     * still generate video, and the task started on the phone. [com.wangzan101.mika.settings.videoModel] decides the
     * provider and protocol from the provider's declaration; when the selection no longer matches, no port is built and
     * the tools do not appear.
     */
    internal val videoGenerationPort by lazy {
        val state = videoSettings.state.value
        if (!state.enabled || provenance.origin != RunOrigin.Ui) null
        else com.wangzan101.mika.settings.ProviderStore.get(service).videoModel(state.choice)?.let { model ->
            com.wangzan101.mika.media.AndroidVideoGenerationPort(
                service, conversationId, model,
                com.wangzan101.mika.settings.ProviderStore.modelHttp,
                onProgress = { stage -> statusNote(tr(videoStageText(stage))) },
                onGenerated = ::addVideo,
            )
        }
    }

    internal val audioPort by lazy {
        val state = audioSettings.state.value
        if (!state.enabled || provenance.origin != RunOrigin.Ui) null
        else {
            val voiceModels = com.wangzan101.mika.voice.model.VoiceModelEnvironment.get(service)
            if (voiceModels.tts.migrated) migratedAudioPort(voiceModels, state) else legacyAudioPort(state)
        }
    }

    private fun legacyAudioPort(state: com.wangzan101.mika.settings.VoiceSettingsState): com.wangzan101.mika.media.AndroidAudioPort? {
        val choice = state.choice ?: return null
        return audioSettings.capabilities()?.let { caps ->
            com.wangzan101.mika.settings.ProviderStore.get(service).ttsModel(choice, caps)?.let { model ->
                buildAudioPort(model, caps, choice.voice, choice.speed)
            }
        }
    }

    private fun migratedAudioPort(
        voiceModels: com.wangzan101.mika.voice.model.VoiceModelEnvironment,
        state: com.wangzan101.mika.settings.VoiceSettingsState,
    ): com.wangzan101.mika.media.AndroidAudioPort? {
        val ref = voiceModels.tts.current() ?: return null
        return try {
            val protocol = voiceModels.tts.requireModel(ref).model.tts?.protocol
                ?.let { it.settingsProtocol() } ?: return null
            val caps = audioSettings.capabilities(protocol)
            val model = voiceModels.tts.createSpeaker(service, ref, caps)
            buildAudioPort(model, caps, state.voice, state.speed)
        } catch (_: com.wangzan101.mika.voice.model.VoiceModelError) {
            null
        }
    }

    private fun buildAudioPort(
        model: com.wangzan101.mika.core.model.TtsModel,
        caps: com.wangzan101.mika.core.model.TtsCapabilities,
        defaultVoice: String,
        defaultSpeed: Float,
    ): com.wangzan101.mika.media.AndroidAudioPort =
        com.wangzan101.mika.media.AndroidAudioPort(
            service, conversationId, model, caps, onSaved = ::addSavedAudio,
            onGenerated = ::addAudio, defaultVoice = defaultVoice.ifBlank { null }, defaultSpeed = defaultSpeed,
            onSpeak = { response -> (service.application as com.wangzan101.mika.MikaApp).voicePlayer.play(response.bytes, response.mime) },
            resolveWorkspace = { workspaceKey },
        )

    /** The sandbox exported a file (share_file or `mika share`). */
    fun addShared(f: SharedFile) = runState.addShared(f)

    /** The audio capability generated a piece of audio (called back by the port when written into the workspace). */
    fun addAudio(a: com.wangzan101.mika.core.chat.GeneratedAudio) = runState.addAudio(a)

    /** The audio capability saved a piece of audio into the music library: treated as a file exported in this turn, and a card appears in the chat. */
    fun addSavedAudio(s: com.wangzan101.mika.core.model.SavedAudio) = runState.addSavedAudio(s)

    fun addVideo(v: com.wangzan101.mika.core.chat.GeneratedVideo) = runState.addVideo(v)

    fun addSaved(s: com.wangzan101.mika.core.cap.image.SavedImage) = runState.addSaved(s)

    fun clearTakeoverStage() = runState.clearTakeoverStage()

    /** The state to show right now; null once torn down, so notifications are no longer redrawn. */
    fun shownState(): TaskState? = runState.shownState()

    /** On a model failure where the main model has not passed a test at its provider yet, suggest "test again" for that provider (see [RetestHint]). When testing swapped the main model, the configuration in settings is not consulted. */
    private fun withRetestHint(end: TaskState): TaskState {
        if (end !is TaskState.Failed || env.modelFactory !== TaskEnvironment.defaultModelFactory) return end
        return RetestHint.apply(end, modelSnapshot?.retestProvider) { tr(R.string.fail_model_untested, it) }
    }

    /** Teardown of execute; split into its own function to shrink execute's suspend state machine (an oversized suspend function is rejected by the ART verifier). */
    private suspend fun cleanup(c: RunAssembly) {
        withContext(NonCancellable) { manualControl.close() }
        manualTarget = null
        manualTargetNow = null
        activePhoneDisplay = null
        if (resultReadyAtMs == 0L) resultReadyAtMs = SystemClock.elapsedRealtime()
        handoffUnconsumed()
        c.side.cancel()
        runState._frames.value = null
        runState._phoneFrames.value = null
        runState._webOpen.value = false
        synchronized(runState.publishLock) { runState.ended = true }
        if (awake != null && service.hasQueuedRuns()) wakeHandoff = service.wakeLock.lease().also { it.acquire() }
        awake?.close()
        awake = null
        c.shellDeath?.let { d -> runCatching { c.shellBinder?.unlinkToDeath(d, 0) } }
        loop = null
        children = null
        // Close early: the teardown below waits on the screen and the web page; closing resources closes it once more (closed once only, no duplicates)
        mcpConnections?.close()
        mcpConnections = null
        c.mcpEvents?.close()
        c.liveFeed?.close()
        c.approval?.let { c.approvalControl.end(it) }
        gate = null
        takeover = null
        handoverRef = null
        sideScope = null
        // Return borrowed pages to the browser pages (before the web stack is shut down)
        withContext(Dispatchers.Main + NonCancellable) { runCatching { UserBrowser.existing()?.loans?.end(key) } }
        withContext(Dispatchers.IO + NonCancellable) {
            // Even if an earlier step threw, the screen and web display are still released (in reverse order of creation)
            try {
                // First decide the fate of the apps on Claire's screen (kept ones are moved behind the main screen, ones still playing sound are closed) and release the screen, then restore mute: what is kept does not suddenly make noise
                val apps = phone?.takeIf { !shellDied }?.let { p ->
                    p.display.current?.let { d -> AppsAfterTaskEnd.finish(p.shell, d.displayId, c.afterTask) }
                }
                c.record?.let { r ->
                    apps?.let { r.event(RunEvent.State(System.currentTimeMillis(), "apps_after_task", it.detail)) }
                    val cost = c.tally.deepSeekCost?.let { usage ->
                        val table = if (usage.hasUsage()) {
                            withTimeoutOrNull(2_000L) { deepSeekPrices.prices() }.also {
                                if (it == null) Log.d(TAG, "DeepSeek price table is temporarily unavailable")
                            }
                        } else null
                        usage.estimate(table)
                    }
                    r.event(c.tally.event(System.currentTimeMillis(), cost))
                    r.close()
                    if (BuildConfig.DEBUG) c.runStore?.mirrorToExternal(r.runId)
                }
                // Even if restoring throws, the "running" registration is removed, otherwise this record would never be handled by recovery again
                try { c.mute?.restoreAll() } finally { recovery.endRun(key) }
                c.watcher?.close()
            } finally {
                try { c.resources.close() } finally { c.smsLogin?.close() }
            }
        }
        phone = null
        // Clear after the screen is released and before the end is reported outward: by the time the UI sees the task ended there is no screen
        runState._displayId.value = -1
        runState._pipInput.value = null
        runState._pipSoundOn.value = false
        runState._appShown.value = false
        if (c.connection != null) phoneServices.sharedShell.release()
        // A relock obligation exists only if this run unlocked the phone; wait if other tasks are running, and lock again here once all are done (returns immediately when there is no obligation)
        withContext(Dispatchers.Main + NonCancellable) { service.finishTaskRelock(this@TaskRun) }
    }

    suspend fun execute(priorTurns: List<Turn>): TaskState {
        // Every start (UI, schedule or channel alike) re-reads the conversation it belongs to; the whole run then uses only this immutable route.
        val choice = withContext(Dispatchers.IO) {
            runCatching { ConversationStore.shared(service).settings(conversationId).main }.getOrNull()
        }
        val snapshot = env.modelSnapshot(service, session, choice).also { modelSnapshot = it }
        noticeTainted = NoticeTurn.tainted(priorTurns)
        phone = null
        val a = RunAssembly(
            traces = (service.application as MikaApp).detailedTraces,
            // The whole run shares one reported-token budget; subtasks inherit the one in the coroutine context and do not open an allowance of their own.
            tokenBudget = if (conditionCheck) null else FeatureFlags.get(service).taskTokenBudget(),
            showCost = FeatureFlags.get(service).showTaskCost(),
            approvalControl = (service.application as MikaApp).approvalControl,
            // Side coroutines during the task (assistant connection state, mute and move-away watchers, state publishing), all cancelled when the task ends
            side = CoroutineScope(
                scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) +
                    CoroutineExceptionHandler { _, e -> Log.w(TAG, "task side job failed", e) },
            ),
        )
        sideScope = a.side
        try {
            // If the user turned off keep-screen-on "while working", no wake lock is taken; when starting, take it according to the current state (preparing):
            // so the screen does not go off during the preparation and reasoning before the first step, such as opening models, reading knowledge, choosing routes and detecting intent
            awake = if (FeatureFlags.get(service).keepScreenOn()) KeepAwake(service.wakeLock.lease(), a.side).also { it.onState(runState._state.value) } else null
            // The lease the previous task held on this one's behalf is released as soon as its own is taken
            wakeHandoff?.release()
            wakeHandoff = null
            val m = assembleModels(snapshot) ?: return TaskState.Failed(tr(R.string.fail_no_main_model), FailKind.Environment)
            val ph = assemblePhone(a)
            val taskGate = assembleGate(ph)
            val rec = assembleRecord(a)
            val mem = assembleMemory(m, ph)
            // Without a WebView in the system there are no web tools
            val web = assembleWeb(a, ph)
            if (conditionCheck && web == null) return TaskState.Failed(tr(R.string.fail_no_web), FailKind.Environment)
            lendPage(a, m, ph, web)
            val pr = assemblePrompt(a, priorTurns)
            val t = assembleTools(a, m, ph, mem, rec, pr, web)
            val agent = assembleAgent(a, m, ph, taskGate, mem, rec, pr, web, t, priorTurns)
            assembleTakeover(a, agent, ph, t)
            val end = withContext(a.tally) { agent.run(pr.runSpec, inbox) }
            resultReadyAtMs = SystemClock.elapsedRealtime()
            return end.also { manualControl.awaitAutomatic(); usedGroups = agent.usedGroups.toList() }.let(::localizeTaskFailure).let(::withRetestHint)
        } finally {
            cleanup(a)
        }
    }

    @Volatile internal var handoverRef: Takeover? = null
    @Volatile private var sideScope: CoroutineScope? = null

    /** Needed after teardown to judge the outcome and to learn; filled in by [execute]. */
    @Volatile internal var kit: LearnKit? = null

    /** The text being written when it stopped (visible text already streamed by the model request); taken before teardown, since the state becomes the final one afterwards. */
    fun draftText(): String? = runState.draftText()

    /** Whether there is anything to report after stopping: at least one thing was done, or text had already been written when it stopped. */
    fun hasStopMaterial(draft: String?): Boolean = runState.hasStopMaterial(draft)

    /** The checkpoint summary after a real failure: assembled from the record only, without asking the model again, and without rewriting a short failure reason as success. */
    suspend fun checkpointSummary(): String? = runTeardown.checkpointSummary()

    /**
     * After the user stops: write a few lines summing up what this run has already done (see [StopSummary]), reading only the run record and operating nothing.
     * A lightweight model is preferred, falling back to the main model; with no model, on error, or on timeout it is assembled directly from the record. Null when there is nothing to report.
     */
    suspend fun stopSummary(draft: String?): String? = runTeardown.stopSummary(draft)

    /**
     * Called after the result is handed to the user (without slowing it down or changing the delivered answer): the answer's outcome is judged
     * against the user's request ([OutcomeJudge], lightweight model first) and recorded in the run record and this turn of the conversation; only what
     * succeeded ([LearnGate]) records a route, and marks the replayed route as successful. Abandoned, failed and cancelled runs are not judged (the
     * outcome is already clear) and return null; a failed judgment is [RunOutcome.Unknown].
     */
    suspend fun settleOutcome(end: TaskState, conversations: ConversationStore): JudgedOutcome? = runTeardown.settleOutcome(end, conversations)

    /**
     * Called after the result notification is sent: record the results of the personal skills that were used, extract memories, draft skills, and attach the hints
     * to this turn's result card and save them into the conversation. Runs in a process-level scope, so it is not interrupted when the service exits.
     */
    /** Whether this conversation had notification reminders when the task started (see [NoticeTurn]); [execute] determines it at the start from all stored turns. */
    @Volatile internal var noticeTainted = false

    suspend fun learnAfter(end: TaskState, outcome: RunOutcome?, conversations: ConversationStore) = runTeardown.learnAfter(end, outcome, conversations)

    private fun videoStageText(stage: com.wangzan101.mika.core.model.VideoJobStage): Int = when (stage) {
        com.wangzan101.mika.core.model.VideoJobStage.Submitted -> R.string.task_video_submitted
        com.wangzan101.mika.core.model.VideoJobStage.Queued -> R.string.task_video_queued
        com.wangzan101.mika.core.model.VideoJobStage.Running -> R.string.task_video_running
    }

    /** Write a background step's explanation to the task card (the current step's note); does nothing when not working (paused/ended). */
    private fun statusNote(text: String) {
        val s = state.value as? TaskState.Running ?: return
        publish(s.copy(note = text))
    }

    internal fun publish(state: TaskState) {
        // Between being moved away and the gate stopping it, the loop may still be reasoning: show it as paused outward first
        // A final state reported by the loop itself is not published: the final state is written only by [end] after the run ID is recorded, otherwise the UI would receive the end before it can get the ID
        if (!TaskService.loopMayPublish(state)) return
        val shown = when {
            state is TaskState.Running && manualControl.state.value in setOf(com.wangzan101.mika.core.run.ManualControl.State.Requesting, com.wangzan101.mika.core.run.ManualControl.State.Manual) ->
                TaskState.Held(TaskGate.paused(tr(R.string.gate_user_on_page)))
            state is TaskState.Running && gate?.movedAwayPending == true -> TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY))
            // The user is interacting with a borrowed page: show it as paused before the gate is even reached
            state is TaskState.Running && gate?.userOnPagePending == true -> TaskState.Held(TaskGate.paused(TaskGate.USER_ON_PAGE))
            else -> state
        }
        synchronized(runState.publishLock) {
            if (runState.ended) return
            runState.setState(shown)
            awake?.onState(state)
        }
        service.refreshNotifications(this)
    }

    /** The note at the top of the web-takeover UI: the reason for the takeover, plus the goods, amount and address when paying. */
    internal fun takeoverMessage(state: TaskState): String? = runControl.takeoverMessage(state)

    internal companion object {
        /** Interval for capturing frames for Picture-in-Picture while on the web UI. */
        const val WEB_FRAME_MS = 500L
        const val SHELL_WAIT_MS = 15_000L
        const val DISPLAY_RETRY_MS = 1_000L
        val WAITING_DISPLAY: String get() = tr(R.string.task_waiting_display)
        const val A11Y_WAIT_MS = 5_000L
        val NO_PHONE_HELPER: String get() = tr(R.string.task_no_phone_helper)
        val NO_PHONE_HELPER_USB: String get() = tr(R.string.task_no_phone_helper_usb)
        val NO_PHONE_HELPER_SHIZUKU: String get() = tr(R.string.task_no_phone_helper_shizuku)
        const val TAG = "MikaTask"
    }
}
