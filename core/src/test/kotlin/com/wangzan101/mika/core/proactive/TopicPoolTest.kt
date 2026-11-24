package com.wangzan101.mika.core.proactive

import java.time.ZoneOffset
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicPoolTest {
    private val zone = ZoneOffset.UTC
    private val day = LearnTuning.DAY_MS
    private val t0 = 20_000 * day + 3_600_000L

    private fun ev(i: Int, at: Long = t0, pkg: String = "com.shop", outcome: LearnOutcome = LearnOutcome.None) =
        LearnEvent(i.toLong(), "k$i", pkg, at, "", "", outcome)

    private fun new(vararg idx: Int, kind: TopicKind = TopicKind.Shopping, label: String = "running shoes") =
        LearnSignal(idx.toList(), newKind = kind, newLabel = label)

    @Test fun evidenceIsPerAppAndDayNotPerEvent() {
        val p = TopicPool()
        // ten notifications from the same app on the same day count as one
        val events = (0 until 10).map { ev(it, at = t0 + it * 60_000L) }
        val r = p.apply("com.shop", events, listOf(new(*(0 until 10).toList().toIntArray())), t0 + day / 2, zone)
        val t = p.all().single()
        assertEquals(1, t.evidenceCount)
        assertEquals(mapOf("com.shop" to listOf(20_000L)), t.evidence)
        assertEquals(1, r.applied)
        assertEquals((0 until 10).toSet(), r.byEvent.keys)
        assertFalse(t.established)
        // once more the next day: two pieces of evidence, so it is established
        p.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(LearnSignal(listOf(0), topicId = t.id)), t0 + day, zone)
        val t2 = p[t.id]!!
        assertEquals(2, t2.evidenceCount)
        assertEquals(2, t2.days)
        assertTrue(t2.established)
        assertEquals(t0 + day, t2.lastSeenMs)
        assertEquals(t0 + day / 2, t2.firstSeenMs)
    }

    @Test fun secondAppSameDayIsMoreEvidenceButNotEstablished() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        p.apply("com.mall", listOf(ev(0, pkg = "com.mall")), listOf(new(0)), t0, zone)
        val t = p.all().single()
        assertEquals(2, t.apps)
        assertEquals(2, t.evidenceCount)
        assertEquals(1, t.days)
        assertFalse(t.established)
    }

    // which app evidence is attributed to depends only on the package name from the caller: nothing written in the reply can affect it (LearnSignal has no app field at all)
    @Test fun replyCannotAttributeEvidenceToAnotherApp() {
        val p = TopicPool()
        val parsed = NoticeLearn.parse(
            """{"signals":[{"ids":["n1"],"app":"com.bank","package":"com.bank","new":{"kind":"interest","label":"Hiking","app":"com.bank"}}]}""",
            1, emptyList(),
        )!!
        // the package name written on the events does not count either: only the batch's package name is used
        p.apply("com.shop", listOf(ev(0, pkg = "com.bank")), parsed.signals, t0, zone)
        assertEquals(setOf("com.shop"), p.all().single().evidence.keys)
    }

    @Test fun sameKindAndNormalizedLabelIsAReference() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "Running Shoes")), t0, zone)
        p.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(new(0, label = "ｒｕｎｎｉｎｇ　shoes")), t0 + day, zone)
        p.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(new(0, label = "runningshoes")), t0 + day, zone)
        assertEquals(1, p.all().size)
        assertEquals("Running Shoes", p.all().single().label)
        // a different kind means a different topic
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, kind = TopicKind.Interest, label = "running shoes")), t0, zone)
        assertEquals(2, p.all().size)
        // two same-named new topics in one reply create only one
        val q = TopicPool()
        q.apply("com.shop", listOf(ev(0), ev(1)), listOf(new(0, label = "Tea"), new(1, label = "tea")), t0, zone)
        assertEquals(1, q.all().size)
    }

    @Test fun outcomesAreCountedOncePerEventAndTopic() {
        val p = TopicPool()
        val events = listOf(ev(0, outcome = LearnOutcome.Opened), ev(1, outcome = LearnOutcome.Dismissed), ev(2))
        // two entries in the same reply land on the same topic: the same notification is not counted twice
        p.apply("com.shop", events, listOf(new(0, 1, 2), new(0, 1)), t0, zone)
        val id = p.all().single().id
        assertEquals(1, p[id]!!.opened)
        assertEquals(1, p[id]!!.dismissed)
        assertTrue(p.addOutcome(setOf(id, "gone"), LearnOutcome.Opened))
        assertEquals(2, p[id]!!.opened)
        assertFalse(p.addOutcome(setOf("gone"), LearnOutcome.Dismissed))
    }

    @Test fun deletedTopicIsNotResurrected() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        val id = p.all().single().id
        assertTrue(p.delete(id))
        val r = p.apply("com.shop", listOf(ev(0)), listOf(LearnSignal(listOf(0), topicId = id)), t0, zone)
        assertTrue(p.all().isEmpty())
        assertEquals(1, r.missing)
        assertTrue(r.byEvent.isEmpty())
    }

    @Test fun strengthAndRetention() {
        val t = ObservedTopic("a", TopicKind.Interest, "x", t0, t0, mapOf("p" to listOf(1L, 2L), "q" to listOf(2L)), opened = 2, dismissed = 4)
        assertEquals(3 + 2 * LearnTuning.OPENED_BONUS - 4 * LearnTuning.DISMISSED_PENALTY, t.strength, 1e-9)
        assertEquals(1.0, t.retention(t0), 1e-9)
        val s = LearnTuning.STABILITY_DAYS * (1 + t.strength)
        assertEquals(exp(-10 / s), t.retention(t0 + 10 * day), 1e-9)
        // many dismissals still do not take it below 0
        assertEquals(0.0, t.copy(dismissed = 100).strength, 0.0)
        // strong ones are forgotten more slowly
        val weak = t.copy(evidence = mapOf("p" to listOf(1L)), opened = 0, dismissed = 0)
        assertTrue(weak.retention(t0 + 20 * day) < t.retention(t0 + 20 * day))
    }

    @Test fun maintenanceForgetsFadedTopics() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "old")), t0, zone)
        repeat(5) { d -> p.apply("com.shop", listOf(ev(0, at = t0 + d * day)), listOf(new(0, label = "strong")), t0 + d * day, zone) }
        assertEquals(5, p.all().first { it.label == "strong" }.days)
        // one piece of evidence: S = 14 days, and retention below 0.1 needs ln(10)×14 ≈ 32 days
        assertTrue(p.maintain(t0 + 30 * day).isEmpty())
        val dropped = p.maintain(t0 + 33 * day)
        assertEquals(1, dropped.size)
        assertEquals(listOf("strong"), p.all().map { it.label })
    }

    @Test fun capEvictsLowestScoreFirst() {
        val p = TopicPool()
        repeat(LearnTuning.POOL_CAP) { i -> p.apply("com.shop", listOf(ev(0, at = t0 + i)), listOf(new(0, label = "t$i")), t0 + i, zone) }
        // make t5 strong
        val strong = p.all().first { it.label == "t5" }.id
        p.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(LearnSignal(listOf(0), topicId = strong)), t0 + day, zone)
        p.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(new(0, label = "newcomer")), t0 + day, zone)
        assertEquals(LearnTuning.POOL_CAP, p.all().size)
        assertNull(p.all().firstOrNull { it.label == "t0" }) // the oldest and weakest is evicted
        assertTrue(p.all().any { it.label == "t5" })
        assertTrue(p.all().any { it.label == "newcomer" })
    }

    @Test fun daysPerAppAreCapped() {
        var ev: Map<String, List<Long>> = emptyMap()
        repeat(80) { ev = TopicPool.addDays(ev, "p", listOf(it.toLong())) }
        assertEquals((20L until 80L).toList(), ev.getValue("p"))
        repeat(LearnTuning.APPS_PER_TOPIC + 3) { ev = TopicPool.addDays(ev, "a$it", listOf(100L + it)) }
        assertEquals(LearnTuning.APPS_PER_TOPIC, ev.size)
        assertFalse("p" in ev) // the app that has not shown up for longest goes first
    }

    @Test fun clearAppRemovesItsEvidenceAndOrphans() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "only shop")), t0, zone)
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "both")), t0, zone)
        p.apply("com.mall", listOf(ev(0, pkg = "com.mall")), listOf(new(0, label = "both")), t0, zone)
        val dropped = p.clearApp("com.shop")
        assertEquals(1, dropped.size)
        assertEquals(listOf("both"), p.all().map { it.label })
        assertEquals(setOf("com.mall"), p.all().single().evidence.keys)
    }

    @Test fun sensitiveTopicsFollowTheSwitch() {
        val p = TopicPool()
        val flagged = LearnSignal(listOf(0), newKind = TopicKind.Interest, newLabel = "diabetes", sensitive = true)
        // switch off: a new topic flagged sensitive is not accepted
        assertEquals(1, p.apply("com.health", listOf(ev(0)), listOf(flagged), t0, zone).missing)
        assertTrue(p.all().isEmpty())
        // switch on: it is accepted and carries the flag
        p.apply("com.health", listOf(ev(0)), listOf(flagged), t0, zone, allowSensitive = true)
        val t = p.all().single()
        assertTrue(t.sensitive)
        // with the switch off, neither referencing it nor creating a same-named new topic adds evidence
        val r = p.apply("com.health", listOf(ev(0, at = t0 + day)),
            listOf(LearnSignal(listOf(0), topicId = t.id), new(0, kind = TopicKind.Interest, label = "Diabetes")), t0 + day, zone)
        assertEquals(2, r.missing)
        assertEquals(1, p[t.id]!!.evidenceCount)
        // with the switch off, an open/dismiss that arrives later is not credited to a sensitive topic either
        assertFalse(p.addOutcome(setOf(t.id), LearnOutcome.Opened))
        assertEquals(0, p[t.id]!!.opened)
        assertTrue(p.addOutcome(setOf(t.id), LearnOutcome.Opened, allowSensitive = true))
        assertEquals(1, p[t.id]!!.opened)
        // with the switch off it is not shown to the model either
        assertTrue(p.strongest(t0).isEmpty())
        assertEquals(1, p.strongest(t0, includeSensitive = true).size)
        p.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        assertEquals(setOf(t.id), p.deleteSensitive())
        assertEquals(listOf("running shoes"), p.all().map { it.label })
    }

    // only `limit` topics are shown to the model, and the ones this batch's app has corroborated itself come first (even if weak); otherwise, when it suggests the same thing again, the model cannot see the existing topic and can only start a new one
    @Test fun theBatchAppsOwnTopicsAreShownFirst() {
        val p = TopicPool()
        // five topics from other apps, each with two days of evidence (strong)
        for (i in 0 until 5) {
            p.apply("com.news", listOf(ev(0, pkg = "com.news")), listOf(new(0, label = "news $i")), t0, zone)
            p.apply("com.news", listOf(ev(0, at = t0 + day, pkg = "com.news")), listOf(new(0, label = "news $i")), t0 + day, zone)
        }
        // this app's two topics, each with only one day (weak)
        p.apply("com.shop", listOf(ev(0), ev(1)), listOf(new(0, label = "running shoes"), new(1, label = "tents")), t0, zone)
        val now = t0 + day
        // without naming an app: strength only, so all three slots are other apps' topics
        assertTrue(p.strongest(now, limit = 3).all { it.label.startsWith("news") })
        // when named: both of its own are there, up front, and the remaining slots are filled by strength
        val shown = p.strongest(now, limit = 3, packageName = "com.shop")
        assertEquals(setOf("running shoes", "tents"), shown.take(2).map { it.label }.toSet())
        assertTrue(shown[2].label.startsWith("news"))
        // when there are enough slots, nothing is left out
        assertEquals(7, p.strongest(now, packageName = "com.shop").size)
        // an app that has never suggested anything: same as naming no app
        assertEquals(p.strongest(now, limit = 3), p.strongest(now, limit = 3, packageName = "com.other"))
        // an app with more of its own topics than slots: it takes only two thirds (its own strongest ones), the rest go to the strongest of the others, so the other apps are not all pushed out
        // ("tents" gains two more days and becomes the strongest)
        p.apply("com.shop", listOf(ev(0, at = t0 - day)), listOf(new(0, label = "tents")), now, zone)
        p.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(new(0, label = "tents")), now, zone)
        val news = p.strongest(now, limit = 3, packageName = "com.news")
        assertEquals(listOf(true, true, false), news.map { it.label.startsWith("news") })
        assertEquals("tents", news[2].label)
        // six slots: its own take four, the fifth goes to somebody else's strongest, and the last falls back by strength to the one of its own that is left
        val six = p.strongest(now, limit = 6, packageName = "com.news")
        assertEquals(listOf(true, true, true, true, false, true), six.map { it.label.startsWith("news") })
        assertEquals("tents", six[4].label)
    }

    @Test fun dataRoundTrip() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0, outcome = LearnOutcome.Opened)), listOf(new(0)), t0, zone)
        val back = TopicPool(p.data())
        assertEquals(p.all(), back.all())
        back.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "other")), t0, zone)
        assertEquals(2, back.all().map { it.id }.toSet().size)
    }

    // topic ids are not reused: if a newly created topic never got saved (the process died, the save failed), a topic created after the old data is read back must not get the same id —
    // other places (topics linked from contacts) may still remember that id and would end up linked to an unrelated topic
    @Test fun idsAreNotReusedAfterALostSave() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "kept")), t0, zone)
        val saved = p.data()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "lost")), t0, zone)
        val lost = p.all().first { it.label == "lost" }.id
        val back = TopicPool(saved)
        back.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "later")), t0, zone)
        assertTrue(lost !in back.all().map { it.id })
        // the same holds when the whole pool is gone and it starts over from empty
        val fresh = TopicPool()
        fresh.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "again")), t0, zone)
        assertTrue(fresh.all().single().id !in p.all().map { it.id })
    }

    // if the offered id collides with an existing one, another is requested
    @Test fun aCollidingNewIdIsNotUsed() {
        val ids = ArrayDeque(listOf("a", "a", "a", "b"))
        val p = TopicPool(newId = { ids.removeFirst() })
        p.apply("com.shop", listOf(ev(0), ev(1)), listOf(new(0, label = "one"), new(1, label = "two")), t0, zone)
        assertEquals(mapOf("one" to "a", "two" to "b"), p.all().associate { it.label to it.id })
    }

    // one reply records at most one day per topic: neither a batch crossing midnight nor several entries pointing at the same topic can add up to "established" from a single judgement
    @Test fun oneReplyCountsAsOneDayPerTopic() {
        val events = listOf(ev(0, at = t0), ev(1, at = t0 + day))
        val p = TopicPool()
        p.apply("com.shop", events, listOf(new(0, 1)), t0 + day, zone)
        // recorded against the day of the newest notification referenced
        assertEquals(mapOf("com.shop" to listOf(20_001L)), p.all().single().evidence)
        assertFalse(p.all().single().established)
        // the same when it is split into two same-named new topics, each referencing a different day
        val q = TopicPool()
        q.apply("com.shop", events, listOf(new(0), new(1)), t0 + day, zone)
        assertEquals(mapOf("com.shop" to listOf(20_001L)), q.all().single().evidence)
        // one referencing an existing topic and one starting a same-named new topic are combined too
        val r = TopicPool()
        r.apply("com.shop", listOf(ev(0, at = t0 - 5 * day)), listOf(new(0)), t0 - 5 * day, zone)
        val id = r.all().single().id
        val applied = r.apply("com.shop", events, listOf(LearnSignal(listOf(0), topicId = id), new(1)), t0 + day, zone)
        assertEquals(listOf(19_995L, 20_001L), r[id]!!.evidence.getValue("com.shop"))
        assertEquals(2, applied.applied)
        assertEquals(mapOf(0 to setOf(id), 1 to setOf(id)), applied.byEvent)
        assertEquals(t0 + day, r[id]!!.lastSeenMs)
    }

    // the day of the evidence is the day the notification was posted, not the day it was judged: notifications from one day judged on two separate days still count as one day
    @Test fun judgingOnAnotherDayAddsNoDay() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0, at = t0)), listOf(new(0)), t0, zone)
        p.apply("com.shop", listOf(ev(0, at = t0 + 60_000L)), listOf(new(0)), t0 + day, zone)
        assertEquals(1, p.all().single().days)
        assertFalse(p.all().single().established)
    }

    // a topic the user deleted individually is never learned back: a same-named new topic is not accepted, whatever kind it is filed under and however case and whitespace are written; other names are accepted as usual
    @Test fun aDeletedTopicNameIsNotLearnedAgain() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        assertTrue(p.delete(p.all().single().id))
        assertEquals(1, p.blockedCount)
        val r = p.apply("com.shop", listOf(ev(0), ev(1), ev(2)),
            listOf(new(0), new(1, kind = TopicKind.Interest, label = " Running  SHOES "), new(2, label = "trail shoes")), t0 + 1, zone)
        assertEquals(listOf("trail shoes"), p.all().map { it.label })
        assertEquals(2, r.blocked)
        assertEquals(1, r.applied)
        assertEquals(0, r.missing)
        assertEquals(setOf(2), r.byEvent.keys)
        // only a fingerprint is stored, not the deleted name; it still blocks after read-back
        val data = p.data()
        assertEquals(listOf(TopicPool.fingerprint("running shoes")), data.blocked)
        assertFalse(data.toString().contains("running shoes", ignoreCase = true))
        val back = TopicPool(data)
        back.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0 + 2, zone)
        assertEquals(listOf("trail shoes"), back.all().map { it.label })
        // after allowing again it can be learned once more
        assertTrue(back.allowAgain())
        assertFalse(back.allowAgain())
        back.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0 + 3, zone)
        assertEquals(listOf("trail shoes", "running shoes"), back.all().map { it.label })
    }

    // a kept topic with the same name keeps working as usual: existing topics are matched first, and only when starting a new one is a deleted name checked
    @Test fun aKeptTopicWithABlockedNameKeepsWorking() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0), ev(1)), listOf(new(0), new(1, kind = TopicKind.Interest)), t0, zone)
        val (shopping, interest) = p.all()
        assertTrue(p.delete(shopping.id))
        // the same-named topic of the other kind is still there: when the model mentions it again as a new name it counts as a reference to it, and evidence is added as usual
        val r = p.apply("com.mail", listOf(ev(0, at = t0 + day, pkg = "com.mail")), listOf(new(0, kind = TopicKind.Interest)), t0 + day, zone)
        assertEquals(1, r.applied)
        assertEquals(0, r.blocked)
        assertEquals(setOf("com.shop", "com.mail"), p[interest.id]!!.evidence.keys)
        // the deleted kind does not come back on the back of it
        assertEquals(1, p.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0 + day, zone).blocked)
        assertEquals(1, p.all().size)
    }

    // only an individual delete records "never record again": clearing everything, clearing one app, deleting the sensitive ones, forgetting faded ones and evicting on the cap do not
    @Test fun onlyASingleDeleteBlocksAName() {
        val cleared = TopicPool()
        cleared.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        cleared.clear()
        val byApp = TopicPool()
        byApp.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        assertEquals(1, byApp.clearApp("com.shop").size)
        val sensitive = TopicPool()
        sensitive.apply("com.shop", listOf(ev(0)), listOf(new(0).copy(sensitive = true)), t0, zone, allowSensitive = true)
        assertEquals(1, sensitive.deleteSensitive().size)
        val faded = TopicPool()
        faded.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        assertEquals(1, faded.maintain(t0 + 400 * day).size)
        for (p in listOf(cleared, byApp, sensitive, faded)) {
            assertEquals(0, p.blockedCount)
            assertTrue(p.all().isEmpty())
            p.apply("com.shop", listOf(ev(0, at = t0 + 400 * day)), listOf(new(0)), t0 + 400 * day, zone, allowSensitive = true)
            assertEquals(1, p.all().size)
        }
        // clearing everything starts over, but names deleted individually before are still remembered
        val both = TopicPool()
        both.apply("com.shop", listOf(ev(0), ev(1)), listOf(new(0), new(1, label = "tent")), t0, zone)
        both.delete(both.all().first { it.label == "tent" }.id)
        both.clear()
        assertEquals(1, both.blockedCount)
        // the list is capped, and past the cap the earliest entries are dropped
        val many = TopicPool()
        repeat(LearnTuning.TOPIC_BLOCKED_CAP + 3) { i ->
            many.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "topic $i")), t0, zone)
            many.delete(many.all().single().id)
        }
        assertEquals(LearnTuning.TOPIC_BLOCKED_CAP, many.blockedCount)
        assertEquals(0, many.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "topic 0")), t0, zone).blocked)
        assertEquals(1, many.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "topic 5")), t0, zone).blocked)
    }

    // saved by an older version (no "never record again" list): it still decodes
    // ---- the user merges one topic into another ----

    /**
     * Two topics worded almost the same; returns the ids of (a, b). a "trail shoes": com.shop on two days (yesterday, today), opened 1, dismissed 1.
     * b "running shoes": com.shop today, com.mall tomorrow, opened 1.
     */
    private fun twins(p: TopicPool): Pair<String, String> {
        p.apply("com.shop", listOf(ev(0, at = t0 - day, outcome = LearnOutcome.Opened)), listOf(new(0, label = "trail shoes")), t0 - day, zone)
        p.apply("com.shop", listOf(ev(0, outcome = LearnOutcome.Dismissed), ev(1)), listOf(new(0, label = "trail shoes"), new(1)), t0, zone)
        p.apply("com.mall", listOf(ev(0, at = t0 + day, pkg = "com.mall", outcome = LearnOutcome.Opened)), listOf(new(0)), t0 + day, zone)
        val (a, b) = p.all()
        return a.id to b.id
    }

    @Test fun mergingAddsOneTopicsEvidenceToTheOther() {
        val p = TopicPool()
        val (a, b) = twins(p)
        assertTrue(p.merge(a, b))
        val t = p.all().single()
        assertEquals(b, t.id)
        assertEquals("running shoes", t.label)
        assertEquals(TopicKind.Shopping, t.kind)
        // the same app on the same day counted from both sides counts once
        assertEquals(mapOf("com.shop" to listOf(19_999L, 20_000L), "com.mall" to listOf(20_001L)), t.evidence)
        assertEquals(3, t.days)
        assertTrue(t.established)
        assertEquals(2, t.opened)
        assertEquals(1, t.dismissed)
        assertEquals(t0 - day, t.firstSeenMs)
        assertEquals(t0 + day, t.lastSeenMs)
        // one already merged, the same one, or a missing one: nothing happens
        assertFalse(p.merge(a, b))
        assertFalse(p.merge(b, b))
        assertFalse(p.merge(b, "nope"))
        assertEquals(t, p.all().single())
        // merging is not deleting: the name does not go into "never record again"
        assertEquals(0, p.blockedCount)
    }

    // a name that was merged away, when the model starts it again later (whatever kind, however case and whitespace are written), counts as a reference to the topic it went into rather than a new topic
    @Test fun aMergedAwayNameIsCountedOnTheTopicItWentInto() {
        val p = TopicPool()
        val (a, b) = twins(p)
        p.merge(a, b)
        val r = p.apply("com.shop", listOf(ev(0, at = t0 + 2 * day), ev(1, at = t0 + 2 * day)),
            listOf(new(0, kind = TopicKind.Interest, label = " Trail  SHOES "), new(1, label = "tents")), t0 + 2 * day, zone)
        assertEquals(setOf(b), r.byEvent.getValue(0))
        assertEquals(2, r.applied)
        assertEquals(listOf("running shoes", "tents"), p.all().map { it.label })
        assertEquals(20_002L, p[b]!!.evidence.getValue("com.shop").last())
        // a request that was in flight at the moment of the merge still references the merged-away id in its reply: it is credited to the topic it went into rather than lost
        val late = p.apply("com.mall", listOf(ev(0, at = t0 + 3 * day, pkg = "com.mall")), listOf(LearnSignal(listOf(0), topicId = a)), t0 + 3 * day, zone)
        assertEquals(1, late.applied)
        assertEquals(0, late.missing)
        assertEquals(20_003L, p[b]!!.evidence.getValue("com.mall").last())
        // only a fingerprint is stored, not the merged-away name; it is still recognised after a file round trip
        val data = p.data()
        assertEquals(mapOf(TopicPool.fingerprint("trail shoes") to b), data.merged)
        assertFalse(data.toString().contains("trail", ignoreCase = true))
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val back = TopicPool(json.decodeFromString(TopicPoolData.serializer(), json.encodeToString(TopicPoolData.serializer(), data)))
        back.apply("com.shop", listOf(ev(0, at = t0 + 4 * day)), listOf(new(0, label = "trail shoes")), t0 + 4 * day, zone)
        assertEquals(listOf("running shoes", "tents"), back.all().map { it.label })
        assertEquals(20_004L, back[b]!!.evidence.getValue("com.shop").last())
        assertEquals(data.merged, back.data().merged)
    }

    // after several merges: a name follows through to the topic it eventually went into; deleting that merged result individually also stops recording every name merged into it
    @Test fun mergedNamesFollowLaterMergesAndAreBlockedWithTheTopic() {
        val p = TopicPool()
        val four = listOf(new(0, label = "a"), new(1, label = "b"), new(2, label = "c"), new(3, label = "d"))
        p.apply("com.shop", listOf(ev(0), ev(1), ev(2), ev(3)), four, t0, zone)
        val (a, b, c, d) = p.all().map { it.id }
        assertTrue(p.merge(a, b))
        assertTrue(p.merge(b, c))
        assertEquals(mapOf(TopicPool.fingerprint("a") to c, TopicPool.fingerprint("b") to c), p.data().merged)
        // a request in flight references the earliest merged-away topic: it follows through to c
        val late = p.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(LearnSignal(listOf(0), topicId = a)), t0 + day, zone)
        assertEquals(setOf(c), late.byEvent.getValue(0))
        // delete c: the three names a, b and c are no longer recorded; d is unaffected
        assertTrue(p.delete(c))
        assertEquals(3, p.blockedCount)
        assertTrue(p.data().merged.isEmpty())
        val r = p.apply("com.shop", listOf(ev(0), ev(1), ev(2), ev(3)), four, t0 + day, zone)
        assertEquals(3, r.blocked)
        assertEquals(1, r.applied)
        assertEquals(listOf(d), p.all().map { it.id })
        // after allowing again it can be learned once more (the alias is gone, so this is a new topic)
        assertTrue(p.allowAgain())
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "a")), t0 + day, zone)
        assertEquals(listOf("d", "a"), p.all().map { it.label })
    }

    // "never record again" and merged-away are two decisions about the same name, and the later one wins; if the topic merged into was cleared rather than deleted individually, the name can be learned again
    @Test fun aNameIsEitherBlockedOrMergedWhicheverTheUserDidLast() {
        val three = listOf(new(0), new(1, kind = TopicKind.Interest), new(2, label = "tents"))
        // two topics with the same name: delete one (the name goes into "never record again"), then merge the kept one into another → this name now counts over there
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0), ev(1), ev(2)), three, t0, zone)
        val (shopping, interest, tents) = p.all().map { it.id }
        assertTrue(p.delete(shopping))
        assertTrue(p.merge(interest, tents))
        assertEquals(0, p.blockedCount)
        val r = p.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(new(0)), t0 + day, zone)
        assertEquals(0, r.blocked)
        assertEquals(setOf(tents), r.byEvent.getValue(0))
        assertEquals(1, p.all().size)
        // the other way round: after the name was merged away, the other topic with the same name is deleted individually → the name is no longer recorded
        val q = TopicPool()
        q.apply("com.shop", listOf(ev(0), ev(1), ev(2)), three, t0, zone)
        val (s2, i2, t2) = q.all().map { it.id }
        assertTrue(q.merge(s2, t2))
        // the kept topic with the same name keeps working as usual (existing topics are matched first)
        assertEquals(setOf(i2), q.apply("com.shop", listOf(ev(0)), listOf(new(0, kind = TopicKind.Interest)), t0, zone).byEvent.getValue(0))
        assertTrue(q.delete(i2))
        assertTrue(q.data().merged.isEmpty())
        assertEquals(1, q.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(new(0)), t0 + day, zone).blocked)
        assertEquals(listOf(t2), q.all().map { it.id })
        // the topic merged into was cleared (not deleted individually): the alias no longer counts, the name can be learned again as usual, and it does not go into "never record again"
        val c = TopicPool()
        val (a, b) = twins(c)
        c.merge(a, b)
        assertEquals(setOf(b), c.clearApp("com.shop") + c.clearApp("com.mall"))
        assertTrue(c.data().merged.isEmpty())
        assertEquals(0, c.blockedCount)
        c.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "trail shoes")), t0, zone)
        assertEquals(listOf("trail shoes"), c.all().map { it.label })
        // clearing everything behaves the same way
        val e = TopicPool()
        val (x, y) = twins(e)
        e.merge(x, y)
        e.clear()
        assertTrue(e.data().merged.isEmpty())
        e.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "trail shoes")), t0, zone)
        assertEquals(listOf("trail shoes"), e.all().map { it.label })
    }

    // if either one is sensitive the result is sensitive (it can only become stricter); the per-app day cap and the per-topic app cap still apply
    @Test fun mergingKeepsTheStricterFlagAndTheCaps() {
        val p = TopicPool()
        val diabetes = new(0, kind = TopicKind.Interest, label = "Diabetes")
        p.apply("com.health", listOf(ev(0, pkg = "com.health")), listOf(diabetes.copy(sensitive = true)), t0, zone, allowSensitive = true)
        p.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        val (sensitive, plain) = p.all().map { it.id }
        assertTrue(p.merge(sensitive, plain))
        assertTrue(p[plain]!!.sensitive)
        // with "infer sensitive topics" off, the merged-away name coming back adds no evidence (the topic it went into is sensitive now)
        val r = p.apply("com.health", listOf(ev(0, at = t0 + day, pkg = "com.health")), listOf(diabetes), t0 + day, zone)
        assertEquals(1, r.missing)
        assertEquals(2, p[plain]!!.evidenceCount)
        // the cap: `from` has 20 apps (one day each, newer towards the end) plus one shared app; `into` has 5 newer apps plus that shared one
        val cap = LearnTuning.APPS_PER_TOPIC
        val from = ObservedTopic("kx", TopicKind.Shopping, "x", 1, 2,
            (0 until cap - 1).associate { "com.a$it" to listOf(20_000L + it) } + ("com.same" to (0 until 60).map { 20_000L + it }))
        val into = ObservedTopic("ky", TopicKind.Shopping, "y", 3, 4,
            (0 until 5).associate { "com.b$it" to listOf(20_100L + it) } + ("com.same" to (30 until 90).map { 20_000L + it }))
        val q = TopicPool(TopicPoolData(topics = listOf(from, into)))
        assertTrue(q.merge("kx", "ky"))
        val e = q["ky"]!!.evidence
        assertEquals(cap, e.size)
        assertEquals((30 until 90).map { 20_000L + it }, e.getValue("com.same"))
        assertTrue((0 until 5).all { "com.b$it" in e })
        // the 5 oldest of from's 19 make room
        assertTrue((0 until 5).none { "com.a$it" in e })
        assertTrue((5 until cap - 1).all { "com.a$it" in e })
    }

    // the other topics listed when choosing where to merge: similar names come first, the rest by strength
    @Test fun lookAlikeNamesComeFirstWhenPickingWhereToMerge() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0), ev(1), ev(2), ev(3)),
            listOf(new(0, label = "AI 编程工具"), new(1, label = "露营装备"), new(2, label = "ai编程助手"), new(3, label = "编程")), t0, zone)
        // the "camping gear" topic has one more day of evidence (the strongest), but its name is not similar, so it still comes after the similar ones
        p.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(new(0, label = "露营装备")), t0 + day, zone)
        val all = p.all()
        val of = all.first { it.label == "AI 编程工具" }
        assertEquals(listOf("ai编程助手", "编程", "露营装备"), TopicPool.mergeCandidates(of, all, t0 + day).map { it.label })
        // none of them similar: by strength
        val camping = all.first { it.label == "露营装备" }
        assertEquals(3, TopicPool.mergeCandidates(camping, all, t0 + day).size)
        assertFalse(camping in TopicPool.mergeCandidates(camping, all, t0 + day))
    }

    @Test fun olderTopicFilesStillDecode() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        // an empty list is not written to the file: this is what an older version's file looks like
        val old = json.encodeToString(TopicPoolData.serializer(), TopicPoolData(topics = listOf(ObservedTopic("k1", TopicKind.Shopping, "running shoes", 1, 2))))
        assertFalse(old, "blocked" in old)
        assertFalse(old, "merged" in old)
        val p = TopicPool(json.decodeFromString(TopicPoolData.serializer(), old))
        assertEquals(listOf("running shoes"), p.all().map { it.label })
        assertEquals(0, p.blockedCount)
    }
}
