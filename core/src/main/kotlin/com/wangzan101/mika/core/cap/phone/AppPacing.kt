package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.Pace
import kotlin.random.Random
import com.wangzan101.mika.core.screen.uistate.IdleScroll

/**
 * Pacing for step-by-step operation inside Apps (pure logic; touches neither the clock nor the UI): after a click a
 * person always takes a look before the next click, and on reaching a new screen reads for a while first.
 * After every screen-changing action ([noteAction]) it fixes the minimum gap until the next action based on
 * whether it moved to a new screen; before the next action ask [waitBefore]: if the gap is not yet met, top up the
 * remaining time.
 *
 * - Continuing on the same screen (typing, toggling a switch, only one or two elements changed): dwell for a random
 *   stretch within [Limits.sameScreenMs];
 * - Moved to a new screen (big UI change, switched App): dwell for a stretch within [Limits.newScreenMs], plus
 *   reading time for the character count on that screen ([Limits.readMsPerChar], at most [Limits.maxReadMs]);
 * - Occasionally ([Limits.pauseChance]) dwell a bit longer ([Limits.pauseMs]); a single dwell never exceeds
 *   [Limits.maxDwellMs];
 * - A dwell counts from when the previous action finished, so time the model spends thinking and reading the screen
 *   counts toward it, and only the shortfall is made up.
 * - When the App's effective tempo is [AppTempo.CAREFUL] (human in settings, a per-app override, or App knowledge
 *   says it limits browsing speed) it dwells the [careful] range (longer);
 *   in an [AppTempo.FAST] App completed actions record no dwell and also clear any earlier record: it does not wait
 *   inside, and leaving it does not make up old dwells either.
 * - For browsing-speed-limited Apps ([capped] true) transitions to a new screen are still counted; when [screenCap]
 *   is given, a task may move to a new screen at most that many times, after which [capReached] is true and the
 *   caller stops switching screens; the count restarts once the user answers ([userAnswered]); after this task has
 *   completed an outward action in this App ([outwardDone], sending/submitting and the like) no cap is applied
 *   anymore. By default there is no cap ([screenCap] null): stopping hard to ask a human would leave tasks like
 *   reading and summarizing unfinished, as well as tasks with nobody around, so the risk of being blocked is
 *   suppressed with longer dwells. This mirrors the page-by-page pacing of the web
 *   ([com.wangzan101.mika.core.cap.web.PagePacing]).
 *
 * [Off] never waits and sets no cap (used by "fast", tests, and callers that need no pacing).
 */
