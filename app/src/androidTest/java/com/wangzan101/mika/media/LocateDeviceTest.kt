package com.wangzan101.mika.media

import com.wangzan101.mika.runAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.screen.vision.VisionService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * phone_find_visually browses the system settings read-only on the Agent virtual display (requires the built-in assistant and the accessibility service).
 * There is no real vision model: the vision path uses a scripted model, and the box is computed from the coordinates of the "WLAN" row in the view tree.
 */
@RunWith(AndroidJUnit4::class)
class LocateDeviceTest {
    private var agent: DeviceSupport.Agent? = null

    @After
    fun tearDown() {
        agent?.release()
    }

    /** A vision model that hands out the fixed answers in order. */
    private class ScriptedVision(vararg answers: String, verified: Boolean = true) : ChatModel {
        override val capabilities = Capabilities(toolCalls = false, vision = true, contextTokens = 32_000, reasoning = false, groundingVerified = verified)
        private val answers = ArrayDeque(answers.toList())
        var asked = 0
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            asked++
            return ModelReply(listOf(Part.Text(answers.removeFirst())), StopReason.EndTurn, null)
        }
    }

    private fun DeviceSupport.Agent.snapUntil(test: (ScreenSnapshot) -> Boolean): ScreenSnapshot = runBlocking {
        val end = System.currentTimeMillis() + 5_000
        var s = screen.snapshot()
        while (!test(s) && System.currentTimeMillis() < end) {
            Thread.sleep(200)
            s = screen.snapshot()
        }
        assertTrue("screen never matched: ${s.elements.map { it.name }}", test(s))
        s
    }

    private fun openSettingsHome(): Pair<DeviceSupport.Agent, ScreenSnapshot> {
        val a = DeviceSupport.newAgent().also { agent = it }
        val r = runBlocking { a.screen.openApp(SETTINGS) }
        assertTrue("openApp: $r", r is ActionResult.Ok)
        val home = a.snapUntil { s -> s.elements.any { it.name.trim() == "WLAN" } }
        // Only after the transition animation finishes do two screenshots line up
        Thread.sleep(1_000)
        return a to home
    }

    private fun ToolDispatcher.run(name: String, args: String): JsonObject =
        runBlocking { runAction(Part.ToolCall("d", name, args)).content }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private fun tapAndExpectWlanPage(a: DeviceSupport.Agent, d: ToolDispatcher, r: JsonObject) {
        val tap = d.run("phone_tap", """{"ref":"${r.s("ref")}","snapshotId":"${r.s("snapshotId")}"}""")
        assertEquals(tap.toString(), "true", tap.s("ok"))
        a.snapUntil { s -> s.elements.any { it.role == "Switch" } }
    }

    @Test
    fun treePathFindsWlan() {
        val (a, _) = openSettingsHome()
        val model = ScriptedVision()
        val d = toolDispatcher(a.screen, vision = VisionService(model, BitmapImageOps))
        val r = d.run("phone_find_visually", """{"target":"WLAN","text":"WLAN"}""")
        assertEquals(r.toString(), "true", r.s("found"))
        assertEquals("elements", r.s("source"))
        assertEquals("控件树命中不问视觉模型", 0, model.asked)
        tapAndExpectWlanPage(a, d, r)
    }

    @Test
    fun visionPathTapsWlan() {
        val (a, home) = openSettingsHome()
        val row = home.elements.first { it.name.trim() == "WLAN" }.bounds
        // Convert the 0–999 screen-reader scale to the 0–1000 scale the model uses
        val box = row.map { (it * 1000 / 999.0).roundToInt() }
        val model = ScriptedVision("""{"bbox_2d": [${box.joinToString(", ")}], "seen": "WLAN"}""", "是")
        val d = toolDispatcher(a.screen, vision = VisionService(model, BitmapImageOps))
        // No text given: the view-tree path is skipped
        val r = d.run("phone_find_visually", """{"target":"WLAN 那一行"}""")
        assertEquals(r.toString(), "true", r.s("found"))
        assertEquals("vision", r.s("source"))
        assertEquals(2, model.asked)
        tapAndExpectWlanPage(a, d, r)
    }

    @Test
    fun unverifiedVisionNeedsSecondLook() {
        val (a, home) = openSettingsHome()
        val row = home.elements.first { it.name.trim() == "WLAN" }.bounds
        val box = """{"bbox_2d": [${row.map { (it * 1000 / 999.0).roundToInt() }.joinToString(", ")}], "seen": "WLAN"}"""
        // Localization accuracy has not been verified: box once more, and only hand it over after both re-checks confirm it
        val model = ScriptedVision(box, "是", box, "是", verified = false)
        val d = toolDispatcher(a.screen, vision = VisionService(model, BitmapImageOps))
        val r = d.run("phone_find_visually", """{"target":"WLAN 那一行"}""")
        assertEquals(r.toString(), "true", r.s("found"))
        assertEquals(4, model.asked)
        tapAndExpectWlanPage(a, d, r)
    }
}
