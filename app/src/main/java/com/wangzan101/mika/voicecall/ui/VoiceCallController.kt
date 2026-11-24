package com.wangzan101.mika.voicecall.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn as ChatTurn
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.browser.ui.findActivity
import com.wangzan101.mika.voice.cascade.CallUse
import com.wangzan101.mika.voice.cascade.CascadeEngine
import com.wangzan101.mika.voice.cascade.LlmBrain
import com.wangzan101.mika.voice.model.CallEngine
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voicecall.AndroidVoiceCallAudio
import com.wangzan101.mika.voicecall.EndReason
import com.wangzan101.mika.voicecall.VoiceCallAudioRoute
import com.wangzan101.mika.voicecall.VoiceCallFeed
import com.wangzan101.mika.voicecall.VoiceCallRoute
import com.wangzan101.mika.voicecall.VoiceCallSession
import com.wangzan101.mika.voicecall.VoiceCallState
import com.wangzan101.mika.voicecall.VoiceCallTaskLauncher
import com.wangzan101.mika.voicecall.VoiceCallTranscript
import com.wangzan101.mika.voicecall.typedResultText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the page side of a call holds: the session, the route, speakerphone routing, the transcript, the on-screen feed, and whether the page is currently visible. */
@Stable
class VoiceCallHolder(
    val session: VoiceCallSession,
    val route: VoiceCallRoute,
    val audioRoute: VoiceCallAudioRoute,
    val transcript: VoiceCallTranscript,
    val feed: VoiceCallFeed,
) {
    var visible by mutableStateOf(true)
}

/** Which stage the call page is at right now: gate (no call yet) or in a call. */
sealed interface VoiceCallStage {
    data class Gate(val stage: VoiceCallGateStage) : VoiceCallStage
    data class InCall(val call: VoiceCallHolder) : VoiceCallStage
}

/**
 * Assembly and state control for the call page. All production dependencies are injected ([deps]); a call starts from [start]:
 * missing credentials/permissions go to the gate first, and the session is only created once everything is in place. The assembly side never awaits the route in the session's event loop:
 * onVoiceRequest always does `scope.launch { route.execute(...) }`.
 */
