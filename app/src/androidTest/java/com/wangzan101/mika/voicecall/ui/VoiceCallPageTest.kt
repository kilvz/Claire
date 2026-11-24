package com.wangzan101.mika.voicecall.ui

import androidx.compose.ui.test.assertCountEquals
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voicecall.EndReason
import com.wangzan101.mika.voicecall.VoiceCallAudio
import com.wangzan101.mika.voicecall.VoiceCallAudioRoute
import com.wangzan101.mika.voicecall.VoiceCallError
import com.wangzan101.mika.voicecall.VoiceCallFeed
import com.wangzan101.mika.voicecall.VoiceCallSession
import com.wangzan101.mika.voicecall.VoiceCallState
import com.wangzan101.mika.voicecall.VoiceCallTranscript
import com.wangzan101.mika.voicecall.ECHO_WINDOW_CAPACITY_CHARS
import com.wangzan101.mika.voicecall.RingBuffer
import com.wangzan101.mika.voicecall.VoiceCallRequest
import com.wangzan101.mika.voicecall.VoiceDeskBridge
import com.wangzan101.mika.voicecall.typedDeskResult
import com.wangzan101.mika.core.frontdesk.DeskLedger
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSink
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskPort
import com.wangzan101.mika.core.frontdesk.DeskToolExecutor
import com.wangzan101.mika.core.frontdesk.DispatchOutcome
import com.wangzan101.mika.core.frontdesk.FrontDesk
import com.wangzan101.mika.core.frontdesk.ReportPolicy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Call screen smoke test: fake audio + injected fake realtime session (following the hand-written fake pattern in VoiceHoldTest), no real microphone or real service.
 * Chain: connect → listen (waveform) → playback animation → barge-in → hang up → retry → time limit; permission denied → go to settings; missing credentials → go to configuration.
 */
