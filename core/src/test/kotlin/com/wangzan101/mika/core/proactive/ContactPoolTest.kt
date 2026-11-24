package com.wangzan101.mika.core.proactive

import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactPoolTest {
    private val day = LearnTuning.DAY_MS
    private val start = 20_000 * day
    private val utc = ZoneOffset.UTC
    private val s = ContactIdStrategy.Key

    private fun ev(i: Int, key: String, title: String, atMs: Long, pkg: String = "com.chat") =
        LearnEvent(i.toLong(), key, pkg, atMs, title, "text $i")

    private fun cid(key: String, pkg: String = "com.chat") = ContactIdentity.of(pkg, key, "x", s)!!.id

    /** On a given day this conversation sent [n] messages, and the model grouped them into one contact. */
    private fun ContactPool.day(
        d: Int,
        relation: ContactRelation,
        key: String = "alice",
        title: String = "Alice",
        n: Int = 1,
        type: ContactType = ContactType.Person,
        topics: Map<Int, Set<String>> = emptyMap(),
        allowSensitive: Boolean = false,
        pkg: String = "com.chat",
    ): ContactPool.Applied {
        val at = start + d * day + 3_600_000L
        val events = (0 until n).map { ev(it, key, title, at + it, pkg) }
        return apply(events, listOf(ContactSignal(events.indices.toList(), type, relation)), topics, at + n, utc, allowSensitive, s)
    }

    // ---- Identity looks only at the conversation name (the current default): a changed notification key is still the same contact ----

    private val old = ContactIdStrategy.KeyAndTitle
    private fun oldId(key: String, title: String, pkg: String = "com.chat") = ContactIdentity.of(pkg, key, title, old)!!.id
    private fun nowId(title: String, pkg: String = "com.chat") = ContactIdentity.of(pkg, "", title)!!.id

    /** Under the default algorithm, this conversation got one message on a given day (notification key is [key]). */
    private fun ContactPool.seen(d: Int, key: String, title: String, relation: ContactRelation = ContactRelation.Unknown, pkg: String = "com.chat", group: Boolean? = true) {
        val at = start + d * day + 3_600_000L
        val e = LearnEvent(1, key, pkg, at, title, "text", group = group)
        apply(listOf(e), listOf(ContactSignal(listOf(0), ContactType.Group, relation)), emptyMap(), at + 1, utc)
    }

    // Some chat apps increment the notification id: the same group with three different keys over three days is still one contact, and its days still accumulate
    @Test fun theSameConversationUnderChangingNotificationKeysIsOneContact() {
        val p = ContactPool()
        p.seen(0, "0|com.chat|512|null|1", "三年2班家长群(3条新消息)")
        p.seen(1, "0|com.chat|517|null|1", "三年2班家长群(99+条新消息)")
        p.seen(2, "0|com.chat|521|null|1", "三年2班家长群")
        val c = p.all().single()
        assertEquals(nowId("三年2班家长群"), c.id)
        assertEquals(listOf(20_000L, 20_001L, 20_002L), c.days)
        assertTrue(c.established)
        assertEquals("三年2班家长群", c.name)
        // Another group, and a group with the same name in another app: each stays separate
        p.seen(2, "0|com.chat|521|null|1", "三年3班家长群")
        p.seen(2, "0|com.other|521|null|1", "三年2班家长群", pkg = "com.other")
        assertEquals(3, p.all().size)
    }

    // Stored earlier under "key + title": each key change added another record for the same group. Adopting the current identity merges them back into one
    @Test fun storedDuplicatesOfOneConversationAreMergedIntoOne() {
        fun stored(key: String, d: Long, last: Long, name: String = "三年2班家长群", topics: List<String> = emptyList(),
                   relation: ContactRelation? = null, votes: Map<ContactRelation, List<Long>> = emptyMap()) =
            ObservedContact(oldId(key, name), "com.chat", name, firstSeenMs = last - 10, lastSeenMs = last, days = listOf(d),
                typeVotes = mapOf(ContactType.Group to listOf(d)), relationVotes = votes, topics = topics, userRelation = relation)
        val p = ContactPool(ContactPoolData(contacts = listOf(
            stored("k512", 20_000, start + 100, topics = listOf("t1", "t2"), votes = mapOf(ContactRelation.Classmate to listOf(20_000L))),
            stored("k517", 20_001, start + day + 100, topics = listOf("t2", "t3"), relation = ContactRelation.Family),
            stored("k521", 20_002, start + 2 * day + 100, votes = mapOf(ContactRelation.Classmate to listOf(20_002L), ContactRelation.Unknown to listOf(20_002L))),
            stored("k600", 20_002, start + 2 * day, name = "另一个群"),
            ObservedContact(oldId("k1", "张三", "com.mail"), "com.mail", "张三", 1, 2, days = listOf(20_000L)),
        )))
        assertTrue(p.adoptIdentities())
        assertEquals(3, p.all().size)
        val c = p[nowId("三年2班家长群")]!!
        assertEquals(listOf(20_000L, 20_001L, 20_002L), c.days)
        assertEquals(mapOf(ContactType.Group to listOf(20_000L, 20_001L, 20_002L)), c.typeVotes)
        assertEquals(listOf(20_000L, 20_002L), c.relationVotes[ContactRelation.Classmate])
        assertEquals(listOf(20_002L), c.relationVotes[ContactRelation.Unknown])
        assertEquals(start + 90, c.firstSeenMs)
        assertEquals(start + 2 * day + 100, c.lastSeenMs)
        // A user-set relation is not lost (the most recent record has none, so the one that does is used)
        assertEquals(ContactRelation.Family, c.userRelation)
        assertEquals(listOf("t1", "t2", "t3"), c.topics)
        assertTrue(c.established)
        assertEquals("com.mail", p[nowId("张三", "com.mail")]!!.packageName)
        // New notifications land on the merged record
        p.seen(3, "0|com.chat|530|null|1", "三年2班家长群(2条新消息)")
        assertEquals(4, p[nowId("三年2班家长群")]!!.days.size)
        assertEquals(3, p.all().size)
        // All already carry the current identity: nothing moves
        assertFalse(p.adoptIdentities())
        // Both have a user-set relation: use the one seen most recently
        val q = ContactPool(ContactPoolData(contacts = listOf(
            stored("a", 20_000, start, relation = ContactRelation.Friend), stored("b", 20_001, start + day, relation = ContactRelation.Colleague))))
        assertTrue(q.adoptIdentities())
        assertEquals(ContactRelation.Colleague, q.all().single().userRelation)
    }

    // The "do not record" list holds ids from the old algorithm: changing the algorithm must not bring deleted contacts back
    @Test fun theBlockListSurvivesTheChangeOfIdentity() {
        val key = "0|com.chat|7|null|1"
        // Deleted earlier (the contact is gone, only the old id remains in the list): the same key and name coming back is recognized, and the list swaps in the current id
        val p = ContactPool(ContactPoolData(blocked = listOf(oldId(key, "张三"))))
        val first = p.apply(listOf(LearnEvent(1, key, "com.chat", start, "张三", "text", group = false)),
            listOf(ContactSignal(listOf(0), ContactType.Person, ContactRelation.Unknown)), emptyMap(), start + 1, utc)
        assertTrue(p.all().isEmpty())
        // The list changed: counts as a change (callers persist off it). The stored list holds the current id with the old one swapped out (a deletion occupies only one slot)
        assertTrue(first.changed)
        assertEquals(listOf(nowId("张三")), p.data().blocked)
        // Coming again has nothing new to record: not a change
        assertFalse(p.apply(listOf(LearnEvent(2, key, "com.chat", start + 5, "张三", "text", group = false)),
            listOf(ContactSignal(listOf(0), ContactType.Person, ContactRelation.Unknown)), emptyMap(), start + 6, utc).changed)
        // A later key change still doesn't bring it back
        p.seen(1, "0|com.chat|8|null|1", "张三(2条新消息)", group = false)
        assertTrue(p.all().isEmpty())
        // Catch up on a deletion that was never persisted (the record with the old id): other same-named records must not come back through the merge
        val q = ContactPool(ContactPoolData(contacts = listOf(
            ObservedContact(oldId("k1", "家长群"), "com.chat", "家长群", 1, 2, days = listOf(20_000L)),
            ObservedContact(oldId("k2", "家长群"), "com.chat", "家长群", 3, 4, days = listOf(20_001L)),
            ObservedContact(oldId("k3", "别的群"), "com.chat", "别的群", 3, 4, days = listOf(20_001L)),
        )))
        assertTrue(q.delete(oldId("k1", "家长群")))
        assertTrue(q.adoptIdentities())
        assertEquals(listOf("别的群"), q.all().map { it.name })
        q.seen(2, "0|com.chat|9|null|1", "家长群")
        assertEquals(listOf("别的群"), q.all().map { it.name })
        // Only after allowing again is it recorded again
        assertTrue(q.allowAgain())
        q.seen(3, "0|com.chat|9|null|1", "家长群")
        assertEquals(2, q.all().size)
    }

    // The same group used to be stored several times; the user deleted the duplicates and kept one. The kept one switched to the current identity; later one of the deleted notification keys shows up again:
    // it must not put the kept contact on the "do not record" list — that would leave it on the page yet frozen forever
    @Test fun aKeptContactIsNotBlockedByADeletedDuplicateOfIt() {
        val p = ContactPool(ContactPoolData(
            contacts = listOf(ObservedContact(oldId("k1", "家长群"), "com.chat", "家长群", 1, 2, days = listOf(19_999L))),
            blocked = listOf(oldId("k2", "家长群"), oldId("k3", "家长群")),
        ))
        assertTrue(p.adoptIdentities())
        p.seen(0, "k2", "家长群")
        val c = p.all().single()
        assertEquals(nowId("家长群"), c.id)
        assertEquals(listOf(19_999L, 20_000L), c.days)
        assertEquals(2, p.blockedCount)
        // The user deletes it now: remembered by conversation name, so no later key change records it again
        assertTrue(p.delete(c.id))
        p.seen(1, "k9", "家长群(3条新消息)")
        assertTrue(p.all().isEmpty())
    }

    // Catch up on a remembered deletion: even when the contact is gone (the file read predates the identity switch) it must still go on the "do not record" list, so that same-named ones don't come back when the identity switch runs
    @Test fun aRememberedDeleteBlocksTheIdEvenWhenTheContactIsNotThere() {
        val p = ContactPool(ContactPoolData(contacts = listOf(
            ObservedContact(oldId("k1", "家长群"), "com.chat", "家长群", 1, 2, days = listOf(20_000L)),
            ObservedContact(oldId("k2", "家长群"), "com.chat", "家长群", 3, 4, days = listOf(20_001L)),
            ObservedContact(oldId("k3", "别的群"), "com.chat", "别的群", 3, 4, days = listOf(20_001L)),
        )))
        assertTrue(p.forget(nowId("家长群")))
        // One already recorded is not a change
        assertFalse(p.forget(nowId("家长群")))
        assertEquals(3, p.all().size)
        assertTrue(p.adoptIdentities())
        assertEquals(listOf("别的群"), p.all().map { it.name })
        // Ones still present are deleted as usual
        assertTrue(p.forget(nowId("别的群")))
        assertTrue(p.all().isEmpty())
    }

    // The "do not record" list is full (all old ids). Swapping in the current id is a replacement, not an addition: otherwise each swap would push out another deletion that hasn't been converted yet,
    // the user never deleted anything extra, yet deleted contacts would come back. The number of conversations shown on the page stays the same too
    @Test fun carryingABlockedIdentityOverDoesNotPushOutAnotherDeletedContact() {
        val cap = LearnTuning.CONTACT_BLOCKED_CAP
        val p = ContactPool(ContactPoolData(blocked = (0 until cap).map { oldId("key-$it", "联系人$it") }))
        p.seen(0, "key-${cap - 1}", "联系人${cap - 1}", group = false)
        assertTrue(p.all().isEmpty())
        assertEquals(cap, p.blockedCount)
        p.seen(0, "key-0", "联系人0", group = false)
        assertTrue(p.all().isEmpty())
        assertEquals(cap, p.blockedCount)
        assertTrue(nowId("联系人0") in p.data().blocked)
        assertFalse(oldId("key-0", "联系人0") in p.data().blocked)
    }

    // When several old records merge into one and the linked topics exceed the cap, keep the most recent ones: a topic the newer record also links must not be pushed out because it ranked early in the older one
    @Test fun mergingStoredDuplicatesKeepsTheTopicsTheNewerOneStillLinks() {
        val cap = LearnTuning.CONTACT_TOPICS
        val older = ObservedContact(oldId("k1", "工作群"), "com.chat", "工作群", 1, 100, days = listOf(1), topics = (1..cap).map { "topic$it" })
        val newer = ObservedContact(oldId("k2", "工作群"), "com.chat", "工作群", 101, 200, days = listOf(2), topics = listOf("topic1", "topic${cap + 1}"))
        val p = ContactPool(ContactPoolData(contacts = listOf(older, newer)))
        assertTrue(p.adoptIdentities())
        assertEquals((3..cap).map { "topic$it" } + listOf("topic1", "topic${cap + 1}"), p.all().single().topics)
    }

    // Old entries with an empty name that yield no current identity: left as they are (until they age out), neither dropped nor merged arbitrarily
    @Test fun storedEntriesWithoutAUsableNameAreLeftAsTheyAre() {
        val odd = ObservedContact("b0123456789abcdef", "com.chat", " ", 1, 2, days = listOf(20_000L))
        val p = ContactPool(ContactPoolData(contacts = listOf(odd, ObservedContact(oldId("k", "张三"), "com.chat", "张三", 1, 2))))
        assertTrue(p.adoptIdentities())
        assertEquals(setOf("b0123456789abcdef", nowId("张三")), p.all().map { it.id }.toSet())
    }

    @Test fun oneVotePerDayAndManyMessagesAreOneVote() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend, n = 10)
        p.day(0, ContactRelation.Friend, n = 3)
        val c = p[cid("alice")]!!
        assertEquals(listOf(20_000L), c.days)
        assertEquals(mapOf(ContactRelation.Friend to listOf(20_000L)), c.relationVotes)
        assertEquals(mapOf(ContactType.Person to listOf(20_000L)), c.typeVotes)
        assertEquals("Alice", c.name)
        // Votes from a single day: not shown yet
        assertEquals(ContactRelation.Unknown, c.relation)
    }

    @Test fun shownRelationNeedsTwoDaysAndAMajority() {
        val p = ContactPool()
        p.day(0, ContactRelation.Family)
        p.day(1, ContactRelation.Family)
        assertEquals(ContactRelation.Family, p[cid("alice")]!!.relation)
        // "Unknown" counts toward the total too: 2 against 2 is not a majority
        p.day(2, ContactRelation.Unknown)
        p.day(3, ContactRelation.Colleague)
        assertEquals(ContactRelation.Unknown, p[cid("alice")]!!.relation)
        p.day(4, ContactRelation.Family)
        assertEquals(ContactRelation.Family, p[cid("alice")]!!.relation)
    }

    @Test fun establishedAtThreeDays() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend)
        p.day(1, ContactRelation.Friend)
        assertFalse(p[cid("alice")]!!.established)
        p.day(5, ContactRelation.Friend)
        assertTrue(p[cid("alice")]!!.established)
        assertEquals(3, p[cid("alice")]!!.days.size)
    }

    @Test fun userRelationWinsAndSurvivesVotesAndPersistence() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend)
        assertTrue(p.setRelation(cid("alice"), ContactRelation.Classmate))
        repeat(5) { p.day(it + 1, ContactRelation.Colleague) }
        assertEquals(ContactRelation.Classmate, p[cid("alice")]!!.relation)
        assertTrue(p[cid("alice")]!!.userSet)
        val json = Json.encodeToString(ContactPoolData.serializer(), p.data())
        val back = ContactPool(Json.decodeFromString(ContactPoolData.serializer(), json))
        assertEquals(p.data(), back.data())
        assertEquals(ContactRelation.Classmate, back[cid("alice")]!!.relation)
        // Hand back to the vote-based guess
        assertTrue(back.setRelation(cid("alice"), null))
        assertEquals(ContactRelation.Colleague, back[cid("alice")]!!.relation)
    }

    @Test fun typeIsTheTopVote() {
        val p = ContactPool()
        p.day(0, ContactRelation.Family, type = ContactType.Group)
        p.day(1, ContactRelation.Family, type = ContactType.Group)
        p.day(2, ContactRelation.Family, type = ContactType.Person)
        assertEquals(ContactType.Group, p[cid("alice")]!!.type)
    }

    @Test fun sameKeyNewTitleKeepsOneContactAndUpdatesTheName() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend, title = "Alice")
        p.day(1, ContactRelation.Friend, title = "Alice (work)")
        p.day(2, ContactRelation.Friend, title = " ")
        assertEquals(1, p.all().size)
        assertEquals("Alice (work)", p.all().single().name)
        // Different key, same title: a different contact
        p.day(2, ContactRelation.Friend, key = "stranger", title = "Alice (work)")
        assertEquals(2, p.all().size)
    }

    @Test fun firstSightingWithoutAUsableNameIsNotRecorded() {
        val p = ContactPool()
        val r = p.day(0, ContactRelation.Friend, title = "")
        assertTrue(p.all().isEmpty())
        assertEquals(mapOf(ContactDrop.NoIdentity to 1), r.dropped)
        assertEquals(0, r.accepted)
    }

    // A sender shown only as a phone number never becomes a contact
    @Test fun aSenderShownOnlyAsAPhoneNumberIsNotRecorded() {
        val p = ContactPool()
        val r = p.day(0, ContactRelation.Friend, title = "13812345678")
        assertTrue(p.all().isEmpty())
        assertEquals(mapOf(ContactDrop.NoIdentity to 1), r.dropped)
        // For a contact already recorded, a batch whose title is a number keeps the old name
        p.day(0, ContactRelation.Friend, title = "Alice")
        p.day(1, ContactRelation.Friend, title = "13812345678")
        assertEquals("Alice", p.all().single().name)
    }

    @Test fun oneEntryOverSeveralIdentitiesVotesForEach() {
        val p = ContactPool()
        val at = start + 3_600_000L
        val events = listOf(ev(0, "a", "A", at), ev(1, "b", "B", at), ev(2, "a", "A", at))
        val r = p.apply(events, listOf(ContactSignal(listOf(0, 1, 2), ContactType.Person, ContactRelation.Friend)), emptyMap(), at, utc, false, s)
        assertEquals(1, r.accepted)
        assertEquals(setOf("A", "B"), p.all().map { it.name }.toSet())
        p.all().forEach { assertEquals(listOf(20_000L), it.relationVotes.getValue(ContactRelation.Friend)) }
    }

    @Test fun partnerNeedsTheSensitiveSwitch() {
        val p = ContactPool()
        p.day(0, ContactRelation.Partner)
        assertEquals(setOf(ContactRelation.Unknown), p.all().single().relationVotes.keys)
        assertFalse(p.all().single().sensitive)
        p.day(1, ContactRelation.Partner, allowSensitive = true)
        assertTrue(p.all().single().sensitive)
        assertTrue(p.deleteSensitive())
        assertFalse(p.all().single().sensitive)
        assertEquals(setOf(ContactRelation.Unknown), p.all().single().relationVotes.keys)
    }

    @Test fun deletionBlocksRecreationButClearsDoNot() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend)
        assertTrue(p.delete(cid("alice")))
        val r = p.day(1, ContactRelation.Friend)
        assertTrue(p.all().isEmpty())
        assertEquals(mapOf(ContactDrop.Blocked to 1), r.dropped)
        assertEquals(1, p.blockedCount)
        // The "do not record" list holds ids only
        assertFalse(p.data().blocked.single().contains("alice"))
        assertTrue(p.allowAgain())
        p.day(2, ContactRelation.Friend)
        assertEquals(1, p.all().size)

        // Clearing one app's entries or clearing everything is a fresh start, it doesn't block
        p.day(2, ContactRelation.Friend, key = "bob", title = "Bob", pkg = "com.other")
        assertTrue(p.clearApp("com.chat"))
        assertEquals(listOf("Bob"), p.all().map { it.name })
        p.day(3, ContactRelation.Friend)
        assertEquals(2, p.all().size)
        assertTrue(p.clear())
        p.day(4, ContactRelation.Friend)
        assertEquals(1, p.all().size)
        assertEquals(0, p.blockedCount)
    }

    // A topic the user merged into another: links to it move to that one, keeping its position; when both are linked only one is kept
    @Test fun aMergedTopicsLinksMoveToTheTopicItWentInto() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend, topics = mapOf(0 to setOf("k1")))
        p.day(1, ContactRelation.Friend, topics = mapOf(0 to setOf("k2")))
        p.day(2, ContactRelation.Friend, topics = mapOf(0 to setOf("k3")))
        p.day(2, ContactRelation.Friend, key = "bob", title = "Bob", topics = mapOf(0 to setOf("k3")))
        fun topics(name: String) = p.all().single { it.name == name }.topics
        assertEquals(listOf("k1", "k2", "k3"), topics("Alice"))
        assertTrue(p.remapTopic("k1", "k9"))
        assertEquals(listOf("k9", "k2", "k3"), topics("Alice"))
        assertEquals(listOf("k3"), topics("Bob"))
        assertTrue(p.remapTopic("k2", "k3"))
        assertEquals(listOf("k9", "k3"), topics("Alice"))
        // Nothing links to it: no change
        assertFalse(p.remapTopic("k1", "k3"))
    }

    // A group chat without a conversation name: the notification title is the speaker, not the conversation. No contact is recorded — otherwise every member would become a record and it would merge with a same-named direct chat
    @Test fun aGroupWhoseTitleIsOnlyTheSpeakerIsNotRecorded() {
        val p = ContactPool()
        val at = start + 3_600_000L
        val events = listOf(
            LearnEvent(1, "g", "com.chat", at, "张三", "明天见", group = true, titleIsSpeaker = true),
            LearnEvent(2, "p", "com.chat", at, "张三", "在吗", group = false),
        )
        val r = p.apply(events, listOf(ContactSignal(listOf(0), ContactType.Group, ContactRelation.Friend),
            ContactSignal(listOf(1), ContactType.Person, ContactRelation.Friend)), mapOf(0 to setOf("k1"), 1 to setOf("k2")), at + 1, utc)
        assertEquals(1, r.accepted)
        assertEquals(1, r.dropped[ContactDrop.NoIdentity])
        val c = p.all().single()
        assertEquals(ContactType.Person, c.type)
        // The group's topic isn't linked to this person from the direct chat either
        assertEquals(listOf("k2"), c.topics)
    }

    @Test fun topicLinksAreMechanicalBoundedAndPruned() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend, topics = mapOf(0 to setOf("k1", "k2")))
        assertEquals(listOf("k1", "k2"), p.all().single().topics.sorted())
        // No contact grouping, but the notification belongs to an existing contact: the topic is still linked
        val at = start + day
        p.apply(listOf(ev(0, "alice", "Alice", at), ev(1, "shop", "Promo", at)), emptyList(), mapOf(0 to setOf("k3"), 1 to setOf("k4")), at, utc, false, s)
        assertEquals(listOf("k1", "k2", "k3"), p.all().single().topics.sorted())
        assertEquals(1, p.all().size)
        (10 until 30).forEach { p.day(1, ContactRelation.Friend, topics = mapOf(0 to setOf("k$it"))) }
        assertEquals(LearnTuning.CONTACT_TOPICS, p.all().single().topics.size)
        assertEquals("k29", p.all().single().topics.last())
        assertTrue(p.forgetTopics(setOf("k29")))
        assertFalse("k29" in p.all().single().topics)
        assertTrue(p.maintain(at, liveTopics = setOf("k28")))
        assertEquals(listOf("k28"), p.all().single().topics)
    }

    @Test fun forgettingSparesUserSetAndCapApplies() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend)
        p.day(0, ContactRelation.Friend, key = "bob", title = "Bob")
        p.setRelation(cid("bob"), ContactRelation.Family)
        // Gone for a long time: guesses are forgotten, user-set ones stay
        p.maintain(start + 400 * day, emptySet())
        assertEquals(listOf("Bob"), p.all().map { it.name })

        val q = ContactPool()
        repeat(LearnTuning.CONTACT_CAP + 5) { q.day(0, ContactRelation.Friend, key = "c$it", title = "C$it") }
        q.day(1, ContactRelation.Friend, key = "c0", title = "C0")
        q.maintain(start + day, emptySet())
        assertEquals(LearnTuning.CONTACT_CAP, q.all().size)
        // The stronger one (two days) is kept
        assertTrue(q[cid("c0")] != null)
        assertNull(ContactPool()[cid("c0")])
    }

    // One reply records at most one day per contact and at most one vote per relation: a batch crossing midnight, or several groupings landing on the same person, must not let a relation show up from a single judgment
    @Test fun oneReplyCountsAsOneDayPerContact() {
        val at = start + 3_600_000L
        val events = listOf(ev(0, "alice", "Alice", at), ev(1, "alice", "Alice", at + day))
        fun check(signals: List<ContactSignal>) {
            val p = ContactPool()
            p.apply(events, signals, emptyMap(), at + day, utc, false, s)
            val c = p[cid("alice")]!!
            // Recorded on the day of the newest notification it references
            assertEquals(listOf(20_001L), c.days)
            assertEquals(mapOf(ContactRelation.Family to listOf(20_001L)), c.relationVotes)
            assertEquals(mapOf(ContactType.Person to listOf(20_001L)), c.typeVotes)
            assertEquals(ContactRelation.Unknown, c.relation)
            assertEquals(at, c.firstSeenMs)
            assertEquals(at + day, c.lastSeenMs)
        }
        check(listOf(ContactSignal(listOf(0, 1), ContactType.Person, ContactRelation.Family)))
        // Split into two signals each referencing one day: the same
        check(listOf(
            ContactSignal(listOf(0), ContactType.Person, ContactRelation.Family),
            ContactSignal(listOf(1), ContactType.Person, ContactRelation.Family),
        ))
    }

    // One reply states two relations for the same person: one vote each, both on the same day
    @Test fun twoRelationsInOneReplyAreOneVoteEach() {
        val at = start + 3_600_000L
        val events = listOf(ev(0, "alice", "Alice", at), ev(1, "alice", "Alice", at + day))
        val p = ContactPool()
        val r = p.apply(events, listOf(
            ContactSignal(listOf(0), ContactType.Person, ContactRelation.Friend),
            ContactSignal(listOf(1), ContactType.Group, ContactRelation.Colleague),
        ), emptyMap(), at + day, utc, false, s)
        assertEquals(2, r.accepted)
        val c = p[cid("alice")]!!
        assertEquals(mapOf(ContactRelation.Friend to listOf(20_001L), ContactRelation.Colleague to listOf(20_001L)), c.relationVotes)
        assertEquals(mapOf(ContactType.Person to listOf(20_001L), ContactType.Group to listOf(20_001L)), c.typeVotes)
        assertEquals(listOf(20_001L), c.days)
    }

    // A vote's day is the day the notification was sent, not the day of the judgment
    @Test fun judgingOnAnotherDayAddsNoDayOrVote() {
        val at = start + 3_600_000L
        val p = ContactPool()
        val one = listOf(ContactSignal(listOf(0), ContactType.Person, ContactRelation.Family))
        p.apply(listOf(ev(0, "alice", "Alice", at)), one, emptyMap(), at, utc, false, s)
        p.apply(listOf(ev(0, "alice", "Alice", at + 60_000L)), one, emptyMap(), at + day, utc, false, s)
        val c = p[cid("alice")]!!
        assertEquals(listOf(20_000L), c.days)
        assertEquals(ContactRelation.Unknown, c.relation)
    }

    // When the notification's shape makes clear whether it's a group or a direct chat, the type follows it and the model is ignored; only when unclear (other apps) does the model's type apply
    @Test fun typeFollowsTheNotificationShapeWhenItIsKnown() {
        val at = start + 3_600_000L
        fun type(group: Boolean?, model: ContactType): ContactType {
            val p = ContactPool()
            val events = listOf(ev(0, "g", "家长群", at).copy(group = group), ev(1, "g", "家长群", at + 1).copy(group = group))
            p.apply(events, listOf(ContactSignal(listOf(0, 1), model, ContactRelation.Unknown)), emptyMap(), at + 2, utc, false, s)
            return p.all().single().type
        }
        assertEquals(ContactType.Group, type(true, ContactType.Person))
        assertEquals(ContactType.Person, type(false, ContactType.Group))
        assertEquals(ContactType.Group, type(null, ContactType.Group))
        // The referenced notifications disagree: use the model's
        val p = ContactPool()
        val mixed = listOf(ev(0, "g", "家长群", at).copy(group = true), ev(1, "g", "家长群", at + 1).copy(group = false))
        p.apply(mixed, listOf(ContactSignal(listOf(0, 1), ContactType.Person, ContactRelation.Unknown)), emptyMap(), at + 2, utc, false, s)
        assertEquals(ContactType.Person, p.all().single().type)
    }
}
