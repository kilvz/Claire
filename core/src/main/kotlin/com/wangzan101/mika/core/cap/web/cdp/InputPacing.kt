package com.wangzan101.mika.core.cap.web.cdp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import com.wangzan101.mika.core.screen.uistate.Pace
import kotlin.random.Random

/**
 * Pacing of DevTools mouse and keyboard input: the mouse travels along a small arc before pressing, holds the button briefly before releasing, keys have varying gaps between them, and the wheel turns notch by notch.
 * All durations and magnitudes have upper and lower bounds ([Limits]), so the total duration of one action is bounded. [Instant] adds no pauses and no intermediate points
 * (the event sequence is the same as without pacing; tests and callers that need no pacing use it).
 */
class InputPacing(private val random: Random?, val limits: Limits = Limits()) {
    /**
     * Upper and lower bounds. Durations are in milliseconds, distances in CSS pixels.
     * [approachPx]: when the pointer's position is unknown, how far from the target it comes in from; [bowFraction] / [maxBowPx]: the maximum deviation of the path from a straight line (relative distance / absolute);
     * [maxKeyedChars]: text longer than this is handed to the input as a whole (like a paste) instead of being typed key by key.
     */
    data class Limits(
        val moveSteps: IntRange = 4..9,
        val moveStepMs: LongRange = 8L..22L,
        val approachPx: IntRange = 60..180,
        val bowFraction: Double = 0.12,
        val maxBowPx: Double = 40.0,
        val dwellMs: LongRange = 40L..120L,
        val pressMs: LongRange = 50L..120L,
        val keyHoldMs: LongRange = 25L..70L,
        val keyGapMs: LongRange = 35L..120L,
        val modifierGapMs: LongRange = 15L..45L,
        val wheelNotchPx: Double = 53.0,
        val maxNotches: Int = 12,
        val wheelGapMs: LongRange = 25L..70L,
        val maxKeyedChars: Int = 30,
    )

    /** Paced (not [Instant]). */
    val paced: Boolean get() = random != null

    fun dwell(): Long = pick(limits.dwellMs)
    fun press(): Long = pick(limits.pressMs)
    fun keyHold(): Long = pick(limits.keyHoldMs)
    fun keyGap(): Long = pick(limits.keyGapMs)
    fun modifierGap(): Long = pick(limits.modifierGapMs)
    fun moveStep(): Long = pick(limits.moveStepMs)
    fun wheelGap(): Long = pick(limits.wheelGapMs)

    private fun pick(r: LongRange): Long = random?.let { Pace.pick(it, r) } ?: 0L

    /** Starting point when the pointer's position is unknown: somewhere around the target, [Limits.approachPx] away (coordinates not below 0). With [Instant] it is the target itself. */
    fun origin(x: Double, y: Double): Pair<Double, Double> {
        val r = random ?: return x to y
        val dist = Pace.pick(r, limits.approachPx).toDouble()
        val angle = r.nextDouble(0.0, 2 * PI)
        return (x + dist * cos(angle)).coerceAtLeast(0.0) to (y + dist * sin(angle)).coerceAtLeast(0.0)
    }

    /**
     * Intermediate points from [from] to [to] (excluding the start, with the last one exactly the destination): a quadratic Bezier arc, slow at both ends and fast in the middle.
     * With [Instant], or when the distance is under 3 pixels, only the destination.
     */
    fun path(from: Pair<Double, Double>, to: Pair<Double, Double>): List<Pair<Double, Double>> {
        val r = random ?: return listOf(to)
        val (x0, y0) = from
        val (x1, y1) = to
        val dist = hypot(x1 - x0, y1 - y0)
        if (dist < 3.0) return listOf(to)
        val n = Pace.pick(r, limits.moveSteps)
        val c = Pace.control(from, to, Pace.bow(r, dist, limits.bowFraction, limits.maxBowPx))
        return (1..n).map { i -> if (i == n) to else Pace.bezier(from, c, to, Pace.ease(i.toDouble() / n)) }
    }

    /**
     * Splits the scroll distance [distance] (positive is downward) into wheel increments that add up to exactly [distance]: [Limits.wheelNotchPx] per notch,
     * with the last notch taking the remainder; when there would be more than [Limits.maxNotches] notches, it switches to [Limits.maxNotches] equal notches (each larger, so the total duration stays bounded).
     * With [Instant] it scrolls in one go. 0 yields an empty list.
     */
    fun notches(distance: Double): List<Double> {
        if (distance == 0.0) return emptyList()
        if (random == null) return listOf(distance)
        val sign = if (distance < 0) -1.0 else 1.0
        val total = abs(distance)
        val count = ceil(total / limits.wheelNotchPx - 1e-9).toInt().coerceAtLeast(1)
        if (count > limits.maxNotches) return List(limits.maxNotches) { sign * total / limits.maxNotches }
        return List(count) { i -> sign * if (i < count - 1) limits.wheelNotchPx else total - limits.wheelNotchPx * (count - 1) }
    }

    companion object {
        /** No pauses, no intermediate points. */
        val Instant = InputPacing(null)

        /** Human-hand pacing; [random] can be swapped for a fixed seed (tests). */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = InputPacing(random, limits)
    }
}
