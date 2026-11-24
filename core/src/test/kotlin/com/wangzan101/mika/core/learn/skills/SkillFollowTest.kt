package com.wangzan101.mika.core.learn.skills

import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.knowledge.SkillPlaces
import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.run.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only actually following it counts; when places are declared, there must be a successful action that happened at that place. */
class SkillFollowTest {
    private val tools = knownTools()
    private fun read(name: String, at: Long) = RunEvent.SkillUsed(at, name, "viewed")
    private fun act(at: Long, tool: String = "phone_tap", ok: Boolean = true) =
        RunEvent.ToolResult(at, 1, tool, if (ok) """{"ok":true}""" else """{"ok":false}""", ok, null)
    private fun saw(at: Long, place: Place) = RunEvent.Observed(at, 1, "界面", place = place)
    private fun followed(events: List<RunEvent>, places: Map<String, SkillPlaces> = emptyMap()) =
        SkillFollow.followed(events, events.filterIsInstance<RunEvent.SkillUsed>(), tools) { places[it] }

    @Test fun readThenActedCountsWhenNoAppsAreDeclared() {
        assertEquals(setOf("s"), followed(listOf(read("s", 10), act(20))))
    }

    @Test fun readingAfterTheWorkOrOnlyFailedActionsDoNotCount() {
        assertTrue(followed(listOf(act(5), read("s", 10))).isEmpty())
        assertTrue(followed(listOf(read("s", 10), act(20, ok = false))).isEmpty())
        // reading the screen or looking up a skill is not acting
        assertTrue(followed(listOf(read("s", 10), act(20, tool = "phone_observe"), act(21, tool = "skill_read"))).isEmpty())
    }

    @Test fun actionMustMatchDeclaredAppAfterReading() {
        val wechat = mapOf("s" to SkillPlaces(packages = setOf("com.tencent.mm")))
        val inWechat = act(20).copy(resultJson = """{"ok":true,"actionApp":"com.tencent.mm"}""")
        assertEquals(setOf("s"), followed(listOf(read("s", 10), inWechat), wechat))
        assertTrue(followed(listOf(read("s", 10), act(20), saw(21, Place.app("com.tencent.mm"))), wechat).isEmpty())
        // WeChat is entered before the skill is read, so the action after reading happens elsewhere
        assertTrue(followed(listOf(saw(5, Place.app("com.tencent.mm")), read("s", 10), act(20)), wechat).isEmpty())
    }

    @Test fun visitingSkillAppDoesNotAttributeAnActionInAnotherApp() {
        val wechat = mapOf("s" to SkillPlaces(packages = setOf("com.tencent.mm")))
        val unrelated = act(20).copy(resultJson = """{"ok":true,"screen":{"currentApp":"com.android.settings"}}""")
        assertTrue(followed(listOf(read("s", 10), saw(15, Place.app("com.tencent.mm")), unrelated), wechat).isEmpty())
    }

    @Test fun declaredSiteMatchesSubdomainsAndPages() {
        val bili = mapOf("s" to SkillPlaces(domains = setOf("bilibili.com")))
        val page = act(20, "web_navigate").copy(resultJson = """{"ok":true,"url":"https://m.bilibili.com/video/1"}""")
        assertEquals(setOf("s"), followed(listOf(read("s", 10), page), bili))
        val unrelated = page.copy(resultJson = """{"ok":true,"url":"https://notbilibili.com/video/1"}""")
        assertTrue(followed(listOf(read("s", 10), unrelated), bili).isEmpty())
    }

    @Test fun eachSkillIsJudgedOnItsOwn() {
        val places = mapOf("wx" to SkillPlaces(packages = setOf("com.tencent.mm")), "dy" to SkillPlaces(packages = setOf("com.ss.android.ugc.aweme")))
        val tap = act(20).copy(resultJson = """{"ok":true,"actionApp":"com.tencent.mm","screen":{"currentApp":"com.ss.android.ugc.aweme"}}""")
        val events = listOf(read("dy", 10), read("wx", 11), tap)
        assertEquals(setOf("wx"), followed(events, places))
    }

    // when not a single character can be read off the screen there is no screen observation record: currentApp / currentSite reported in tool results still count as having visited
    @Test fun currentAppInToolResultsCountsAsVisiting() {
        val wechat = mapOf("s" to SkillPlaces(packages = setOf("com.tencent.mm")))
        val result = RunEvent.ToolResult(20, 1, "phone_tap", """{"ok":true,"screen":{"currentApp":"com.tencent.mm","full":false}}""", true, null)
        assertEquals(setOf("s"), followed(listOf(read("s", 10), result), wechat))
        val bili = mapOf("s" to SkillPlaces(domains = setOf("bilibili.com")))
        val page = RunEvent.ToolResult(20, 1, "web_navigate", """{"ok":true,"currentSite":"m.bilibili.com"}""", true, null)
        assertEquals(setOf("s"), followed(listOf(read("s", 10), page), bili))
        // results from before the skill was read do not count
        assertTrue(followed(listOf(result.copy(atMs = 5), read("s", 10), act(20)), wechat).isEmpty())
    }

    // skill headers may hold a knowledge name, an alias, a display name (hand-written) or a package name (learned): anything recognizable is converted to package names and domains
    @Test fun catalogResolvesKnowledgeNamesAliasesAndKeepsRawPackages() {
        val skills = InMemoryPersonalSkills(
            personalSkill("by-name", extraMeta = "apps: [\"wechat\"]\nsites: [\"bilibili.com\"]\n"),
            personalSkill("by-alias", extraMeta = "apps: [\"微信\", \"WeChat\"]\n"),
            personalSkill("by-package", extraMeta = "apps: [com.example.app]\n"),
            personalSkill("unknown", extraMeta = "apps: [\"一个不存在的 App\", \"wechatt\"]\n"),
            personalSkill("plain"),
        )
        val catalog = GuideCatalog.withPersonal(GuideCatalog.loadBundled(), skills)
        val byName = catalog.skillPlaces("by-name")!!
        assertTrue("com.tencent.mm" in byName.packages)
        assertTrue("bilibili.com" in byName.domains)
        assertEquals(setOf("com.tencent.mm"), catalog.skillPlaces("by-alias")!!.packages)
        assertTrue("com.example.app" in catalog.skillPlaces("by-package")!!.packages)
        // unrecognizable values that do not look like package names do not count as declared: otherwise this skill would never recognize that it was followed
        assertEquals(false, catalog.skillPlaces("unknown")!!.declared)
        assertEquals(false, catalog.skillPlaces("plain")!!.declared)
        assertNull(catalog.skillPlaces("missing"))
    }

    // app and site knowledge that register each other count together: a skill declares a site and the task is done in its app, which still counts as following it
    @Test fun appAndSiteKnowledgeAreLinked() {
        val skills = InMemoryPersonalSkills(personalSkill("bili", extraMeta = "sites: [\"bilibili.com\"]\n"))
        val places = GuideCatalog.withPersonal(GuideCatalog.loadBundled(), skills).skillPlaces("bili")!!
        val biliApp = GuideCatalog.loadBundled().rows.first { it.name == "bilibili" && it.packages.isNotEmpty() }.packages.first()
        assertTrue(biliApp in places.packages)
        val events = listOf(read("bili", 10), act(20).copy(resultJson = """{"ok":true,"actionApp":"$biliApp"}"""))
        assertEquals(setOf("bili"), SkillFollow.followed(events, events.filterIsInstance<RunEvent.SkillUsed>(), tools) { places })
    }
}
