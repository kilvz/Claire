package com.wangzan101.mika.core.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalReportCompareTest {
    private fun s(id: String, passed: Boolean, falseSuccess: Boolean = false) =
        TaskScore(id, passed, falseSuccess, unverified = false, failedChecks = emptyList(), failureClass = null)

    private val base = listOf(s("a", true), s("b", true), s("c", true), s("d", false))

    @Test fun deltasAndGate() {
        val treated = listOf(s("a", true), s("b", true), s("c", false), s("d", false))
        val report = EvalReport.compare(base, treated, mapOf("send-note" to 3, "set-alarm" to 1))
        assertTrue(report.contains("| Accomplished | 3/4 | 2/4 | -1 |"))
        assertTrue(report.contains("| False success | 0/4 | 0/4 | 0 |"))
        assertTrue(report.contains("- send-note: 3 times"))
        assertTrue(report.contains("Passed:"))
        assertTrue(EvalReport.gate(base, treated).passed)

        // Two tasks missing: fail
        val worse = listOf(s("a", true), s("b", false), s("c", false), s("d", false))
        val g = EvalReport.gate(base, worse)
        assertFalse(g.passed)
        assertEquals(1, g.reasons.size)
        assertTrue(g.reasons.single().contains("2 fewer"))

        // One task is a false success: fail, and list its id
        val lying = listOf(s("a", true), s("b", true), s("c", true), s("d", false, falseSuccess = true))
        val g2 = EvalReport.gate(base, lying)
        assertFalse(g2.passed)
        assertTrue(g2.reasons.single().contains("d"))
        assertTrue(EvalReport.compare(base, lying, emptyMap()).contains("Not passed:"))
    }

    @Test fun mediansWhenObserved() {
        fun o(steps: Int, ms: Long) = Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), steps, ms, 0, 0, 0, null)
        val r = EvalReport.compare(base, base, emptyMap(), listOf(o(10, 60_000), o(20, 80_000)), listOf(o(8, 50_000), o(12, 70_000)))
        assertTrue(r.contains("| Median steps | 15 | 10 | -5 |"))
        assertTrue(r.contains("| Median duration (s) | 70 | 60 | -10 |"))
        assertTrue(r.contains("No learned skills were used."))
    }

    @Test fun tokensPairedByTaskId() {
        fun o(input: Long, cached: Long, output: Long) =
            Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), 1, 1, input, output, cached, null)
        // Baseline is a b c d; the with-skill round ran only a and c: compare just a and c
        val treated = listOf(s("c", true), s("a", true))
        val r = EvalReport.compare(base, treated, emptyMap(),
            listOf(o(1000, 200, 50), o(99_999, 0, 9_999), o(3000, 600, 70), o(99_999, 0, 9_999)), listOf(o(2400, 1800, 60), o(800, 600, 40)))
        assertTrue(r, r.contains("| Input tokens (median of 2 paired tasks) | 2000 | 1600 | -400 |"))
        assertTrue(r, r.contains("| Output tokens (median of 2 paired tasks) | 60 | 50 | -10 |"))
        assertTrue(r, r.contains("| Cache hit rate % (median of 2 paired tasks) | 20 | 75 | +55 |"))
    }
}
