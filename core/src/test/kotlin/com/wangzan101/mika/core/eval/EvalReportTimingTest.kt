package com.wangzan101.mika.core.eval

import org.junit.Assert.assertTrue
import org.junit.Test

class EvalReportTimingTest {
    private fun pair(id: String, calls: Int, input: Long, cached: Long, modelMs: Long, toolMs: Long) =
        TaskScore(id, true, false, false, emptyList(), null) to
            Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), calls, 10_000, input, 10, cached, null,
                modelCalls = calls, modelMs = modelMs, toolMs = toolMs)

    @Test fun reportsCallMedianAndCacheRatio() {
        val scores = listOf(
            pair("a", 4, 400, 100, 5_000, 2_000),
            pair("b", 6, 300, 50, 5_000, 2_000),
            pair("c", 10, 300, 100, 5_000, 1_000),
        )
        val md = EvalReport.markdown("r1", "m", scores, emptyList(), null, flags = "inlineScreen,replay")
        assertTrue(md, "| Median model calls | 6 |" in md)
        assertTrue(md, "| Model calls P90 | 10 |" in md)
        assertTrue(md, "| Cache hit rate (cached/input) | 25.0% |" in md)
        // 30 seconds in total: 15 for the model, 5 for tools
        assertTrue(md, "| Model wait share | 50.0% |" in md)
        assertTrue(md, "| Tool execution share | 16.7% |" in md)
        assertTrue(md, "| Replay hits | 0 |" in md)
        assertTrue(md, "| Replay hand-backs | 0 |" in md)
        assertTrue(md, "inlineScreen,replay" in md)
    }

    // The shares add up to 100%: everything besides model and tools is "other/waiting for the user"; when waiting time was not measured, the no-data marker is shown instead
    @Test fun sharesSumToHundredWithOtherRow() {
        val scores = listOf(pair("a", 4, 400, 100, 5_000, 2_000), pair("b", 6, 300, 50, 5_000, 2_000), pair("c", 10, 300, 100, 5_000, 1_000))
        val md = EvalReport.markdown("r1", "m", scores, emptyList(), null)
        assertTrue(md, "| Other/waiting for the user share | 33.3% |" in md)
        assertTrue(md, "| Of which waiting for the UI | (no data yet) |" in md)
    }

    // Replay eval: variants' replay hits, hand-backs and completions after a hand-back; the original tasks are listed separately and do not enter the metrics
    @Test fun replayRatesAndOriginalsSection() {
        fun v(id: String, passed: Boolean, hits: Int, backs: Int) = TaskScore(id, passed, false, false, emptyList(), null) to
            Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), 2, 10_000, 0, 0, 0, null, replayHits = hits, replayHandBacks = backs)
        val scores = listOf(v("a~v1", true, 1, 0), v("a~v2", true, 0, 1), v("b~v1", false, 0, 1), v("b~v2", true, 0, 0))
        val md = EvalReport.markdown("r", "m", scores, emptyList(), null, originals = listOf(pair("a", 4, 1, 0, 1, 1)))
        assertTrue(md, "| Replay hit rate (tasks that completed the route) | 1/4(25.0%) |" in md)
        assertTrue(md, "| Replay hand-back rate (tasks handed back midway) | 2/4(50.0%) |" in md)
        assertTrue(md, "| Success rate after hand-back | 1/2(50.0%) |" in md)
        assertTrue(md, "## Original tasks (routes only, not counted in the metrics above)" in md)
        assertTrue(md, "ran 4 tasks" in md)
    }
}
