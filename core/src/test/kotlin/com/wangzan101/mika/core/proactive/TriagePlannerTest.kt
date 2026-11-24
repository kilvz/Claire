package com.wangzan101.mika.core.proactive

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriagePlannerTest {
    private val day = 86_400_000L
    private val t0 = 100 * day + 3_600_000L

    private fun rec(key: String, pkg: String, posted: Long = t0) = NoticeRecord(key, pkg, posted, "t", "x")

    private fun snap(vararg r: NoticeRecord) = NoticeInbox.Snapshot(r.toList(), 0, 0, 0)

    private fun planner() = TriagePlanner(zone = { ZoneOffset.UTC })

    private val send = { pkg: String -> TriagePlan.Send(pkg) }

    // ---- decide: a pure function ----

    @Test fun lockedNeverSendsNorWaits() {
        val p = mapOf("a" to PendingApp(0, 8))
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Locked), TriagePlanner.decide(false, false, true, p, 1_000_000))
    }

    @Test fun busyWaitsBudgetStopsNothingIsIdle() {
        val p = mapOf("a" to PendingApp(0, 8))
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Busy), TriagePlanner.decide(true, true, true, p, 1_000_000))
        assertEquals(TriagePlan.Idle(TriagePlan.Why.BudgetUsedUp), TriagePlanner.decide(true, false, false, p, 1_000_000))
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Nothing), TriagePlanner.decide(true, false, true, emptyMap(), 1_000_000))
    }

    @Test fun batchesAfterTheWindowOrWhenFull() {
        val now = 1_000_000L
        val young = mapOf("a" to PendingApp(now - 5_000, 3), "b" to PendingApp(now - 1_000, 1))
        assertEquals(TriagePlan.WaitUntil(now - 5_000 + TriagePlanner.WINDOW_MS), TriagePlanner.decide(true, false, true, young, now))
        val full = young + ("b" to PendingApp(now - 1_000, 8))
        assertEquals(send("b"), TriagePlanner.decide(true, false, true, full, now))
        val old = mapOf("a" to PendingApp(now - 30_000, 1), "b" to PendingApp(now - 40_000, 1))
        assertEquals("先到点的先发", send("b"), TriagePlanner.decide(true, false, true, old, now))
    }

    // ---- state ----

    @Test fun batchesArePerAppAndAtMostEight() {
        val p = planner()
        val recs = (1..10).map { rec("a$it", "com.a", t0 + it) } + (1..3).map { rec("b$it", "com.b", t0 + it) }
        p.sync(snap(*recs.toTypedArray()), t0)
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, t0))
        val batch = p.begin("com.a")
        assertEquals(8, batch.size)
        assertTrue(batch.all { it.record.packageName == "com.a" })
        assertEquals((1..8).map { "a$it" }, batch.map { it.record.key })
        assertEquals(TriagePlan.Idle(TriagePlan.Why.InFlight), p.plan(true, false, true, t0))
        assertEquals(TriageCounts(pending = 5, sending = 8), p.counts())
    }

    @Test fun surfaceIsCappedPerAppPerDay() {
        assertEquals(10, TriagePlanner.SURFACE_CAP_PER_APP)
        val p = planner()
        val a = (1..11).map { rec("a$it", "com.a", t0 + it) }
        val b = listOf(rec("b1", "com.b", t0))
        p.sync(snap(*(a + b).toTypedArray()), t0)
        val surface = TriageVerdict(TriageDecision.Surface, "r")
        // a batch of 8, then a batch of 3
        repeat(2) { p.begin("com.a").let { batch -> p.finish(batch.associate { it.record.key to surface }, t0) } }
        val decisions = a.map { (p.mark(it) as TriageMark.Done).verdict }
        assertEquals(10, decisions.count { it.decision == TriageDecision.Surface })
        assertEquals(TriageVerdict(TriageDecision.Ignore, note = TriageNote.SurfaceCapped), decisions.last())
        // other apps are unaffected
        p.begin("com.b").let { batch -> p.finish(batch.associate { it.record.key to surface }, t0) }
        assertEquals(surface, (p.mark(b[0]) as TriageMark.Done).verdict)
        // counted afresh the next day
        val next = rec("a99", "com.a", t0 + day)
        p.sync(snap(*(a + b + next).toTypedArray()), t0 + day)
        p.begin("com.a").let { batch -> p.finish(batch.associate { it.record.key to surface }, t0 + day) }
        assertEquals(surface, (p.mark(next) as TriageMark.Done).verdict)
    }

    // the cap counts conversations (notification keys), not judgements: when the same key is judged worth surfacing again after its cooldown, it does not take another slot
    @Test fun surfaceCapCountsDistinctKeysNotVerdicts() {
        val p = planner()
        val chat = rec("chat", "com.a", t0)
        var at = t0
        repeat(12) { i ->
            judge(p, chat.copy(postedAtMs = at), surface, at)
            assertEquals("第 ${i + 1} 次", surface, (p.mark(chat.copy(postedAtMs = at)) as TriageMark.Done).verdict)
            at += TriagePlanner.SURFACE_COOLDOWN_MS + 1
        }
        // the other 9 conversations still have slots (this key took only 1), the 10th does not
        val others = (1..10).map { rec("o$it", "com.a", at + it) }
        // judge leaves only this one record in the inbox each time: the verdict can be read straight away
        val verdicts = others.map { judge(p, it, surface, at); (p.mark(it) as TriageMark.Done).verdict }
        assertEquals(9, verdicts.count { it == surface })
        assertEquals(TriageNote.SurfaceCapped, verdicts.last().note)
        // a key that has already been counted can still surface even once the cap is reached
        judge(p, chat.copy(postedAtMs = at + 100), surface, at + 100)
        assertEquals(surface, (p.mark(chat.copy(postedAtMs = at + 100)) as TriageMark.Done).verdict)
    }

    // a key that disappears from the inbox (the user dealt with it, the results were cleared) and then reappears does not take a second slot that day; the count survives result clearing and starts over after midnight
    @Test fun aKeyThatComesBackTheSameDayKeepsItsOneSlot() {
        val p = planner()
        val keys = (1..10).map { rec("k$it", "com.a", t0 + it) }
        keys.forEach { judge(p, it, surface, t0) }
        p.sync(snap(), t0 + 1)
        assertTrue(p.marks().isEmpty())
        // the returning k1 still has its own slot; a new key finds no slot left
        val back = keys.first().copy(postedAtMs = t0 + 1_000)
        judge(p, back, surface, t0 + 1_000)
        assertEquals(surface, (p.mark(back) as TriageMark.Done).verdict)
        val fresh = rec("new", "com.a", t0 + 2_000)
        judge(p, fresh, surface, t0 + 2_000)
        assertEquals(TriageNote.SurfaceCapped, (p.mark(fresh) as TriageMark.Done).verdict.note)
        // clear() (turning triage off and on again) does not reset that day's count either
        p.clear()
        val fresh2 = rec("new2", "com.a", t0 + 3_000)
        judge(p, fresh2, surface, t0 + 3_000)
        assertEquals(TriageNote.SurfaceCapped, (p.mark(fresh2) as TriageMark.Done).verdict.note)
        // local time has passed midnight: counted afresh
        val tomorrow = (t0 / day + 1) * day
        val fresh3 = rec("new3", "com.a", tomorrow)
        judge(p, fresh3, surface, tomorrow)
        assertEquals(surface, (p.mark(fresh3) as TriageMark.Done).verdict)
    }

    @Test fun abortedBatchIsPendingAgainNotFailed() {
        val p = planner()
        val r = rec("a1", "com.a")
        p.sync(snap(r), t0)
        p.begin("com.a")
        assertTrue(p.pending().isEmpty())
        // the inbox changes during the request (sync still runs around the lock screen)
        p.sync(snap(r), t0 + 1_000)
        p.abort()
        assertEquals(listOf(r), p.pending())
        assertNull(p.mark(r))
        assertEquals(TriageCounts(pending = 1), p.counts())
        // after unlocking there is no need to wait again: its timer start is still there
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, t0 + TriagePlanner.WINDOW_MS))
    }

    @Test fun failedStaysFailedUntilRetriedOnce() {
        val p = planner()
        val r = rec("a1", "com.a")
        p.sync(snap(r), t0)
        p.begin("com.a")
        p.fail(t0)
        assertTrue(p.mark(r) is TriageMark.Failed)
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Nothing), p.plan(true, false, true, t0 + 3_600_000))
        assertEquals(TriageCounts(failed = 1), p.counts())
        val later = t0 + 3_600_000
        assertEquals(1, p.retryFailed(later))
        p.sync(snap(r), later)
        assertEquals("再试一次不用再等凑批", TriagePlan.Send("com.a"), p.plan(true, false, true, later))
        p.begin("com.a")
        p.fail(t0)
        assertTrue("再失败就又是失败,不会自己再发", p.mark(r) is TriageMark.Failed)
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Nothing), p.plan(true, false, true, later + day / 2))
    }

    @Test fun updateDuringTheRequestKeepsTheVerdictAndCoolsDown() {
        val p = planner()
        val v1 = rec("k", "com.a", t0)
        p.sync(snap(v1), t0)
        p.begin("com.a")
        // the same notification was updated during the request: the verdict is recorded against the post that was sent, and the new content cools down first instead of being judged again straight away
        val v2 = v1.copy(postedAtMs = t0 + 5)
        p.sync(snap(v2), t0 + 5)
        p.finish(mapOf("k" to remember), t0 + 6)
        p.sync(snap(v2), t0 + 6)
        assertNull(p.mark(v2))
        assertEquals(TriageMark.Done(t0, remember), p.marks()["k"])
        assertTrue(p.pending().isEmpty())
        assertEquals(listOf(v2), p.cooling())
        assertEquals(TriageCounts(cooling = 1), p.counts())
        assertEquals(TriagePlan.WaitUntil(t0 + 6 + min(2)), p.plan(true, false, true, t0 + 6))
        // it is gone from the inbox: neither the verdict nor the cooldown is kept
        p.sync(snap(), t0 + 10)
        assertTrue(p.marks().isEmpty())
        assertEquals(TriageCounts(), p.counts())
    }

    @Test fun surfaceForANoticeUpdatedDuringTheRequestIsJudgedAgainSooner() {
        val p = planner()
        val v1 = rec("k", "com.a", t0)
        p.sync(snap(v1), t0)
        p.begin("com.a")
        val v2 = v1.copy(postedAtMs = t0 + 5)
        p.sync(snap(v2), t0 + 5)
        p.finish(mapOf("k" to surface), t0 + 6)
        p.sync(snap(v2), t0 + 6)
        // the verdict is recorded against the post that was sent and does not match the new post time: no hint comes out
        assertEquals(TriageMark.Done(t0, surface), p.marks()["k"])
        assertEquals(0, NoticeHints().apply { sync(listOf(v2), p.marks(), triageOn = true) }.state.value.unseen)
        // so it does not cool down for 30 minutes as "already surfaced"; like ignore and remember, the new content is judged again after 2 minutes
        val at = t0 + 6 + min(2)
        assertEquals(TriagePlan.WaitUntil(at), p.plan(true, false, true, t0 + 6))
        assertEquals(TriagePlan.WaitUntil(at + W), p.plan(true, false, true, at))
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, at + W))
        assertEquals(listOf("k"), p.begin("com.a").map { it.record.key })
        p.finish(mapOf("k" to surface), at + W)
        p.sync(snap(v2), at + W)
        assertEquals(1, NoticeHints().apply { sync(listOf(v2), p.marks(), triageOn = true) }.state.value.unseen)
    }

    // ---- cooldown ----

    private val remember = TriageVerdict(TriageDecision.Remember, "r")
    private val surface = TriageVerdict(TriageDecision.Surface, "r")
    private fun min(n: Long) = n * 60_000L
    private val W = TriagePlanner.WINDOW_MS

    /** Sends [r] as a batch and judges it as [v], at time [at]. */
    private fun judge(p: TriagePlanner, r: NoticeRecord, v: TriageVerdict, at: Long) {
        p.sync(snap(r), at)
        assertEquals(listOf(TriageItem(r)), p.begin(r.packageName).map { it.copy(history = emptyList()) })
        p.finish(mapOf(r.key to v), at)
        p.sync(snap(r), at)
    }

    @Test fun updateRightAfterAVerdictWaitsForTheCooldown() {
        val p = planner()
        val v1 = rec("k", "com.a", t0)
        judge(p, v1, remember, t0)
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Nothing), p.plan(true, false, true, t0))
        val v2 = v1.copy(postedAtMs = t0 + 20_000)
        p.sync(snap(v2), t0 + 20_000)
        assertTrue("更新不马上待判定", p.pending().isEmpty())
        assertEquals(TriageCounts(cooling = 1), p.counts())
        assertEquals("只等冷却到点", TriagePlan.WaitUntil(t0 + min(2)), p.plan(true, false, true, t0 + 20_000))
        assertEquals(TriagePlan.WaitUntil(t0 + min(2)), p.plan(true, false, true, t0 + min(2) - 1))
        // exactly at the deadline: pending, and batching resumes from the deadline as usual
        assertEquals(TriagePlan.WaitUntil(t0 + min(2) + W), p.plan(true, false, true, t0 + min(2)))
        assertEquals(listOf(v2), p.pending())
        assertEquals(TriageCounts(pending = 1), p.counts())
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, t0 + min(2) + W))
    }

    @Test fun updateAfterTheCooldownEndedWaitsForTheWindowOnly() {
        val p = planner()
        val v1 = rec("k", "com.a", t0)
        judge(p, v1, remember, t0)
        val late = t0 + min(10)
        val v2 = v1.copy(postedAtMs = late)
        p.sync(snap(v2), late)
        assertEquals(listOf(v2), p.pending())
        assertEquals(TriagePlan.WaitUntil(late + TriagePlanner.WINDOW_MS), p.plan(true, false, true, late))
    }

    @Test fun backoffIs2_5_15_30_30AndSurfaceIs30() {
        val p = planner()
        var r = rec("k", "com.a", t0)
        var at = t0
        val waits = mutableListOf<Long>()
        repeat(5) {
            judge(p, r, remember, at)
            r = r.copy(postedAtMs = r.postedAtMs + 1)
            p.sync(snap(r), at + 1)
            val wake = (p.plan(true, false, true, at + 1) as TriagePlan.WaitUntil).atMs
            waits += wake - at
            assertEquals(TriagePlan.WaitUntil(wake + W), p.plan(true, false, true, wake))
            at = wake + W
            assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, at))
        }
        assertEquals(listOf(min(2), min(5), min(15), min(30), min(30)), waits)
        assertEquals(min(30), TriagePlanner.cooldownMs(TriageDecision.Surface, 1))
        assertEquals(min(30), TriagePlanner.cooldownMs(TriageDecision.Surface, 3))
        assertEquals(min(2), TriagePlanner.cooldownMs(TriageDecision.Ignore, 1))
        // worth surfacing: 30 minutes
        val q = planner()
        val s1 = rec("s", "com.b", t0)
        judge(q, s1, surface, t0)
        q.sync(snap(s1.copy(postedAtMs = t0 + 1)), t0 + 1)
        assertEquals(TriagePlan.WaitUntil(t0 + min(30)), q.plan(true, false, true, t0 + 1))
    }

    @Test fun handledNotificationStartsFresh() {
        val p = planner()
        var r = rec("k", "com.a", t0)
        judge(p, r, remember, t0)
        r = r.copy(postedAtMs = t0 + 1)
        p.sync(snap(r), t0 + 1)
        assertEquals(TriagePlan.WaitUntil(t0 + min(2) + W), p.plan(true, false, true, t0 + min(2)))
        judge(p, r, remember, t0 + min(2) + W)
        // the user opened or swiped it away: it is gone from the inbox
        p.sync(snap(), t0 + min(3))
        assertTrue(p.marks().isEmpty())
        // the same key comes in again: it is a new record, batches as usual, and the cooldown after it starts at 2 minutes again
        val again = r.copy(postedAtMs = t0 + min(4))
        p.sync(snap(again), t0 + min(4))
        assertEquals(listOf(again), p.pending())
        assertEquals(TriagePlan.WaitUntil(t0 + min(4) + TriagePlanner.WINDOW_MS), p.plan(true, false, true, t0 + min(4)))
        val at = t0 + min(5)
        judge(p, again, remember, at)
        p.sync(snap(again.copy(postedAtMs = at + 1)), at + 1)
        assertEquals(TriagePlan.WaitUntil(at + min(2)), p.plan(true, false, true, at + 1))
        // clearing behaves the same way
        p.clear()
        p.sync(snap(again.copy(postedAtMs = at + 2)), at + 2)
        assertEquals(TriagePlan.WaitUntil(at + 2 + TriagePlanner.WINDOW_MS), p.plan(true, false, true, at + 2))
    }

    @Test fun waitsForTheEarlierOfWindowAndCooldown() {
        val p = planner()
        val k = rec("k", "com.a", t0)
        judge(p, k, remember, t0)
        val k2 = k.copy(postedAtMs = t0 + 1)
        // the cooldown expires at t0+2min; a new notification from another app only arrives shortly before that, so its batch time is later than the cooldown
        val other = rec("o", "com.b", t0 + min(2) - 5_000)
        p.sync(snap(k2, other), t0 + min(2) - 5_000)
        assertEquals(TriagePlan.WaitUntil(t0 + min(2)), p.plan(true, false, true, t0 + min(2) - 5_000))
        // the batch time comes first
        val q = planner()
        judge(q, k, remember, t0)
        val early = rec("o", "com.b", t0 + 1_000)
        q.sync(snap(k2, early), t0 + 1_000)
        assertEquals(TriagePlan.WaitUntil(t0 + 1_000 + TriagePlanner.WINDOW_MS), q.plan(true, false, true, t0 + 1_000))
    }

    @Test fun lockedHasNoWaitAndSendsAfterUnlock() {
        val p = planner()
        val k = rec("k", "com.a", t0)
        judge(p, k, remember, t0)
        val k2 = k.copy(postedAtMs = t0 + 1)
        p.sync(snap(k2), t0 + 1)
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Locked), p.plan(false, false, true, t0 + 1))
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Locked), p.plan(false, false, true, t0 + min(60)))
        // no cooldown is timed while the budget is used up or a task is running
        assertEquals(TriagePlan.Idle(TriagePlan.Why.BudgetUsedUp), p.plan(true, false, false, t0 + 1))
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Busy), p.plan(true, true, true, t0 + 1))
        // the cooldown expired long ago when unlocking: send immediately
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, t0 + min(60)))
    }

    @Test fun retryBypassesTheFailureHoldAndSuccessKeepsTheVerdictLadder() {
        val p = planner()
        val k = rec("k", "com.a", t0)
        judge(p, k, remember, t0)
        val k2 = k.copy(postedAtMs = t0 + 1)
        p.sync(snap(k2), t0 + 1)
        p.plan(true, false, true, t0 + min(2))
        val at = t0 + min(2) + W
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, at))
        p.begin("com.a")
        p.fail(at)
        p.sync(snap(k2), at)
        assertTrue(p.mark(k2) is TriageMark.Failed)
        assertEquals(TriageCounts(failed = 1), p.counts())
        // retrying skips the cooldown; after it succeeds this is the second judgement: a 5-minute cooldown (the failure does not count)
        assertEquals(1, p.retryFailed(at + 10))
        p.sync(snap(k2), at + 10)
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, at + 10))
        p.begin("com.a")
        p.finish(mapOf("k" to remember), at + 10)
        p.sync(snap(k2.copy(postedAtMs = at + 11)), at + 11)
        assertEquals(TriagePlan.WaitUntil(at + 10 + min(5)), p.plan(true, false, true, at + 11))
    }

    /** Sends [r] as a batch, failing at [at]. */
    private fun failAt(p: TriagePlanner, r: NoticeRecord, at: Long) {
        p.sync(snap(r), at)
        assertEquals(listOf(r.key), p.begin(r.packageName).map { it.record.key })
        p.fail(at)
        p.sync(snap(r), at)
    }

    @Test fun failedKeyIsHeldThenRejudgedOnlyWithNewerContent() {
        val p = planner()
        val k = rec("k", "com.a", t0)
        failAt(p, k, t0)
        // no new content: it stays failed and is not retried automatically
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Nothing), p.plan(true, false, true, t0 + day / 2))
        val q = planner()
        failAt(q, k, t0)
        val k2 = k.copy(postedAtMs = t0 + 1)
        q.sync(snap(k2), t0 + 1)
        assertTrue("停着期间的更新不待判定", q.pending().isEmpty())
        assertEquals("界面上还是失败", TriageCounts(failed = 1), q.counts())
        assertTrue(q.marks()["k"] is TriageMark.Failed)
        assertEquals(TriagePlan.WaitUntil(t0 + min(2)), q.plan(true, false, true, t0 + 1))
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Locked), q.plan(false, false, true, t0 + 1))
        assertEquals(TriagePlan.WaitUntil(t0 + min(2) + W), q.plan(true, false, true, t0 + min(2)))
        assertEquals(TriageCounts(pending = 1), q.counts())
        assertEquals(TriagePlan.Send("com.a"), q.plan(true, false, true, t0 + min(2) + W))
    }

    @Test fun consecutiveFailuresBackOff2_5_15_30_30AndSuccessResets() {
        val p = planner()
        var r = rec("k", "com.a", t0)
        var at = t0
        val holds = mutableListOf<Long>()
        repeat(5) {
            failAt(p, r, at)
            r = r.copy(postedAtMs = r.postedAtMs + 1)
            p.sync(snap(r), at + 1)
            val wake = (p.plan(true, false, true, at + 1) as TriagePlan.WaitUntil).atMs
            holds += wake - at
            at = wake + W
            assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, at))
        }
        assertEquals(listOf(min(2), min(5), min(15), min(30), min(30)), holds)
        // one successful judgement: the failure count resets, and judgement cooldowns apply after that (first judgement, 2 minutes)
        judge(p, r, remember, at)
        r = r.copy(postedAtMs = r.postedAtMs + 1)
        p.sync(snap(r), at + 1)
        assertEquals(TriagePlan.WaitUntil(at + min(2)), p.plan(true, false, true, at + 1))
        p.plan(true, false, true, at + min(2))
        at += min(2) + W
        failAt(p, r, at)
        r = r.copy(postedAtMs = r.postedAtMs + 1)
        p.sync(snap(r), at + 1)
        assertEquals("又从 2 分钟起", TriagePlan.WaitUntil(at + min(2)), p.plan(true, false, true, at + 1))
    }

    @Test fun manualRetryIgnoresTheHoldAndAFailureContinuesTheLadder() {
        val p = planner()
        val k = rec("k", "com.a", t0)
        failAt(p, k, t0)
        val k2 = k.copy(postedAtMs = t0 + 1)
        p.sync(snap(k2), t0 + 1)
        assertEquals(1, p.retryFailed(t0 + 2))
        p.sync(snap(k2), t0 + 2)
        assertEquals("马上发", TriagePlan.Send("com.a"), p.plan(true, false, true, t0 + 2))
        p.begin("com.a")
        p.fail(t0 + 2)
        p.sync(snap(k2), t0 + 2)
        assertEquals(TriageCounts(failed = 1), p.counts())
        val k3 = k.copy(postedAtMs = t0 + 3)
        p.sync(snap(k3), t0 + 3)
        assertEquals("第二次失败:停 5 分钟", TriagePlan.WaitUntil(t0 + 2 + min(5)), p.plan(true, false, true, t0 + 3))
    }

    @Test fun removalForgetsTheFailureHold() {
        val p = planner()
        var k = rec("k", "com.a", t0)
        failAt(p, k, t0)
        k = k.copy(postedAtMs = t0 + 1)
        p.plan(true, false, true, t0 + min(2))
        failAt(p, k, t0 + min(2) + W)
        p.sync(snap(), t0 + min(3))
        assertTrue(p.marks().isEmpty())
        val again = k.copy(postedAtMs = t0 + min(4))
        p.sync(snap(again), t0 + min(4))
        assertEquals("新的一条:照常凑批", listOf(again), p.pending())
        failAt(p, again, t0 + min(5))
        p.sync(snap(again.copy(postedAtMs = t0 + min(5) + 1)), t0 + min(5) + 1)
        assertEquals("失败次数从头算", TriagePlan.WaitUntil(t0 + min(7)), p.plan(true, false, true, t0 + min(5) + 1))
    }

    @Test fun newKeyIsJudgedWhileAnotherKeyOfTheAppIsHeld() {
        val p = planner()
        val k = rec("k", "com.a", t0)
        failAt(p, k, t0)
        val held = k.copy(postedAtMs = t0 + 1)
        val fresh = rec("n", "com.a", t0 + 1)
        p.sync(snap(held, fresh), t0 + 1)
        assertEquals(listOf(fresh), p.pending())
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, t0 + 1 + W))
        assertEquals(listOf("n"), p.begin("com.a").map { it.record.key })
    }

    @Test fun keyHandledDuringTheRequestGetsNoCooldown() {
        val p = planner()
        val k = rec("k", "com.a", t0)
        p.sync(snap(k), t0)
        p.begin("com.a")
        p.sync(snap(), t0 + 1)
        val back = k.copy(postedAtMs = t0 + 2)
        p.sync(snap(back), t0 + 2)
        p.finish(mapOf("k" to remember), t0 + 3)
        p.sync(snap(back), t0 + 3)
        assertNull(p.marks()["k"])
        assertEquals(listOf(back), p.pending())
    }

    @Test fun budgetLatchHoldsForTheDay() {
        val p = planner()
        val r = rec("a1", "com.a")
        p.sync(snap(r), t0)
        p.begin("com.a")
        p.budgetExhausted(t0)
        assertEquals(listOf(r), p.pending())
        val later = t0 + TriagePlanner.WINDOW_MS
        assertEquals(TriagePlan.Idle(TriagePlan.Why.BudgetUsedUp), p.plan(true, false, true, later))
        assertTrue(p.budgetLatched(later))
        assertFalse(p.budgetLatched(t0 + day))
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, t0 + day))
        p.clearBudgetLatch()
        assertEquals(TriagePlan.Send("com.a"), p.plan(true, false, true, later))
    }

    @Test fun lockedWithPendingIsLocked() {
        val p = planner()
        p.sync(snap(rec("a1", "com.a")), t0)
        assertEquals(TriagePlan.Idle(TriagePlan.Why.Locked), p.plan(false, false, true, t0 + day))
    }

    // consent follows the master switch: turning it off clears the consent (even if a model to confirm was supplied at the same time); when turning it on with a consent, that provider is recorded; when turning it on with nothing to confirm, nothing changes
    @Test fun consentFollowsTheMasterSwitch() {
        val t = TriageTarget("p1", "Provider", "m1")
        val had = TriageConsent("p0", "m0")
        assertEquals(null, TriageConsent.afterSwitch(on = false, confirmed = null, current = had))
        assertEquals(null, TriageConsent.afterSwitch(on = false, confirmed = t, current = had))
        assertEquals(TriageConsent("p1", "m1"), TriageConsent.afterSwitch(on = true, confirmed = t, current = null))
        // confirming again with a different provider: the new one is recorded and the old one is not kept
        assertEquals(TriageConsent("p1", "m1"), TriageConsent.afterSwitch(on = true, confirmed = t, current = had))
        // turned on with no model configured: there is no consent, so the gate stays at "needs consent", and it will not let anything through by itself once a model is configured
        val none = TriageConsent.afterSwitch(on = true, confirmed = null, current = null)
        assertEquals(null, none)
        assertEquals(TriageGate.NoModel, TriageGate.of(true, none, null))
        assertEquals(TriageGate.NeedsConsent, TriageGate.of(true, none, t))
        // turned off and then on again without confirming this time: the earlier consent was already cleared and is not reused
        val reopened = TriageConsent.afterSwitch(on = true, confirmed = null, current = TriageConsent.afterSwitch(false, null, had))
        assertEquals(TriageGate.NeedsConsent, TriageGate.of(true, reopened, t))
    }

    @Test fun consentMatchesProviderAndModel() {
        val t = TriageTarget("p1", "Provider", "m1")
        val c = TriageConsent.of(t)
        assertTrue(c.covers(t))
        assertTrue("改名不算换", c.covers(t.copy(providerName = "Renamed")))
        assertFalse(c.covers(t.copy(model = "m2")))
        assertFalse(c.covers(t.copy(providerId = "p2")))
        assertFalse(c.covers(null))
        assertEquals(TriageGate.Off, TriageGate.of(false, c, t))
        assertEquals(TriageGate.NoModel, TriageGate.of(true, c, null))
        assertEquals(TriageGate.NeedsConsent, TriageGate.of(true, null, t))
        assertEquals(TriageGate.NeedsConsent, TriageGate.of(true, c, t.copy(model = "m2")))
        assertEquals(TriageGate.Ready, TriageGate.of(true, c, t))
    }
}
