package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.PlacePace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pacing inside apps: a short dwell on the same screen, a long dwell on a new screen; for apps that throttle
 * browsing, extra reading time per character and a longer dwell, both bounded; time already spent by the model
 * counts; the screen cap applies only to throttling apps, resets after the user replies, and is lifted once an
 * outward action has been made; "fast" never waits.
 */
class AppPacingTest {
    private val std = AppPacing.Limits()
    private val careful = AppPacing.Limits.CAREFUL

    @Test fun firstActionDoesNotWait() {
        assertEquals(0, AppPacing.human(Random(1)).waitBefore(0, 500))
    }

    @Test fun sameScreenAndNewScreenUseTheirRanges() {
        repeat(100) { seed ->
            val p = AppPacing.human(Random(seed))
            p.noteAction("com.a", 1_000, newScreen = false)
            val same = p.waitBefore(1_000)
            assertTrue("$same", same in std.sameScreenMs.first..std.maxDwellMs)
            if (same > std.sameScreenMs.last) assertTrue(same <= std.sameScreenMs.last + std.pauseMs.last)
            p.noteAction("com.a", 2_000, newScreen = true)
            val fresh = p.waitBefore(2_000, textChars = 5_000)
            // standard ranges add no reading time for character count
            assertTrue("$fresh", fresh in std.newScreenMs.first..std.maxDwellMs)
        }
        // on average a new screen dwells longer than the same screen
        fun mean(newScreen: Boolean) = (0 until 200).map { s ->
            AppPacing.human(Random(s)).apply { noteAction("com.a", 0, newScreen) }.waitBefore(0)
        }.average()
        assertTrue(mean(false) < mean(true))
    }

    @Test fun standardDwellIsModest() {
        repeat(200) { seed ->
            val p = AppPacing.human(Random(seed))
            p.noteAction("com.a", 0, newScreen = seed % 2 == 0)
            assertTrue(p.waitBefore(0) in 500L..3_500L)
        }
    }

    @Test fun carefulAppsReadInProportionToTheContentWithBounds() {
        val p = AppPacing.human(Random(4))
        p.noteAction("com.slow", 0, newScreen = true, tempo = AppTempo.CAREFUL)
        val empty = p.waitBefore(0, textChars = 0)
        assertTrue("$empty", empty in careful.newScreenMs.first..careful.newScreenMs.last + careful.pauseMs.last)
        // each character adds readMsPerChar, reading time is capped at maxReadMs, and the whole dwell at maxDwellMs
        assertEquals(empty + 100 * careful.readMsPerChar, p.waitBefore(0, textChars = 100))
        assertEquals((empty + careful.maxReadMs).coerceAtMost(careful.maxDwellMs), p.waitBefore(0, textChars = 1_000_000))
        // continuing on the same screen adds no reading time
        p.noteAction("com.slow", 0, newScreen = false, tempo = AppTempo.CAREFUL)
        val same = p.waitBefore(0, textChars = 1_000)
        assertTrue("$same", same in careful.sameScreenMs.first..careful.sameScreenMs.last + careful.pauseMs.last)
    }

    @Test fun fastTempoRecordsNoDwell() {
        val p = AppPacing.human(Random(4))
        p.noteAction("com.a", 0, newScreen = true, tempo = AppTempo.NORMAL)
        assertTrue(p.waitBefore(0) > 0)
        // actions done in a fast app record no dwell: waiting again gives 0
        p.noteAction("com.quick", 0, newScreen = true, tempo = AppTempo.FAST)
        assertEquals(0, p.waitBefore(0, textChars = 1_000))
    }

    @Test fun timeAlreadySpentCounts() {
        val p = AppPacing.human(Random(2))
        p.noteAction("com.a", 10_000, newScreen = true)
        val full = p.waitBefore(10_000)
        assertEquals(full - 400, p.waitBefore(10_400))
        assertEquals(0, p.waitBefore(10_000 + full))
        assertEquals(0, p.waitBefore(60_000))
    }

    @Test fun idleScrollOnlyOnNewCarefulScreens() {
        val p = AppPacing.human(Random(2))
        p.noteAction("com.a", 0, newScreen = true)
        assertFalse(p.idleAllowed())
        p.noteAction("com.slow", 0, newScreen = true, tempo = AppTempo.CAREFUL)
        assertTrue(p.idleAllowed())
        p.noteAction("com.slow", 0, newScreen = false, tempo = AppTempo.CAREFUL)
        assertFalse(p.idleAllowed())
    }

    @Test fun carefulAppsHaveAScreenBudgetThatResetsWhenTheUserAnswers() {
        val p = AppPacing(Random(1), screenCap = 3)
        repeat(3) { p.noteAction("com.slow", 0, newScreen = true, tempo = AppTempo.CAREFUL, capped = true) }
        // actions on the same screen and scrolling down to read the rest do not count; nor does an app that is slow (careful) but not screen-capped
        p.noteAction("com.slow", 0, newScreen = false, tempo = AppTempo.CAREFUL, capped = true)
        p.noteAction("com.slow", 0, newScreen = true, tempo = AppTempo.CAREFUL, capped = true, counts = false)
        p.noteAction("com.calm", 0, newScreen = true, tempo = AppTempo.CAREFUL, capped = false)
        assertEquals(3, p.screensIn("com.slow"))
        assertEquals(0, p.screensIn("com.calm"))
        assertTrue(p.capReached("com.slow", capped = true))
        // ordinary apps have no cap
        repeat(10) { p.noteAction("com.a", 0, newScreen = true) }
        assertFalse(p.capReached("com.a", capped = true))
        p.userAnswered()
        assertEquals(0, p.screensIn("com.slow"))
        assertFalse(p.capReached("com.slow", capped = true))
    }

