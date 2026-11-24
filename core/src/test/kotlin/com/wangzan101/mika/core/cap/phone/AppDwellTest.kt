package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.execution
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.screen.uistate.IdleScroll
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * App pacing hooks into the execution entry point: both single steps and every step of a batch top up the dwell before the action, and how long it waited is recorded as this call's wait;
 * "fast" does not wait; for apps that throttle browsing, once screen changes hit the cap nothing is done and page_limit is reported, typing still works, and it carries on after the user answers; a gentle scroll during the dwell only goes into the run record.
 */
class AppDwellTest {
    /** One app: every tap swaps a whole screen (six different items); records taps, typing and gentle scrolls. */
    private class Pages(private val pkg: String = "com.slow") : ScreenPort by FakeScreen() {
        var page = 0
        val calls = mutableListOf<String>()
        private fun shown() = (1..6).map { i ->
            UiElement("@e$i", "text", "第${page}屏的第${i}条内容", 540, 200 * i, listOf(0, 80 * i, 999, 80 * i + 60), null, true, i == 1, false)
        }
        override suspend fun snapshot() = ScreenSnapshot("s$page", pkg, shown(), 1080, 2400)
        override suspend fun peek() = snapshot()
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { calls += "tap"; page++; return ActionResult.Ok(changed = true) }
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { calls += "type"; return ActionResult.Ok(changed = true) }
        override suspend fun back(): ActionResult { calls += "back"; page++; return ActionResult.Ok(changed = true) }
        override suspend fun idleScroll(fraction: Double): Boolean { calls += "idle"; return true }
    }

    private fun tools(screen: ScreenPort, pacing: AppPacing, idle: IdleScroll? = null): ToolDispatcher =
        toolDispatcher(screen, appPacing = { pacing }, appTempo = { if (it == "com.slow") AppTempo.CAREFUL else AppTempo.NORMAL }, appCapped = { it == "com.slow" }, appIdleScroll = { idle })

    private fun executor(t: ToolDispatcher) = ActionExecutor(t, OperationGuards(), null, gate = {}, recorder = ActionRecorder(RunSink.NONE))

    private suspend fun ActionExecutor.tap(t: ToolDispatcher): ToolExecution {
        val sid = t.execute(Part.ToolCall("o", "phone_observe", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        return run(Part.ToolCall("t", "phone_tap", """{"ref":"@e2","snapshotId":"$sid"}"""), Origin.Model(1)).execution()
    }

    private fun ToolExecution.error() = (content["error"] as? JsonPrimitive)?.content

    @Test fun fastAddsNoDwell() = runTest {
        val screen = Pages()
        val t = tools(screen, OperatingPace.FAST.appPacing())
        val ex = executor(t)
        val start = currentTime
        repeat(5) { assertEquals(0L, ex.tap(t).waitMs) }
        // The number of screens is not capped either
        assertEquals(5, screen.page)
        assertEquals(0L, currentTime - start)
    }

    @Test fun perAppFastTempoSkipsDwellEvenWhenOthersWait() = runTest {
        // The dwell engine is on (other apps are waiting) but this app is overridden to "fast": no waiting and no cap inside it
        val screen = Pages("com.quick")
        val t = toolDispatcher(screen, appPacing = { AppPacing.human(Random(3)) }, appTempo = { AppTempo.FAST }, appIdleScroll = { null })
        val ex = executor(t)
        val start = currentTime
        repeat(5) { assertEquals(0L, ex.tap(t).waitMs) }
        assertEquals(5, screen.page)
        assertEquals(0L, currentTime - start)
    }

    @Test fun eachActionWaitsAfterTheLastOneAndTheWaitIsRecorded() = runTest {
        val t = tools(Pages("com.plain"), OperatingPace.STANDARD.appPacing(Random(3)))
        val ex = executor(t)
        assertEquals(0L, ex.tap(t).waitMs)
        val before = currentTime
        val second = ex.tap(t)
        // The previous action changed the screen: the new-screen range (standard)
        assertTrue("${second.waitMs}", second.waitMs in AppPacing.Limits().newScreenMs.first..AppPacing.Limits().maxDwellMs)
        assertTrue(currentTime - before >= second.waitMs)
        // Each step inside a batch also waits through the execution entry point
        val sid = t.execute(Part.ToolCall("o", "phone_observe", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        val typed = ex.run(Part.ToolCall("b1", "phone_type_text", """{"ref":"@e1","snapshotId":"$sid","text":"你好"}"""), Origin.Batch(2)).execution()
        assertTrue("${typed.waitMs}", typed.waitMs > 0)
    }

    @Test fun carefulAppsStopAtTheScreenBudgetUntilTheUserAnswers() = runTest {
        val screen = Pages()
        val t = tools(screen, AppPacing(Random(1), screenCap = 3))
        val ex = executor(t)
        repeat(3) { assertEquals(JsonPrimitive(true), ex.tap(t).content["ok"]) }
        // Three screens have already been changed: actions that change screens are not done, and the message explains why it stopped
        val stopped = ex.tap(t)
        assertEquals("page_limit", stopped.error())
        assertTrue(stopped.content["message"].toString(), stopped.content["message"].toString().contains("ask_user"))
        assertTrue(stopped.content["message"].toString().contains("Claire reached its per-task app screen budget"))
        val budget = stopped.content["navigationBudget"] as JsonObject
        assertEquals(JsonPrimitive("app_screens"), budget["scope"])
        assertEquals(JsonPrimitive("com.slow"), budget["target"])
        assertEquals(JsonPrimitive(3), budget["used"])
        assertEquals(JsonPrimitive(3), budget["limit"])
        assertEquals(3, screen.page)
        // Typing works as usual
        val sid = t.execute(Part.ToolCall("o", "phone_observe", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        assertEquals(JsonPrimitive(true), ex.run(Part.ToolCall("y", "phone_type_text", """{"ref":"@e1","snapshotId":"$sid","text":"a"}"""), Origin.Model(1)).execution().content["ok"])
        // The user has answered: carry on
        t.noteUserAnswer("还要接着看吗?", "继续")
        assertEquals(JsonPrimitive(true), ex.tap(t).content["ok"])
        assertEquals(4, screen.page)
    }

    @Test fun ordinaryAppsHaveNoBudget() = runTest {
        val screen = Pages("com.plain")
        val t = tools(screen, AppPacing(Random(1), screenCap = 2))
        val ex = executor(t)
        repeat(4) { assertEquals(JsonPrimitive(true), ex.tap(t).content["ok"]) }
    }

    @Test fun idleScrollsHappenOnlyBeforeActionsThatDoNotAimAtAPlaceAndAreOnlyRecorded() = runTest {
        val screen = Pages()
        val t = tools(screen, AppPacing.human(Random(5)), IdleScroll(Random(6)))
        val ex = executor(t)
        ex.tap(t)
        // No idle scroll before actions that tap by coordinate or ref
        ex.tap(t)
        assertTrue(screen.calls.none { it == "idle" })
        // Back does not aim at a position on screen: it waits long enough on the new screen and scrolls gently a few times during the dwell, which only goes into the run record
        val back = ex.run(Part.ToolCall("k", "phone_back", "{}"), Origin.Model(1)).execution()
        val idled = screen.calls.count { it == "idle" }
        assertTrue("$idled", idled > 0)
        assertEquals(JsonPrimitive(idled), back.record["idleScrolls"])
        assertTrue(back.content["idleScrolls"] == null)
    }
}
