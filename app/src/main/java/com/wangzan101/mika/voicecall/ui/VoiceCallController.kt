package com.wangzan101.mika.voicecall.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.calls.CallVoiceProtocol
import com.wangzan101.mika.calls.VolcRealtimeClient
import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.core.agent.Turn as ChatTurn
import com.wangzan101.mika.core.frontdesk.DeskPersona
import com.wangzan101.mika.core.frontdesk.DeskPrompt
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskToolSet
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.LlmBrain
import com.wangzan101.mika.core.frontdesk.opening
import com.wangzan101.mika.frontdesk.DeskRegistry
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.browser.ui.findActivity
import com.wangzan101.mika.voice.cascade.CallUse
import com.wangzan101.mika.voice.cascade.CascadeEngine
import com.wangzan101.mika.voice.model.CallEngine
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voicecall.AndroidVoiceCallAudio
import com.wangzan101.mika.voicecall.EndReason
import com.wangzan101.mika.voicecall.VoiceCallAudioRoute
import com.wangzan101.mika.voicecall.VoiceCallFeed
import com.wangzan101.mika.voicecall.ECHO_WINDOW_CAPACITY_CHARS
import com.wangzan101.mika.voicecall.RingBuffer
import com.wangzan101.mika.voicecall.VoiceCallRequest
import com.wangzan101.mika.voicecall.VoiceCallSession
import com.wangzan101.mika.voicecall.VOICECALL_IDLE_CUE
import com.wangzan101.mika.voicecall.VoiceCallState
import com.wangzan101.mika.voicecall.VoiceCallTranscript
import com.wangzan101.mika.voicecall.VoiceDeskBridge
import com.wangzan101.mika.voicecall.deskReportSpeech
import com.wangzan101.mika.voicecall.typedDeskResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import android.graphics.Bitmap
import com.wangzan101.mika.voicecall.VoiceCallTaskLink
import com.wangzan101.mika.voicecall.VoiceCallConversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * The page-side holder of one call: the session, whether the front desk has tasks running ([busy]), speakerphone, transcript, the on-screen conversation feed, and whether the page is showing right now;
 * [screens] are the task conversations that currently have a virtual screen (or web page), which drives the "View screen" on task cards; [frames] supplies the live picture of that screen per task.
 */
@Stable
class VoiceCallHolder(
    val session: VoiceCallSession,
    val busy: StateFlow<Boolean>,
    val audioRoute: VoiceCallAudioRoute,
    val transcript: VoiceCallTranscript,
    val feed: VoiceCallFeed,
    val screens: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    val frames: (String) -> StateFlow<Bitmap?>? = { null },
    /** The conversation this call is attached to: call records and jumping back to chat both go by it. */
    val conversationId: String = "",
) {
    /** This call's id (the one in the call record). */
    val callId: String get() = transcript.callId

    /** Which task's screen is being watched on the call page (the task conversation id); collapsing and re-expanding keeps watching it. */
    var watching by mutableStateOf<String?>(null)
}

/** Which stage the call page is at right now: gate (no call yet) or in a call. */
sealed interface VoiceCallStage {
    data class Gate(val stage: VoiceCallGateStage) : VoiceCallStage
    data class InCall(val call: VoiceCallHolder) : VoiceCallStage
}

/**
 * Wiring and state control for the call page. All production dependencies are injected ([deps]); a call starts at [start]:
 * missing credentials or permissions lead to the gate first, and the session is only created once they are in place. The call attaches to this conversation's front desk: model-called front desk tools are executed by [VoiceDeskBridge],
 * always launched in the call's coroutine scope rather than awaited in the session's event loop; background task reports are handed back by the front desk for the call to speak.
 */
