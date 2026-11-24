package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.EffectTuning
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the dispatcher judges action effects: asynchronous updates are re-checked, a port that already waited is not waited on again, the extra time spent is capped, and changed follows the effect. */
class ToolDispatcherEffectTest {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)
    private fun JsonObject.kind() = this["effect"]!!.jsonObject["kind"]!!.jsonPrimitive.content

    private fun page(vararg names: String) = ScreenSnapshot("p", "com.x", names.mapIndexed { i, n ->
        UiElement("@e${i + 1}", "text", n, 500, 100 + i * 100, listOf(0, 40 + i * 40, 999, 70 + i * 40), null, true, false, false)
    }, 1080, 2400)

    /** The UI switches to [later] only [afterMs] milliseconds after the action (asynchronous update); [peekMs] is how long one look takes; [result] is the port's action result. */
    private class LateScreen(
        val now: () -> Long, val first: ScreenSnapshot, val later: ScreenSnapshot, val afterMs: Long,
        val peekMs: Long = 0, val result: ActionResult = ActionResult.Ok(),
    ) : ScreenPort by FakeScreen() {
        var actedAt = Long.MAX_VALUE
        var settles = 0
        private fun current() = if (now() - actedAt >= afterMs) later else first
        override suspend fun snapshot() = current()
        override suspend fun peek(): ScreenSnapshot { if (peekMs > 0) delay(peekMs); return current() }
        override suspend fun waitStable(timeoutMs: Long): Boolean { settles++; return true }
        override suspend fun tapXY(x: Int, y: Int): ActionResult { actedAt = now(); return result }
        override suspend fun back(): ActionResult { actedAt = now(); return result }
    }

    @Test fun asyncUpdateIsCaughtByTheRecheck() = runTest {
        val s = LateScreen({ currentTime }, page("列表", "一", "二"), page("详情", "价格", "加入购物车", "评价"), afterMs = EffectTuning.RECHECK_MS / 2)
        val r = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}"""))
        assertEquals("done", r.content.kind())
        assertEquals(true, r.changed)
    }

    @Test fun actionRecordsTheSourceAppWhenTheScreenChangesApps() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("设置").copy(packageName = "com.android.settings"), afterMs = 0)
        val r = toolDispatcher(s, inlineScreen = false).execute(call("phone_tap", """{"x":500,"y":500}"""))
        assertEquals("place_changed", r.content.kind())
        assertEquals("com.x", r.record["actionApp"]?.jsonPrimitive?.content)
        assertEquals(null, r.content["actionApp"])
    }

    // A "limit reached" notice appears only after the action: the result reports limitReached along with a hint to stop; descriptive text that was already on screen before the action does not count
    @Test fun limitNoticeAfterTheActionIsReported() = runTest {
        val s = LateScreen({ currentTime }, page("职位详情", "立即沟通"), page("职位详情", "立即沟通", "今日沟通次数已用完，明天再来吧"), afterMs = 0)
        val c = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}""")).content
        assertEquals("今日沟通次数已用完，明天再来吧", c["limitReached"]!!.jsonPrimitive.content)
        assertTrue(c["hint"]!!.jsonPrimitive.content.contains("stop here"))
        val faq = LateScreen({ currentTime }, page("常见问题", "次数已达上限怎么办"), page("常见问题", "次数已达上限怎么办", "答案"), afterMs = 0)
        assertEquals(null, toolDispatcher(faq).execute(call("phone_tap", """{"x":500,"y":500}""")).content["limitReached"])
    }

    // A notice that arrives a little late (absent on the first look, present on the re-check) counts as well
    @Test fun lateLimitToastIsCaughtByTheRecheck() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("列表", "操作过于频繁，请稍后再试"), afterMs = EffectTuning.RECHECK_MS / 2)
        val c = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}""")).content
        assertEquals("操作过于频繁，请稍后再试", c["limitReached"]!!.jsonPrimitive.content)
    }

    @Test fun stillNothingAfterTheRecheckIsNoEffect() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("列表"), afterMs = 0)
        val r = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}"""))
        assertEquals("no_effect", r.content.kind())
        assertEquals(false, r.changed)
    }

    @Test fun currentAdequateTreeOverridesAnEarlierUnusableRead() = runTest {
        var empty = true
        val s = object : ScreenPort by FakeScreen() {
            private fun current() = if (empty) ScreenSnapshot("empty", "com.x", emptyList(), 1080, 2400)
            else page("一", "二", "三", "四", "五", "六")
            override suspend fun snapshot() = current()
            override suspend fun peek() = current()
        }
        val d = toolDispatcher(s)
        assertEquals("unusable", d.execute(call("phone_observe")).content["treeQuality"]!!.jsonPrimitive.content)
        empty = false
        val c = d.execute(call("phone_tap", """{"x":500,"y":500}""")).content
        assertEquals("no_effect", c.kind())
        assertEquals(null, c["effect"]!!.jsonObject["treeBlind"])
    }

    @Test fun portThatAlreadyWaitedIsNotWaitedForAgain() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("详情", "价格", "加入购物车"), afterMs = 0, result = ActionResult.Ok(changed = true))
        val r = toolDispatcher(s).execute(call("phone_back"))
        assertEquals("done", r.content.kind())
        assertEquals(0, s.settles)
    }

    @Test fun portSaysChangedIsNeverReportedAsNoEffect() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("列表"), afterMs = 0, result = ActionResult.Ok(changed = true))
        val c = toolDispatcher(s).execute(call("phone_back")).content
        assertEquals("small_change", c.kind())
        assertEquals("true", c["changed"]!!.jsonPrimitive.content)
    }

    @Test fun portSaysUnchangedButTheInterfaceChangedFollowsTheEffect() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("详情", "价格", "加入购物车"), afterMs = 0, result = ActionResult.Ok(changed = false))
        val r = toolDispatcher(s).execute(call("phone_back"))
        assertEquals("done", r.content.kind())
        assertEquals(true, r.changed)
        assertEquals("true", r.content["changed"]!!.jsonPrimitive.content)
    }

    @Test fun extraTimePerActionIsBounded() = runTest {
        // A slow port where one look takes 2 seconds: judging the effect costs at most BUDGET_MS extra, then it concludes from the port's facts
        val s = LateScreen({ currentTime }, page("列表"), page("列表"), afterMs = 0, peekMs = 2_000, result = ActionResult.Ok(changed = true))
        val d = toolDispatcher(s)
        val start = currentTime
        val c = d.execute(call("phone_back")).content
        val spent = currentTime - start
        // The look before the action and the judgement after it each have their own cap
        assertTrue("spent $spent", spent <= EffectTuning.PROBE_MS + EffectTuning.BUDGET_MS)
        assertEquals("small_change", c.kind())
    }
}
