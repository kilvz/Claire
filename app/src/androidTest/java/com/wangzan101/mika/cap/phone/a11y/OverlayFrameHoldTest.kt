package com.wangzan101.mika.cap.phone.a11y

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Long press on the overlay window root: holding still until the deadline hands the gesture to the long press and the buttons inside receive a cancel; if the hold is removed mid-gesture the cancel is signalled, and a later up no longer counts. */
@RunWith(AndroidJUnit4::class)
class OverlayFrameHoldTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val calls = mutableListOf<String>()
    private val hold = object : OverlayHold {
        override fun start(rawX: Float, rawY: Float) { calls += "hold-start" }
        override fun move(rawX: Float, rawY: Float) = Unit
        override fun end(rawX: Float, rawY: Float) { calls += "hold-end" }
        override fun cancel() { calls += "hold-cancel" }
    }

    private inner class Content : View(context) {
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> calls += "down"
                MotionEvent.ACTION_UP -> calls += "up"
                MotionEvent.ACTION_CANCEL -> calls += "cancel"
            }
            return true
        }
    }

    private lateinit var frame: OverlayFrame
    private val downAt = SystemClock.uptimeMillis()

    private fun ev(action: Int) = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, 50f, 50f, 0)
        .also { frame.dispatchTouchEvent(it); it.recycle() }

    /** Press down and wait past the long-press deadline (the main thread is idle, so the timer can fire). */
    private fun pressAndWait() {
        instrumentation.runOnMainSync {
            frame = OverlayFrame(context).apply {
                addView(Content(), FrameLayout.LayoutParams(100, 100))
                measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
                layout(0, 0, 100, 100)
                setHold(hold, HOLD_MS)
            }
            ev(MotionEvent.ACTION_DOWN)
        }
        Thread.sleep(HOLD_MS * 4)
        instrumentation.waitForIdleSync()
    }

    @Test fun stillPressBecomesHoldAndUpEndsIt() {
        pressAndWait()
        instrumentation.runOnMainSync { ev(MotionEvent.ACTION_UP) }
        assertEquals(listOf("down", "cancel", "hold-start", "hold-end"), calls)
    }

    @Test fun clearingTheHoldMidGestureCancelsItAndALaterUpDoesNothing() {
        pressAndWait()
        instrumentation.runOnMainSync {
            frame.setHold(null, HOLD_MS)
            ev(MotionEvent.ACTION_UP)
        }
        assertEquals(listOf("down", "cancel", "hold-start", "hold-cancel"), calls)
    }

    @Test fun replacingTheHoldMidGestureCancelsTheOldOne() {
        pressAndWait()
        val other = mutableListOf<String>()
        instrumentation.runOnMainSync {
            frame.setHold(object : OverlayHold {
                override fun start(rawX: Float, rawY: Float) { other += "start" }
                override fun move(rawX: Float, rawY: Float) = Unit
                override fun end(rawX: Float, rawY: Float) { other += "end" }
                override fun cancel() { other += "cancel" }
            }, HOLD_MS)
            ev(MotionEvent.ACTION_UP)
        }
        assertEquals(listOf("down", "cancel", "hold-start", "hold-cancel"), calls)
        assertEquals(emptyList<String>(), other)
    }

    @Test fun settingTheSameHoldAgainKeepsTheGesture() {
        pressAndWait()
        instrumentation.runOnMainSync {
            // Every redraw installs the same hold again: this must not interrupt the one in progress
            frame.setHold(hold, HOLD_MS)
            ev(MotionEvent.ACTION_UP)
        }
        assertEquals(listOf("down", "cancel", "hold-start", "hold-end"), calls)
    }

    private companion object {
        const val HOLD_MS = 60L
    }
}