@Stable
class VoiceCallController(
    internal val deps: VoiceCallDeps,
    private val micGranted: () -> Boolean,
    private val micPermanentlyDenied: () -> Boolean,
    private val markMicDenied: () -> Unit,
) {
    var stage by mutableStateOf<VoiceCallStage?>(null)
        private set

    /** Bring the call page to the top (pop back to it if it is in the page stack, otherwise push it); the host wires navigation. */
    var showPage: () -> Unit = {}
    /** The call page leaves the page stack (pops one page if it is on top); the host wires navigation. */
    var hidePage: () -> Unit = {}

    /** There is a call that has not ended yet (in progress, connecting, or failed waiting to retry all count): other pages show "In call · Back to call" at the top. */
    val live: VoiceCallHolder?
        get() = (stage as? VoiceCallStage.InCall)?.call?.takeIf { it.session.state.value !is VoiceCallState.Ended }

    @Volatile private var currentRealtime: CallRealtimeSession? = null
    private var callScope: CoroutineScope? = null

    /** After migration both entry points resolve the active model through this one controller. */
    private val realtime = VoiceModelEnvironment.get(deps.context).realtime
    private val cascade = CascadeEngine(deps.context)

    /** Standard calls (listen → think → speak) need no realtime model; their gate is the cascade's readiness. */
    private fun standard(): Boolean = realtime.migrated && cascade.engine(CallUse.InApp) == CallEngine.Standard

    /** The active realtime profile; legacy reads the profile store, migrated reads the catalog. */
    private fun activeProfile(): CallVoiceProfile? {
        if (!realtime.migrated) return deps.profile()
        val ref = realtime.current() ?: return null
        return runCatching { realtime.profile(ref) }.getOrNull()
    }

    private fun activeCredentials(profile: CallVoiceProfile): VolcRealtimeCredentials? =
        if (realtime.migrated) runCatching { realtime.credentials() }.getOrNull()
        else deps.credentials(profile.id)

    /** Tear down the current call's coroutine scope (when swapping in a new call, collapsing a terminal call, or when the host leaves); the session is already terminal itself or the caller sets it terminal first. */
    private fun cancelCallScope() {
        callScope?.cancel()
        callScope = null
        currentRealtime = null
    }

    /** Entry points (earpiece button / capsule menu / direct-open extra): a live call is just brought back into view, a terminal one gets a new call. */
    fun start() {
        val current = stage
        if (current is VoiceCallStage.InCall && current.call.session.state.value !is VoiceCallState.Ended) {
            showPage()
            return
        }
        if (standard()) {
            if (cascade.blocker(CallUse.InApp) != null) {
                stage = VoiceCallStage.Gate(VoiceCallGateStage.NeedListenSpeak)
                return
            }
            when {
                micGranted() -> openCall()
                micPermanentlyDenied() -> stage = VoiceCallStage.Gate(VoiceCallGateStage.PermissionDenied)
                else -> stage = VoiceCallStage.Gate(VoiceCallGateStage.NeedPermission)
            }
            return
        }
        val profile = activeProfile()
        if (profile == null) {
            stage = VoiceCallStage.Gate(VoiceCallGateStage.NeedCredentials)
            return
        }
        // In-app calls only use the bidirectional real-time voice protocol: other protocols cannot get in, and it does not fail silently — the gate page explains and points to the configuration
        if (profile.protocol != CallVoiceProtocol.VOLC_DUPLEX) {
            stage = VoiceCallStage.Gate(VoiceCallGateStage.UnsupportedProtocol)
            return
        }
        if (!profile.credentialsConfigured || activeCredentials(profile) == null) {
            stage = VoiceCallStage.Gate(VoiceCallGateStage.NeedCredentials)
            return
        }
        when {
            micGranted() -> openCall()
            micPermanentlyDenied() -> stage = VoiceCallStage.Gate(VoiceCallGateStage.PermissionDenied)
            else -> stage = VoiceCallStage.Gate(VoiceCallGateStage.NeedPermission)
        }
    }

    /** Result of the system permission dialog: if granted, open straight away; if not granted and it will not ask again, switch to "go to settings". */
    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            openCall()
        } else {
            markMicDenied()
            stage = VoiceCallStage.Gate(
                if (micPermanentlyDenied()) VoiceCallGateStage.PermissionDenied else VoiceCallGateStage.NeedPermission,
            )
        }
    }

    /** Re-check when returning to the foreground: if the permission was granted in settings, open straight away. */
    fun onResume() {
        val current = stage
        if (current is VoiceCallStage.Gate && (current.stage == VoiceCallGateStage.NeedPermission || current.stage == VoiceCallGateStage.PermissionDenied) && micGranted()) openCall()
    }

    /** Collapse: the call page exits the page stack. If the call is still alive it keeps going (the foreground service keeps it alive); a failure that will not retry ends as Ended; an already-ended one is torn down. */
    fun collapse() {
        when (val current = stage) {
            is VoiceCallStage.Gate -> stage = null
            is VoiceCallStage.InCall -> {
                hidePage()
                when (current.call.session.state.value) {
                    is VoiceCallState.Ended -> {
                        stage = null
                        cancelCallScope()
                    }
                    is VoiceCallState.Failed -> {
                        // Failure without retry and collapsed straight away: settle into a terminal state and tear down the page as well (end alone without removing the page would make the user tap again)
                        current.call.session.end(EndReason.Hangup)
                        stage = null
                        cancelCallScope()
                    }
                    else -> Unit
                }
            }
            null -> Unit
        }
    }

    /**
     * A task card on the call page was tapped: the call keeps going and that task's "run details" opens on top of the call page, returning from there to the call page;
     * when it has not started (queued) or no run can be found, fall back to opening that task's conversation.
     */
    fun openTask(taskId: String, phase: DeskTaskPhase) {
        val run = VoiceCallTaskLink.runFor(phase, deps.liveRun(taskId)) { deps.turns(taskId) }
        if (run == null) deps.openConversation(taskId) else deps.openRun(run)
    }

    /** The host is going away: hang up a call that has not reached a terminal state, and the transcript is written in close under NonCancellable. */
    fun teardown() {
        val current = stage as? VoiceCallStage.InCall ?: return
        if (current.call.session.state.value !is VoiceCallState.Ended) current.call.session.end(EndReason.Hangup)
        stage = null
        cancelCallScope()
        // Once the page is destroyed state collection is gone too: do the write-back here as a fallback. Wait for the session's wind-down first (so the last transcript segment lands in the aggregation first),
        // close is idempotent and NonCancellable, so it does not write twice and still completes even if the scope is cancelled.
        val session = current.call.session
        val transcript = current.call.transcript
        // It must hang on a scope that outlives the page: teardown only fires when the composition is destroyed, at which point deps.scope is being cancelled,
        // so a body launched there would never get to run and join would also drop close because the parent scope was cancelled
        deps.appScope.launch {
            session.awaitFinished()
            transcript.close()
        }
    }

    private fun openCall() {
        cancelCallScope()
        val standard = standard()
        val profile = if (standard) null else activeProfile()
        if (!standard && (profile == null || profile.protocol != CallVoiceProtocol.VOLC_DUPLEX)) {
            stage = VoiceCallStage.Gate(VoiceCallGateStage.UnsupportedProtocol)
            return
        }
        // The call attaches to the conversation that started it (reattaching to its front desk conversation when stopped in a task conversation); with no conversation it reserves one, and the task and transcript hang under it.
        val conversation = VoiceCallConversation.pick(deps.conversationId(), deps.deskParent, deps.newConversation)
        // Save as you go: each completed turn is written into the conversation (on the app-level scope, so it is written even after the call page is gone)
        val transcript = VoiceCallTranscript(
            conversationId = { conversation },
            appendTurn = deps.appendTurn,
            writeScope = deps.appScope,
            recordCall = deps.recordCall,
        )
        val desk = deps.desks.desk(conversation)
        // The call page always starts from blank and holds only this call; the whole conversation is on the chat page
        val feed = VoiceCallFeed()
        val echoWindow = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(echoWindow)
        val executor = deps.desks.executor(conversation, gate)
        val bridge = VoiceDeskBridge(desk, executor, { currentRealtime }, ::deskReportSpeech, onSpoken = echoWindow::append)
        // The session's callbacks need to tell the session (the typed result has been displayed): declare it first, build it below
        lateinit var session: VoiceCallSession
        val idleMinutes = VoiceModelEnvironment.get(deps.context).catalog.state.value.selections.idleMinutes
        val callScope = CoroutineScope(SupervisorJob(deps.scope.coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Default)
        this.callScope = callScope
        session = VoiceCallSession(
            scope = callScope,
            audio = AndroidVoiceCallAudio(deps.context),
            foregroundResumed = deps.foregroundResumed,
            connect = connect@{
                if (standard) {
                    return@connect try {
                        cascade.open(
                            CallUse.InApp,
                            DeskPrompt.user(spoken = true, persona = deps.persona()),
                            DeskTools.specs(DeskToolSet.User).map(LlmBrain::toolSpec),
                            language = null,
                            prior = deps.turns(conversation),
                            background = deps.desks.background(conversation),
                            meter = deps.meter(conversation),
                        ).also { currentRealtime = it }
                    } catch (e: VoiceModelError) {
                        throw VolcRealtimeException(VolcRealtimeException.Kind.AUTH)
                    }
                }
                val profile = profile!!
                val credentials = activeCredentials(profile)
                    ?: throw VolcRealtimeException(VolcRealtimeException.Kind.AUTH)
                // Realtime models get no per-turn background note: the task board and related memories are put into the opening description (memories are fetched once from this conversation's latest user utterance),
                // and later changes are read to it by front desk reports.
                val board = deps.desks.background(conversation).opening(deps.turns(conversation)).orEmpty().take(MAX_OPENING_BOARD_CHARS)
                VolcRealtimeClient(endpoint = profile.endpoint).connect(
                    credentials,
                    VolcRealtimeConfig(
                        systemPrompt = board,
                        speaker = profile.speaker,
                        model = profile.model,
                        agentTools = true,
                        protocol = profile.protocol,
                        deskPersona = deps.persona(),
                    ),
                ).also { currentRealtime = it }
            },
            greeting = tr(R.string.voicecall_greeting),
            echoWindow = echoWindow,
            // How long without new activity before hanging up: the minutes set in "Call mode", where 0 means no automatic hangup
            idleMs = idleMinutes.takeIf { it > 0 }?.let { it * 60_000L },
            idleCue = VOICECALL_IDLE_CUE,
            idleLine = tr(R.string.voicecall_idle_line),
            requestGate = gate,
            onDeskCall = { callId, name, args ->
                callScope.launch {
                    val result = bridge.onFunctionCall(callId, name, args)
                    startedTask(result)?.let(transcript::onTask)
                }
            },
            onTypedText = { text ->
                callScope.launch {
                    // Realtime sessions take no text: the typed text is dispatched as-is as a task, and the result is said locally
                    val result = bridge.dispatchTyped(text)
                    startedTask(result)?.let(transcript::onTask)
                    typedDeskResult(result).let {
                        transcript.onAssistant(it)
                        feed.onAssistant(it, isFinal = true)
                    }
                    session.onLocalReply()
                }
            },
            onUserTranscript = { text, isFinal ->
                feed.onUser(text, isFinal)
                if (isFinal) transcript.onUser(text)
            },
            onAssistantTranscript = { text, isFinal ->
                feed.onAssistant(text, isFinal)
                if (isFinal) transcript.onAssistant(text)
            },
            onAssistantDraft = feed::onAssistantDraft,
        )
        // When there is already an active session the service rejects a second one: destroy the new session immediately so it does not take the audio
        val attached = runCatching { deps.attachService(session) }.getOrElse {
            session.end(EndReason.Hangup)
            return
        }
        if (!attached) {
            session.end(EndReason.Hangup)
            return
        }
        // Attach the front desk only while the call is alive; release once it ends (even if the page stays hidden) so the chat page can attach it back, and later reports no longer go to the fallback
        callScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bridge.attachWhileLive(session.state)
            // Do not touch the session if it has already switched to the next call by now
            if (this@VoiceCallController.callScope === callScope) currentRealtime = null
        }
        // The front desk's task board goes into the conversation feed: one card per task, with progress adding steps; task progress also counts as new activity in the call, restarting the idle timer
        callScope.launch {
            desk.active.collect {
                feed.onTasks(it)
                session.onActivity()
            }
        }
        val busy = desk.active
            .map { views -> views.any { it.state == DeskTaskPhase.Running || it.state == DeskTaskPhase.Queued } }
            .stateIn(callScope, SharingStarted.Eagerly, false)
        stage = VoiceCallStage.InCall(
            VoiceCallHolder(session, busy, VoiceCallAudioRoute(deps.context), transcript, feed, deps.screens, deps.frames, conversation),
        )
        showPage()
    }

    /** The id of the task that started or was queued from a dispatch result; null for any other result. */
    private fun startedTask(result: kotlinx.serialization.json.JsonObject): String? {
        val status = (result["status"] as? JsonPrimitive)?.content
        if (status != "started" && status != "queued") return null
        return (result["task_id"] as? JsonPrimitive)?.content
    }

    private companion object {
        /** The maximum number of characters for the task board and memories in the realtime session's opening description (the description as a whole has a cap). */
        const val MAX_OPENING_BOARD_CHARS = 4_000
    }
}

