package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Gap between outward actions: the first one doesn't wait, no wait once the gap has passed, each site counts on its own, the gap is bounded and grows with the count; the executor waits accordingly and records the wait as waiting time. */
class OutwardPacingTest {
    private val limits = OutwardPacing.Limits()

    @Test fun firstActionOnASiteDoesNotWait() {
        val p = OutwardPacing.human(Random(1))
        assertEquals(0, p.waitBefore("example.com", 0))
        p.noteDone("example.com", 0)
        assertEquals(0, p.waitBefore("other.com", 10))
        assertEquals(0, OutwardPacing.Off.also { it.noteDone("example.com", 0) }.waitBefore("example.com", 1))
    }

    @Test fun gapIsWithinTheFirstRangeAndCountsElapsedTime() {
        repeat(50) { seed ->
            val p = OutwardPacing.human(Random(seed))
            p.noteDone("example.com", 1_000)
            val gap = p.waitBefore("example.com", 1_000)
            assertTrue("$gap", gap in limits.firstGapMs)
            // Time spent reading the page and thinking about the next step in between counts toward the gap
            assertEquals(gap - 3_000, p.waitBefore("example.com", 4_000))
            assertEquals(0, p.waitBefore("example.com", 1_000 + gap))
        }
    }

    @Test fun gapGrowsWithCountAndNeverPassesTheCap() {
        val p = OutwardPacing.human(Random(3))
        var t = 0L
        for (n in 1..40) {
            p.noteDone("example.com", t)
            val gap = p.waitBefore("example.com", t)
            val lo = (limits.firstGapMs.first + (n - 1) * limits.perActionMs.first).coerceAtMost(limits.maxGapMs)
            assertTrue("action $n: $gap", gap in lo..limits.maxGapMs)
            t += gap
        }
    }

    @Test fun oldActionsFallOutOfTheWindow() {
        val p = OutwardPacing.human(Random(5), OutwardPacing.Limits(windowMs = 60_000))
        p.noteDone("example.com", 0)
        assertEquals(0, p.waitBefore("example.com", 61_000))
    }

    @Test fun sitesAreRegistrableDomainsOnTheWebAndPackagesOnThePhone() {
        assertEquals("example.com", OutwardPacing.siteOf("www.example.com", web = true))
        assertEquals("example.com", OutwardPacing.siteOf("m.example.com", web = true))
        assertEquals("com.chat.app", OutwardPacing.siteOf("com.chat.app", web = false))
        assertEquals(null, OutwardPacing.siteOf(null, web = false))
    }

    /** One screen: a "Contact now" and an ordinary button; records what was tapped. */
    private class GreetPage : ScreenPort by FakeScreen() {
        val taps = mutableListOf<String>()
        private val shown = listOf(
            UiElement("@e1", "button", "立即沟通", 900, 300, listOf(800, 100, 999, 160), null, true, false, false),
            UiElement("@e2", "button", "下一个", 900, 1300, listOf(800, 500, 999, 560), null, true, false, false),
        )
        override suspend fun snapshot() = ScreenSnapshot("s1", "com.jobs", shown, 1080, 2400)
        override suspend fun peek() = snapshot()
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok(changed = true).also { taps += ref }
    }

    // Autonomous execution is paced the same way: wait out the full gap before the second "Contact now", record it as this call's wait rather than operation time; ordinary taps don't wait
    @Test fun executorWaitsBetweenOutwardTapsAndRecordsTheWait() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        val screen = GreetPage()
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("o", "phone_observe", "{}"))
        val pacing = OutwardPacing.human(Random(9))
        val executor = ActionExecutor(tools, OperationGuards(), null, gate = {}, recorder = ActionRecorder(sink, { currentTime }), autonomous = true, pacing = pacing)
        suspend fun tap(id: String, ref: String) = executor.run(Part.ToolCall(id, "phone_tap", """{"ref":"$ref","snapshotId":"s1"}"""), Origin.Model(1))
        tap("c1", "@e1")
        val gap = pacing.waitBefore("com.jobs", currentTime)
        assertTrue("$gap", gap in limits.firstGapMs)
        val before = currentTime
        tap("c2", "@e2")
        val spent = currentTime - before
        tap("c3", "@e1")
        assertEquals(listOf("@e1", "@e2", "@e1"), screen.taps)
        val results = events.filterIsInstance<RunEvent.ToolResult>().associateBy { it.callId }
        assertEquals(0L, results.getValue("c1").waitMs)
        assertEquals(0L, results.getValue("c2").waitMs)
        // Time an ordinary tap takes counts toward the gap, so only the shortfall is filled in
        assertEquals(gap - spent, results.getValue("c3").waitMs)
        assertTrue(results.getValue("c3").durationMs < results.getValue("c3").waitMs)
        // Mark on the activity which steps were outward actions: the wrap-up after a stop counts them to report how many were sent
        val outward = events.filterIsInstance<RunEvent.Activity>().associate { it.callId to it.outward }
        assertEquals(mapOf("c1" to true, "c2" to false, "c3" to true), outward)
    }
}
