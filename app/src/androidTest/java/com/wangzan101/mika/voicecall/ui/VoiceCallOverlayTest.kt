package com.wangzan101.mika.voicecall.ui

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
import com.wangzan101.mika.voicecall.VoiceCallRoute
import com.wangzan101.mika.voicecall.VoiceCallSession
import com.wangzan101.mika.voicecall.VoiceCallState
import com.wangzan101.mika.voicecall.VoiceCallTaskLauncher
import com.wangzan101.mika.voicecall.VoiceCallTranscript
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
import kotlinx.coroutines.flow.combine
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
class VoiceCallOverlayTest {

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

    /** One fake call: a real session wired to the fake device; [connect] decides whether connecting succeeds (it may run multiple times, which pairs with retry). The conversation feed is fed exactly as in the production wiring. */
    private inner class Call(
        val route: VoiceCallRoute,
        connect: suspend (Int) -> CallRealtimeSession,
        timeLimitMs: Long = 600_000L,
    ) {
        val audio = FakeAudio()
        val rt = FakeRealtime()
        val feed = VoiceCallFeed()
        val transcript = VoiceCallTranscript(
            conversationId = { null },
            appendTurn = { _, _ -> },
        )
        val session = VoiceCallSession(
            scope = scope,
            audio = audio,
            foregroundResumed = MutableStateFlow(true),
            connect = connect,
            greeting = "你好",
            onVoiceRequest = { callId, text -> scope.launch { route.execute(callId, text) } },
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
            timeLimitMs = timeLimitMs,
        )

        init {
            scope.launch { combine(route.busy, route.lines, ::Pair).collect { (busy, lines) -> feed.onTask(busy, lines) } }
        }
    }

    private fun fakeRoute(
        flows: Map<String, MutableStateFlow<TaskState>> = emptyMap(),
        cancelled: MutableList<String> = mutableListOf(),
    ) = VoiceCallRoute(
        launcher = VoiceCallTaskLauncher { "conv-1" },
        runState = { flows[it] },
        sendResult = { _, _ -> },
        cancelTask = { id ->
            cancelled += id
            flows[id]?.value = TaskState.Cancelled
        },
    )