/** Production dependencies of the call page; all functions, filled by the host (the layer holding ChatScreen) from the app container. */
class VoiceCallDeps(
    val context: android.content.Context,
    val scope: CoroutineScope,
    /** App-level scope (outlives the page): used for wind-up work that must still complete after the page is destroyed. */
    val appScope: CoroutineScope,
    val profile: () -> CallVoiceProfile,
    val credentials: (String) -> VolcRealtimeCredentials?,
    val foregroundResumed: StateFlow<Boolean>,
    /** Each conversation's front desk: the call attaches to the one of the conversation that started it. */
    val desks: DeskRegistry,
    val conversationId: () -> String?,
    /** When no conversation started it (the chat page is a blank new conversation, opened straight from the capsule), reserve a conversation that the chat page also stays on; returns its id. */
    val newConversation: () -> String,
    /** Which front desk conversation a task conversation dispatched by the front desk belongs to; null when it is not a task conversation. */
    val deskParent: (String) -> String? = { null },
    /** The turns a conversation already has, used in standard mode to give the thinking model the preceding context at the opening. */
    val turns: (String) -> List<ChatTurn>,
    val appendTurn: suspend (String, ChatTurn) -> Unit,
    /** Record a call for the conversation (one on connect, completed with the end on hangup). */
    val recordCall: suspend (String, com.wangzan101.mika.chat.ConversationCall) -> Unit = { _, _ -> },
    val attachService: (VoiceCallSession) -> Boolean,
    /** The persona the front desk is using right now (form of address and persona layer), read once on each connect. */
    val persona: () -> DeskPersona = { DeskPersona() },
    /** Open a conversation (when a task card is tapped and that task has no run to view, enter its conversation). */
    val openConversation: (String) -> Unit = {},
    /** Open one run's "run details" on top of the call page; returning from that page goes back to the call page. */
    val openRun: (runId: String) -> Unit = {},
    /** A conversation's call records ([com.wangzan101.mika.chat.ConversationCall]), updated whenever one is written. */
    val calls: (String) -> kotlinx.coroutines.flow.Flow<List<com.wangzan101.mika.chat.ConversationCall>> = { kotlinx.coroutines.flow.flowOf(emptyList()) },
    /** Go to the start of that call in that conversation on the chat page (tapping an entry in call history). */
    val openCallRecord: (conversationId: String, callId: String) -> Unit = { _, _ -> },
    /** The run this task (conversation id) is executing right now; null when it is not running. */
    val liveRun: (String) -> String? = { null },
    /** Task conversations that currently have a virtual screen or web page. */
    val screens: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    /** The live picture of that task's (conversation id's) screen; null when nothing is running. */
    val frames: (String) -> StateFlow<Bitmap?>? = { null },
    /** Conversation usage meter; standard-mode calls are recorded against this conversation. */
    val meter: (String) -> com.wangzan101.mika.core.model.usage.ConversationMeter? = { null },
)

