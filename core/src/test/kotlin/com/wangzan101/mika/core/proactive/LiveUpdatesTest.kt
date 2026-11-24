package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.proactive.LiveUpdates.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdatesTest {
    private val t0 = 1_000_000_000L
    private fun post(key: String, at: Long, text: String, silent: Boolean = true, category: String? = null, pkg: String = "com.maps") =
        NoticeRecord(key, pkg, at, "标题", text, category = category, silent = silent)

    // many apps share one notification key for all their pushes: several unrelated ones arrive within a minute and the user opens and swipes them away one by one — this is not one notification being reposted
    @Test fun notificationsTheUserDealtWithOneByOneAreNotCountedAsOneBeingReposted() {
        val live = LiveUpdates()
        repeat(6) { i ->
            assertEquals(Verdict.Normal, live.observe(post("shared", t0 + i * 10_000L, "第 $i 件事")))
            live.ended("shared")
        }
        // those not dealt with are counted as before
        val other = LiveUpdates()
        assertEquals(listOf(Verdict.Normal, Verdict.Normal, Verdict.Normal, Verdict.BecameLive),
            (0 until 4).map { i -> other.observe(post("shared", t0 + i * 10_000L, "第 $i 件事")) })
        // the one recognised as a status display is swiped away: the next one on the same key counts from scratch; something genuinely being reposted is recognised again once counted enough times
        other.ended("shared")
        assertEquals(listOf(Verdict.Normal, Verdict.Normal, Verdict.Normal, Verdict.BecameLive, Verdict.Live),
            (5 until 10).map { i -> other.observe(post("shared", t0 + i * 2_500L + 60_000L, "${i} 米后右转")) })
        other.ended("nothing")
    }

    // the navigation announcement seen on a real device: an ordinary dismissible notification, no category, silent, reposted every two or three seconds, with different text whenever the distance changes
    @Test fun aSilentNotificationRepostedEveryFewSecondsIsAStatusDisplay() {
        val live = LiveUpdates()
        val verdicts = (0 until 40).map { i -> live.observe(post("nav", t0 + i * 2_500L, "${300 - i * 5} 米后右转")) }
        assertEquals(listOf(Verdict.Normal, Verdict.Normal, Verdict.Normal, Verdict.BecameLive), verdicts.take(4))
        assertTrue(verdicts.drop(4).all { it == Verdict.Live })
        // it paused for less than the quiet period and resumed: still not taken
        assertEquals(Verdict.Live, live.observe(post("nav", t0 + 39 * 2_500L + LiveUpdates.QUIET_MS - 1, "到达目的地附近")))
        // once it has been quiet long enough, anything new on this key is taken as usual
        val later = t0 + 40 * 2_500L + 3 * LiveUpdates.QUIET_MS
        assertEquals(Verdict.Normal, live.observe(post("nav", later, "新的一条")))
        // other keys are unaffected
        assertEquals(Verdict.Normal, live.observe(post("promo", t0 + 10_000L, "满减券到账", pkg = "com.shop")))
    }

    // well-spaced updates do not count: a status change once every half minute or every few minutes is a series of separate events
    @Test fun spacedOutUpdatesAreOrdinaryNotifications() {
        val live = LiveUpdates()
        assertTrue((0 until 30).all { live.observe(post("order", t0 + it * 25_000L, "订单状态 $it")) == Verdict.Normal })
    }

    // a notification that makes a sound needs a far higher rate: the twelfth time within a minute
    @Test fun aNotificationThatAlertsNeedsFarMoreReposts() {
        val live = LiveUpdates()
        val verdicts = (0 until 14).map { live.observe(post("ticker", t0 + it * 4_000L, "第 $it 次", silent = false)) }
        assertTrue(verdicts.take(LiveUpdates.LOUD_POSTS - 1).all { it == Verdict.Normal })
        assertEquals(Verdict.BecameLive, verdicts[LiveUpdates.LOUD_POSTS - 1])
        assertEquals(Verdict.Live, verdicts.last())
        // anything less dense does not count (once every five seconds is exactly twelve times a minute, at most twelve in the window — so slow it down to six seconds here)
        val slower = LiveUpdates()
        assertTrue((0 until 60).all { slower.observe(post("x", t0 + it * 6_000L, "第 $it 次", silent = false)) == Verdict.Normal })
    }

    // conversations are never considered: a conversation notification updating once per message is how it is supposed to look (conversation style, message category, split apart the way chats are written)
    @Test fun conversationsAreNeverTreatedAsStatusDisplays() {
        val styled = { at: Long -> NoticeRecord("c", "com.chat", at, "群", "一句", listOf(NoticeMessage("甲", "一句", at)), conversation = true, silent = true) }
        val byCategory = { at: Long -> post("m", at, "张三: 一句", category = "msg") }
        val shaped = { at: Long -> NoticeRecord("q", "com.chat.other", at, "群", "一句", sender = "甲", group = true, silent = true) }
        for (make in listOf(styled, byCategory, shaped)) {
            val live = LiveUpdates()
            assertTrue((0 until 50).all { live.observe(make(t0 + it * 1_000L)) == Verdict.Normal })
        }
    }

    // reading the same post again (a catch-up pass re-reads what is in the notification shade) does not count twice
    @Test fun readingTheSamePostAgainDoesNotCount() {
        val live = LiveUpdates()
        repeat(20) { assertEquals(Verdict.Normal, live.observe(post("nav", t0, "300 米后右转"))) }
        assertEquals(Verdict.Normal, live.observe(post("nav", t0 + 1_000L, "290 米后右转")))
    }

    // only the most recently used keys are remembered
    @Test fun onlyTheMostRecentKeysAreRemembered() {
        val live = LiveUpdates(capacity = 2)
        repeat(3) { live.observe(post("a", t0 + it * 1_000L, "a$it")) }
        live.observe(post("b", t0, "b"))
        live.observe(post("c", t0, "c"))
        // `a` was evicted: counting starts over
        assertEquals(Verdict.Normal, live.observe(post("a", t0 + 3_000L, "a3")))
    }
}
