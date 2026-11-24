package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.memory.MemoryContext
import com.wangzan101.mika.core.learn.memory.MemoryItem
import com.wangzan101.mika.core.learn.memory.MemoryKind
import com.wangzan101.mika.core.learn.memory.MemorySource
import com.wangzan101.mika.core.learn.memory.MemoryStatus
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.learn.replay.Anchors
import com.wangzan101.mika.core.learn.replay.Route
import com.wangzan101.mika.core.learn.replay.RouteStep
import com.wangzan101.mika.core.learn.replay.withCurrentToolNames
import com.wangzan101.mika.core.learn.skills.InMemoryPersonalSkills
import com.wangzan101.mika.core.learn.skills.PersonalSkill
import com.wangzan101.mika.core.learn.skills.SkillStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Data stored before the rename is converted to current names via the old-name table when read in; calling an old name only produces a hint and is never executed on the model's behalf. */
class LegacyNamesTest {
    @Test fun unknownLegacyCallNamesTheReplacementAndDoesNothing() = runTest {
        val screen = FakeScreen()
        val d = toolDispatcher(screen, web = FakeWebPort())
        val r = d.execute(Part.ToolCall("c", "tap", """{"x":1,"y":1}""")).content
        assertEquals("unknown_tool", r["error"]!!.jsonPrimitive.content)
        val m = r["message"]!!.jsonPrimitive.content
        assertTrue(m, "phone_tap or web_tap" in m && "renamed" in m)
        assertTrue(screen.calls.isEmpty())
        // When only the phone tools are available, only the phone variant is mentioned
        val phoneOnly = toolDispatcher(FakeScreen()).execute(Part.ToolCall("c", "browser_navigate", "{}")).content
        assertFalse(phoneOnly["message"]!!.jsonPrimitive.content.contains("renamed"))
        val launch = toolDispatcher(FakeScreen()).execute(Part.ToolCall("c", "launch_app", "{}")).content["message"]!!.jsonPrimitive.content
        assertTrue(launch, "phone_launch_app" in launch)
    }

    // The retired finish: occurrences in stored skills and procedures are rewritten to the current approach; when the model calls it, it is told how to finish now
    @Test fun retiredFinishIsRewrittenAndExplained() = runTest {
        val legacy = legacyNames()
        val zh = legacy.rewrite("查完后调用 finish 交结果")
        assertFalse(zh, "finish" in zh)
        assertTrue(zh, "reply to the user with your answer as text" in zh)
        val en = legacy.rewrite("When done, call `finish` with the price. The page may finish loading slowly.")
        assertTrue(en, "When done, reply to the user with your answer as text" in en)
        // The English verb "finish" is not a call to the tool and is left untouched
        assertTrue(en, "may finish loading" in en)
        val r = toolDispatcher(FakeScreen()).execute(Part.ToolCall("c", "finish", """{"answer":"x"}""")).content
        val m = r["message"]!!.jsonPrimitive.content
        assertTrue(m, "no tool 'finish'" in m && "without calling any tool" in m)
    }

    // The retired phone_ask_app_ai: occurrences in old records and old skills are rewritten to the current approach, and when the model calls it, it is told it no longer exists
    @Test fun retiredAskAppAiIsRewrittenAndExplained() = runTest {
        val zh = legacyNames().rewrite("先 `phone_ask_app_ai` 问一下")
        assertFalse(zh, "phone_ask_app_ai" in zh)
        assertTrue(zh, "read the app's own screens yourself" in zh)
        val m = toolDispatcher(FakeScreen()).execute(Part.ToolCall("c", "phone_ask_app_ai", "{}")).content["message"]!!.jsonPrimitive.content
        assertTrue(m, "no tool 'phone_ask_app_ai'" in m)
    }

    @Test fun storedRoutesGetTheRoutableName() {
        val none = Anchors(null, emptyList())
        val r = Route("r", "t", 0, listOf("com.a"), emptyMap(), listOf(
            RouteStep("launch_app", "{}", null, none, none), RouteStep("tap", "{}", null, none, none), RouteStep("phone_back", "{}", null, none, none),
        ), lastUsedMs = 1)
        assertEquals(listOf("phone_launch_app", "phone_tap", "phone_back"), r.withCurrentToolNames(legacyNames(), knownTools()).steps.map { it.tool })
    }

