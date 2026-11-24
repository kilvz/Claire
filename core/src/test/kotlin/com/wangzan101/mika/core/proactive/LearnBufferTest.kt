package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnBufferTest {
    private fun rec(key: String, pkg: String = "com.shop", at: Long = 0, title: String = "t $key", text: String = "x $key") =
        NoticeRecord(key, pkg, at, title, text)

    @Test fun oneEventPerDistinctTextOfAKey() {
        val b = LearnBuffer()
        assertTrue(b.capture(rec("a")))
        assertFalse(b.capture(rec("a", at = 5)))
        assertTrue(b.capture(rec("a", text = "changed")))
        assertFalse(b.capture(rec("b", title = " ", text = "")))
        assertEquals(LearnBuffer.Summary(2, 2), b.state.value)
    }

    // Conversation-shaped notifications: summarization uses the messages in the message list first seen this time (with who said them), not the notification body — the body is often empty or holds only the last line
    @Test fun newMessagesOfAConversationAreWhatGetsLearnedFrom() {
        val dedup = NoticeDedup()
        val b = LearnBuffer()
        fun post(at: Long, text: String, vararg messages: Pair<String, Long>) {
            val r = NoticeRecord("chat", "com.chat", at, "Alice", text, conversation = true,
                messages = messages.map { (said, time) -> NoticeMessage("Alice", said, time) })
            dedup.admit(r)?.let { b.capture(r, it.newMessages) }
        }
        // The body is empty; the words are in the message list
        post(100, "", "明天三点开会" to 90)
        // Two lines arrived while the screen was locked, and the notification body holds only the last one
        post(200, "好的", "明天三点开会" to 90, "带上合同" to 150, "好的" to 190)
        val batch = b.begin("com.chat")
        assertEquals(listOf("Alice: 明天三点开会", "Alice: 带上合同 / Alice: 好的"), batch.map { it.text })
        // The input sent to the model contains these words
        val input = NoticeLearn.buildInput("com.chat", batch, emptyList(), "English")
        for (said in listOf("明天三点开会", "带上合同", "好的")) assertTrue(said, said in input)
    }

    // Too many new messages at once: take at most the newest few, with a cap on total length, keeping the new ones when they don't fit
    @Test fun manyNewMessagesAreCappedKeepingTheNewest() {
        val b = LearnBuffer()
        val many = (1..9).map { NoticeMessage("A", "m$it", it.toLong()) }
        assertTrue(b.capture(NoticeRecord("chat", "com.chat", 100, "Alice", "", conversation = true, messages = many), many))
        val text = b.begin("com.chat").single().text
        assertEquals((9 - LearnTuning.NEW_LINES_MAX + 1..9).joinToString(" / ") { "A: m$it" }, text)
        val long = (1..3).map { NoticeMessage("A", "$it" + "x".repeat(120), it.toLong()) }
        val c = LearnBuffer()
        c.capture(NoticeRecord("chat", "com.chat", 100, "Alice", "", conversation = true, messages = long), long)
        val kept = c.begin("com.chat").single().text
        assertTrue(kept.length <= LearnTuning.TEXT_MAX)
        assertTrue(kept.startsWith("A: 3"))
        // No new messages (not a conversation-shaped notification): use the body as before
        val d = LearnBuffer()
        d.capture(NoticeRecord("k", "com.shop", 1, "到账通知", "收到 20 元"))
        assertEquals("收到 20 元", d.begin("com.shop").single().text)
    }

    // An app that reposts the same notification verbatim for every line: identical text close together is recorded only once, and a repost a few minutes past midnight doesn't count as the next day's;
    // but the same sentence really sent again the next day is a new occurrence (topics and contacts rely on it to accumulate "how many days it appeared") — the same once summarized
    @Test fun theSameTextCountsAgainOnlyAfterALongGap() {
        val day = 24 * 3_600_000L
        val b = LearnBuffer()
        assertTrue(b.capture(rec("greet", at = day - 10 * 60_000, text = "早上好")))
        // Reposted verbatim right after midnight
        assertFalse(b.capture(rec("greet", at = day + 5 * 60_000, text = "早上好")))
        b.begin("com.shop")
        b.finish(emptyMap())
        assertFalse(b.capture(rec("greet", at = day + 30 * 60_000, text = "早上好")))
        // The same sentence on the next day
        assertTrue(b.capture(rec("greet", at = 2 * day - 10 * 60_000, text = "早上好")))
        assertEquals(1, b.pendingCount)
        // Just under half a day doesn't count, reaching it does (measured from the most recent occurrence)
        val c = LearnBuffer()
        assertTrue(c.capture(rec("k", at = 0)))
        assertFalse(c.capture(rec("k", at = LearnTuning.REPEAT_WINDOW_MS - 1)))
        assertFalse(c.capture(rec("k", at = LearnTuning.REPEAT_WINDOW_MS)))
        assertFalse(c.capture(rec("k", at = 2 * LearnTuning.REPEAT_WINDOW_MS - 1)))
        assertTrue(c.capture(rec("k", at = 3 * LearnTuning.REPEAT_WINDOW_MS - 1)))
    }

    // A notification nobody handles is reposted verbatim every hour by the app for two days: it counts once (a single old message must not accumulate several days);
    // the same sentence returning after a quiet day is a new occurrence
    @Test fun aNotificationRepostedForDaysCountsOnce() {
        val hour = 3_600_000L
        val b = LearnBuffer()
        assertTrue(b.capture(rec("stale", at = 0, text = "您有一张优惠券")))
        for (h in 1..48) assertFalse("$h", b.capture(rec("stale", at = h * hour, text = "您有一张优惠券")))
        assertEquals(1, b.pendingCount)
        assertTrue(b.capture(rec("stale", at = (48 + 24) * hour, text = "您有一张优惠券")))
        // Due timing still uses when it was first sent (reposts don't push it later)
        assertEquals("com.shop", b.due(LearnTuning.DUE_AGE_MS, { true }))
    }

    // A 32-bit hashCode treats "Aa" and "BB" as the same line; the current fingerprint doesn't
    @Test fun differentTextsWithTheSameHashCodeAreBothKept() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val b = LearnBuffer()
        assertTrue(b.capture(rec("k", title = "t", text = "Aa")))
        assertTrue(b.capture(rec("k", title = "t", text = "BB")))
        assertEquals(2, b.pendingCount)
    }

    @Test fun textIsCappedAtCapture() {
        val b = LearnBuffer()
        b.capture(rec("a", title = "t".repeat(500), text = "x".repeat(900)))
        val e = b.begin("com.shop").single()
        assertEquals(LearnTuning.TITLE_MAX, e.title.length)
        assertEquals(LearnTuning.TEXT_MAX, e.text.length)
    }

    @Test fun dueByCountOrAge() {
        val b = LearnBuffer()
        repeat(LearnTuning.DUE_COUNT - 1) { b.capture(rec("k$it", at = 1_000)) }
        assertNull(b.due(1_000, { true }))
        assertNull(b.due(1_000 + LearnTuning.DUE_AGE_MS - 1, { true }))
        assertEquals("com.shop", b.due(1_000 + LearnTuning.DUE_AGE_MS, { true }))
        b.capture(rec("last", at = 1_000))
        assertEquals("com.shop", b.due(1_000, { true }))
        assertNull(b.due(1_000) { it != "com.shop" })
    }

    @Test fun oldestDueAppGoesFirstAndBatchIsBounded() {
        val b = LearnBuffer()
        repeat(20) { b.capture(rec("s$it", at = 100L + it)) }
        b.capture(rec("m", pkg = "com.mall", at = 50))
        assertEquals("com.mall", b.due(50 + LearnTuning.DUE_AGE_MS, { true }))
        val batch = b.begin("com.shop")
        assertEquals(LearnTuning.BATCH_MAX, batch.size)
        assertEquals((0 until LearnTuning.BATCH_MAX).map { "s$it" }, batch.map { it.key })
        assertEquals(LearnBuffer.Summary(21, 9), b.state.value)
    }

    @Test fun abortFailFinish() {
        val b = LearnBuffer()
        b.capture(rec("a")); b.capture(rec("b"))
        b.begin("com.shop")
        b.abort()
        assertEquals(2, b.pendingCount)
        val batch = b.begin("com.shop")
        assertEquals(2, b.fail())
        assertEquals(LearnBuffer.Summary(0, 0), b.state.value)
        b.capture(rec("c")); b.capture(rec("d"))
        val again = b.begin("com.shop")
        b.finish(mapOf(again[0].seq to setOf("k1")))
        assertEquals(LearnBuffer.Summary(2, 0), b.state.value)
        assertTrue(batch.isNotEmpty())
        // Summarized events keep no text
        assertTrue(b.sending().isEmpty())
    }

    @Test fun outcomeGoesToTheLatestEventOfTheKeyOnce() {
        val b = LearnBuffer()
        b.capture(rec("a", text = "one"))
        b.capture(rec("a", text = "two"))
        assertTrue(b.outcome("a", LearnOutcome.Opened).isEmpty())
        val sent = b.begin("com.shop")
        assertEquals(listOf(LearnOutcome.None, LearnOutcome.Opened), sent.map { it.outcome })
        // One that already has an outcome isn't changed
        b.outcome("a", LearnOutcome.Dismissed)
        assertEquals(LearnOutcome.Opened, b.sending().last().outcome)
        assertTrue(b.outcome("missing", LearnOutcome.Opened).isEmpty())
    }

    @Test fun lateOutcomeOnDistilledEventReturnsItsTopics() {
        val b = LearnBuffer()
        b.capture(rec("a"))
        val e = b.begin("com.shop").single()
        b.outcome("a", LearnOutcome.None)
        b.finish(mapOf(e.seq to setOf("k1", "k2")))
        assertEquals(setOf("k1", "k2"), b.outcome("a", LearnOutcome.Dismissed))
        assertTrue(b.outcome("a", LearnOutcome.Opened).isEmpty())
        b.capture(rec("z"))
        val z = b.begin("com.shop").single()
        b.finish(mapOf(z.seq to setOf("k1")))
        b.forgetTopics(setOf("k1"))
        assertTrue(b.outcome("z", LearnOutcome.Opened).isEmpty())
    }

    // A topic the user merged into another: notifications assigned to it that are opened/dismissed afterwards count toward the one it went into
    @Test fun eventsOfAMergedTopicPointAtTheTopicItWentInto() {
        val b = LearnBuffer()
        b.capture(rec("a")); b.capture(rec("z"))
        val (a, z) = b.begin("com.shop")
        b.finish(mapOf(a.seq to setOf("k1", "k2"), z.seq to setOf("k3")))
        b.remapTopic("k1", "k2")
        b.remapTopic("k3", "k4")
        assertEquals(setOf("k2"), b.outcome("a", LearnOutcome.Opened))
        assertEquals(setOf("k4"), b.outcome("z", LearnOutcome.Dismissed))
    }

    // The "title is the speaker" mark travels with the event (contact recognition looks at it)
    @Test fun theSpeakerTitleMarkTravelsWithTheEvent() {
        val b = LearnBuffer()
        b.capture(rec("a").copy(group = true, titleIsSpeaker = true))
        b.capture(rec("b"))
        assertEquals(listOf(true, false), b.begin("com.shop").map { it.titleIsSpeaker })
    }

    // A notification taken as a status display is dismissed later: its event is already gone, so it doesn't fall back to an earlier, unrelated event under the same key and count it as feedback
    @Test fun dismissingAWithdrawnStatusDisplayDoesNotReachAnOlderEventOfTheKey() {
        val b = LearnBuffer()
        b.capture(rec("shared", at = 1_000, text = "一小时前的正常通知"))
        val old = b.begin("com.shop").single()
        b.finish(mapOf(old.seq to setOf("k1")))
        repeat(3) { i -> b.capture(rec("shared", at = 3_600_000L + i * 2_000L, text = "${300 - i * 10} 米后右转")) }
        assertTrue(b.forgetKey("shared", 3_600_000L - 60_000L))
        assertTrue(b.outcome("shared", LearnOutcome.Dismissed).isEmpty())
        // It only blocks this once: after a new record is captured under the key things work as usual
        b.forgetKey("shared", 7_200_000L)
        b.capture(rec("shared", at = 9_000_000L, text = "新的正常通知"))
        val next = b.begin("com.shop").single()
        b.finish(mapOf(next.seq to setOf("k2")))
        assertEquals(setOf("k2"), b.outcome("shared", LearnOutcome.Opened))
    }

    @Test fun evictsDistilledFirstThenOldestPending() {
        val b = LearnBuffer(capacity = 3)
        b.capture(rec("a")); b.capture(rec("b"))
        b.begin("com.shop"); b.finish(emptyMap())
        b.capture(rec("c")); b.capture(rec("d")); b.capture(rec("e"))
        assertEquals(LearnBuffer.Summary(3, 3), b.state.value)
        b.capture(rec("f"))
        assertEquals(listOf("d", "e", "f"), b.begin("com.shop").map { it.key })
    }

    @Test fun purgeAndClear() {
        val b = LearnBuffer()
        b.capture(rec("a")); b.capture(rec("b", pkg = "com.chat"))
        b.purge { it != "com.chat" }
        assertEquals(LearnBuffer.Summary(1, 1), b.state.value)
        b.clear()
        assertEquals(LearnBuffer.Summary(0, 0), b.state.value)
    }
}