    @Test fun anOutwardActionLiftsTheBudgetForThatApp() {
        val p = AppPacing(Random(1), screenCap = 1)
        p.noteAction("com.slow", 0, newScreen = true, tempo = AppTempo.CAREFUL, capped = true)
        p.noteAction("com.other", 0, newScreen = true, tempo = AppTempo.CAREFUL, capped = true)
        p.outwardDone("com.slow")
        assertFalse(p.capReached("com.slow", capped = true))
        assertTrue(p.capReached("com.other", capped = true))
        // replying does not restore it either
        p.userAnswered()
        repeat(3) { p.noteAction("com.slow", 0, newScreen = true, tempo = AppTempo.CAREFUL, capped = true) }
        assertFalse(p.capReached("com.slow", capped = true))
    }

    /** No screen cap by default: reading, summarizing and similar tasks, and tasks with nobody around, can still finish in a browsing-throttled app, only with longer dwells. */
    @Test fun carefulAppsHaveNoScreenBudgetByDefault() {
        val p = AppPacing.human(Random(1))
        assertEquals(null, p.screenCap)
        repeat(200) { p.noteAction("com.slow", 0, newScreen = true, tempo = AppTempo.CAREFUL, capped = true) }
        assertEquals(200, p.screensIn("com.slow"))
        assertFalse(p.capReached("com.slow", capped = true))
        assertTrue(p.waitBefore(0, 500) > 0)
    }

    @Test fun offNeverWaitsNorCaps() {
        val p = AppPacing.Off
        repeat(50) { p.noteAction("com.slow", 0, newScreen = true, tempo = AppTempo.CAREFUL, capped = true) }
        assertEquals(0, p.waitBefore(0, 10_000))
        assertFalse(p.capReached("com.slow", capped = true))
        assertFalse(p.idleAllowed())
    }

    @Test fun settingMapsToPacing() {
        assertSame(AppPacing.Off, OperatingPace.FAST.appPacing())
        assertEquals(AppPacing.Limits(), OperatingPace.STANDARD.appPacing().limits)
        assertEquals(AppPacing.Limits.CAREFUL, OperatingPace.STANDARD.appPacing().careful)
        assertEquals(AppPacing.Limits.CAREFUL, OperatingPace.HUMAN.appPacing().limits)
        assertTrue(OperatingPace.HUMAN.appPacing().paced)

        assertSame(TypingPacing.Instant, OperatingPace.FAST.typing(PlacePace.CAREFUL))
        assertEquals(TypingPacing.Limits(), OperatingPace.STANDARD.typing(PlacePace.NORMAL).limits)
        assertEquals(TypingPacing.Limits.CAREFUL, OperatingPace.STANDARD.typing(PlacePace.CAREFUL).limits)
        assertEquals(TypingPacing.Limits.CAREFUL, OperatingPace.HUMAN.typing(PlacePace.NORMAL).limits)

        assertEquals(AppTempo.FAST, OperatingPace.FAST.tempo)
        assertEquals(AppTempo.NORMAL, OperatingPace.STANDARD.tempo)
        assertEquals(AppTempo.CAREFUL, OperatingPace.HUMAN.tempo)
        assertSame(TypingPacing.Instant, OperatingPace.STANDARD.typing(AppTempo.FAST))
        assertEquals(TypingPacing.Limits(), OperatingPace.HUMAN.typing(AppTempo.NORMAL).limits)
        assertEquals(TypingPacing.Limits.CAREFUL, OperatingPace.FAST.typing(AppTempo.CAREFUL).limits)

        assertEquals(OperatingPace.HUMAN, OperatingPace.parse("human"))
        assertEquals(OperatingPace.FAST, OperatingPace.parse(" FAST "))
        assertEquals(null, OperatingPace.parse("quick"))
        assertEquals(null, OperatingPace.parse(null))
        assertEquals(AppTempo.CAREFUL, AppTempo.of("careful"))
        assertEquals(AppTempo.NORMAL, AppTempo.of(null))
        assertEquals(AppTempo.NORMAL, AppTempo.of("brisk"))
    }

    @Test fun planResolvesPerApp() {
        val plan = AppPacePlan(OperatingPace.STANDARD, mapOf("com.xhs" to OperatingPace.HUMAN, "com.bank" to OperatingPace.FAST))
        assertEquals(OperatingPace.HUMAN, plan.of("com.xhs"))
        assertEquals(OperatingPace.FAST, plan.of("com.bank"))
        // apps without an override follow the global setting; an unknown package name also follows it
        assertEquals(OperatingPace.STANDARD, plan.of("com.other"))
        assertEquals(OperatingPace.STANDARD, plan.of(null))
        // the cap applies only to apps overridden as "human-like"
        assertTrue(plan.capped("com.xhs"))
        assertFalse(plan.capped("com.bank"))
        assertFalse(plan.capped("com.other"))
        assertFalse(plan.capped(null))
        // a slower override prevents turning dwell off entirely
        assertFalse(plan.allFast)
        assertTrue(AppPacePlan(OperatingPace.FAST).allFast)
        assertTrue(AppPacePlan(OperatingPace.FAST, mapOf("com.a" to OperatingPace.FAST)).allFast)
        assertFalse(AppPacePlan(OperatingPace.FAST, mapOf("com.a" to OperatingPace.HUMAN)).allFast)
        // global fast + one human-like app: the dwell engine must still run; the other apps are skipped via tempo=FAST
        val pacing = AppPacePlan(OperatingPace.FAST, mapOf("com.xhs" to OperatingPace.HUMAN)).appPacing()
        assertTrue(pacing.paced)
        assertSame(AppPacing.Off, AppPacePlan(OperatingPace.FAST).appPacing())
    }
}
