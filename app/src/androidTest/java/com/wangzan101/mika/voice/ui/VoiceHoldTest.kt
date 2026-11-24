package com.wangzan101.mika.voice.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechSession
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import com.wangzan101.mika.voice.SpeechFailure
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.chat.ui.Composer
import com.wangzan101.mika.chat.ui.ComposerMode

/** Hold-to-talk in the chat composer: long-pressing the input floats up a "Listening" card, and after release the text is appended to the input field; sliding up to cancel fills nothing. Uses a fake voice source, no microphone. */
@RunWith(AndroidJUnit4::class)
class VoiceHoldTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Fake session: events are fed by the test; records the release and cancel counts. */
    private class FakeSession : SpeechSession {
        val out = Channel<SpeechEvent>(Channel.UNLIMITED)
        override val events: Flow<SpeechEvent> = out.receiveAsFlow()
        override val live = true
        @Volatile var released = 0
        @Volatile var cancelled = 0
        override fun release() { released++ }
        override fun cancel() { cancelled++; out.close() }
    }

    private class FakeVoice : VoiceSource {
        val sessions = mutableListOf<FakeSession>()
        override fun spec(): SpeechEngineSpec = SpeechEngineSpec.System(onDevice = true)
        override fun start(language: String?): SpeechSession = FakeSession().also { sessions += it }
    }

    /** Fake source for an unavailable engine (system recognition is not responding); nobody should start a session on it. */
    private class UnavailableVoice : VoiceSource {
        override fun spec(): SpeechEngineSpec = SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)
        override fun start(language: String?): SpeechSession = error("an unavailable engine should not start a session")
    }

    private val voice = FakeVoice()

    @Before fun grantMic() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
    }

    /** Text emitted by "Release to send"; that path only exists when [composer] is given sendVoice = true. */
    private val voiceSent = mutableListOf<String>()

    private fun composer(text: String = "", sendVoice: Boolean = false, source: VoiceSource = voice) = compose.setContent {
        MikaTheme {
            var t by remember { mutableStateOf(text) }
            Box(Modifier.fillMaxSize()) {
                Composer(
                    Modifier, t, { t = it }, ComposerMode.Idle, hasHistory = false, onSend = {}, onStop = {},
                    voice = source,
                    onVoiceText = { t = com.wangzan101.mika.voice.HoldToTalk.join(t, it) },
                    onVoiceSend = if (sendVoice) ({ voiceSent += com.wangzan101.mika.voice.HoldToTalk.join(t, it); t = "" }) else null,
                )
            }
        }
    }

    private fun waitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitNoTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    /** How far a finger pressed in the middle of the input field must move up to reach the "Listening" card at y = card top + [intoCard] (a negative value is above the card's top edge). */
    private fun liftTo(intoCard: Float): Float {
        val card = compose.onNodeWithTag("voice-listen").fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithTag("composer-field").fetchSemanticsNode().boundsInRoot
        return (card.top + intoCard) - field.center.y
    }

    @Test fun holdShowsTheListenCardAndReleaseFillsTheField() {
        composer("帮我")
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        session.out.trySend(SpeechEvent.Partial("订明天", "的票"))
        // Only a hold of half a second or more counts as intentional rather than an accidental touch
        Thread.sleep(700)
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitUntil(5_000) { session.released == 1 }
        session.out.trySend(SpeechEvent.Final("订明天的票"))
        session.out.close()
        waitNoTag("voice-listen")
        compose.onNodeWithTag("composer-field").assertTextEquals("帮我订明天的票")
    }

    @Test fun focusedFieldKeepsLongPressForTheSystem() {
        composer("帮我订票")
        // One tap: focus and type
        compose.onNodeWithTag("composer-field").performClick()
        compose.onNodeWithTag("composer-field").assertIsFocused()
        // Once focused, a long press belongs to the system's text selection and paste, so listening does not start
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        Thread.sleep(800)
        compose.waitForIdle()
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(0, voice.sessions.size)
        assertEquals(0, compose.onAllNodesWithTag("voice-listen").fetchSemanticsNodes().size)
    }

    @Test fun focusedMicButtonFillsTheField() {
        composer("帮我")
        compose.onNodeWithTag("composer-field").performClick()
        compose.onNodeWithTag("composer-field").assertIsFocused()
        waitTag("composer-mic")
        compose.onNodeWithTag("composer-mic").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        session.out.trySend(SpeechEvent.Partial("订明天", "的票"))
        Thread.sleep(700)
        compose.onNodeWithTag("composer-mic").performTouchInput { up() }
        compose.waitUntil(5_000) { session.released == 1 }
        session.out.trySend(SpeechEvent.Final("订明天的票"))
        session.out.close()
        waitNoTag("voice-listen")
        compose.onNodeWithTag("composer-field").assertTextEquals("帮我订明天的票")
    }

    /** Recording is allowed but the recognition service still reports an audio error: show the explanation (mic master switch, permission management); when another microphone fails to start, keep the single hint as before. */
    @Test fun audioErrorExplainsWhereToCheckButClientErrorKeepsTheOldHint() {
        composer()
        fun failWith(why: SpeechFailure) {
            compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
            waitTag("voice-listen")
            voice.sessions.last().out.trySend(SpeechEvent.Failed(why))
            compose.onNodeWithTag("composer-field").performTouchInput { up() }
        }
        failWith(SpeechFailure.MicUnavailable)
        waitTag("voice-toast")
        compose.onNodeWithText("麦克风用不了，可能正被别的 App 占着").assertExists()
        assertEquals(0, compose.onAllNodesWithTag("voice-sheet").fetchSemanticsNodes().size)
        failWith(SpeechFailure.AudioError)
        waitTag("voice-sheet")
        compose.onNodeWithText("麦克风还是用不了").assertExists()
    }

    @Test fun slidingUpCancels() {
        composer()
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        Thread.sleep(700)
        // Only sliding past the card's top edge cancels (sliding into the card sends directly)
        val dy = liftTo(-40f)
        compose.onNodeWithTag("composer-field").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, dy)) }
        // Both the input bar and the small capsule above it read "Release to cancel"
        compose.waitUntil(5_000) { compose.onAllNodesWithText("松开 取消", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitUntil(5_000) { session.cancelled == 1 }
        assertEquals(0, session.released)
        waitNoTag("voice-listen")
        compose.onNodeWithTag("composer-field").assertTextEquals("")
    }

    @Test fun slidingIntoTheCardSendsWithWhatWasTyped() {
        composer("帮我", sendVoice = true)
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        session.out.trySend(SpeechEvent.Partial("订明天", "的票"))
        Thread.sleep(700)
        val card = compose.onNodeWithTag("voice-listen").fetchSemanticsNode().boundsInRoot
        val dy = liftTo(card.height / 2)
        compose.onNodeWithTag("composer-field").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, dy)) }
        compose.onNodeWithText("松开 发送", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitUntil(5_000) { session.released == 1 }
        session.out.trySend(SpeechEvent.Final("订明天的票"))
        session.out.close()
        compose.waitUntil(5_000) { voiceSent.isNotEmpty() }
        assertEquals(listOf("帮我订明天的票"), voiceSent)
        compose.onNodeWithTag("composer-field").assertTextEquals("")
    }

    @Test fun withoutASendPathTheCardOnlyFills() {
        composer()
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        Thread.sleep(700)
        val card = compose.onNodeWithTag("voice-listen").fetchSemanticsNode().boundsInRoot
        val dy = liftTo(card.height / 2)
        compose.onNodeWithTag("composer-field").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, dy)) }
        compose.onNodeWithText("松开 填入", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitUntil(5_000) { session.released == 1 }
        session.out.trySend(SpeechEvent.Final("订明天的票"))
        session.out.close()
        waitNoTag("voice-listen")
        compose.onNodeWithTag("composer-field").assertTextEquals("订明天的票")
    }

    /** The feature does not vanish when the engine is unavailable: holding still pops up the "voice input isn't available yet" explanation page (reason + path to choose an engine), and the page stays while held. */
    @Test fun unavailableEngineHoldShowsTheExplanationSheet() {
        composer(source = UnavailableVoice())
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        waitTag("voice-sheet")
        compose.onNodeWithText("还不能语音输入").assertExists()
        // Holding for half a second or more is deliberate: the explanation page stays and is not dismissed
        Thread.sleep(700)
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithTag("voice-sheet").assertExists()
    }

    /** With an unavailable engine a short press is still a tap: even if the explanation page popped up it must be dismissed and the field focused for typing (option A holds for the explanation page too). */
    @Test fun unavailableEngineShortHoldFallsBackToTap() {
        composer(source = UnavailableVoice())
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        // 350ms: past the 300ms listening threshold (the explanation page has already popped) but under half a second, so releasing must fall back to a tap
        Thread.sleep(350)
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        waitNoTag("voice-sheet")
        compose.onNodeWithTag("composer-field").assertIsFocused()
    }

    /** Tapping the mic with an unavailable engine: opens the explanation page directly instead of showing the misleading "hold to talk" hint. */
    @Test fun unavailableEngineMicTapOpensTheSheet() {
        composer(source = UnavailableVoice())
        waitTag("composer-mic")
        compose.onNodeWithTag("composer-mic").performClick()
        waitTag("voice-sheet")
        compose.onNodeWithText("还不能语音输入").assertExists()
    }
}
