package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeDedupTest {
    private fun rec(key: String, at: Long, vararg msgAt: Long) = NoticeRecord(key, "com.tencent.mm", at, "t", "x",
        messages = msgAt.map { NoticeMessage("a", "m$it", it) }, conversation = msgAt.isNotEmpty())

    @Test fun samePostIsSeenOnce() {
        val d = NoticeDedup()
        assertTrue(d.accept(rec("k", 1)))
        assertFalse(d.accept(rec("k", 1)))
        assertTrue(d.accept(rec("k", 2)))
        assertTrue(d.accept(rec("other", 2)))
    }

    @Test fun messagingUpdatesCountOnlyNewerMessages() {
        val d = NoticeDedup()
        assertTrue(d.accept(rec("chat", 10, 5, 8)))
        // reposted (the post time changed) but with no new messages: not new
        assertFalse(d.accept(rec("chat", 11, 5, 8)))
        assertTrue(d.accept(rec("chat", 12, 5, 8, 9)))
        assertFalse(d.accept(rec("chat", 13, 8, 9)))
    }

    @Test fun boundedLeastRecentlyUsed() {
        val d = NoticeDedup(capacity = 3)
        d.accept(rec("a", 1)); d.accept(rec("b", 1)); d.accept(rec("c", 1))
        assertFalse(d.accept(rec("a", 1))) // used once, so `a` becomes the most recent
        d.accept(rec("d", 1)) // evicts the least recently used, `b`
        assertEquals(3, d.size())
        assertTrue(d.accept(rec("b", 1)))
    }

    @Test fun roundTripKeepsStateAndHostileKeys() {
        val d = NoticeDedup()
        val nasty = "0|pkg|1|\"],\n\t[\\u0000|x"
        d.accept(rec(nasty, 7))
        d.accept(rec("chat", 10, 5, 8))
        val back = NoticeDedup.decode(d.encode())
        assertEquals(2, back.size())
        assertFalse(back.accept(rec(nasty, 7)))
        assertFalse(back.accept(rec("chat", 11, 8)))
        assertTrue(back.accept(rec("chat", 12, 9)))
    }

    @Test fun garbageDecodesToEmpty() {
        for (s in listOf(
            null, "", "not json", "[[1]]", "{\"a\":1}", "[[\"k\",\"x\",1]]",
            "{\"v\":2,\"seen\":[[\"k\",1,1]]}", // the id does not look like an id
            "{\"v\":2,\"seen\":[[1234567890123456,1,1]]}", // the id is not a string
            "{\"v\":3,\"seen\":[]}",
        )) assertEquals(s, 0, NoticeDedup.decode(s).size())
    }

    // what is stored contains no raw key, not even a fragment of one
    @Test fun encodedFormHasNoRawKey() {
        val keys = listOf("0|com.tencent.mm|1|wxid_abcdefg12345@chatroom|10123", "0|p|7|secret-tag|0")
        val d = NoticeDedup()
        keys.forEach { d.accept(rec(it, 1)) }
        val out = d.encode()
        for (k in keys) for (part in k.split('|').filter { it.length >= 4 }) assertFalse(part, out.contains(part))
        assertEquals(16, NoticeDedup.idOf(keys[0]).length)
        assertTrue(NoticeDedup.idOf(keys[0]).all { it in "0123456789abcdef" })
        assertEquals(NoticeDedup.idOf(keys[0]), NoticeDedup.idOf(keys[0]))
        assertFalse(NoticeDedup.idOf(keys[0]) == NoticeDedup.idOf(keys[1]))
        // the first 16 characters of SHA-256("abc")
        assertEquals("ba7816bf8f01cfea", NoticeDedup.idOf("abc"))
    }

    // the old format (raw keys recorded directly, v1) decodes to empty and is never written back out as the new format
    @Test fun oldFormatDecodesToEmpty() {
        val v1 = "[[\"0|com.tencent.mm|1|wxid_abc@chatroom|10\",1700000000000,-9223372036854775808],[\"0123456789abcdef\",1,1]]"
        val back = NoticeDedup.decode(v1)
        assertEquals(0, back.size())
        assertFalse(back.encode().contains("wxid"))
    }

    @Test fun decodeRespectsCapacity() {
        val d = NoticeDedup()
        (1..10).forEach { d.accept(rec("k$it", 1)) }
        val back = NoticeDedup.decode(d.encode(), capacity = 4)
        assertEquals(4, back.size())
        assertFalse(back.accept(rec("k10", 1)))
        assertTrue(back.accept(rec("k1", 1)))
    }

    // ---- message times are filled in by the app and cannot be fully trusted ----

    private fun chat(at: Long, vararg messages: Pair<String, Long>) = NoticeRecord("chat", "com.chat", at, "t", "x",
        messages = messages.map { (text, time) -> NoticeMessage("a", text, time) }, conversation = true)

    // a time filled in the future: it must not be recorded as the watermark, or later genuine messages would always be older than it
    @Test fun aFutureMessageTimeDoesNotHideLaterMessages() {
        val d = NoticeDedup()
        assertTrue(d.accept(chat(100, "first" to 9_999_999)))
        assertTrue(d.accept(chat(200, "first" to 9_999_999, "second" to 150)))
        assertFalse(d.accept(chat(300, "first" to 9_999_999, "second" to 150)))
        // the future time is not in the watermark
        assertFalse("9999999" in d.encode())
    }

    // all filled with 0, several squeezed into the same millisecond: new messages still count as new; a verbatim repost (post time changed) does not
    @Test fun zeroAndSameMillisecondTimesStillShowNewMessages() {
        val zero = NoticeDedup()
        assertTrue(zero.accept(chat(100, "one" to 0)))
        assertFalse(zero.accept(chat(110, "one" to 0)))
        assertTrue(zero.accept(chat(120, "one" to 0, "two" to 0)))
        assertFalse(zero.accept(chat(130, "one" to 0, "two" to 0)))
        val same = NoticeDedup()
        assertTrue(same.accept(chat(100, "one" to 50)))
        assertTrue(same.accept(chat(110, "one" to 50, "two" to 50)))
        assertFalse(same.accept(chat(120, "one" to 50, "two" to 50)))
        // the app pushed the oldest one out of the list: no new messages, so not new
        assertFalse(same.accept(chat(130, "two" to 50)))
    }

    // which messages were seen for the first time now (used by the digest): all of them the first time, only the new ones after that; none for a non-conversation record
    @Test fun admitSaysWhichMessagesAreNew() {
        val d = NoticeDedup()
        assertEquals(listOf("one", "two"), d.admit(chat(100, "one" to 10, "two" to 20))!!.newMessages.map { it.text })
        assertEquals(listOf("three"), d.admit(chat(200, "one" to 10, "two" to 20, "three" to 0))!!.newMessages.map { it.text })
        assertEquals(null, d.admit(chat(300, "two" to 20, "three" to 0)))
        assertEquals(emptyList<NoticeMessage>(), d.admit(rec("plain", 1))!!.newMessages)
    }

    // after a process restart (the fingerprints are gone) the first sighting can only go by time: trustworthy times behave as usual; a verbatim repost is not new
    @Test fun afterARestartTimesDecideTheFirstSighting() {
        val before = NoticeDedup()
        before.accept(chat(100, "one" to 10, "two" to 20))
        val saved = before.encode()
        val repost = NoticeDedup.decode(saved)
        assertFalse(repost.accept(chat(300, "one" to 10, "two" to 20)))
        // after that one there is something to compare against again
        assertTrue(repost.accept(chat(310, "one" to 10, "two" to 20, "three" to 0)))
        val newer = NoticeDedup.decode(saved)
        assertEquals(listOf("three"), newer.admit(chat(300, "two" to 20, "three" to 250))!!.newMessages.map { it.text })
        // the message server's clock runs a little ahead of the phone's (the message time is one second later than the post time): still trusted, still counted as new
        val skewed = NoticeDedup.decode(saved)
        assertEquals(listOf("three"), skewed.admit(chat(300, "two" to 20, "three" to 1_300))!!.newMessages.map { it.text })
        assertFalse(skewed.accept(chat(310, "two" to 20, "three" to 1_300)))
        // the first time over the times are untrustworthy again: treat as seen (there is no way to tell), then behave as usual from the next one on
        val blind = NoticeDedup.decode(saved)
        assertFalse(blind.accept(chat(300, "two" to 20, "three" to 0)))
        assertTrue(blind.accept(chat(310, "two" to 20, "three" to 0, "four" to 0)))
    }

    // the stored watermark is later than the post time (corrupted by a future time filled in by the app): it cannot be real, so treat it as absent
    @Test fun aSavedWatermarkFromTheFutureIsIgnored() {
        val id = NoticeDedup.idOf("chat")
        val poisoned = NoticeDedup.decode("""{"v":2,"seen":[["$id",100,9999999]]}""")
        assertTrue(poisoned.accept(chat(200, "one" to 150)))
        assertFalse(poisoned.accept(chat(210, "one" to 150)))
        assertTrue(poisoned.accept(chat(220, "one" to 150, "two" to 215)))
        assertFalse("9999999" in poisoned.encode())
    }

    // a sighting without messages arrives in between (all empty): the fingerprint of the previous messages is kept, so the next one carrying them is still compared by content
    @Test fun aSightingWithoutMessagesKeepsWhatWasSeen() {
        val d = NoticeDedup()
        assertTrue(d.accept(chat(100, "one" to 0)))
        assertTrue(d.accept(NoticeRecord("chat", "com.chat", 110, "t", "x", conversation = true)))
        assertFalse(d.accept(chat(120, "one" to 0)))
        assertTrue(d.accept(chat(130, "one" to 0, "two" to 0)))
    }

    // what is stored looks the same as before: the key id, the post time, the latest message time; no content fingerprint
    @Test fun encodedFormIsUnchanged() {
        val d = NoticeDedup()
        d.accept(chat(100, "one" to 10, "two" to 20))
        assertEquals("""{"v":2,"seen":[["${NoticeDedup.idOf("chat")}",100,20]]}""", d.encode())
    }
}
