package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.SystemPrompt
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.fakeModel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tools come in two layers: the always-present general tools plus per-group entries (short descriptions); the first use of a group expands the whole group (full text) and it stays that way until the task ends;
 * the order is fixed and expanding only changes the part from that group onward; at startup, groups are pre-expanded from those used in the previous turn and from attached workspace files.
 */
class ToolGroupsTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("g${++seq}", name, args)
    private fun all() = toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(), workspace = { "w" })
    private fun names(specs: List<ToolSpec>) = specs.map { it.name }

    /** Sent to the model for chat only: the general tools (excluding hand_to_user, handle_obstacle and view_past_screens) plus the entry point of each capability. */
    private val chatOnly = listOf(
        "wait", "ask_user", "request_confirmation", "end_not_done",
        "phone_list_apps", "phone_launch_app", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event",
        "phone_calendar_bulk_insert", "web_navigate", "web_read_page", "run_command",
    )

    @Test fun chatOnlyGetsBaseAndEntries() {
        val d = all()
        assertEquals(chatOnly, names(d.offeredNow()))
        assertEquals(emptySet<String>(), d.expanded)
        // While an entry is collapsed it uses the short description, with the same parameters as the full text
        val now = d.offeredNow().associateBy { it.name }
        val full = d.definitions().associateBy { it.name }
        for (e in listOf("phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "phone_calendar_bulk_insert", "web_navigate", "web_read_page", "run_command")) {
            assertEquals(Prompts.entry(e), now.getValue(e).description)
            assertEquals(full.getValue(e).parametersJsonSchema, now.getValue(e).parametersJsonSchema)
            assertTrue(e, now.getValue(e).description.length < full.getValue(e).description.length)
        }
        // All tools (every group expanded), with none missing
        assertTrue(names(d.definitions()).containsAll(listOf("phone_tap", "web_tap", "read_file", "hand_to_user", "handle_obstacle", "view_past_screens")))
    }

    @Test fun entryCallExpandsItsGroup() = runTest {
        val d = all()
        val r = d.execute(call("phone_launch_app", """{"package":"com.x"}""")).content
        assertTrue("phone" in r["moreTools"]!!.jsonPrimitive.content)
        assertEquals(setOf("phone"), d.expanded)
        val now = d.offeredNow()
        // The whole group and the screen-dependent general tools are present, and the entry switches back to the full text
        assertTrue(names(now).containsAll(listOf("phone_observe", "phone_tap", "hand_to_user", "handle_obstacle", "view_past_screens")))
        assertEquals(d.definitions().single { it.name == "phone_launch_app" }, now.single { it.name == "phone_launch_app" })
        // Web and sandbox are still collapsed
        assertFalse("web_tap" in names(now))
        assertEquals(Prompts.entry("web_navigate"), now.single { it.name == "web_navigate" }.description)
        // A second call no longer reports it
        assertNull(d.execute(call("phone_launch_app", """{"package":"com.x"}""")).content["moreTools"])
        assertEquals(setOf("phone"), d.used)
    }

    @Test fun readersAloneDoNotExpand() = runTest {
        val d = all()
        d.execute(call("phone_list_apps"))
        // Setting an alarm or a timer is enough on its own: the phone group is not expanded
        d.execute(call("phone_set_alarm", """{"hour":9,"minute":40}"""))
        d.execute(call("phone_set_timer", """{"seconds":600}"""))
        d.execute(call("phone_calendar_bulk_insert", """{"events":[{"title":"t","start":"2026-11-02T14:30"}]}"""))
        d.execute(call("web_read_page", """{"url":"https://example.com"}"""))
        assertEquals(emptySet<String>(), d.expanded)
        assertEquals(emptySet<String>(), d.used)
        assertEquals(chatOnly, names(d.offeredNow()))
    }

    @Test fun collapsedToolCalledFromMemoryRunsAndExpands() = runTest {
        val d = all()
        // The model never saw phone_observe (the phone group is collapsed) and calls it from memory: it runs as usual and the phone group expands
        val r = d.execute(call("phone_observe")).content
        assertEquals("true", r["ok"]!!.jsonPrimitive.content)
        assertTrue(r["moreTools"] != null)
        assertEquals(setOf("phone"), d.expanded)
        // Bad arguments expand it too: next time the full text is visible so it can be corrected
        val w = d.execute(call("web_tap", """{"bogus":1}""")).content
        assertEquals("false", w["ok"]!!.jsonPrimitive.content)
        assertTrue(w["moreTools"] != null)
        assertEquals(setOf("phone", "web"), d.expanded)
    }

    @Test fun orderIsFixedAndExpandingOnlyChangesFromItsGroup() = runTest {
        val d = all()
        val collapsed = d.offeredNow()
        d.execute(call("web_navigate", """{"url":"https://example.com"}"""))
        val web = d.offeredNow()
        d.execute(call("phone_launch_app", """{"package":"com.x"}"""))
        val both = d.offeredNow()
        d.execute(call("run_command", """{"command":"ls"}"""))
        val everything = d.offeredNow()
        // The always-present ones stay at the very front, unchanged word for word
        val base = collapsed.takeWhile { it.name !in setOf("phone_list_apps", "phone_launch_app") }
        for (l in listOf(web, both, everything)) assertEquals(base, l.take(base.size))
        // Opening web then phone gives the same result as the reverse: order depends only on the group, not on the expansion sequence
        assertEquals(both, toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(), workspace = { "w" }).also {
            it.expand("phone"); it.expand("web")
        }.offeredNow())
        // Expanding the sandbox (last in order) only changes the tail: the phone and web groups before it stay word-for-word identical
        val runCommandAt = both.indexOfFirst { it.name == "run_command" }
        assertEquals(both.take(runCommandAt), everything.take(runCommandAt))
        // Every group expanded: this is all the tools (ordered by group)
        assertEquals(d.definitions().map { it.name }.toSet(), names(everything).toSet())
        assertEquals(everything, d.offeredNow(all = true))
        // The screen-dependent general-tools group sits after the always-present ones and before the phone group
        val i = names(everything)
        assertTrue(i.indexOf("end_not_done") < i.indexOf("hand_to_user") && i.indexOf("view_past_screens") < i.indexOf("phone_observe"))
    }

    @Test fun batchSpecGoesWithScreenGroups() {
        val d = all()
        val batch = ToolSpec("do_steps", "d", "{}")
        assertFalse("do_steps" in names(d.offeredNow(listOf(batch))))
        d.expand("sandbox")
        assertFalse("do_steps" in names(d.offeredNow(listOf(batch))))
        d.expand("web")
        val now = names(d.offeredNow(listOf(batch)))
        // It is placed at the end of the screen-dependent general-tools group, immediately before the phone group (collapsed, entries only)
        assertEquals(now.indexOf("handle_obstacle") + 1, now.indexOf("do_steps"))
        assertEquals("phone_list_apps", now[now.indexOf("do_steps") + 1])
    }

    @Test fun preloadRules() {
        // Groups used in the previous turn
        assertEquals(listOf("phone", "web"), all().preload("再看一下", listOf("web", "phone")))
        // With a workspace file attached (the attachment line contains a path under /workspace/)
        assertEquals(listOf("sandbox"), all().preload("总结一下\n附件:/workspace/inbox/a.pdf(12 KB)", emptyList()))
        // Asked from a browser page about that page: the page is already the current tab
        val page = com.wangzan101.mika.core.agent.AttachedPage.compose(com.wangzan101.mika.core.agent.AttachedPage.Page("https://shop.example.com/", "店"), "挑一个")
        assertEquals(listOf("web"), all().preload(page, emptyList()))
        // The device does not support the sandbox: do not expand
        val none = toolDispatcher(FakeScreen(), sandbox = FakeSandbox(SandboxStatus.Unsupported("x86")), workspace = { "w" })
        assertEquals(emptyList<String>(), none.preload("附件:/workspace/inbox/a.pdf", listOf("sandbox")))
        // Groups this task does not have are not expanded; with nothing at all, everything stays collapsed
        assertEquals(emptyList<String>(), toolDispatcher(FakeScreen()).preload("看看", listOf("web")))
        assertEquals(emptyList<String>(), all().preload("1+1 等于几", emptyList()))
    }

    @Test fun loopPreloadsFromPreviousTurnAndRecordsUsedGroups() = runTest {
        val m = fakeModel("main") { call("phone_launch_app", "package" to "com.x"); answer("好了") }
        val loop = AgentLoop(m, all(), SystemPrompt.identity())
        loop.run("打开看看")
        assertEquals(setOf("phone"), loop.usedGroups)
        // The next turn carries 'phone was used last turn': the very first request already includes the whole phone group and the phone prompt section
        val m2 = fakeModel("main") { answer("好") }
        AgentLoop(m2, all(), SystemPrompt.identity()).run("再看一下", listOf(Turn("打开看看", "好了", "succeeded", groups = loop.usedGroups.toList())))
        val first = m2.requests.first()
        assertTrue("phone_tap" in first.tools.map { it.name })
        assertFalse("web_tap" in first.tools.map { it.name })
        assertTrue(SystemPrompt.read("phone").lineSequence().first() in first.system)
    }

    @Test fun childAndScheduledRunsUseTheSameLayers() = runTest {
        // A child task has only web and the (read-only) sandbox: web likewise gets entries only and expands on use; the read-only sandbox has no entry (running commands is not offered), so that group starts expanded
        val child = toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(), workspace = { "w" },
            runSpec = RunSpec(RunId("c"), "查", "t", provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended)))
        val start = names(child.offeredNow())
        assertEquals(listOf("web_navigate", "web_read_page"), start.filter { it.startsWith("web_") || it.startsWith("phone_") })
        assertTrue("read_file" in start && "search_files" in start && "run_command" !in start)
        assertEquals(setOf("sandbox"), child.expanded)
        child.execute(call("web_navigate", """{"url":"https://example.com"}"""))
        assertEquals(setOf("sandbox", "web"), child.expanded)
        assertTrue("web_observe" in names(child.offeredNow()))
        // A trigger-scheduled task (nobody present): hand_to_user is not offered, the rest is layered the same way
        val scheduled = toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = RunSpec(RunId("s"), "查", "t",
            provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended)))
        assertFalse("phone_observe" in names(scheduled.offeredNow()))
        scheduled.execute(call("phone_launch_app", """{"package":"com.x"}"""))
        val now = names(scheduled.offeredNow())
        assertTrue("phone_observe" in now && "handle_obstacle" in now && "hand_to_user" !in now)
    }
}
