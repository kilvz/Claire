package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Dragging on the overlay window root: a drag starts only when the press is inside the handle, and while dragging the buttons inside receive a cancel and do not count as taps; a swipe outside the handle is left to the content inside. */
@RunWith(AndroidJUnit4::class)
class OverlayFrameDragTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val calls = mutableListOf<String>()
    private val drag = object : OverlayDrag {
        override fun start() { calls += "start" }
        override fun move(dx: Float, dy: Float) = Unit
        override fun end(dx: Float, dy: Float) { calls += "end" }
    }

    /** The content inside (like the collapse button on the card, and something that can be scrolled): records the downs, ups and cancels it receives. */
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

    /** A 400x800 window whose whole area is content. */
    private fun frame(handle: Rect?): OverlayFrame = OverlayFrame(context).apply {
        addView(Content(), FrameLayout.LayoutParams(400, 800))
        measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        layout(0, 0, 400, 800)
        drag = this@OverlayFrameDragTest.drag
        this.handle = handle
    }

    private fun OverlayFrame.gesture(x: Float, y: Float, dy: Float) {
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int, yy: Float) = MotionEvent.obtain(t, t, action, x, yy, 0).also { dispatchTouchEvent(it); it.recycle() }
        ev(MotionEvent.ACTION_DOWN, y)
        ev(MotionEvent.ACTION_MOVE, y + dy / 2)
        ev(MotionEvent.ACTION_MOVE, y + dy)
        ev(MotionEvent.ACTION_UP, y + dy)
    }

    private val far get() = ViewConfiguration.get(context).scaledTouchSlop * 4f

    @Test fun dragInTheHandleMovesAndNeverClicks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            frame(Rect(0, 0, 400, 60)).gesture(200f, 30f, far)
        }
        // Moving past the slop: the drag starts, the content receives a cancel (so it is not taken for a tap), and the up only ends the drag
        assertEquals(listOf("down", "start", "cancel", "end"), calls)
    }

    @Test fun swipeOutsideTheHandleIsLeftToTheContent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            frame(Rect(0, 0, 400, 60)).gesture(200f, 400f, far)
        }
        // No drag; the whole touch sequence is handed to the content inside (scrolling, for example)
        assertEquals(listOf("down", "up"), calls)
    }

    @Test fun tapInTheHandleStillClicks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            frame(Rect(0, 0, 400, 60)).gesture(200f, 30f, 0f)
        }
        assertEquals(listOf("down", "up"), calls)
    }

    @Test fun emptyHandleMeansNoDrag() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            frame(Rect()).gesture(200f, 30f, far)
        }
        assertEquals(listOf("down", "up"), calls)
    }
}