@Stable
class VoiceCallController(
    private val deps: VoiceCallDeps,
    private val micGranted: () -> Boolean,
    private val micPermanentlyDenied: () -> Boolean,
    private val markMicDenied: () -> Unit,
) {
    var stage by mutableStateOf<VoiceCallStage?>(null)
        private set

    private var currentRealtime: CallRealtimeSession? = null
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
            current.call.visible = true
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

    /** Collapse: if the call is still alive just hide the page (the foreground service keeps it alive); a failure without retry settles into Ended; an already-ended call is torn down directly. */
    fun collapse() {
        when (val current = stage) {
            is VoiceCallStage.Gate -> stage = null
            is VoiceCallStage.InCall -> {
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
                    else -> current.call.visible = false
                }
            }
            null -> Unit
        }
    }

    /** The host is leaving: hang up any call that is not terminal; the transcript is finished writing in close under NonCancellable. */
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
        val originConversation = deps.conversationId()
        val transcript = VoiceCallTranscript(
            conversationId = { originConversation },
            appendTurn = deps.appendTurn,
        )
        val feed = VoiceCallFeed()
        // The route's callback needs to tell the session (a typed result has been displayed), and the session needs the route to dispatch requests: declare it first, create it below
        lateinit var session: VoiceCallSession
        val callScope = CoroutineScope(SupervisorJob(deps.scope.coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Default)
        this.callScope = callScope
        val route = VoiceCallRoute(
            launcher = deps.launcher,
            runState = deps.runState,
            sendResult = { callId, text ->
                if (callId.startsWith(VoiceCallSession.TYPED_CALL_PREFIX)) {
                    // For a typed request the model side has no function_call, so the result goes back locally: into the transcript (answer); the panel status lines have already been shown by the route itself
                    typedResultText(text).let {
                        transcript.onAssistant(it)
                        feed.onAssistant(it, isFinal = true)
                    }
                    session.onLocalReply()
                } else {
                    currentRealtime?.sendToolResult(callId, buildJsonObject {
                        put("status", "completed")
                        put("result", text)
                    })
                }
            },
            cancelTask = deps.cancelTask,
        )
        session = VoiceCallSession(
            scope = callScope,
            audio = AndroidVoiceCallAudio(deps.context),
            foregroundResumed = deps.foregroundResumed,
            connect = connect@{
                if (standard) {
                    return@connect try {
                        cascade.open(
                            CallUse.InApp,
                            VolcRealtimeProtocol.AGENT_TOOLS_PROMPT.trim(),
                            listOf(LlmBrain.toolSpec(VolcRealtimeProtocol.mikaRequestTool())),
                            language = null,
                        ).also { currentRealtime = it }
                    } catch (e: VoiceModelError) {
                        throw VolcRealtimeException(VolcRealtimeException.Kind.AUTH)
                    }
                }
                val profile = profile!!
                val credentials = activeCredentials(profile)
                    ?: throw VolcRealtimeException(VolcRealtimeException.Kind.AUTH)
                VolcRealtimeClient(endpoint = profile.endpoint).connect(
                    credentials,
                    VolcRealtimeConfig(
                        speaker = profile.speaker,
                        model = profile.model,
                        agentTools = true,
                        protocol = profile.protocol,
                    ),
                ).also { currentRealtime = it }
            },
            greeting = tr(R.string.voicecall_greeting),
            onVoiceRequest = { callId, text -> callScope.launch { route.execute(callId, text) } },
            onBargeIn = route::onBargeIn,
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
        // Task status into the feed: busy starting/stopping opens/closes the step card, status-line changes add steps
        callScope.launch { combine(route.busy, route.lines, ::Pair).collect { (busy, lines) -> feed.onTask(busy, lines) } }
        stage = VoiceCallStage.InCall(VoiceCallHolder(session, route, VoiceCallAudioRoute(deps.context), transcript, feed))
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
    val launcher: VoiceCallTaskLauncher,
    val runState: (String) -> StateFlow<TaskState>?,
    val cancelTask: (String) -> Unit,
    val conversationId: () -> String?,
    val appendTurn: suspend (String, ChatTurn) -> Unit,
    val attachService: (VoiceCallSession) -> Boolean,
)

/**
 * Host of the call page: opens (or brings back into view) the call as [openSignal] changes; the gate's three buttons, the permission result and
 * the re-check on returning to the foreground are all wired up. The call page itself does not care whether the foreground service exists — staying alive is the service's job, the page only watches the session state.
 */
@Composable
fun VoiceCallHost(deps: VoiceCallDeps, openSignal: Int, onGoConfigure: (VoiceCallGateStage) -> Unit) {
    val context = LocalContext.current
    val controller = remember(deps) {
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
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.onPermissionResult(granted)
    }
    LaunchedEffect(openSignal) { if (openSignal > 0) controller.start() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.onResume() }
    DisposableEffect(controller) { onDispose { controller.teardown() } }

    // Call state progression: once connected, take over speakerphone routing; at a terminal state restore the route and write the transcript back in a batch (NonCancellable inside close)
    val call = (controller.stage as? VoiceCallStage.InCall)?.call
    LaunchedEffect(call) {
        if (call == null) return@LaunchedEffect
        call.session.state.collect { state ->
            when (state) {
                is VoiceCallState.Active -> call.audioRoute.begin()
                is VoiceCallState.Failed -> call.audioRoute.end()
                is VoiceCallState.Ended -> {
                    call.audioRoute.end()
                    call.transcript.close()
                }
                else -> Unit
            }
        }
    }

    when (val stage = controller.stage) {
        is VoiceCallStage.Gate -> VoiceCallGate(
            stage.stage,
            onRequestPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onGoConfigure = {
                controller.collapse()
                onGoConfigure(stage.stage)
            },
            onCollapse = controller::collapse,
        )
        is VoiceCallStage.InCall -> if (stage.call.visible) {
            VoiceCallOverlay(stage.call, controller::collapse)
        }
        null -> Unit
    }
}