class AppPacing(
    private val random: Random?,
    val limits: Limits = Limits(),
    val careful: Limits = Limits.CAREFUL,
    /** How many times a browsing-speed-limited App may move to a new screen in one task (between user answers); null means no cap (the default). */
    val screenCap: Int? = null,
) {
    /** Durations are in milliseconds. */
    data class Limits(
        /** Dwell range when continuing on the same screen. */
        val sameScreenMs: LongRange = 500L..1_200L,
        /** Dwell range after moving to a new screen (excluding reading time). */
        val newScreenMs: LongRange = 900L..2_000L,
        /** Reading time added per character on a new screen; 0 adds nothing. */
        val readMsPerChar: Long = 0L,
        /** Upper bound on reading time. */
        val maxReadMs: Long = 0L,
        /** Chance of an extra pause. */
        val pauseChance: Double = 0.05,
        /** Range added on top when taking an extra pause. */
        val pauseMs: LongRange = 600L..1_500L,
        /** Upper bound on a single dwell. */
        val maxDwellMs: Long = 3_500L,
        /** When the dwell on a new screen is long enough, the dwell may scroll gently a few times (see [com.wangzan101.mika.core.screen.uistate.IdleScroll]). */
        val idleScroll: Boolean = false,
    ) {
        companion object {
            /** Browsing-speed-limited Apps (and every App in "human" mode): dwell longer, read by character count, scroll gently during the dwell. */
            val CAREFUL = Limits(
                sameScreenMs = 900L..2_200L, newScreenMs = 2_500L..5_000L, readMsPerChar = 15L, maxReadMs = 8_000L,
                pauseChance = 0.1, pauseMs = 2_000L..5_000L, maxDwellMs = 15_000L, idleScroll = true,
            )
        }
    }

    private class Last(val app: String?, val atMs: Long, val newScreen: Boolean, val baseMs: Long, val limits: Limits)

    private var last: Last? = null

    /** How many times each browsing-speed-limited App has moved to a new screen in this task (since the last user answer). */
    private val screens = HashMap<String, Int>()

    /** Apps that performed an outward action in this task: the screen cap no longer applies to them, and a user answer does not clear them. */
    private val outward = HashSet<String>()

    /** Paced (not [Off]). */
    val paced: Boolean get() = random != null

    /**
     * A screen-changing action completed in [app] (package name; null when unknown): [newScreen] means it moved to a
     * new screen, and with [counts] this screen counts toward the tally of browsing-speed-limited Apps (scrolling down
     * to read the rest of the same page does not count).
     * [tempo] is the App's effective tempo (an [AppTempo.FAST] one records no dwell), and [capped] says whether it is
     * browsing-speed limited (screens counted, cap applied).
     */
    @Synchronized
    fun noteAction(app: String?, nowMs: Long, newScreen: Boolean, tempo: AppTempo = AppTempo.NORMAL, counts: Boolean = newScreen, capped: Boolean = false) {
        val r = random ?: return
        // An action completed in a "fast" App clears the previously recorded dwell: no waiting inside it, and no making up old dwells when leaving
        if (tempo == AppTempo.FAST) {
            last = null
            return
        }
        val l = limitsFor(tempo)
        var base = Pace.pick(r, if (newScreen) l.newScreenMs else l.sameScreenMs)
        if (r.nextDouble() < l.pauseChance) base += Pace.pick(r, l.pauseMs)
        last = Last(app, nowMs, newScreen, base.coerceAtMost(l.maxDwellMs), l)
        if (newScreen && counts && capped && app != null) screens[app] = (screens[app] ?: 0) + 1
    }

    /**
     * How long to wait before the next action: the remaining time when the dwell after the last action is not yet
     * met, otherwise 0.
     * [textChars] is the character count on the current screen: when the last action moved to a new screen it adds
     * reading time accordingly. Always 0 for [Off].
     */
    @Synchronized
    fun waitBefore(nowMs: Long, textChars: Int = 0): Long {
        val l = last ?: return 0
        val read = if (l.newScreen) (textChars.coerceAtLeast(0) * l.limits.readMsPerChar).coerceAtMost(l.limits.maxReadMs) else 0L
        val dwell = (l.baseMs + read).coerceAtMost(l.limits.maxDwellMs)
        return (dwell - (nowMs - l.atMs)).coerceIn(0, l.limits.maxDwellMs)
    }

    /** Whether this dwell may scroll gently a few times: the last action moved to a new screen and the applicable range allows it. */
    @Synchronized
    fun idleAllowed(): Boolean = last?.let { it.newScreen && it.limits.idleScroll } == true

    /** How many times [app] has moved to a new screen in this task (since the last user answer). */
    @Synchronized
    fun screensIn(app: String): Int = screens[app] ?: 0

    /** Whether [app] has reached the cap on moves to a new screen (checked only for [capped] Apps): it must stop before switching screens again (and look again once the user agrees). Always false for ordinary Apps, Apps that performed an outward action, and [Off]. */
    @Synchronized
    fun capReached(app: String?, capped: Boolean): Boolean =
        random != null && app != null && capped && app !in outward && screenCap != null && screensIn(app) >= screenCap

    /** This task performed an outward action (sending, submitting) in [app]: no screen cap applies to this App for the rest of the time. */
    @Synchronized
    fun outwardDone(app: String) {
        if (random != null) outward += app
    }

    /** The user answered (replied to a follow-up question, took the session back): screen tallies for every App restart. */
    @Synchronized
    fun userAnswered() = screens.clear()

    private fun limitsFor(tempo: AppTempo) = if (tempo == AppTempo.CAREFUL) careful else limits

    companion object {
        /** Never waits. */
        val Off = AppPacing(null)

        /** Human-like pacing for step-by-step operation inside Apps; [random] can be replaced with a fixed seed (tests). */
        fun human(random: Random = Random.Default, limits: Limits = Limits(), careful: Limits = Limits.CAREFUL) =
            AppPacing(random, limits, careful)
    }
}
