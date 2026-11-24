package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.net.UrlHost
import kotlin.random.Random

/**
 * Spacing between outward actions (pure logic; it touches neither the clock nor the UI): sends and submissions (contacting someone, filing an application), deletions, and orders identified by irreversible detection,
 * when done back to back on the same site or app, are separated by a random interval between two of them, as a human handling things one by one would, instead of tapping in quick succession.
 * This applies whether or not a page change happens in between: the dwell on a page change is governed separately by the web pacing. Phone and web share one set because the detection is the same.
 *
 * - The first one on a site (app) does not wait;
 * - afterwards every one is at least a randomly picked interval from [Limits.firstGapMs] after the previous one; the more are done in this window, the more both bounds grow ([Limits.perActionMs]),
 *   never exceeding [Limits.maxGapMs];
 * - the interval counts from the previous completion, so time spent reading the page or thinking about the next step in between is included and only the remainder is waited out;
 * - web pages are identified by registrable domain (www.example.com and m.example.com are the same), phones by package name; other sites and apps are counted separately.
 *
 * [Off] never waits (used by tests and by callers that need no pacing).
 */
class OutwardPacing(private val random: Random?, val limits: Limits = Limits()) {
    /** Durations are in milliseconds. */
    data class Limits(
        /** The interval range between the second action and the first. */
        val firstGapMs: LongRange = 8_000L..20_000L,
        /** How much the lower and upper bounds each grow per further action (lower, upper). */
        val perActionMs: Pair<Long, Long> = 500L to 1_000L,
        /** The upper bound of the interval. */
        val maxGapMs: Long = 30_000L,
        /** The time window used to count "how many were done recently". */
        val windowMs: Long = 30 * 60_000L,
    )

    private class Done(val site: String, val atMs: Long, val gapMs: Long)

    private val done = ArrayDeque<Done>()

    /** Pacing is enabled (not [Off]). */
    val paced: Boolean get() = random != null

    /** How much longer to wait before the next outward action on [site]: the remaining time when the interval after the previous one has not elapsed yet, otherwise 0. */
    @Synchronized
    fun waitBefore(site: String, nowMs: Long): Long {
        if (random == null) return 0
        prune(nowMs)
        val last = done.lastOrNull { it.site == site } ?: return 0
        return (last.gapMs - (nowMs - last.atMs)).coerceIn(0, limits.maxGapMs)
    }

    /** An outward action was done on [site]: decide from how many were done in this window how long at least must pass until the next one. */
    @Synchronized
    fun noteDone(site: String, nowMs: Long) {
        if (random == null) return
        prune(nowMs)
        val count = done.count { it.site == site } + 1
        done.addLast(Done(site, nowMs, gapAfter(count)))
    }

    /** The interval after [n] actions (n ≥ 1) until the next one. */
    private fun gapAfter(n: Int): Long {
        val r = random ?: return 0
        val extra = (n - 1).toLong()
        val lo = (limits.firstGapMs.first + extra * limits.perActionMs.first).coerceAtMost(limits.maxGapMs)
        val hi = (limits.firstGapMs.last + extra * limits.perActionMs.second).coerceAtMost(limits.maxGapMs)
        return if (lo >= hi) hi else r.nextLong(lo, hi + 1)
    }

    private fun prune(nowMs: Long) {
        while (done.isNotEmpty() && nowMs - done.first().atMs > limits.windowMs) done.removeFirst()
    }

    companion object {
        /** Never waits. */
        val Off = OutwardPacing(null)

        /** Pacing like a human handling things one by one; [random] can be swapped for a fixed seed (tests). */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = OutwardPacing(random, limits)

        /** Which site or app a screen belongs to: for a web page ([web] is true) the registrable domain, for a phone the package name. */
        fun siteOf(packageName: String?, web: Boolean): String? {
            val p = packageName?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            return if (web) UrlHost.registrable(p) ?: p else p
        }
    }
}
