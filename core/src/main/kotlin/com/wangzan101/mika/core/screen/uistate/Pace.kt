package com.wangzan101.mika.core.screen.uistate

import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * Pacing primitives shared by the web mouse ([com.wangzan101.mika.core.cap.web.cdp.InputPacing]) and phone gestures
 * ([com.wangzan101.mika.core.cap.phone.GesturePacing]): bounded random sampling within a range, a slightly bowed
 * quadratic Bezier, and an ease that is slow at both ends and fast in the middle.
 */
object Pace {
    /** Pick a random value from [r] (both ends inclusive); when the range holds a single value, that value. */
    fun pick(random: Random, r: LongRange): Long = if (r.first >= r.last) r.first else random.nextLong(r.first, r.last + 1)

    /** Pick a random integer from [r] (both ends inclusive). */
    fun pick(random: Random, r: IntRange): Int = if (r.first >= r.last) r.first else random.nextInt(r.first, r.last + 1)

    /** How far the arc bows away from the straight line: at most [fraction] of the distance and at most [maxPx]; the side is random (signed). */
    fun bow(random: Random, dist: Double, fraction: Double, maxPx: Double): Double =
        min(dist * fraction, maxPx) * random.nextDouble(-1.0, 1.0)

    /** Control point of the quadratic Bezier: the midpoint of the [from]–[to] segment offset along the normal by [bow]. When the two points coincide, it is the midpoint. */
    fun control(from: Pair<Double, Double>, to: Pair<Double, Double>, bow: Double): Pair<Double, Double> {
        val (x0, y0) = from
        val (x1, y1) = to
        val dist = hypot(x1 - x0, y1 - y0)
        if (dist == 0.0) return (x0 + x1) / 2 to (y0 + y1) / 2
        return (x0 + x1) / 2 - (y1 - y0) / dist * bow to (y0 + y1) / 2 + (x1 - x0) / dist * bow
    }

    /** Point on the quadratic Bezier at [t] (0..1). */
    fun bezier(from: Pair<Double, Double>, control: Pair<Double, Double>, to: Pair<Double, Double>, t: Double): Pair<Double, Double> {
        val u = 1 - t
        return (u * u * from.first + 2 * u * t * control.first + t * t * to.first) to
            (u * u * from.second + 2 * u * t * control.second + t * t * to.second)
    }

    /** Slow at both ends, fast in the middle (smoothstep): 0→0, 1→1, zero velocity at both ends. */
    fun ease(t: Double) = t * t * (3 - 2 * t)
}
