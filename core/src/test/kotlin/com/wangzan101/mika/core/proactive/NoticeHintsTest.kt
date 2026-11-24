package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeHintsTest {
    // a notification recognised as a status display: the hint is withdrawn, but the user did not deal with it, so it is not counted as "dismissed unseen"
    @Test fun aWithdrawnHintIsNotCountedAsDismissedUnseen() {
        val h = NoticeHints()
        val a = rec("a", pkg = "p1")
        h.sync(listOf(a), surface(a), triageOn = true)
        assertEquals(1, h.state.value.list.size)
        h.withdraw("a")
        assertTrue(h.state.value.list.isEmpty())
        assertEquals(0, h.state.value.dismissedUnseenTotal)
        h.withdraw("nothing")
    }

    private fun rec(key: String, posted: Long = 1, pkg: String = "p", text: String = "x") = NoticeRecord(key, pkg, posted, "t-$key", text)
    private fun done(posted: Long, d: TriageDecision, reason: String = "why") = TriageMark.Done(posted, TriageVerdict(d, reason))
    private fun surface(vararg rs: NoticeRecord) = rs.associate { it.key to done(it.postedAtMs, TriageDecision.Surface) }

    @Test fun onlySurfaceWithMatchingPostTimeCounts() {
        val h = NoticeHints()
        val a = rec("a"); val b = rec("b"); val c = rec("c", posted = 5); val d = rec("d")
        val marks = mapOf(
            "a" to done(1, TriageDecision.Surface, "r-a"),
            "b" to done(1, TriageDecision.Remember),
            "c" to done(4, TriageDecision.Surface), // judged on the earlier post
            "d" to TriageMark.Failed(1),
        )
        h.sync(listOf(a, b, c, d), marks, triageOn = true)
        val s = h.state.value
        assertEquals(listOf("a"), s.list.map { it.key })
        assertEquals(1, s.unseen)
        assertEquals("r-a", s.list.single().reason)
        assertEquals("t-a", s.list.single().title)
    }

    @Test fun newestFirstAndBounded() {
        val h = NoticeHints(limit = 3)
        val rs = (1..5).map { rec("k$it", posted = it.toLong()) }
        h.sync(rs, surface(*rs.toTypedArray()), true)
        assertEquals(listOf("k5", "k4", "k3"), h.state.value.list.map { it.key })
        // syncing again leaves the result unchanged
        h.sync(rs, surface(*rs.toTypedArray()), true)
        assertEquals(listOf("k5", "k4", "k3"), h.state.value.list.map { it.key })
    }

    @Test fun droppedByBoundDoNotComeBack() {
        val h = NoticeHints(limit = 2)
        val rs = (1..4).map { rec("k$it", posted = it.toLong()) }
        h.sync(rs, surface(*rs.toTypedArray()), true)
        h.markSeen(h.state.value.list)
        h.sync(rs, surface(*rs.toTypedArray()), true)
        assertEquals(listOf("k4", "k3"), h.state.value.list.map { it.key })
        assertEquals(0, h.state.value.unseen)
        // the dropped one was updated and judged worth surfacing again: it counts as new
        val k1 = rec("k1", posted = 9)
        val now = rs.drop(1) + k1
        h.sync(now, surface(*now.toTypedArray()), true)
        assertEquals(listOf("k1"), h.state.value.list.filter { !it.seen }.map { it.key })
    }

    @Test fun openingMarksSeenKeepsListAndCountsPerApp() {
        val h = NoticeHints()
        val a = rec("a", pkg = "p1"); val b = rec("b", pkg = "p2"); val c = rec("c", pkg = "p1")
        h.sync(listOf(a, b, c), surface(a, b, c), true)
        assertEquals(3, h.state.value.unseen)
        h.markSeen(h.state.value.list)
        val s = h.state.value
        assertEquals(0, s.unseen)
        assertEquals(3, s.list.size)
        assertTrue(s.list.all { it.seen })
        assertEquals(HintFeedback(seen = 2), s.feedback["p1"])
        assertEquals(HintFeedback(seen = 1), s.feedback["p2"])
        // opening it a second time does not count again
        h.markSeen(h.state.value.list)
        assertEquals(3, h.state.value.seenTotal)
        // syncing the same judgement again does not turn it back into unseen
        h.sync(listOf(a, b, c), surface(a, b, c), true)
        assertEquals(0, h.state.value.unseen)
    }

    @Test fun removedFromInboxDisappears() {
        val h = NoticeHints()
        val a = rec("a"); val b = rec("b")
        h.sync(listOf(a, b), surface(a, b), true)
        h.sync(listOf(b), surface(a, b), true) // no longer selected, pushed out
        assertEquals(listOf("b"), h.state.value.list.map { it.key })
        assertEquals(0, h.state.value.dismissedUnseenTotal)
    }

    @Test fun userDismissBeforeSeenIsCounted() {
        val h = NoticeHints()
        val a = rec("a", pkg = "p1"); val b = rec("b", pkg = "p1"); val c = rec("c", pkg = "p2")
        h.sync(listOf(a, b, c), surface(a, b, c), true)
        h.dismissed("a")
        h.sync(listOf(b, c), surface(a, b, c), true)
        assertEquals(listOf("b", "c").toSet(), h.state.value.list.map { it.key }.toSet())
        assertEquals(HintFeedback(dismissedUnseen = 1), h.state.value.feedback["p1"])
        // dismissing it after it was seen does not count as "dealt with before being seen"
        h.markSeen(h.state.value.list)
        h.dismissed("b")
        assertEquals(1, h.state.value.dismissedUnseenTotal)
        assertEquals(2, h.state.value.seenTotal)
        // dismissing a key that is not a hint has no effect
        h.dismissed("zzz")
        assertEquals(1, h.state.value.dismissedUnseenTotal)
    }

    @Test fun reSurfaceAfterSeenOnlyWhenPostedLater() {
        val h = NoticeHints()
        val a1 = rec("a", posted = 10, text = "one")
        h.sync(listOf(a1), surface(a1), true)
        h.markSeen(h.state.value.list)
        assertEquals(0, h.state.value.unseen)
        // judging the same post again (post time unchanged) does not turn it back into unseen
        h.sync(listOf(a1), surface(a1), true)
        assertEquals(0, h.state.value.unseen)
        // the notification was updated and judged worth surfacing again: it counts as new
        val a2 = rec("a", posted = 20, text = "two")
        h.sync(listOf(a2), surface(a2), true)
        val s = h.state.value
        assertEquals(1, s.unseen)
        assertEquals("two", s.list.single().text)
    }

    @Test fun updatedNoticeReplacesUnseenItemInPlace() {
        val h = NoticeHints()
        val a1 = rec("a", posted = 10, text = "one")
        h.sync(listOf(a1), surface(a1), true)
        val a2 = rec("a", posted = 20, text = "two")
        // the new post has not been judged yet: keep the original entry for now
        h.sync(listOf(a2), surface(a1), true)
        assertEquals("one", h.state.value.list.single().text)
        h.sync(listOf(a2), surface(a2), true)
        assertEquals("two", h.state.value.list.single().text)
        assertEquals(1, h.state.value.unseen)
        // updated again and judged as ignore: removed
        val a3 = rec("a", posted = 30)
        h.sync(listOf(a3), mapOf("a" to done(30, TriageDecision.Ignore)), true)
        assertEquals(0, h.state.value.list.size)
    }

    @Test fun seenItemUpdatedButStillPendingStaysSeen() {
        val h = NoticeHints()
        val a1 = rec("a", posted = 10)
        h.sync(listOf(a1), surface(a1), true)
        h.markSeen(h.state.value.list)
        h.sync(listOf(rec("a", posted = 20)), surface(a1), true)
        assertEquals(0, h.state.value.unseen)
        assertEquals(1, h.state.value.list.size)
    }

    @Test fun triageOffClearsListAndSeenOnesDoNotReturn() {
        val h = NoticeHints()
        val a = rec("a"); val b = rec("b")
        h.sync(listOf(a), surface(a), true)
        h.markSeen(h.state.value.list)
        h.sync(listOf(a, b), surface(a, b), true)
        assertEquals(1, h.state.value.unseen)
        // triage turned off: the results are still in `marks`, but the list is emptied
        h.sync(listOf(a, b), surface(a, b), false)
        assertEquals(0, h.state.value.list.size)
        assertEquals(0, h.state.value.unseen)
        // turned back on: the seen `a` does not return, the unseen `b` does
        h.sync(listOf(a, b), surface(a, b), true)
        assertEquals(listOf("b"), h.state.value.list.map { it.key })
    }

    // opening the hints marks only the entries that were written into the conversation as seen: anything new that arrived while writing, and any entry that was updated meanwhile, has not been shown to the user yet
    @Test fun markSeenOnlyCoversWhatWasShown() {
        val h = NoticeHints()
        val a1 = rec("a", posted = 10, text = "one"); val b = rec("b", posted = 11)
        h.sync(listOf(a1, b), surface(a1, b), true)
        val shown = h.state.value.list
        // while writing: `a` was updated and judged worth surfacing again, `c` is new
        val a2 = rec("a", posted = 20, text = "two"); val c = rec("c", posted = 21)
        h.sync(listOf(a2, b, c), surface(a2, b, c), true)
        h.markSeen(shown)
        val s = h.state.value
        assertEquals(setOf("a", "c"), s.list.filter { !it.seen }.map { it.key }.toSet())
        assertEquals(2, s.unseen)
        assertEquals(1, s.seenTotal)
        // ones that are gone or empty have no effect
        h.markSeen(listOf(NoticeHint("gone", "p", 1, "", "", "")))
        h.markSeen(emptyList())
        assertEquals(2, h.state.value.unseen)
    }

    @Test fun featureOffEmptiesListButKeepsCounters() {
        val h = NoticeHints()
        val a = rec("a")
        h.sync(listOf(a), surface(a), true)
        h.markSeen(h.state.value.list)
        h.sync(emptyList(), emptyMap(), true)
        assertEquals(0, h.state.value.list.size)
        assertEquals(1, h.state.value.seenTotal)
        // after the inbox is emptied the same key coming in again (feature turned back on) counts as new
        h.sync(listOf(a), surface(a), true)
        assertEquals(1, h.state.value.unseen)
    }
}
