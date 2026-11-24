package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeJournalTest {
    private var now = 1_000_000_000L
    private fun journal(capacity: Int = 100, keepMs: Long = 48 * 3_600_000L, data: NoticeJournalData? = null) =
        NoticeJournal(data, capacity, keepMs) { now }
    private fun plain(key: String, text: String, at: Long = now, title: String = "T", pkg: String = "com.chat") =
        NoticeRecord(key, pkg, at, title, text)

    // once a notification is opened or swiped away it is gone from the inbox; it stays in the journal, along with every successive piece of text that one notification showed
    @Test fun keepsEveryDistinctTextOfANotificationInOrder() {
        val j = journal()
        assertEquals(1, j.add(plain("k", "[1条]A: 在吗", at = now + 1)))
        assertEquals(1, j.add(plain("k", "[2条]A: 明天来吗", at = now + 2)))
        // a verbatim repost is not recorded twice
        assertEquals(0, j.add(plain("k", "[2条]A: 明天来吗", at = now + 3)))
        assertEquals(1, j.add(plain("other", "快递到了", at = now + 4, pkg = "com.mail")))
        assertEquals(listOf("[1条]A: 在吗", "[2条]A: 明天来吗", "快递到了"), j.between(0, Long.MAX_VALUE).map { it.text })
        assertEquals(listOf("com.chat", "com.chat", "com.mail"), j.between(0, Long.MAX_VALUE).map { it.packageName })
        // all blank means nothing is recorded
        assertEquals(0, j.add(plain("e", "", title = "")))
    }

    // a conversation-style notification carries its earlier messages on every repost: each message is recorded once, with who said it and its own time
    // the fingerprint is only 32 bits, so two different lines can collide: on a collision the original text is compared and both lines must be recorded
    @Test fun differentTextsWithTheSameFingerprintAreBothRecorded() {
        // "Aa" and "BB" have the same hashCode, so the whole-record fingerprints match too
        assertEquals(listOf("T", null, "Aa").hashCode(), listOf("T", null, "BB").hashCode())
        val j = journal()
        assertEquals(1, j.add(plain("k", "Aa", at = now + 1)))
        assertEquals(1, j.add(plain("k", "BB", at = now + 2)))
        assertEquals(0, j.add(plain("k", "BB", at = now + 3)))
        assertEquals(listOf("Aa", "BB"), j.between(0, Long.MAX_VALUE).map { it.text })
        // the same holds for conversation-style records
        val said = now
        assertEquals(listOf("a", "Aa", said).hashCode(), listOf("a", "BB", said).hashCode())
        val styled = journal()
        fun chat(at: Long, vararg texts: String) = NoticeRecord("c", "com.chat", at, "T", "", conversation = true,
            messages = texts.map { NoticeMessage("a", it, said) })
        assertEquals(1, styled.add(chat(now + 1, "Aa")))
        assertEquals(1, styled.add(chat(now + 2, "Aa", "BB")))
        assertEquals(0, styled.add(chat(now + 3, "Aa", "BB")))
    }

    // a verbatim repost is not recorded twice; but the same line sent again after more than half a day (the next day's greeting) is a new occurrence and must be recorded — otherwise that day's digest would not contain it
    @Test fun theSameTextIsRecordedAgainAfterALongGap() {
        val j = journal()
        val t0 = now
        assertEquals(1, j.add(plain("k", "早上好", at = t0)))
        assertEquals(0, j.add(plain("k", "早上好", at = t0 + LearnTuning.REPEAT_WINDOW_MS - 1)))
        now = t0 + LearnTuning.REPEAT_WINDOW_MS
        assertEquals(1, j.add(plain("k", "早上好", at = now)))
        assertEquals(2, j.between(0, Long.MAX_VALUE).size)
    }

    @Test fun messagingStyleRecordsEachMessageOnce() {
        val j = journal()
        val m1 = NoticeMessage("Alice", "hi", now + 1); val m2 = NoticeMessage("Bob", "hello", now + 2); val m3 = NoticeMessage(null, "ok", 0)
        assertEquals(2, j.add(NoticeRecord("k", "com.chat", now + 2, "Group", "hello", listOf(m1, m2))))
        assertEquals(1, j.add(NoticeRecord("k", "com.chat", now + 9, "Group", "ok", listOf(m1, m2, m3))))
        val all = j.between(0, Long.MAX_VALUE)
        assertEquals(listOf("Alice" to "hi", "Bob" to "hello", null to "ok"), all.map { it.sender to it.text })
        assertTrue(all.all { it.title == "Group" })
        // a message with no time of its own uses the notification's post time
        assertEquals(listOf(now + 1, now + 2, now + 9), all.map { it.atMs })
    }

    @Test fun readsATimeRangeOldestFirst() {
        val j = journal()
        j.add(plain("b", "second", at = now + 200))
        j.add(plain("a", "first", at = now + 100))
        j.add(plain("c", "third", at = now + 300))
        assertEquals(listOf("first", "second"), j.between(now + 100, now + 300).map { it.text })
        assertEquals(emptyList<String>(), j.between(now + 301, now + 400).map { it.text })
    }

    @Test fun boundedByAgeAndCount() {
        val j = journal(capacity = 3, keepMs = 1_000)
        j.add(plain("old", "too old", at = now - 5_000))
        assertEquals(0, j.size)
        (1..5).forEach { j.add(plain("k$it", "t$it", at = now + it)) }
        assertEquals(listOf("t3", "t4", "t5"), j.between(0, Long.MAX_VALUE).map { it.text })
        now += 2_000
        j.add(plain("new", "fresh", at = now))
        assertEquals(listOf("fresh"), j.between(0, Long.MAX_VALUE).map { it.text })
    }

    // an app that is deselected or disabled: its records go with it; feature turned off: everything is cleared, and the time it restarted from is recorded
    @Test fun purgeByAppAndClear() {
        val j = journal()
        val started = j.sinceMs
        j.add(plain("a", "x", pkg = "com.chat"))
        j.add(plain("b", "y", pkg = "com.mail"))
        j.removePackages { it != "com.chat" }
        assertEquals(listOf("y"), j.between(0, Long.MAX_VALUE).map { it.text })
        assertEquals(started, j.sinceMs)
        now += 60_000
        j.clear()
        assertEquals(0, j.size)
        assertEquals(now, j.sinceMs)
        // the same content coming in after the clear is recorded as usual (the dedup state was cleared too)
        assertEquals(1, j.add(plain("b", "y", pkg = "com.mail")))
    }

    // saved and read back (restart): the content and the time recording started carry over from last time
    @Test fun dataRoundTripKeepsEntriesAndSince() {
        val j = journal()
        val since = j.sinceMs
        j.add(plain("a", "one", at = now + 1))
        j.add(NoticeRecord("s", "com.chat", now + 2, "Group", "x", listOf(NoticeMessage("Alice", "hi", now + 2))))
        now += 3_600_000
        val back = journal(data = j.data())
        assertEquals(since, back.sinceMs)
        assertEquals(j.between(0, Long.MAX_VALUE), back.between(0, Long.MAX_VALUE))
        assertEquals(listOf("one", "hi"), back.between(0, Long.MAX_VALUE).map { it.text })
        // only a hash of the key is stored
        assertTrue(back.data().entries.none { it.keyId == "a" || it.keyId == "s" })
        assertEquals(NoticeDedup.idOf("a"), back.data().entries.first().keyId)
    }

    // after a restart, the same notification reposted verbatim or with its earlier messages: only the new segment is recorded
    @Test fun repostsAfterRestartAreNotRecordedAgain() {
        val j = journal()
        val m1 = NoticeMessage("Alice", "hi", now + 1); val m2 = NoticeMessage("Bob", "hello", now + 2)
        j.add(NoticeRecord("s", "com.chat", now + 2, "Group", "hello", listOf(m1, m2)))
        j.add(plain("p", "[2条]A: 明天来吗", at = now + 3))
        val back = journal(data = j.data())
        assertEquals(0, back.add(plain("p", "[2条]A: 明天来吗", at = now + 9)))
        val m3 = NoticeMessage("Alice", "new one", now + 10)
        assertEquals(1, back.add(NoticeRecord("s", "com.chat", now + 10, "Group", "new one", listOf(m1, m2, m3))))
        assertEquals(listOf("hi", "hello", "[2条]A: 明天来吗", "new one"), back.between(0, Long.MAX_VALUE).map { it.text })
    }

    // expired entries must be dropped: even without new notifications arriving, and on read-back too
    @Test fun expiresWithoutNewNotifications() {
        val j = journal(keepMs = 1_000)
        j.add(plain("a", "old", at = now))
        now += 500
        j.add(plain("b", "newer", at = now))
        assertFalse(j.expire())
        now += 600
        assertTrue(j.expire())
        assertEquals(listOf("newer"), j.between(0, Long.MAX_VALUE).map { it.text })
        val saved = j.data()
        now += 1_000
        assertEquals(0, journal(keepMs = 1_000, data = saved).size)
    }

    // a day that has already been digested: the whole span is dropped
    @Test fun removeBeforeDropsADigestedDay() {
        val j = journal()
        j.add(plain("a", "yesterday", at = now + 1))
        j.add(plain("b", "today", at = now + 100))
        assertTrue(j.removeBefore(now + 50))
        assertEquals(listOf("today"), j.between(0, Long.MAX_VALUE).map { it.text })
        assertFalse(j.removeBefore(now + 50))
    }

    // used by the segmented digests: which app is due and which of its entries have not been digested yet; ones already done (whether they succeeded or not) never reappear, and that holds after saving too
    @Test fun pendingDueAndNoted() {
        val j = journal()
        (1..3).forEach { j.add(plain("a$it", "a$it", at = now + it, pkg = "com.a")) }
        j.add(plain("b1", "b1", at = now + 10, pkg = "com.b"))
        assertNull(j.due(now + 20, { true }, dueCount = 5, dueAgeMs = 1_000))
        assertEquals("com.a", j.due(now + 20, { true }, dueCount = 3, dueAgeMs = 1_000))
        // ones past the age threshold count too; when several are due the oldest goes first; apps excluded from digesting are skipped
        assertEquals("com.a", j.due(now + 2_000, { true }, dueCount = 50, dueAgeMs = 1_000))
        assertEquals("com.b", j.due(now + 2_000, { it != "com.a" }, dueCount = 50, dueAgeMs = 1_000))
        val batch = j.pending("com.a", limit = 2)
        assertEquals(listOf("a1", "a2"), batch.map { it.text })
        assertTrue(j.markNoted(batch))
        assertFalse(j.markNoted(batch))
        assertEquals(listOf("a3"), j.pending("com.a", 10).map { it.text })
        assertEquals(2, j.pendingCount)
        val back = journal(data = j.data())
        assertEquals(listOf("a3"), back.pending("com.a", 10).map { it.text })
        // digested entries are still kept (needed when the original text is looked up that day)
        assertEquals(4, back.size)
    }

    // a message's own time is filled in by the app that posted the notification: a future time is clamped to the post time (otherwise the entry would never expire and would be counted into some future day)
    @Test fun aMessageTimeInTheFutureIsClampedToThePostTime() {
        val j = journal(keepMs = 1_000)
        val future = NoticeMessage("Eve", "from the future", now + 10_000_000)
        val ok = NoticeMessage("Bob", "fine", now - 10)
        j.add(NoticeRecord("s", "com.chat", now, "Group", "x", listOf(ok, future)))
        assertEquals(listOf(now - 10, now), j.between(0, Long.MAX_VALUE).map { it.atMs })
        now += 2_000
        assertTrue(j.expire())
        assertEquals(0, j.size)
    }

    // when the count limit evicts older entries, "since when it has been complete" moves forward with them (otherwise the day with the most notifications would be treated as complete), and it survives save and read-back; it is recomputed after a clear
    @Test fun completeSinceMovesWhenTheCapEvicts() {
        val j = journal(capacity = 3)
        val started = j.sinceMs
        (1..3).forEach { j.add(plain("k$it", "t$it", at = now + it)) }
        assertEquals(started, j.completeSinceMs)
        (4..5).forEach { j.add(plain("k$it", "t$it", at = now + it)) }
        assertEquals(now + 2, j.completeSinceMs)
        assertEquals(started, j.sinceMs)
        val back = journal(capacity = 3, data = j.data())
        assertEquals(now + 2, back.completeSinceMs)
        // entries dropped by the retention period do not count as "evicted"
        val k = journal(capacity = 100, keepMs = 1_000)
        k.add(plain("a", "x", at = now))
        now += 5_000
        k.add(plain("b", "y", at = now))
        assertEquals(k.sinceMs, k.completeSinceMs)
        now += 10
        back.clear()
        assertEquals(now, back.completeSinceMs)
    }

    // which notifications a segmented-digest line was written from: looked up by app, conversation name and start/end time (all inclusive); the conversation name is computed the same way as when the line was stored (unread counts stripped)
    @Test fun aroundFindsOneChatsEntriesInsideTheWindow() {
        val j = NoticeJournal(clock = { 10_000L })
        fun rec(key: String, pkg: String, at: Long, title: String, text: String) = NoticeRecord(key, pkg, at, title, text)
        j.add(rec("a", "com.chat", 1_000, "家长群(2条新消息)", "王老师: 明天开会"))
        j.add(rec("a", "com.chat", 2_000, "家长群(3条新消息)", "李老师: 收到"))
        j.add(rec("b", "com.chat", 2_500, "同事群", "张三: 周报"))
        j.add(rec("c", "com.mail", 2_000, "家长群", "别的 App"))
        j.add(rec("a", "com.chat", 9_000, "家长群(4条新消息)", "王老师: 改到后天"))
        assertEquals(listOf("王老师: 明天开会", "李老师: 收到"), j.around("com.chat", "家长群", 1_000, 2_000).map { it.text.substringAfter("]") })
        // a line that recorded no conversation name (its source segments were not all from the same conversation): everything this app has in that window is returned
        assertEquals(3, j.around("com.chat", null, 1_000, 2_500).size)
        assertTrue(j.around("com.chat", "家长群", 3_000, 8_000).isEmpty())
        assertTrue(j.around("com.other", null, 0, 10_000).isEmpty())
    }
}