/** The call controller: shared by the host ([VoiceCallHost]) and the call page ([VoiceCallScreen]), living outside navigation. */
@Composable
fun rememberVoiceCallController(deps: VoiceCallDeps): VoiceCallController {
    val context = LocalContext.current
    return remember(deps) {
        VoiceCallController(
            deps,
            micGranted = {
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
            micPermanentlyDenied = {
                val activity = context.findActivity()
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
                    context.getSharedPreferences("voice_call", 0).getBoolean("mic_denied", false) &&
                    activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            },
            markMicDenied = {
                context.getSharedPreferences("voice_call", 0).edit().putBoolean("mic_denied", true).apply()
            },
        )
    }
}

/**
 * The call host (outside navigation and always present): opens (or returns to) the call as [openSignal] changes; the gate's three buttons, permission results,
 * and re-checking on returning to the foreground are all wired up. The call page itself is one page in the page stack ([VoiceCallScreen]), with [showPage]/[hidePage] wired to navigation.
 * The host does not care whether the foreground service is present -- staying alive is the service's business; it only looks at the session state.
 */
@Composable
fun VoiceCallHost(
    controller: VoiceCallController,
    openSignal: Int,
    onGoConfigure: (VoiceCallGateStage) -> Unit,
    showPage: () -> Unit,
    hidePage: () -> Unit,
) {
    val context = LocalContext.current
    SideEffect {
        controller.showPage = showPage
        controller.hidePage = hidePage
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.onPermissionResult(granted)
    }
    LaunchedEffect(openSignal) { if (openSignal > 0) controller.start() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.onResume() }
    DisposableEffect(controller) { onDispose { controller.teardown() } }

    // Call state progression: on connect take over the speakerphone route and record this call; in a terminal state restore the route and write back the transcript and call record (NonCancellable is inside close)
    val call = (controller.stage as? VoiceCallStage.InCall)?.call
    LaunchedEffect(call) {
        if (call == null) return@LaunchedEffect
        call.session.state.collect { state ->
            when (state) {
                is VoiceCallState.Active -> {
                    call.audioRoute.begin()
                    call.transcript.onConnected(state.startedAt)
                }
                is VoiceCallState.Failed -> call.audioRoute.end()
                is VoiceCallState.Ended -> {
                    call.audioRoute.end()
                    call.transcript.close(state.reason.wire)
                }
                else -> Unit
            }
        }
    }

    val gate = controller.stage as? VoiceCallStage.Gate ?: return
    VoiceCallGate(
        gate.stage,
        onRequestPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) },
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        onGoConfigure = {
            controller.collapse()
            onGoConfigure(gate.stage)
        },
        onCollapse = controller::collapse,
    )
}

/**
 * The call page in the page stack: holds only this call. The back key and the top-left collapse both pop one page (the call keeps going, and other pages show the "In call" bar at the top);
 * tapping a task card opens "run details" on top, and back returns here. When there is no call to show (process recreation, or the call was already torn down) this page exits directly, with [onGone] wired to navigation.
 */
@Composable
fun VoiceCallScreen(controller: VoiceCallController, onGone: () -> Unit) {
    val call = (controller.stage as? VoiceCallStage.InCall)?.call
    if (call == null) {
        LaunchedEffect(Unit) { onGone() }
        return
    }
    BackHandler { controller.collapse() }
    val deps = controller.deps
    val calls by remember(call) { deps.calls(call.conversationId) }.collectAsState(emptyList())
    VoiceCallPage(
        call,
        onCollapse = controller::collapse,
        onOpenTask = { controller.openTask(it.taskId, it.phase) },
        calls = calls,
        onOpenRecord = { deps.openCallRecord(call.conversationId, it) },
    )
}
