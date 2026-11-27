package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.tool.placeOf
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GroupMember
import com.wangzan101.mika.core.model.MemberKey
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.BATCHABLE
import com.wangzan101.mika.core.testing.verb
import com.wangzan101.mika.core.testing.SANDBOX_TOOLS

private class NamedModel(override val name: String, toolCalls: Boolean = true, vision: Boolean = false) : ChatModel {
    override val capabilities = Capabilities(toolCalls = toolCalls, vision = vision, contextTokens = 64_000, reasoning = false)
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("not called")
}

class ModelRouterTest {
    private val main = NamedModel("main")
    private val light = NamedModel("light")
    private fun clean(step: Int) = StepContext(step, lastOk = true, lastHadGuard = false, lastChanged = true, lastWasRecovery = false, sameAppAsLast = true)

    @Test fun firstStepUsesMain() {
        assertSame(main, ModelRouter(main, light).pick(clean(1)))
    }

    @Test fun lightGroupPoolIsRecognizedAndActive() {
        val tool = NamedModel("tool")
        val plain = NamedModel("plain", toolCalls = false)
        val g = ModelGroup("g", "轻量组", members = listOf(GroupMember("a"), GroupMember("b")))
        val pool = GroupPool(
            g,
            listOf(PoolMember(MemberKey("p", "tool"), tool), PoolMember(MemberKey("p", "plain"), plain, priority = 1)),
            ModelHealthRegistry(), StickyTable(), null,
        )
        val r = ModelRouter(main, pool)
        // Enable lightSteps as long as at least one member supports tools; for steps that actually need tools, filter by member
        assertTrue(r.active)
        assertSame(pool, r.pick(clean(2)))
        assertTrue(r.isLight(pool))
        assertEquals("轻量组", r.groupOf(pool))
    }

    @Test fun lightAfterCleanStep() {
        val r = ModelRouter(main, light)
        assertSame(light, r.pick(clean(2)))
        assertSame(light, r.pick(clean(3)))
        // Screen reads and similar do not report screen changes, so they do not count as unchanged
        assertSame(light, r.pick(clean(4).copy(lastChanged = null)))
    }

    @Test fun eachConditionKeepsMain() {
        val r = ModelRouter(main, light)
        assertSame(main, r.pick(clean(5).copy(lastWasRecovery = true)))
        assertSame(main, r.pick(clean(5).copy(sameAppAsLast = false)))
        // A light model that cannot see images cannot accept screenshots
        assertSame(main, r.pick(clean(5).copy(hasImages = true)))
        val seeing = NamedModel("seeing", vision = true)
        assertSame(seeing, ModelRouter(main, seeing).pick(clean(5).copy(hasImages = true)))
    }

    @Test fun userSteerKeepsThatStepOnMainOnly() {
        val r = ModelRouter(main, light)
        assertSame(main, r.pick(clean(5).copy(userSpoke = true)))
        // 不算出状况:下一步照常给轻量模型
        assertSame(light, r.pick(clean(6)))
    }

    @Test fun troubleForcesMainForTwoSteps() {
        for (trouble in listOf(clean(3).copy(lastOk = false), clean(3).copy(lastHadGuard = true), clean(3).copy(lastChanged = false))) {
            val r = ModelRouter(main, light)
            assertSame(main, r.pick(trouble))
            assertSame(main, r.pick(clean(4)))
            assertSame(light, r.pick(clean(5)))
        }
        val r = ModelRouter(main, light)
        r.noteTrouble()
        assertSame(main, r.pick(clean(6)))
        assertSame(main, r.pick(clean(7)))
        assertSame(light, r.pick(clean(8)))
    }

    @Test fun disabledRouterAlwaysMain() {
        val r = ModelRouter(main, light)
        r.disable()
        assertSame(main, r.pick(clean(5)))
        org.junit.Assert.assertFalse(r.active)
    }

    @Test fun markRecoveryMakesNextStepRecovery() {
        val t = StepTrack()
        t.after(verb("phone_observe"), ok = true, guard = false, changed = null, recovery = false, app = "com.x")
        t.after(verb("phone_observe"), ok = true, guard = false, changed = null, recovery = false, app = "com.x")
        assertSame(light, ModelRouter(main, light).pick(t.context(3, hasImages = false)))
        t.markRecovery()
        assertSame(main, ModelRouter(main, light).pick(t.context(3, hasImages = false)))
    }

    @Test fun noToolCapableLightMeansMain() {
        val r = ModelRouter(main, NamedModel("plain", toolCalls = false))
        assertSame(main, r.pick(clean(2)))
        assertSame(main, ModelRouter(main, null).pick(clean(2)))
    }

    // root 下跑的命令是模型写的,和 phone_system_command 一样只让主模型给
    @Test fun rootCommandIsMainOnly() {
        assertTrue(ModelRouter.mainOnly("root_run_command", com.wangzan101.mika.core.tool.Verb.RootCommand))
        assertTrue(ModelRouter.mainOnly("phone_system_command", com.wangzan101.mika.core.tool.Verb.PhoneCommand))
    }

    @Test fun sandboxToolsAreMainOnlyAndNotBatchable() {
        assertTrue(SANDBOX_TOOLS.all { ModelRouter.mainOnly(it, verb(it)) })
        assertTrue(SANDBOX_TOOLS.none { it in BATCHABLE })
    }
}

/** "Where" distinguishes phone apps from websites: switching to the web and opening a URL both count as changing place. */
class PlaceTrackingTest {
    @Test fun placeOfSeparatesAppsAndSites() {
        fun o(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject
        org.junit.Assert.assertEquals(Place.app("com.x"), placeOf(o("""{"currentApp":"com.x"}""")))
        org.junit.Assert.assertEquals(Place.site("a.com"), placeOf(o("""{"currentSite":"a.com"}""")))
        org.junit.Assert.assertEquals(Place.site("a.com"), placeOf(o("""{"ok":true,"screen":{"currentSite":"a.com"}}""")))
        org.junit.Assert.assertNull(placeOf(o("""{"ok":true}""")))
    }

    @Test fun navigatingIsASwitch() {
        val t = StepTrack()
        t.after(verb("phone_tap"), true, false, true, false, "网站 a.com")
        t.after(verb("phone_tap"), true, false, true, false, "网站 a.com")
        org.junit.Assert.assertTrue(t.context(3, false).sameAppAsLast)
        t.after(verb("web_navigate"), true, false, true, false, "网站 a.com")
        org.junit.Assert.assertFalse(t.context(4, false).sameAppAsLast)
    }
}