    private fun show(call: Call, onCollapse: () -> Unit = {}) {
        val audioRoute = VoiceCallAudioRoute(InstrumentationRegistry.getInstrumentation().targetContext)
        val holder = VoiceCallHolder(call.session, call.route, audioRoute, call.transcript, call.feed)
        compose.setContent {
            MikaTheme { VoiceCallOverlay(holder, onCollapse) }
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

    /** Waits for a text node to appear (the conversation feed is updated asynchronously through route→feed, so asserting directly can miss it). */
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
        val call = Call(fakeRoute(), connect = { gate.await() })
        show(call)
        // Connecting: status word + Web-shaped ball; the timer has not started yet
        compose.onNodeWithText("正在连接…").assertIsDisplayed()
        awaitBall("Web")

        // Service connected: check the microphone → connected
        gate.complete(call.rt)
        reachActive(call)
        compose.onNodeWithText("在听你说").assertIsDisplayed()
        compose.onNodeWithTag("voicecall-clock").assertTextEquals("0:00")
        awaitBall("Globe")
        call.session.end(EndReason.Hangup)
    }

    @Test fun micLevelSwitchesBallToWave() {
        lateinit var call: Call
        call = Call(fakeRoute(), connect = { call.rt })
        show(call)
        reachActive(call)
        awaitBall("Globe")
        // The user speaks: loud capture chunks come in and the ball switches to Wave
        repeat(3) { call.audio.offerChunk(amplitude = 4_000) }
        awaitBall("Wave")
        call.session.end(EndReason.Hangup)
    }

    @Test fun cloudAudioDrivesSpeakerLevelAndWave() {
        lateinit var call: Call
        call = Call(fakeRoute(), connect = { call.rt })
        show(call)
        reachActive(call)
        awaitBall("Globe")
        // Assistant speaking: cloud audio enters the playback queue, the level rises and the ball switches to Wave
        call.rt.emit(VolcRealtimeEvent.Audio(pcm(4_000, 4_800)))
        awaitBall("Wave")
        call.session.end(EndReason.Hangup)
    }

    @Test fun workingTaskShowsTaskCardAndStatusLines() {
        val flow = MutableStateFlow<TaskState>(TaskState.Running(1, "正在打开设置"))
        val route = fakeRoute(mapOf("conv-1" to flow))
        lateinit var call: Call
        call = Call(route, connect = { call.rt })
        show(call)
        reachActive(call)
        // Typing to get things done: tap the keyboard to expand the input row, and after sending, the route claims it and the step card appears
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

    @Test fun bargeInInterruptsPlaybackAndCancelsTheRunningTask() {
        val flow = MutableStateFlow<TaskState>(TaskState.Running(1, "正在打开设置"))
        val cancelled = mutableListOf<String>()
        val route = fakeRoute(mapOf("conv-1" to flow), cancelled)
        lateinit var call: Call
        call = Call(route, connect = { call.rt })
        show(call)
        reachActive(call)
        compose.onNodeWithTag("voicecall-keyboard").performClick()
        compose.onNodeWithTag("voicecall-input").performTextInput("帮我开深色模式")
        compose.onNodeWithTag("voicecall-send").performClick()
        awaitText("正在打开设置")
        // The user starts speaking to barge in: playback stops at once and tasks that were started but not finished are cancelled
        call.rt.emit(VolcRealtimeEvent.UserSpeaking)
        awaitValue { cancelled == listOf("conv-1") }
        assertEquals(1, call.audio.interruptCount)
        awaitText("已取消")
        call.session.end(EndReason.Hangup)
    }

    @Test fun hangupShowsEndedCopy() {
        lateinit var call: Call
        call = Call(fakeRoute(), connect = { call.rt })
        show(call)
        reachActive(call)
        compose.onNodeWithTag("voicecall-hangup").performClick()
        compose.onNodeWithText("通话已结束").assertIsDisplayed()
        assertTrue(call.session.state.value is VoiceCallState.Ended)
        assertEquals(1, call.audio.focusAbandons)
    }

    @Test fun failureShowsErrorCopyAndRetryRerunsTheFlow() {
        val attempts = AtomicInteger(0)
        lateinit var call: Call
        call = Call(fakeRoute(), connect = {
            if (attempts.incrementAndGet() == 1) throw VolcRealtimeException(VolcRealtimeException.Kind.TIMEOUT) else call.rt
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

    @Test fun timeLimitEndsTheCallWithItsOwnCopy() {
        lateinit var call: Call
        call = Call(fakeRoute(), connect = { call.rt }, timeLimitMs = 300L)
        show(call)
        reachActive(call)
        awaitState(call.session) { it == VoiceCallState.Ended(EndReason.TimeLimit) }
        compose.onNodeWithText("聊满 10 分钟，通话结束").assertIsDisplayed()
    }

    @Test fun collapseInvokesCallback() {
        val collapses = AtomicInteger(0)
        lateinit var call: Call
        call = Call(fakeRoute(), connect = { call.rt })
        show(call, onCollapse = { collapses.incrementAndGet() })
        reachActive(call)
        compose.onNodeWithTag("voicecall-collapse").performClick()
        assertEquals(1, collapses.get())
        call.session.end(EndReason.Hangup)
    }

    // ---------- permission / credential gates ----------

    @Test fun permissionGateRequestsThenPointsToSettings() {
        val requests = AtomicInteger(0)
        val settings = AtomicInteger(0)
        var stage by mutableStateOf(VoiceCallGateStage.NeedPermission)
        compose.setContent {
            MikaTheme {
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
            MikaTheme {
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
            MikaTheme {
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
