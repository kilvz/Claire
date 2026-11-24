package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.run.record.RunMetrics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalReportMetricsTest {
    private fun pair(id: String, metrics: RunMetrics?) =
        TaskScore(id, true, false, false, emptyList(), null) to
            Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), 3, 10_000, 100, 10, 0, null, metrics = metrics)

    private val a = RunMetrics(runId = "r-a", totalMs = 10_000, ended = true, firstFeedbackMs = 2_000, modelCalls = 4, inputTokens = 900,
        outputTokens = 100, modelMs = 5_000, observeMs = 2_000, actMs = 1_000, observeCalls = 3, actCalls = 2, outcome = "succeeded")
    private val b = a.copy(runId = "r-b", totalMs = 30_000, firstFeedbackMs = 4_000, modelCalls = 8, modelMs = 15_000, observeMs = 6_000, actMs = 3_000)

    @Test fun summaryHasARunMetricsSectionWithPerTaskRows() {
        val md = EvalReport.markdown("r1", "m", listOf(pair("t-a", a), pair("t-b", b), pair("t-none", null)), emptyList(), null)
        assertTrue(md, "## Run summary" in md)
        assertTrue(md, "Computed from the run records (2 tasks)" in md)
        assertTrue(md, "| Median time (s) | 20 |" in md)
        assertTrue(md, "| Median first feedback (s) | 3 |" in md)
        assertTrue(md, "| Average model round trips | 6 |" in md)
        // Of the 40 seconds: 20 on the model, 8 reading, 4 acting, the remaining 8
        assertTrue(md, "| Model thinking share | 50.0% |" in md)
        assertTrue(md, "| Screen reading share | 20.0% |" in md)
        assertTrue(md, "| Action share | 10.0% |" in md)
        assertTrue(md, "| Other (waiting, retrying) share | 20.0% |" in md)
        assertTrue(md, "| t-a | 10 | 2 | 4 | — | 5 | 2 | 1 | 2 | 3 / 2 | 1000 |" in md)
        assertFalse(md, "| t-none |" in md.substringAfter("## Run summary").substringBefore("## Count by class"))
    }

    @Test fun noRecordsNoSection() {
        val md = EvalReport.markdown("r1", "m", listOf(pair("t", null)), emptyList(), null)
        assertFalse(md, "## Run summary" in md)
    }

    @Test fun metricsJsonListsEachTaskWithItsMetrics() {
        val out = Json.parseToJsonElement(EvalReport.metricsJson(listOf(pair("t-a", a), pair("t-none", null)))) as JsonArray
        assertEquals(1, out.size)
        val row = out[0] as JsonObject
        assertEquals(JsonPrimitive("t-a"), row["id"])
        val m = row["metrics"] as JsonObject
        assertEquals(JsonPrimitive("r-a"), m["runId"])
        assertEquals(JsonPrimitive(2_000L), m["firstFeedbackMs"])
    }
}
