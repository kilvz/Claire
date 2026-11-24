package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportPolicyTest {
    private var now = 0L
    private val policy = ReportPolicy(clock = { now })

    @Test
    fun milestonesAlwaysReport() {
        val events = listOf(
            DeskEvent.Started("t1"),
            DeskEvent.Queued("t1", 1),
            DeskEvent.NeedsAnswer("t1", "选哪家?", listOf("A", "B")),
            DeskEvent.NeedsAction("t1", ActionKind.Confirm),
            DeskEvent.NeedsAction("t1", ActionKind.Takeover),
            DeskEvent.NeedsAction("t1", ActionKind.Prerequisite),
            DeskEvent.Succeeded("t1", "好了"),
            DeskEvent.Failed("t1", "没成"),
        )
        events.forEach { assertTrue(it.toString(), policy.shouldReport(it)) }
    }

    @Test
    fun cancelledIsNotReported() {
        assertFalse(policy.shouldReport(DeskEvent.Cancelled("t1")))
    }

    @Test
    fun progressIsQuietWithinWindowAfterLastReport() {
        assertTrue(policy.shouldReport(DeskEvent.Started("t1")))
        now = 89_999
        assertFalse(policy.shouldReport(DeskEvent.Progress("t1", "在找")))
        now = 90_000
        assertTrue(policy.shouldReport(DeskEvent.Progress("t1", "找到了")))
        now = 120_000
        assertFalse(policy.shouldReport(DeskEvent.Progress("t1", "在比价")))
    }

    @Test
    fun firstProgressWithoutEarlierReportIsReported() {
        assertTrue(policy.shouldReport(DeskEvent.Progress("t1", "在找")))
    }

    @Test
    fun quietWindowIsPerTask() {
        assertTrue(policy.shouldReport(DeskEvent.Started("t1")))
        now = 10_000
        assertTrue(policy.shouldReport(DeskEvent.Progress("t2", "在找")))
        assertFalse(policy.shouldReport(DeskEvent.Progress("t1", "在找")))
    }

    @Test
    fun customQuietWindowIsHonored() {
        val short = ReportPolicy(clock = { now }, progressQuietMs = 1_000)
        assertTrue(short.shouldReport(DeskEvent.Started("t1")))
        now = 1_000
        assertTrue(short.shouldReport(DeskEvent.Progress("t1", "在找")))
    }

    /** When nobody is watching: start, queued, and progress do not notify; only endings and prerequisites do. */
    @Test
    fun onlyEndingsAndPrerequisitesNotify() {
        // Answers, confirmations, and takeovers send their own "needs you" notification with buttons; the front desk's fallback only writes back to the conversation and does not send a second one;
        // Prerequisite tasks do not get a separate reminder; the front desk sends this one.
        val quiet = listOf(
            DeskEvent.Started("t1"), DeskEvent.Queued("t1", 2), DeskEvent.Progress("t1", "打开设置"),
            DeskEvent.NeedsAnswer("t1", "选哪家?", null),
            DeskEvent.NeedsAction("t1", ActionKind.Confirm),
            DeskEvent.NeedsAction("t1", ActionKind.Takeover),
        )
        val loud = listOf(
            DeskEvent.NeedsAction("t1", ActionKind.Prerequisite),
            DeskEvent.Succeeded("t1", "好了"),
            DeskEvent.Failed("t1", "没成"),
            DeskEvent.Cancelled("t1"),
        )
        quiet.forEach { assertFalse(it.toString(), ReportPolicy.notifies(it)) }
        loud.forEach { assertTrue(it.toString(), ReportPolicy.notifies(it)) }
    }

    /** [ReportPolicy.saysEnding] and [ReportPolicy.shouldReport] agree on every outcome. */
    @Test
    fun saysEndingMatchesWhatTheDeskReports() {
        val ends = listOf(TaskState.Succeeded("好了"), TaskState.Failed("没成", FailKind.Environment), TaskState.Cancelled)
        for (end in ends) {
            val event = DeskEvents.classify("t1", running(), end)!!
            assertEquals(end.toString(), ReportPolicy(clock = { now }).shouldReport(event), ReportPolicy.saysEnding(end))
        }
        assertFalse(ReportPolicy.saysEnding(TaskState.Cancelled))
    }

    private fun running() = TaskState.Running(1, "打开设置")
}
