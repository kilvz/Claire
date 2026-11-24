package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.TriageConsent
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnPrefsTest {
    // Never consented; sensitive inference is off by default; no App is tuned to "low" by default
    @Test fun defaultsAreOff() {
        val p = ProactivePrefs(FeatureFlagsCapsuleTest.MemoryPrefs())
        assertNull(p.consent)
        assertFalse(p.learnSensitive)
        assertFalse(p.learnDigest)
        assertEquals(emptySet<String>(), p.learnOptOut)
    }

    @Test fun switchesAndTiersSurvive() {
        val raw = FeatureFlagsCapsuleTest.MemoryPrefs()
        val p = ProactivePrefs(raw)
        p.learnSensitive = true
        p.learnOptOut = setOf("com.tencent.mm")
        val back = ProactivePrefs(raw)
        assertTrue(back.learnSensitive)
        assertEquals(setOf("com.tencent.mm"), back.learnOptOut)
    }

    // The single consent shared by triage and learning: stored, read back, cleared
    @Test fun consentSurvivesAndCanBeCleared() {
        val raw = FeatureFlagsCapsuleTest.MemoryPrefs()
        val p = ProactivePrefs(raw)
        p.consent = TriageConsent("p1", "m1")
        assertEquals(TriageConsent("p1", "m1"), ProactivePrefs(raw).consent)
        p.consent = null
        assertNull(ProactivePrefs(raw).consent)
    }

    // Deletions that did not go through must be recorded into the next process
    @Test fun pendingPurgesSurvive() {
        val raw = FeatureFlagsCapsuleTest.MemoryPrefs()
        val p = ProactivePrefs(raw)
        assertEquals(emptySet<String>(), p.topicPurges)
        assertEquals(emptySet<String>(), p.contactPurges)
        assertEquals(emptySet<String>(), p.digestPurges)
        // A recorded merge (two topic ids with a > in between) is stored and read back verbatim
        val merge = ":merge:k0123456789ab>kba9876543210"
        p.topicPurges = setOf(LearnRunner.PURGE_SENSITIVE, "com.chat", merge)
        p.contactPurges = setOf("com.chat", merge)
        p.digestPurges = setOf("com.chat", "com.shop")
        val back = ProactivePrefs(raw)
        assertEquals(setOf(LearnRunner.PURGE_SENSITIVE, "com.chat", merge), back.topicPurges)
        assertEquals(setOf("com.chat", merge), back.contactPurges)
        assertEquals(setOf("com.chat", "com.shop"), back.digestPurges)
        back.topicPurges = emptySet()
        assertEquals(emptySet<String>(), ProactivePrefs(raw).topicPurges)
    }

    // Pending work recorded on topics and contacts must be replayed in order: the order must be persisted too (a string set in preferences does not keep order by itself)
    @Test fun pendingPurgesKeepTheirOrder() {
        val raw = FeatureFlagsCapsuleTest.MemoryPrefs()
        val p = ProactivePrefs(raw)
        val marks = (0 until 40).map { ":topic:k%012d".format((it * 7919) % 1000) } + listOf("com.chat", ":merge:kaaaaaaaaaaaa>kbbbbbbbbbbbb")
        var set = emptySet<String>()
        for (m in marks) { set = set - m + m; p.topicPurges = set }
        assertEquals(marks, ProactivePrefs(raw).topicPurges.toList())
        // After striking a few out, the rest keep their order
        val struck = setOf(marks[3], marks[17])
        p.topicPurges = p.topicPurges - struck
        assertEquals(marks - struck, ProactivePrefs(raw).topicPurges.toList())
        // Written by an older version that only knows the set (no order entry): it still reads back, just without order
        val old = FeatureFlagsCapsuleTest.MemoryPrefs()
        old.edit().putStringSet("learn_purge_topics", setOf("com.a", ":topic:k1")).commit()
        assertEquals(setOf("com.a", ":topic:k1"), ProactivePrefs(old).topicPurges)
        // The old version later changed the set so the order entry no longer matches: only the set is honoured
        raw.edit().putStringSet("learn_purge_topics", setOf("com.z")).commit()
        assertEquals(setOf("com.z"), ProactivePrefs(raw).topicPurges)
    }

    // One merge must be recorded on both lists: written in a single save, appended to the end of each
    @Test fun aMergeIsRecordedOnBothListsAtOnce() {
        val raw = FeatureFlagsCapsuleTest.MemoryPrefs()
        val p = ProactivePrefs(raw)
        p.topicPurges = setOf(":topic:k1")
        p.contactPurges = setOf("com.chat")
        val merge = ":merge:k0123456789ab>kba9876543210"
        p.addToTopicsAndContacts(merge)
        val back = ProactivePrefs(raw)
        assertEquals(listOf(":topic:k1", merge), back.topicPurges.toList())
        assertEquals(listOf("com.chat", merge), back.contactPurges.toList())
    }
}
