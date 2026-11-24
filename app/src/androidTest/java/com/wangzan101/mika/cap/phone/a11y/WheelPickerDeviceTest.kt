package com.wangzan101.mika.cap.phone.a11y

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.wangzan101.mika.core.cap.phone.WheelMath
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device test: open [WheelPickerTestActivity] on the Agent virtual display and turn the stock numeric wheel from 0 to 40 in one call.
 * The "locked" one can only be moved via accessibility scrolling (one notch per action; from 0, going back 20 notches is the shortest way); when read by the screen reader both are marked as wheels.
 */
@RunWith(AndroidJUnit4::class)
class WheelPickerDeviceTest {
    private val agent = DeviceSupport.newAgent()
    private val screen get() = agent.screen

    @After
    fun tearDown() = agent.release()

    private fun open(): ScreenSnapshot {
        DeviceSupport.sh("am start --display ${agent.display.displayId} -n $TEST_PACKAGE/${WheelPickerTestActivity::class.java.name}")
        val s = DeviceSupport.pollUntil(8_000) {
            runBlocking { screen.snapshot() }.takeIf { s -> s.elements.count { it.role == "NumberPicker" } == 2 }
        }
        return s ?: error("wheel page never showed: ${runBlocking { screen.snapshot() }.elements.map { "${it.role}:${it.name}" }}")
    }

    /** The stock numeric wheel carries no name in the screen-reader tree: identify it by position; on the page the left one is "minutes" and the right one is "locked" (see [WheelPickerTestActivity]). */
    private fun ScreenSnapshot.picker(label: String): UiElement {
        val pickers = elements.filter { it.role == "NumberPicker" }.sortedBy { it.centerX }
        check(pickers.size == 2) { "expected 2 NumberPickers, got ${pickers.map { it.centerX }}" }
        return if (label == WheelPickerTestActivity.MINUTES) pickers[0] else pickers[1]
    }

    private fun flatten(n: com.wangzan101.mika.core.screen.uistate.UiTreeNode): List<com.wangzan101.mika.core.screen.uistate.UiTreeNode> =
        listOf(n) + n.children.flatMap(::flatten)

    /** Have the tool set the same value once more: the wheel really is already parked there, so nothing needs to move and the starting point is that value. */
    private suspend fun assertStaysAt(label: String, value: String) {
        val s = screen.snapshot()
        val again = screen.setWheel(s.picker(label).ref, s.snapshotId, value)
        assertTrue("$again", again.reached && again.attempts == 0 && WheelMath.matches(again.from, value))
    }

    @Test
    fun wheelsAreMarkedInTheTree() = runBlocking<Unit> {
        val s = open()
        val wheels = s.windows.flatMap { it.nodes }.flatMap(::flatten).filter { it.wheel }
        assertEquals(2, wheels.size)
    }

    @Test
    fun lockedWheelReaches40From0ByStepping() = runBlocking<Unit> {
        val s = open()
        val o = screen.setWheel(s.picker(WheelPickerTestActivity.LOCKED).ref, s.snapshotId, "40")
        assertTrue("$o", o.reached)
        assertEquals("actions", o.method)
        assertEquals("00", o.from)
        assertTrue("$o", WheelMath.matches(o.value, "40"))
        // From 0 to 40, going back 20 notches is the nearest; one notch per action, and reading back is not counted separately
        assertTrue("$o", o.attempts in 20..24)
        assertStaysAt(WheelPickerTestActivity.LOCKED, "40")
    }

    @Test
    fun typableWheelReaches40() = runBlocking<Unit> {
        val s = open()
        val o = screen.setWheel(s.picker(WheelPickerTestActivity.MINUTES).ref, s.snapshotId, "40")
        assertTrue("$o", o.reached)
        assertTrue("$o", o.method in setOf("text", "actions"))
        assertStaysAt(WheelPickerTestActivity.MINUTES, "40")
        // The other wheel was not touched
        assertStaysAt(WheelPickerTestActivity.LOCKED, "0")
    }

    @Test
    fun valueNotOnTheWheelIsReportedNotForced() = runBlocking<Unit> {
        val s = open()
        val o = screen.setWheel(s.picker(WheelPickerTestActivity.LOCKED).ref, s.snapshotId, "75")
        assertTrue("$o", !o.reached)
        assertTrue("$o", o.value != null)
    }
}