@RunWith(AndroidJUnit4::class)
class VoiceCallPageTest {

    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.cancel()
    }

    /** Fake audio device: every channel succeeds, capture chunks are fed by the test, and it records barge-in/start-stop counts. */
    private class FakeAudio : VoiceCallAudio {
        override val micSilenced = MutableStateFlow(false)
        private val capture = Channel<ShortArray>(Channel.UNLIMITED)
        override val captureChunks: ReceiveChannel<ShortArray> get() = capture
        @Volatile override var onFocusLost: (() -> Unit)? = null
        @Volatile var interruptCount = 0
        @Volatile var focusAbandons = 0

        fun offerChunk(amplitude: Short = 500) {
            check(capture.trySend(ShortArray(320) { amplitude }).isSuccess)
        }

        override fun isTelephonyBusy() = false
        override fun requestCallAudio() = true
        override fun abandonCallAudio() { focusAbandons++ }
        override fun startCapture(inputSource: Int) = true
        override fun stopCapture() {}
        override fun startPlayback() = true
        override suspend fun writePlayback(pcm: ByteArray, byteCount: Int) {}
        override fun interruptPlayback() { interruptCount++ }
        override fun stopPlayback() {}
    }

    /** Fake realtime session: events are fed by the test; tool results are recorded. */
    private class FakeRealtime : CallRealtimeSession {
        private val incoming = Channel<VolcRealtimeEvent>(Channel.UNLIMITED)
        override val events: Flow<VolcRealtimeEvent> = flow { for (event in incoming) emit(event) }
        val toolResults = mutableListOf<Pair<String, JsonObject>>()

        fun emit(event: VolcRealtimeEvent) { check(incoming.trySend(event).isSuccess) }
        override fun sendPcm16(bytes: ByteArray, byteCount: Int) = true
        override fun sendGreeting(text: String) = true
        override fun setMuted(muted: Boolean) = true
        override fun sendToolResult(callId: String, result: JsonObject): Boolean {
            toolResults += callId to result
            return true
        }
        override fun close() {}
    }

    /** Fake background: the Nth dispatch opens tN, whose state comes from the same-named flow in [flows]; records cancellations. */
    private class FakePort(private val flows: Map<String, MutableStateFlow<TaskState>>) : DeskTaskPort {
        private var count = 0
        val cancelled = mutableListOf<String>()
        @Synchronized
        override fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome =
            DispatchOutcome.Started("t${++count}")
        override fun state(taskId: String): StateFlow<TaskState>? = flows[taskId]
        override fun queuePosition(taskId: String): Int? = null
        override fun steer(taskId: String, words: String) = true
        override fun answer(taskId: String, words: String) = true
        override fun cancel(taskId: String): Boolean { cancelled += taskId; return true }
    }

    /**
     * A fake call: a real session over fake devices; [connect] decides whether connecting succeeds (repeatable, for retries).
     * The front desk, bridge and conversation feed are wired as in production (with the background replaced by [FakePort]).
     * The session starts connecting as soon as it is built, before the test's variables are assigned: [connect] receives this
     * call as its receiver and connects through its own [rt] rather than an outer variable.
     */
    private inner class Call(
        flows: Map<String, MutableStateFlow<TaskState>> = emptyMap(),
        connect: suspend Call.(Int) -> CallRealtimeSession,
        idleMs: Long? = 600_000L,
    ) {
        val audio = FakeAudio()
        val rt = FakeRealtime()
        val feed = VoiceCallFeed()
        val port = FakePort(flows)
        val transcript = VoiceCallTranscript(
            conversationId = { null },
            appendTurn = { _, _ -> },
        )
        private val ledger = DeskLedger()
        val desk = FrontDesk("conv-1", scope, port, ledger, ReportPolicy(System::currentTimeMillis), object : DeskSink {
            override suspend fun record(conversationId: String, report: DeskReport) {}
        })
        private val echoWindow = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        private val gate = VoiceCallRequest(echoWindow)
        private val executor = DeskToolExecutor(port, ledger, gate, System::currentTimeMillis, "conv-1") {
            desk.track(it.taskId, it.title)
        }
        private val bridge = VoiceDeskBridge(desk, executor, { rt }, { it.title }, echoWindow::append)
        val busy = desk.active.map { views -> views.any { it.state == DeskTaskPhase.Running || it.state == DeskTaskPhase.Queued } }
            .stateIn(scope, SharingStarted.Eagerly, false)
        lateinit var session: VoiceCallSession

        init {
            session = VoiceCallSession(
                scope = scope,
                audio = audio,
                foregroundResumed = MutableStateFlow(true),
                connect = { attempt -> connect(attempt) },
                greeting = "你好",
                echoWindow = echoWindow,
                requestGate = gate,
                onDeskCall = { callId, name, args -> scope.launch { bridge.onFunctionCall(callId, name, args) } },
                onTypedText = { text ->
                    scope.launch {
                        typedDeskResult(bridge.dispatchTyped(text)).let {
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
                idleMs = idleMs,
            )
            bridge.attach()
            scope.launch { desk.active.collect(feed::onTasks) }
        }
    }

    /**
     * Draw with animation off: with the dot-matrix ball animating, every frame requests the next one, so UI tests never
     * reach idle (ComposeNotIdle); with it off the ball is drawn statically by shape and level, and the shape testTag stays the same.
     */
    @androidx.compose.runtime.Composable
    private fun StillTheme(content: @androidx.compose.runtime.Composable () -> Unit) = MikaTheme(reduceMotion = true, content = content)

    private fun show(
        call: Call,
        onCollapse: () -> Unit = {},
        screens: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
        frames: (String) -> StateFlow<android.graphics.Bitmap?>? = { null },
        onOpenTask: (String) -> Unit = {},
        calls: List<com.wangzan101.mika.chat.ConversationCall> = emptyList(),
        onOpenRecord: (String) -> Unit = {},
    ) {
        val audioRoute = VoiceCallAudioRoute(InstrumentationRegistry.getInstrumentation().targetContext)
        val holder = VoiceCallHolder(call.session, call.busy, audioRoute, call.transcript, call.feed, screens, frames)
        compose.setContent {
            StillTheme {
                VoiceCallPage(holder, onCollapse, { onOpenTask(it.taskId) }, calls, onOpenRecord)
            }
        }
    }

    private fun awaitState(session: VoiceCallSession, timeoutMs: Long = 5_000, pred: (VoiceCallState) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!pred(session.state.value) && SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
        assertTrue("state is ${session.state.value}", pred(session.state.value))
    }

    private fun awaitValue(timeoutMs: Long = 5_000, pred: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!pred() && SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
        assertTrue(pred())
    }

    /** Wait for a text node to appear (the conversation feed updates asynchronously through the front desk → feed, so asserting directly can miss it). */
    private fun awaitText(text: String, timeoutMs: Long = 5_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The ball's shape is read from its testTag (the dot-matrix ball no longer reads the English enum name to TalkBack). */
    private fun ballMode(): String? {
        for (mode in listOf("Web", "Globe", "Wave", "Orbits")) {
            if (compose.onAllNodesWithTag("voicecall-ball-" + mode).fetchSemanticsNodes().isNotEmpty()) return mode
        }
        return null
    }

    private fun awaitBall(mode: String) {
        compose.waitUntil(5_000) { ballMode() == mode }
        assertEquals(mode, ballMode())
    }

    /** Connected: feed one quiet first chunk, then wait for Active. */
    private fun reachActive(call: Call) {
        call.audio.offerChunk(amplitude = 100)
        awaitState(call.session) { it is VoiceCallState.Active }
    }

    private fun pcm(sample: Short, bytes: Int): ByteArray {
        val out = ByteArray(bytes)
        for (i in 0 until bytes / 2) {
            out[2 * i] = (sample.toInt() and 0xFF).toByte()
            out[2 * i + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
        }
        return out
    }

    // ---------- connect → listen → playback animation → barge-in → hang up → retry → time limit ----------

    @Test fun connectingShowsStatusAndWebBallThenActiveShowsClockAndGlobe() {
        val gate = kotlinx.coroutines.CompletableDeferred<CallRealtimeSession>()
        val call = Call(connect = { gate.await() })
        show(call)
        // Connecting: status word + Web-shaped ball; the timer has not started yet
        compose.onNodeWithText("正在连接…").assertIsDisplayed()
        awaitBall("Web")

        // Service connected: check the microphone → connected
        gate.complete(call.rt)
        reachActive(call)
        compose.onNodeWithText("在听你说").assertIsDisplayed()
        // The timer lives in the hang-up button and is merged into the button semantics, so search the unmerged tree
        compose.onNodeWithTag("voicecall-clock", useUnmergedTree = true).assertTextEquals("0:00")
        awaitBall("Globe")
        call.session.end(EndReason.Hangup)
    }

    @Test fun micLevelSwitchesBallToWave() {
        val call = Call(connect = { rt })
        show(call)
        reachActive(call)
        awaitBall("Globe")
        // The user speaks: loud capture chunks come in and the ball switches to Wave
        repeat(3) { call.audio.offerChunk(amplitude = 4_000) }
        awaitBall("Wave")
        call.session.end(EndReason.Hangup)
    }

    @Test fun cloudAudioDrivesSpeakerLevelAndWave() {
        val call = Call(connect = { rt })
        show(call)
        reachActive(call)
        awaitBall("Globe")
        // Assistant speaking: cloud audio enters the playback queue, the level rises and the ball switches to Wave
        call.rt.emit(VolcRealtimeEvent.Audio(pcm(4_000, 4_800)))
        awaitBall("Wave")
        call.session.end(EndReason.Hangup)
    }

    @Test fun workingTaskShowsTaskCardAndStatusLines() {
        // The progress on the task card is the line the task shows the user (narration), not the step note
        val flow = MutableStateFlow<TaskState>(TaskState.Running(1, "open_app", narration = "正在打开设置"))
        val call = Call(mapOf("t1" to flow), connect = { rt })
        show(call)
        reachActive(call)
        // Typing to work: tap the keyboard to expand the input row, sending dispatches straight away and the task card appears
        compose.onNodeWithTag("voicecall-keyboard").performClick()
        compose.onNodeWithTag("voicecall-input").performTextInput("帮我开深色模式")
        compose.onNodeWithTag("voicecall-send").performClick()
        awaitText("正在打开设置")
        // Done: the card wraps up and is marked "Completed"
        flow.value = TaskState.Succeeded("开好了")
        awaitText("已完成")
        compose.onNodeWithTag("voicecall-input").assertTextEquals("")
        call.session.end(EndReason.Hangup)
    }

    @Test fun tappingATaskCardOpensItAndViewScreenShowsTheScreenOnTheCallPage() {
        val flow = MutableStateFlow<TaskState>(TaskState.Running(1, "open_app", narration = "正在打开设置"))
        val call = Call(mapOf("t1" to flow), connect = { rt })
        val screens = MutableStateFlow<Set<String>>(emptySet())
        val frame = MutableStateFlow<android.graphics.Bitmap?>(android.graphics.Bitmap.createBitmap(90, 160, android.graphics.Bitmap.Config.ARGB_8888))
        val opened = mutableListOf<String>()
        show(call, screens = screens, frames = { frame }, onOpenTask = { opened += it })
        reachActive(call)
        compose.onNodeWithTag("voicecall-keyboard").performClick()
        compose.onNodeWithTag("voicecall-input").performTextInput("帮我开深色模式")
        compose.onNodeWithTag("voicecall-send").performClick()
        awaitText("正在打开设置")
        // No screen yet: no "view screen"; once the screen exists it appears
        compose.onAllNodesWithTag("voicecall-task-screen").assertCountEquals(0)
        val taskId = call.feed.items.value.filterIsInstance<com.wangzan101.mika.voicecall.VoiceCallFeedItem.Task>().single().taskId
        screens.value = setOf(taskId)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("voicecall-task-screen").fetchSemanticsNodes().isNotEmpty() }
        // Tap "view screen": the picture appears on the call page without leaving the call; × closes it
        compose.onNodeWithTag("voicecall-task-screen").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("voicecall-screen-panel").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(opened.isEmpty())
        compose.onNodeWithTag("voicecall-screen-close").performClick()
        compose.onAllNodesWithTag("voicecall-screen-panel").assertCountEquals(0)
        // Tap the card: hand off to the host to open that task
        compose.onNodeWithTag("voicecall-task").performClick()
        assertEquals(listOf(taskId), opened)
        // Once finished, "view screen" no longer appears
        flow.value = TaskState.Succeeded("开好了")
        awaitText("已完成")
        compose.onAllNodesWithTag("voicecall-task-screen").assertCountEquals(0)
        call.session.end(EndReason.Hangup)
    }

    @Test fun bargeInInterruptsPlaybackButNeverCancelsTheRunningTask() {
        // The progress on the task card is the line the task shows the user (narration), not the step note
        val flow = MutableStateFlow<TaskState>(TaskState.Running(1, "open_app", narration = "正在打开设置"))
        val call = Call(mapOf("t1" to flow), connect = { rt })
        show(call)
        reachActive(call)
        compose.onNodeWithTag("voicecall-keyboard").performClick()
        compose.onNodeWithTag("voicecall-input").performTextInput("帮我开深色模式")
        compose.onNodeWithTag("voicecall-send").performClick()
        awaitText("正在打开设置")
        // The user interrupts by speaking: only playback stops; the background task keeps running
        call.rt.emit(VolcRealtimeEvent.UserSpeaking)
        awaitValue { call.audio.interruptCount == 1 }
        assertTrue(call.port.cancelled.isEmpty())
        assertTrue(flow.value is TaskState.Running)
        compose.onAllNodesWithText("正在打开设置").fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        call.session.end(EndReason.Hangup)
    }

    @Test fun hangupShowsEndedCopy() {
        val call = Call(connect = { rt })
        show(call)
        reachActive(call)
        compose.onNodeWithTag("voicecall-hangup").performClick()
        compose.onNodeWithText("通话已结束").assertIsDisplayed()
        assertTrue(call.session.state.value is VoiceCallState.Ended)
        assertEquals(1, call.audio.focusAbandons)
    }

    @Test fun failureShowsErrorCopyAndRetryRerunsTheFlow() {
        val attempts = AtomicInteger(0)
        val call = Call(connect = {
            if (attempts.incrementAndGet() == 1) throw VolcRealtimeException(VolcRealtimeException.Kind.TIMEOUT) else rt
        })
        show(call)
        // First failure: error copy (one in the top-bar status and one in the error card) + retry in place
        awaitText("连接超时，再试一次")
        compose.onNodeWithTag("voicecall-retry").performClick()
        reachActive(call)
        compose.onNodeWithText("在听你说").assertIsDisplayed()
        assertEquals(2, attempts.get())
        call.session.end(EndReason.Hangup)
    }

    @Test fun aQuietCallEndsWithItsOwnCopy() {
        val call = Call(connect = { rt }, idleMs = 300L)
        show(call)
        reachActive(call)
        awaitState(call.session) { it == VoiceCallState.Ended(EndReason.Idle, idleMinutes = 1) }
        compose.onNodeWithText("1 分钟没有动静，通话已结束", substring = true).assertIsDisplayed()
    }

    @Test fun collapseInvokesCallback() {
        val collapses = AtomicInteger(0)
        val call = Call(connect = { rt })
        show(call, onCollapse = { collapses.incrementAndGet() })
        reachActive(call)
        compose.onNodeWithTag("voicecall-collapse").performClick()
        assertEquals(1, collapses.get())
        call.session.end(EndReason.Hangup)
    }

    @Test fun theLastCallLinkJumpsToItAndThePageHasNoMenu() {
        val picked = mutableListOf<String>()
        val earlier = com.wangzan101.mika.chat.ConversationCall("old", 1_000L, 61_000L, spoke = true)
        val call = Call(connect = { rt })
        show(call, calls = listOf(earlier), onOpenRecord = { picked += it })
        reachActive(call)
        compose.onNodeWithTag("voicecall-last-call").performClick()
        assertEquals(listOf("old"), picked)
        // Call records and call settings are entered from ⋯ at the top right of the chat page; the call page no longer has ⋯
        compose.onNodeWithTag("voicecall-more").assertDoesNotExist()
        call.session.end(EndReason.Hangup)
    }

    // ---------- permission / credential gate ----------

    @Test fun permissionGateRequestsThenPointsToSettings() {
        val requests = AtomicInteger(0)
        val settings = AtomicInteger(0)
        var stage by mutableStateOf(VoiceCallGateStage.NeedPermission)
        compose.setContent {
            StillTheme {
                VoiceCallGate(
                    stage,
                    onRequestPermission = { requests.incrementAndGet() },
                    onOpenSettings = { settings.incrementAndGet() },
                    onGoConfigure = {},
                    onCollapse = {},
                )
            }
        }
        compose.onNodeWithText("语音通话要用麦克风").assertIsDisplayed()
        compose.onNodeWithText("允许麦克风").performClick()
        assertEquals(1, requests.get())
        // The user denied before: point to system settings instead
        stage = VoiceCallGateStage.PermissionDenied
        compose.onNodeWithText("去设置").performClick()
        assertEquals(1, settings.get())
    }

    @Test fun credentialsGatePointsToSetup() {
        val setups = AtomicInteger(0)
        compose.setContent {
            StillTheme {
                VoiceCallGate(
                    VoiceCallGateStage.NeedCredentials,
                    onRequestPermission = {},
                    onOpenSettings = {},
                    onGoConfigure = { setups.incrementAndGet() },
                    onCollapse = {},
                )
            }
        }
        compose.onNodeWithText("先配置实时语音").assertIsDisplayed()
        compose.onNodeWithText("去配置").performClick()
        assertEquals(1, setups.get())
    }

    @Test fun unsupportedProtocolGateExplainsAndPointsToSetup() {
        val setups = AtomicInteger(0)
        compose.setContent {
            StillTheme {
                VoiceCallGate(
                    VoiceCallGateStage.UnsupportedProtocol,
                    onRequestPermission = {},
                    onOpenSettings = {},
                    onGoConfigure = { setups.incrementAndGet() },
                    onCollapse = {},
                )
            }
        }
        compose.onNodeWithText("语音通话需要兼容的配置").assertIsDisplayed()
        compose.onNodeWithText("去配置").performClick()
        assertEquals(1, setups.get())
    }
}