    @Test fun personalSkillsAndProceduresAreRewrittenWhenLoaded() {
        val md = "---\nname: notes\ndescription: d\n---\n## 步骤\n1. 用 `launch_app` 打开,`tap` 新建"
        val catalog = GuideCatalog.withPersonal(GuideCatalog.loadBundled(), InMemoryPersonalSkills(PersonalSkill("notes", md, SkillStatus.active, null, null, 0, emptyList())), legacyNames()::rewrite)
        val text = catalog.fullText(catalog.items.first { it.name == "notes" })!!
        assertTrue(text, "`phone_launch_app`" in text && "`phone_tap` / `web_tap`" in text)
        val item = MemoryItem("m1", MemoryKind.procedure, "打开备忘录先 launch_app 再搜索", listOf("备忘录"), MemorySource("manual", atMs = 0), MemoryStatus.active, pinned = true, createdAt = 0, updatedAt = 0)
        val block = MemoryContext.build(listOf(item), "打开备忘录", null, legacyNames()::rewrite)
        assertTrue(block.message.orEmpty(), "phone_launch_app" in block.message.orEmpty())
    }

    // Second rename: old names that were entirely replaced get a hint about the new name when called, and text occurrences are replaced with the new name
    @Test fun movedNamesAreHintedAndRewritten() = runTest {
        val d = toolDispatcher(FakeScreen(), web = FakeWebPort())
        fun hint(name: String) = d.legacy.replacements(name)
        assertEquals(listOf("end_not_done"), hint("report_failure"))
        assertEquals(listOf("hand_to_user"), hint("user_takeover"))
        assertEquals(listOf("ask_user"), hint("request_input"))
        assertEquals(listOf("handle_obstacle"), hint("report_barrier"))
        assertEquals(listOf("view_past_screens"), hint("recall_screenshot"))
        assertEquals(listOf("run_bundled_script"), hint("run_script"))
        assertEquals(listOf("phone_list_elements"), hint("phone_dump_elements"))
        assertEquals(listOf("web_diagnose"), hint("web_check_screen"))
        // Names shared by several screens in the earliest generation map straight to the current ones
        assertEquals(setOf("phone_find_visually", "web_find_visually"), hint("locate_on_screen").toSet())
        assertEquals(setOf("phone_describe", "web_describe"), hint("screen_description").toSet())
        val m = d.execute(Part.ToolCall("c", "report_failure", """{"message":"x"}""")).content["message"]!!.jsonPrimitive.content
        assertTrue(m, "end_not_done" in m && "renamed" in m)
        val text = legacyNames().rewrite("先 phone_dump_elements,不行就调用 `user_takeover`,再 phone_check_screen")
        assertEquals("先 phone_list_elements,不行就调用 `hand_to_user`,再 phone_diagnose", text)
    }

    // Old names that split into knowledge and skill: pick the matching one by kind or name prefix, and mention both when it cannot be determined
    @Test fun splitNamesPickByKind() = runTest {
        val d = toolDispatcher(FakeScreen(), skills = GuideCatalog.loadBundled())
        suspend fun message(name: String, args: String) = d.execute(Part.ToolCall("c", name, args)).content["message"]!!.jsonPrimitive.content
        val app = message("find_skills", """{"query":"外卖","kind":"app"}""")
        assertTrue(app, "knowledge_search" in app && "skill_search" !in app)
        val skill = message("find_skills", """{"query":"文档","kind":"skill"}""")
        assertTrue(skill, "skill_search" in skill && "knowledge_search" !in skill)
        val either = message("find_skills", """{"query":"外卖"}""")
        assertTrue(either, "knowledge_search or skill_search" in either)
        val site = message("skill_view", """{"name":"site:jd.com"}""")
        assertTrue(site, "knowledge_read" in site && "skill_read" !in site)
        val personal = message("skill_view", """{"name":"personal:my-trip"}""")
        assertTrue(personal, "skill_read" in personal && "knowledge_read" !in personal)
        val bare = message("skill_view", """{"name":"meituan"}""")
        assertTrue(bare, "knowledge_read or skill_read" in bare)
        // Stored text gives no way to tell which one, so both are written
        assertEquals("用 `knowledge_read` / `skill_read` 看全文", legacyNames().rewrite("用 `skill_view` 看全文"))
    }

    // Run records written before the rename: old names resolve to the current tools
    @Test fun recordedOldNamesResolve() {
        val tools = com.wangzan101.mika.core.assembly.recordedTools()
        assertEquals(Verb.EndNotDone, tools.verb("report_failure"))
        assertEquals(Verb.ListElements, tools.verb("phone_dump_elements"))
        assertEquals(Verb.ListElements, tools.verb("dump_elements"))
        assertEquals(Verb.HandToUser, tools.verb("user_takeover"))
    }
}
