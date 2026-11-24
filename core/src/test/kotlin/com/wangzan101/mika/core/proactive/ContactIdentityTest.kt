package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactIdentityTest {
    private val pkg = "com.chat"
    private fun id(key: String, title: String, s: ContactIdStrategy) = ContactIdentity.of(pkg, key, title, s)!!.id

    // Now it only looks at the conversation name: notification keys aren't one per conversation (some apps increment the notification id), so the same group with a new key is still the same contact
    @Test fun defaultLooksAtTheConversationNameOnly() {
        assertEquals(ContactIdStrategy.Title, LearnTuning.CONTACT_ID)
        val ids = (512..521).map { ContactIdentity.of(pkg, "0|com.chat|$it|null|10234", "三年2班家长群(${it - 500}条新消息)")!!.id }
        assertEquals(1, ids.toSet().size)
        assertTrue(ids.first().startsWith("t"))
        // Different conversations and different apps are still kept apart
        assertNotEquals(ids.first(), ContactIdentity.of(pkg, "0|com.chat|512|null|10234", "三年3班家长群")!!.id)
        assertNotEquals(ids.first(), ContactIdentity.of("com.other", "0|com.chat|512|null|10234", "三年2班家长群")!!.id)
    }

    // Identity and display name come from the same string: a stored contact has only a display name, and the identity derived from it must match the live notification's (very long names too)
    @Test fun aStoredNameGivesTheSameIdentityAsTheLiveTitle() {
        for (title in listOf("三年2班家长群", "三年2班家长群(12条新消息)", "三年2班家长群（99+条新消息）", "  Alice\u0000  Bob  ", "很长的群名".repeat(30), "x".repeat(300) + "(3条新消息)")) {
            val live = ContactIdentity.of(pkg, "k1", title)!!
            val stored = ContactIdentity.conversationName(title)
            assertTrue(title, stored.length <= LearnTuning.CONTACT_NAME_MAX)
            assertEquals(title, live.id, ContactIdentity.ofStored(pkg, stored))
            assertEquals(title, live.id, ContactIdentity.ofStored(pkg, live.name!!))
        }
        // With the old algorithm a display name alone yields no identity
        assertNull(ContactIdentity.ofStored(pkg, "Alice", ContactIdStrategy.KeyAndTitle))
    }

    // Seen on a real device: the number in brackets at the end of the conversation name changes ("Group (1,234)" later becomes "Group (1,236)"). Conversation matching ignores it; the display name keeps it
    @Test fun aTrailingBracketedNumberDoesNotSplitTheConversation() {
        val s = ContactIdStrategy.Title
        val ids = listOf("周末爬山群（1,234）", "周末爬山群（1,236）", "周末爬山群 (987)", "周末爬山群(99+)", "周末爬山群").map { id("k", it, s) }
        assertEquals(1, ids.toSet().size)
        assertEquals("周末爬山群（1,234）", ContactIdentity.displayName("周末爬山群（1,234）"))
        // It is still recognized when the name plus that number just exceeds the display-name cap (the bracket is cut in half)
        val long = "群".repeat(LearnTuning.CONTACT_NAME_MAX - 4)
        assertEquals(id("k", long, s), id("k", "$long（1,234）", s))
        assertEquals(id("k", long, s), id("k", "$long（1,301）", s))
        // Name longer than the cap to begin with: only the leading part counts, so a trailing number makes no difference
        val longer = "群".repeat(LearnTuning.CONTACT_NAME_MAX + 10)
        assertEquals(id("k", longer, s), id("k", "$longer（7）", s))
        // A stored display name (with that number) yields the same identity
        assertEquals(ids.first(), ContactIdentity.ofStored(pkg, "周末爬山群（1,236）"))
        // Brackets that are not at the end or not purely numeric are still part of the name
        for (title in listOf("周末爬山群(第3期)", "(3)周末爬山群", "周末爬山群(3)二队")) assertNotEquals(title, ids.first(), id("k", title, s))
        // Nothing left after stripping: no identity can be computed
        assertNull(ContactIdentity.of(pkg, "k", "(12)", s))
    }

    // The old algorithm (key + title) stays as is: ids computed with it still sit in the "do not record" list and must still be recognized
    @Test fun theOldKeyAndTitleIdsAreStillComputedTheSameWay() {
        val old = ContactIdStrategy.KeyAndTitle
        assertEquals("b" + NoticeDedup.idOf("contact\u0000com.chat\u0000k1\n" + TopicPool.normalize("三年2班家长群")), id("k1", "三年2班家长群(2条新消息)", old))
        // The old algorithm didn't recognize the "99+" form: under it that is still part of the name
        assertNotEquals(id("k1", "三年2班家长群", old), id("k1", "三年2班家长群(99+条新消息)", old))
        assertNotEquals(id("k1", "Alice", old), id("k2", "Alice", old))
    }

    @Test fun keyStrategyIgnoresTheTitle() {
        val s = ContactIdStrategy.Key
        // Same key, title changed: still the same contact, and the display name takes the new one
        assertEquals(id("k1", "Alice", s), id("k1", "Alice 🌸", s))
        assertEquals("Alice 🌸", ContactIdentity.of(pkg, "k1", "Alice 🌸", s)!!.name)
        // Different keys, same title: different contacts (a stranger switching to the same nickname doesn't merge in)
        assertNotEquals(id("k1", "妈妈", s), id("k2", "妈妈", s))
        // An empty title still has an identity, it just yields no display name
        assertNull(ContactIdentity.of(pkg, "k1", "  ", s)!!.name)
    }

    @Test fun titleStrategyIgnoresTheKey() {
        val s = ContactIdStrategy.Title
        assertEquals(id("k1", "Alice", s), id("k2", "ａｌｉｃｅ ", s))
        assertNotEquals(id("k1", "Alice", s), id("k1", "Bob", s))
        assertNull(ContactIdentity.of(pkg, "k1", " ", s))
    }

    @Test fun keyAndTitleNeedsBoth() {
        val s = ContactIdStrategy.KeyAndTitle
        assertEquals(id("k1", "Alice", s), id("k1", "Alice", s))
        assertNotEquals(id("k1", "Alice", s), id("k1", "Bob", s))
        assertNotEquals(id("k1", "Alice", s), id("k2", "Alice", s))
        assertNull(ContactIdentity.of(pkg, "k1", "", s))
    }

    @Test fun idsAreHashesPrefixedByStrategyAndScopedToTheApp() {
        val ids = ContactIdStrategy.entries.map { id("k1", "Alice", it) }
        assertEquals(3, ids.toSet().size)
        ids.forEach { assertFalse(it.contains("Alice") || it.contains("k1")) }
        assertTrue(ids[0].startsWith("k") && ids[1].startsWith("t") && ids[2].startsWith("b"))
        assertNotEquals(id("k1", "Alice", ContactIdStrategy.Title), ContactIdentity.of("com.other", "k1", "Alice", ContactIdStrategy.Title)!!.id)
    }

    @Test fun displayNameIsCleanedCappedAndFiltered() {
        assertEquals("Alice Bob", ContactIdentity.displayName(" Alice\u0000‮  Bob "))
        assertEquals(LearnTuning.CONTACT_NAME_MAX, ContactIdentity.displayName("x".repeat(100))!!.length)
        assertNull(ContactIdentity.displayName(" "))
        assertNull(ContactIdentity.displayName("验证码 482913"))
        assertNull(ContactIdentity.displayName("登录码 4829"))
    }

    // Senders whose title is just a number, and pushes carrying an order number or a URL: kept out of names (the same two checks as for new topic names); the identity is still computed
    @Test fun displayNameIsNotAPhoneNumberAnOrderNumberOrAUrl() {
        assertNull(ContactIdentity.displayName("13812345678"))
        assertNull(ContactIdentity.displayName("+86 138-1234-5678".replace("-", "")))
        assertNull(ContactIdentity.displayName("１３８１２３４５６７８")) // Full-width digits are the same
        assertNull(ContactIdentity.displayName("订单 2026111012345 已发货"))
        assertNull(ContactIdentity.displayName("领券 example.com/x"))
        assertNull(ContactIdentity.displayName("https://t.cn/abc"))
        // Short numbers and ordinary names pass as usual
        assertEquals("10086", ContactIdentity.displayName("10086"))
        assertEquals("三年2班家长群", ContactIdentity.displayName("三年2班家长群"))
        assertEquals("Node.js 交流", ContactIdentity.displayName("Node.js 交流"))
        val id = ContactIdentity.of(pkg, "k1", "13812345678", ContactIdStrategy.KeyAndTitle)!!
        assertNull(id.name)
    }

    // Seen on a real device: QQ puts the unread count after the title ("Group (2 new messages)"). Left in, each extra message would turn the same conversation into another contact,
    // the day count would never accumulate, and deleted contacts would come back in a different shape
    @Test fun unreadCountSuffixIsNotPartOfTheIdentityOrTheName() {
        val s = ContactIdStrategy.KeyAndTitle
        val base = ContactIdentity.of(pkg, "k1", "三年2班家长群", s)!!
        for (title in listOf("三年2班家长群(2条新消息)", "三年2班家长群 (12条新消息)", "三年2班家长群（3条新消息）", "三年2班家长群(99条以上新消息)")) {
            val v = ContactIdentity.of(pkg, "k1", title, s)!!
            assertEquals(title, base.id, v.id)
            assertEquals(title, "三年2班家长群", v.name)
        }
        // The "99+" form for larger counts: the current algorithm recognizes it too
        for (title in listOf("三年2班家长群(99+条新消息)", "三年2班家长群（99+ 条新消息）")) {
            assertEquals(title, id("k1", "三年2班家长群", ContactIdStrategy.Title), id("k7", title, ContactIdStrategy.Title))
            assertEquals(title, "三年2班家长群", ContactIdentity.displayName(title))
        }
        assertEquals(id("k9", "Alice", ContactIdStrategy.Title), id("k1", "Alice(5条新消息)", ContactIdStrategy.Title))
        // Only this trailing form is stripped: brackets and numbers that belong to the name stay untouched
        for (title in listOf("项目群(2)", "(2条新消息)通知", "读书会(第3期)", "3条新消息")) {
            assertNotEquals(title, base.id, ContactIdentity.of(pkg, "k1", title, s)!!.id)
            assertEquals(title, ContactIdentity.displayName(title))
        }
        // Nothing left after stripping: no name, and no title-based identity either
        assertNull(ContactIdentity.displayName("(2条新消息)"))
        assertNull(ContactIdentity.of(pkg, "k1", "(2条新消息)", s))
    }
}
