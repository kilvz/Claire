package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS

class HistoryPrunerTest {
    private val big = """[{"ref":"@e1","name":"${"x".repeat(500)}"}]"""
    private val task = Message(Role.User, listOf(Part.Text("task")), Author.User)
    private fun obs(id: String) = listOf(
        Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_observe", "{}")), Author.Mika),
        Message(Role.Tool, listOf(Part.ToolResult(id, """{"ok":true,"currentApp":"com.x","snapshotId":"s$id","elements":$big}""", false)), Author.Mika),
    )
    private fun tap(id: String) = listOf(
        Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_tap", "{}")), Author.Mika),
        Message(Role.Tool, listOf(Part.ToolResult(id, """{"ok":true,"changed":true}""", false)), Author.Mika),
    )
    private fun act(id: String, sid: String) = listOf(
        Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_tap", "{}")), Author.Mika),
        Message(Role.Tool, listOf(Part.ToolResult(id,
            """{"ok":true,"screen":{"snapshotId":"$sid","base":"x","full":false,"added":$big,"removed":[],"changed":[],"unchanged":3}}""", false)), Author.Mika),
    )
    private fun shot(id: Int) = Message(Role.User, listOf(Part.Text("screen"), Part.Image("AAAA", "image/jpeg", 580, 1280, screenshotId = id)), Author.Mika)

    /** [n] screen-read round trips. */
    private fun observations(n: Int) = listOf(task) + (1..n).flatMap { obs("c$it") }
    private fun results(ms: List<Message>) = ms.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
    /** Keep the most recent 2 round trips before the line as-is (the line used during compaction is decided by [ContextCompressor]; only the pruning method is tested here). */
    private val cut = HistoryPruner.Cut(2)
    private fun full(ms: List<Message>) = results(ms).filter { "@e1" in it.content }.map { it.callId }

    @Test fun cutLineKeepsTheLastWindowRounds() {
        assertEquals(listOf(0, 0, 1, 2, 3), (1..5).map { cut.before(it) })
        assertEquals(listOf(0, 1, 2), (1..3).map { HistoryPruner.LAST.before(it) })
    }

    @Test fun beforeTheLineShrinksExceptLatestFullRead() {
        val h = observations(7)
        val out = HistoryPruner.prune(h, TOOLS, cut)
        // The line falls after the 5th round trip: c1–c4 are pruned, while c5 (the latest full read before the line) and c6, c7 after the line stay as-is
        assertEquals(listOf("c5", "c6", "c7"), full(out))
        val first = results(out).first { it.callId == "c1" }.content
        assertTrue(HistoryPruner.OMITTED in first && "\"snapshotId\":\"sc1\"" in first && "\"currentApp\":\"com.x\"" in first)
        assertEquals(h.size, out.size)
        assertEquals(results(h).map { it.callId }, results(out).map { it.callId })
        // The original history is unchanged
        assertTrue("@e1" in results(h).first { it.callId == "c1" }.content)
    }

