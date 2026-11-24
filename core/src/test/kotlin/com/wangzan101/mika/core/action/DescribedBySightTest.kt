package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.safety.gate.ConfirmationGate
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.testing.field
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.Verification
import com.wangzan101.mika.core.screen.vision.JvmTestImageOps
import com.wangzan101.mika.core.screen.vision.LocScreen
import com.wangzan101.mika.core.screen.vision.VisionService
import com.wangzan101.mika.core.screen.vision.uiEl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Point at an input box by description while the tree can't read this screen: fall back to vision to find the box, then verify, judge irreversibility and verify the paste just as when passing @vN. */
class DescribedBySightTest {
    private val sink = object : RunSink { override fun event(e: RunEvent) {} }

    /** One screen (by default the tree holds nothing, i.e. the tree is unusable); records the typing the port receives. */
    private class Blind(inner: LocScreen = LocScreen()) : ScreenPort by inner {
        val calls = mutableListOf<String>()
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { calls += "type $ref $text"; return ActionResult.Ok(changed = true) }
        override suspend fun typeAt(x: Int, y: Int, text: String): ActionResult {
            calls += "typeAt $x,$y $text"; return ActionResult.Ok(changed = true, verification = Verification.MATCH)
        }
        override suspend fun pressKey(keyCode: Int): ActionResult { calls += "key $keyCode"; return ActionResult.Ok() }
    }

    private fun model(vararg replies: String) = FakeModel(
        *replies.map { ModelReply(listOf(Part.Text(it)), StopReason.EndTurn, null) }.toTypedArray(), groundingVerified = false)

    private fun executor(screen: ScreenPort, vision: FakeModel) = ActionExecutor(
        toolDispatcher(screen, vision = VisionService(vision, JvmTestImageOps)), OperationGuards(), ConfirmationGate(), gate = {},
        recorder = ActionRecorder(sink))

    private suspend fun type(e: ActionExecutor, args: String) = e.run(Part.ToolCall("c", "phone_type_text", args), Origin.Model(1))

    private fun content(o: ActionOutcome): JsonObject = (o as ActionOutcome.Ran).content
    private fun JsonObject.s(k: String) = (this[k] as JsonPrimitive).content

    @Test fun blindScreenFindsTheBoxBySightAndTypesThere() = runTest {
        val screen = Blind()
        val vision = model("""{"bbox_2d":[0,880,800,940],"seen":"输入框"}""", "是")
        val c = content(type(executor(screen, vision), """{"element":"底部的消息输入框","text":"明天见"}"""))
        assertEquals(c.toString(), "true", c.s("ok"))
        // Convert the 0–999 box centre to pixels, tap once, then paste
        assertEquals(listOf("typeAt 432,2183 明天见"), screen.calls)
        assertEquals("true", c.s("foundBySight"))
        assertEquals("底部的消息输入框", c.s("resolvedFrom"))
        assertEquals("输入框", c.s("seen"))
        // Vision only, no corroboration: still flagged
        assertEquals("true", c.s("unverifiedPoint"))
        assertEquals(2, vision.requests.size)
    }

    @Test fun notSeenIsReportedAndNothingIsTyped() = runTest {
        val screen = Blind()
        val c = content(type(executor(screen, model("null", "null")), """{"element":"底部的消息输入框","text":"明天见"}"""))
        assertEquals("element_not_found", c.s("error"))
        assertTrue(c.s("message"), "not_found" in c.s("message") && Feedback["described.not_seen.retry"] in c.s("message"))
        assertTrue(screen.calls.isEmpty())
    }

    @Test fun boxFoundOnASendButtonIsUsedWithoutAsking() = runTest {
        // Judged irreversible: it's a send, costs no money, so don't ask first
        val screen = Blind()
        val vision = model("""{"bbox_2d":[800,880,999,940],"seen":"发送"}""", "是")
        val o = type(executor(screen, vision), """{"element":"右下角的框","text":"明天见"}""")
        assertTrue("$o", o is ActionOutcome.Ran)
        assertTrue(screen.calls.isNotEmpty())
    }

    @Test fun readableTreeWithoutAMatchDoesNotLookBySight() = runTest {
        val inner = LocScreen(List(8) { i -> uiEl("@e$i", "设置项$i", listOf(0, i * 100, 999, i * 100 + 80)) } +
            field("@e9", "搜索", y = 900))
        val screen = Blind(inner)
        val vision = model()
        val c = content(type(executor(screen, vision), """{"element":"消息框","text":"明天见"}"""))
        assertEquals("element_not_found", c.s("error"))
        assertTrue(c.s("message"), "phone_observe" in c.s("message"))
        assertTrue(vision.requests.isEmpty())
        assertTrue(screen.calls.isEmpty())
    }

    @Test fun namedCustomEditorInReadableTreeUsesVisualConfirmation() = runTest {
        val inner = LocScreen(List(8) { i -> uiEl("@e$i", "设置项$i", listOf(0, i * 100, 999, i * 100 + 80)) } +
            uiEl("@e9", "消息输入区", listOf(0, 880, 800, 940)).copy(role = "View", editable = false))
        val screen = Blind(inner)
        val box = """{"bbox_2d":[0,880,800,940],"seen":"消息输入区"}"""
        val vision = model(box, "是", box, "是")
        val c = content(type(executor(screen, vision), """{"element":"消息输入区","text":"明天见"}"""))
        assertEquals(c.toString(), "true", c.s("ok"))
        assertEquals(listOf("typeAt 432,2183 明天见"), screen.calls)
        assertEquals(4, vision.requests.size)
    }
}
