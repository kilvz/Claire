package com.wangzan101.mika.capsule

import androidx.compose.ui.geometry.Rect
import com.wangzan101.mika.cap.phone.a11y.HoldGesture
import com.wangzan101.mika.i18n.ChineseTexts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Speaking by long-pressing the capsule: which circle the finger lands on at release, how hold and drag are told apart, and the line that flashes on the capsule after sending. */
@OptIn(ExperimentalCoroutinesApi::class)
class CapsuleVoiceTest {
    @get:Rule val texts = ChineseTexts()

    private val cancel = Rect(100f, 200f, 162f, 262f)
    private val edit = Rect(300f, 200f, 362f, 262f)

    @Test fun insideCancelCircleIsCancel() {
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(130f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun justOutsideCancelCircleStillCancels() {
        // 10px outside the circle, within the 14px expansion
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(90f, 230f, cancel, edit, slopPx = 14))
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(130f, 272f, cancel, edit, slopPx = 14))
    }

    @Test fun farFromBothCirclesIsSend() {
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(230f, 900f, cancel, edit, slopPx = 14))
        // Between the two circles, near enough to neither
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(231f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun insideEditCircleIsEdit() {
        assertEquals(CapsuleVoice.Drop.Edit, CapsuleVoice.drop(330f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun circlesNotLaidOutYetMeanSend() {
        // While the panel has not been laid out yet (circle positions not reported), it is not mistaken for a cancel
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(0f, 0f, Rect.Zero, Rect.Zero, slopPx = 14))
    }

    @Test fun smallMoveThenTimeoutIsHold() {
        val g = HoldGesture(slopPx = 8f)
        assertEquals(HoldGesture.Kind.Tap, g.down())
        assertEquals(HoldGesture.Kind.Tap, g.move(5f))
        assertEquals(HoldGesture.Kind.Hold, g.timeout())
        // After a hold, any further movement stays a hold and never becomes a drag
        assertEquals(HoldGesture.Kind.Hold, g.move(200f))
    }

    @Test fun movedPastSlopFirstIsDragAndNeverHold() {
        val g = HoldGesture(slopPx = 8f)
        g.down()
        assertEquals(HoldGesture.Kind.Drag, g.move(20f))
        assertEquals(HoldGesture.Kind.Drag, g.timeout())
        assertEquals(HoldGesture.Kind.Drag, g.move(2f))
    }

    @Test fun newDownStartsOver() {
        val g = HoldGesture(slopPx = 8f)
        g.down()
        g.move(20f)
        assertEquals(HoldGesture.Kind.Tap, g.down())
        assertEquals(HoldGesture.Kind.Hold, g.timeout())
    }

    @Test fun sentNoteSaysWhatHappenedWithTheFirstWords() {
        assertEquals("已插话：要下午三点以后的，二等座…", CapsuleVoice.sent(CardInputKind.Steer, "要下午三点以后的，二等座靠窗"))
        assertEquals("已回答：明天上午", CapsuleVoice.sent(CardInputKind.Answer, "明天上午"))
        assertEquals("已发出：查一下天气", CapsuleVoice.sent(CardInputKind.Continue, "查一下天气"))
        assertEquals("已发出：查一下天气", CapsuleVoice.sent(CardInputKind.Start, "查一下天气"))
    }

    // The engine never returns a result after release: the deadline must finish it, the panel cannot stay on "transcribing…" forever
    @Test fun waitFiresWhenTheSessionNeverAnswers() = runTest {
        var fired = 0
        val wait = VoiceWait(backgroundScope) { fired++ }
        wait.arm(8_000)
        advanceTimeBy(7_999)
        runCurrent()
        assertEquals(0, fired)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, fired)
    }

    @Test fun waitCancelledByTheResultDoesNotFire() = runTest {
        var fired = 0
        val wait = VoiceWait(backgroundScope) { fired++ }
        wait.arm(8_000)
        advanceTimeBy(1_000)
        // The session hands over the result on time: the panel is already dismissed, so the fallback no longer fires
        wait.cancel()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, fired)
    }

    @Test fun armAgainRestartsTheWait() = runTest {
        var fired = 0
        val wait = VoiceWait(backgroundScope) { fired++ }
        wait.arm(8_000)
        advanceTimeBy(5_000)
        // Pressing again: the previous fallback is voided and the count restarts from this release
        wait.arm(8_000)
        advanceTimeBy(7_999)
        runCurrent()
        assertEquals(0, fired)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, fired)
    }
}
