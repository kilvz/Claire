package com.wangzan101.mika.core.screen.uistate

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A first read that comes back as an empty tree is retried once (a window that just switched away, an in-progress redraw, or accessibility not having emitted nodes yet can all leave the screen temporarily empty);
 * only two empty reads mark it unusable, so a momentary miss does not condemn the whole screen and push the model into full-page OCR.
 */
class BlankReadRetryTest {
    /** [blankFirst] true: the first read is an empty tree and later reads are normal; [blankAlways] true: always an empty tree; [unreadable] non-null: the window cannot be read. */
    private class FlickerScreen(private val blankFirst: Boolean, private val blankAlways: Boolean = false, private val unreadable: String? = null) : ScreenPort by FakeScreen() {
        var snapshots = 0
        override suspend fun snapshot(): ScreenSnapshot {
            val n = ++snapshots
            val empty = blankAlways || (blankFirst && n == 1)
            val els = if (empty) emptyList() else (1..8).map { i ->
                UiElement("@e$i", "TextView", "第${i}行", 0, 0, listOf(50, 100 + i * 60, 950, 150 + i * 60), null, true, false, false)
            }
            return ScreenSnapshot("s$n", "com.example", els, 1080, 2400, unreadableApp = unreadable)
        }
        override suspend fun peek() = snapshot()
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    @Test fun blankReadIsRetriedOnceAndThenTrusted() = runTest {
        val s = FlickerScreen(blankFirst = true)
        val r = toolDispatcher(s).executeRead(Part.ToolCall("o", "phone_observe", "{}"))
        assertEquals(2, s.snapshots)
        // The screen from the second read can be acted on by ref: treeQuality is not written and the model is not sent to OCR
        assertNull(r.content.s("treeQuality"))
        assertNull(r.content.s("imageReason"))
    }

    @Test fun stillBlankAfterRetryIsUnusable() = runTest {
        val s = FlickerScreen(blankFirst = true, blankAlways = true)
        val r = toolDispatcher(s).executeRead(Part.ToolCall("o", "phone_observe", "{}"))
        assertEquals(2, s.snapshots)
        assertEquals("unusable", r.content.s("treeQuality"))
    }

    @Test fun readableFirstReadIsNotReadTwice() = runTest {
        val s = FlickerScreen(blankFirst = false)
        toolDispatcher(s).executeRead(Part.ToolCall("o", "phone_observe", "{}"))
        assertEquals(1, s.snapshots)
    }

    /** An unreadable window is persistent (service/permission level), not nodes that have not been emitted yet: no retry; one read is judged unusable by the usual criteria. */
    @Test fun unreadableWindowIsNotRetried() = runTest {
        val s = FlickerScreen(blankFirst = true, unreadable = "com.example.slow")
        val r = toolDispatcher(s).executeRead(Part.ToolCall("o", "phone_observe", "{}"))
        assertEquals(1, s.snapshots)
        assertEquals("unusable", r.content.s("treeQuality"))
    }
}
