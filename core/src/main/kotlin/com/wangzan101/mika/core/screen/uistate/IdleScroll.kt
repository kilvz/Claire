package com.wangzan101.mika.core.screen.uistate

import kotlin.math.abs
import kotlin.random.Random

/**
 * Small incidental actions while pausing (pure logic, never touches the page): while waiting to turn to the next page,
 * people often scroll the current page down a little and occasionally nudge it back up a bit.
 * Done only when a pause is long enough ([Limits.minWaitMs]); takes 1–3 steps ([Limits.count]), each scrolling a small
 * fraction of the viewport height ([Limits.stepFraction]), mostly downward, occasionally ([Limits.upChance]) nudging
 * back up a little, but never back up more than was scrolled down before; the steps together stay within
 * [Limits.maxTotal] viewports and the net movement within [Limits.maxNet] viewports. The timestamps fall inside the
 * pause, leaving a margin at both ends ([Limits.leadMs], [Limits.tailMs]).
 * Only scrolls the page: no pointer movement, no hovering, no tapping.
 */
class IdleScroll(private val random: Random, val limits: Limits = Limits()) {
    /** Durations are in milliseconds, amplitudes in viewport heights. */
    data class Limits(
        val minWaitMs: Long = 3_000L,
        val count: IntRange = 1..3,
        val stepFraction: ClosedFloatingPointRange<Double> = 0.06..0.16,
        val upChance: Double = 0.25,
        val maxTotal: Double = 0.45,
        val maxNet: Double = 0.3,
        val leadMs: Long = 800L,
        val tailMs: Long = 1_200L,
        /** At least this long between two steps. */
        val gapMs: Long = 700L,
    )

    /** One step: at [atMs] after the pause began, scroll [fraction] viewports (positive is downward). */
    data class Step(val atMs: Long, val fraction: Double)

    /** Which steps to take during a pause of [waitMs]; empty when too short. */
    fun plan(waitMs: Long): List<Step> {
        val l = limits
        if (waitMs < l.minWaitMs) return emptyList()
        val room = waitMs - l.leadMs - l.tailMs
        if (room < 0) return emptyList()
        // How many steps fit in the pause (at least gapMs between two steps)
        val fits = (room / l.gapMs + 1).toInt()
        val n = random.nextInt(l.count.first, l.count.last + 1).coerceAtMost(fits).coerceAtLeast(1)
        var total = 0.0
        var net = 0.0
        val fractions = mutableListOf<Double>()
        repeat(n) { i ->
            val size = random.nextDouble(l.stepFraction.start, l.stepFraction.endInclusive)
            val up = i > 0 && net > 0 && random.nextDouble() < l.upChance
            val f = if (up) -minOf(size, net) else minOf(size, l.maxNet - net)
            if (abs(f) < MIN_STEP || total + abs(f) > l.maxTotal) return@repeat
            fractions += f
            total += abs(f)
            net += f
        }
        // Timestamps: spread uniformly over the usable span, then sorted, with two steps never closer than gapMs
        val times = MutableList(fractions.size) { l.leadMs + random.nextLong(0, room + 1) }.sorted().toMutableList()
        for (i in 1 until times.size) times[i] = maxOf(times[i], times[i - 1] + l.gapMs)
        return fractions.indices.mapNotNull { i -> times[i].takeIf { it <= waitMs - l.tailMs }?.let { Step(it, fractions[i]) } }
    }

    private companion object {
        /** Steps smaller than this are skipped (scrolling that little is not visible). */
        const val MIN_STEP = 0.02
    }
}