    @Test fun oldScreenDropsTreeAndOtherLargeStructuresNotJustElements() {
        val long = "x".repeat(500)
        val structured = listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("structured", "phone_observe", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("structured",
                """{"ok":true,"snapshotId":"old","elements":$big,"tree":[{"id":1,"nodes":[{"role":"TextView","text":"$long"}]}],"screenMap":{"rows":["$long"]}}""",
                false)), Author.Mika),
        )
        // c2 is the latest full screen kept before the line, so structured must be shortened as an older interface state.
        val h = listOf(task) + structured + obs("c2") + (1..6).flatMap { tap("t$it") }
        val old = results(HistoryPruner.prune(h, TOOLS, cut)).single { it.callId == "structured" }.content
        assertTrue(old, "\"snapshotId\":\"old\"" in old && "\"ok\":true" in old)
        assertTrue(old, "\"elements\":\"${HistoryPruner.OMITTED}\"" in old)
        assertTrue(old, "\"tree\":\"${HistoryPruner.OMITTED}\"" in old)
        assertTrue(old, "\"screenMap\":\"${HistoryPruner.OMITTED}\"" in old)
        assertTrue("old tree/read-aid payload survived pruning", long !in old)
    }

    @Test fun oldSmallTableIsOmittedAtTheSamePressureBoundaryAsElements() {
        val packed = obs("p").map { m -> m.copy(parts = m.parts.map { p ->
            if (p is Part.ToolResult) p.copy(content = """{"ok":true,"snapshotId":"s1","elementTables":[{"columns":["ref"],"rows":[["@e1"]]}]}""") else p
        }) }
        val h = listOf(task) + packed + obs("latest") + (1..5).flatMap { tap("t$it") }
        val visible = results(HistoryPruner.prune(h, TOOLS, cut))
        assertTrue(HistoryPruner.OMITTED in visible.first().content)
        assertTrue("@e1" in visible[1].content)
    }

    @Test fun smallResultsAndHeadUntouched() {
        val h = listOf(task) + (1..8).flatMap { obs("c$it") + tap("t$it") }
        val out = HistoryPruner.prune(h, TOOLS, cut)
        assertEquals(results(h).filter { it.callId.startsWith("t") }, results(out).filter { it.callId.startsWith("t") })
        assertEquals(task, out[0])
        assertEquals(observations(2), HistoryPruner.prune(observations(2), TOOLS, cut))
    }

    @Test fun neverBreaksToolPairs() {
        for (n in 1..14) {
            val h = observations(n)
            val out = HistoryPruner.prune(h, TOOLS, cut)
            assertEquals(h.map { it.role }, out.map { it.role })
            assertEquals(results(h).map { it.callId }, results(out).map { it.callId })
            assertTrue(full(out).isNotEmpty())
        }
    }

    @Test fun diffChainKeepsItsBase() {
        val h = listOf(task) + obs("c1") + (1..6).flatMap { act("a$it", "d$it") }
        val r = results(HistoryPruner.prune(h, TOOLS, cut))
        // The latest full read before the line, c1, is the base of the diffs and is not pruned; diffs before the line are pruned (short fields kept), those after the line stay as-is
        assertTrue("@e1" in r.first { it.callId == "c1" }.content)
        val diffs = r.filter { it.callId.startsWith("a") }
        assertTrue(diffs.take(4).all { HistoryPruner.OMITTED in it.content && "@e1" !in it.content && "\"ok\":true" in it.content })
        assertTrue(diffs.drop(4).all { "@e1" in it.content })
        // A full screen result also counts as a full screen read: the latest full one before the line is f, so c1 is pruned as usual
        val withFull = listOf(task) + obs("c1") +
            listOf(Message(Role.Assistant, listOf(Part.ToolCall("f", "phone_back", "{}")), Author.Mika),
                Message(Role.Tool, listOf(Part.ToolResult("f", """{"ok":true,"screen":{"snapshotId":"z","full":true,"elements":$big}}""", false)), Author.Mika)) +
            (1..5).flatMap { act("a$it", "d$it") }
        val r2 = results(HistoryPruner.prune(withFull, TOOLS, cut))
        assertTrue("@e1" !in r2.first { it.callId == "c1" }.content)
        assertTrue("@e1" in r2.first { it.callId == "f" }.content)
    }

    // During compaction images before the line become placeholders; otherwise they are replaced only when the image count exceeds the provider limit
    @Test fun imagesDroppedBeforeTheLineAndAtTheProviderLimit() {
        val file = Message(Role.User, listOf(Part.Text("file"), Part.Image("BBBB", "image/jpeg")), Author.Mika)
        val h = listOf(task) + tap("t0") + file + (1..6).flatMap { tap("t$it") + shot(it) }
        fun ids(out: List<Message>) = out.flatMap { it.parts }.filterIsInstance<Part.Image>().map { it.screenshotId }
        assertEquals(h, HistoryPruner.limitImages(h, Int.MAX_VALUE))
        // Compaction: the line is after the 6th round trip, screenshots 1–5 become placeholders that can be looked back at, and the image file that was read is replaced with an omission
        val tight = HistoryPruner.prune(h, TOOLS, HistoryPruner.LAST)
        assertEquals(listOf<Int?>(6), ids(tight))
        val texts = tight.flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }
        assertTrue((1..5).all { HistoryPruner.olderImage(it) in texts } && HistoryPruner.OMITTED_IMAGE in texts)
        // Once the provider states an explicit limit, only the earliest assistant screenshots are replaced, keeping at most 4
        assertEquals(listOf<Int?>(3, 4, 5, 6), ids(HistoryPruner.limitImages(h, 4)))
        val early = listOf(task) + tap("t0") + file + (1..5).flatMap { tap("t$it") + shot(it) }
        assertEquals(listOf<Int?>(2, 3, 4, 5), ids(HistoryPruner.limitImages(early, 4)))
    }

    // Shortening depends on what kind of observation the result holds; after shortening the outcome and observation IDs are kept
    @Test fun shrinksByObservationFormAndKeepsOutcomeAndObsIds() {
        fun round(id: String, tool: String, content: String, form: com.wangzan101.mika.core.screen.observation.ObsForm, obsId: Int) = listOf(
            Message(Role.Assistant, listOf(Part.ToolCall(id, tool, "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult(id, content, false, listOf(com.wangzan101.mika.core.screen.observation.ReadTag(obsId, form)))), Author.Mika),
        )
        val long = "x".repeat(5000)
        val h = listOf(task) +
            round("i", "phone_launch_app", """{"ok":true,"currentApp":"com.x","note":"${"y".repeat(400)}"}""", com.wangzan101.mika.core.screen.observation.ObsForm.Interface, 1) +
            round("p", "web_read_page", """{"ok":true,"url":"https://a.example.com/","text":"$long"}""", com.wangzan101.mika.core.screen.observation.ObsForm.Page, 2) +
            round("s", "web_read_page", """{"ok":true,"url":"https://a.example.com/","text":"短的正文"}""", com.wangzan101.mika.core.screen.observation.ObsForm.Page, 3) +
            round("m", "memory_search", """{"ok":true,"memories":[{"content":"$long"}]}""", com.wangzan101.mika.core.screen.observation.ObsForm.UserSaid, 4) +
            round("c", "run_command", """{"ok":false,"error":"exit_nonzero","message":"命令失败","exitCode":2,"output":"$long"}""", com.wangzan101.mika.core.screen.observation.ObsForm.Command, 5) +
            (1..6).flatMap { tap("t$it") }
        val rs = results(HistoryPruner.prune(h, TOOLS, cut)).associateBy { it.callId }
        // Interface state is always shortened; pages and command output only when large; what the user said is never shortened
        assertTrue(HistoryPruner.OMITTED in rs["i"]!!.content && "\"obsIds\":[1]" in rs["i"]!!.content && "\"currentApp\":\"com.x\"" in rs["i"]!!.content)
        assertTrue(HistoryPruner.OMITTED_READ in rs["p"]!!.content && "\"obsIds\":[2]" in rs["p"]!!.content && "https://a.example.com/" in rs["p"]!!.content)
        assertTrue("短的正文" in rs["s"]!!.content && "obsIds" !in rs["s"]!!.content)
        assertTrue(long in rs["m"]!!.content)
        val c = rs["c"]!!.content
        assertTrue(c, "\"ok\":false" in c && "\"error\":\"exit_nonzero\"" in c && "命令失败" in c && "\"exitCode\":2" in c && "\"obsIds\":[5]" in c && long !in c)
    }

    @Test fun userMessagesNeverPruned() {
        val photo = Message(Role.User, listOf(Part.Text("就按这张图找"), Part.Image("CCCC", screenshotId = 9)), Author.User)
        val h = listOf(task) + tap("t0") + photo + (1..6).flatMap { tap("t$it") }
        assertTrue(photo in HistoryPruner.prune(h, TOOLS, cut))
        val withFrames = h + shot(10) + shot(11)
        assertTrue(photo in HistoryPruner.limitImages(withFrames, 1))
    }

    @Test fun recallBeforeTheLineLosesElementText() {
        val text = "x".repeat(500)
        val recall = listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("r1", "view_past_screens", """{"ids":[1]}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("r1", """{"ok":true,"screenshots":[{"id":1,"step":1,"app":"com.x","elementsText":"$text"}]}""", false)), Author.Mika),
        )
        val old = results(HistoryPruner.prune(obs("c1") + recall + (2..6).flatMap { obs("c$it") }, TOOLS, cut)).first { it.callId == "r1" }.content
        assertTrue(old, HistoryPruner.OMITTED in old && text !in old && "\"id\":1" in old)
        val recent = (1..6).flatMap { obs("c$it") } + recall
        assertTrue(text in results(HistoryPruner.prune(recent, TOOLS, cut)).first { it.callId == "r1" }.content)
    }
}
