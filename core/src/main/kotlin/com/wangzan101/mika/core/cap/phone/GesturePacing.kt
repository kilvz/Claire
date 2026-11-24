package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.DOUBLE_TAP_GAP_MS
import com.wangzan101.mika.core.screen.uistate.Pace
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Pacing for phone gestures: pause briefly before the finger lands, vary how long a tap is held, let the landing
 * point stray slightly from the element center (always still inside the element), let swipes follow a slight arc,
 * accelerate first and then decelerate, and float durations around the given value. All durations and amplitudes
 * have bounds ([Limits]), so the total duration of one action is bounded too.
 * [Instant] adds no pause, jitter, or arc and keeps durations as before (tap holds [TAP_MS], double-tap gap
 * [DOUBLE_TAP_GAP_MS], swipes travel a straight line at constant speed for the given duration).
 */
class GesturePacing(private val random: Random?, val limits: Limits = Limits()) {
    /**
     * Bounds. Durations are in milliseconds and distances in pixels.
     * [approachMs]: the pause before the finger lands; [pressMs]: holding a single tap; [doublePressMs] /
     * [doubleGapMs]: how long each press of a double tap is held and the gap between the two
     * (the system requires a 40–300 millisecond gap to recognize a double tap); [jitterFraction] / [maxJitterPx] /
     * [minJitterSpanPx]: when tapping an element the landing point strays from the center by no more than this
     * fraction of the element's width (height) and this many pixels, and a direction narrower (shorter) than
     * [minJitterSpanPx] gets no jitter; [pointJitterPx]: the jitter when tapping by coordinates;
     * [durationJitter]: the fraction by which swipe durations float up and down; [holdStretch]: a hold in place
     * (start and end almost coincide, such as a long press) is only lengthened, never shortened, and by at most this
     * fraction; [stillPx]: start and end closer than this count as a hold in place; [bowFraction] / [maxBowPx]: the
     * maximum amount the arc strays from the straight line;
     * [sampleMs]: the time interval between adjacent points as the finger moves; [maxPoints]: the most points in one
     * stroke; [dragMs]: a swipe at least this long is a slow drag that decelerates to a stop at the end and is not
     * flung on release; shorter ones are fast swipes whose release speed is [flingEndSpeed] times the average speed
     * (comparable to the release speed of the previous constant-speed swipe, so scroll distance stays the same).
     */
    data class Limits(
        val approachMs: LongRange = 30L..120L,
        val pressMs: LongRange = 50L..120L,
        val doublePressMs: LongRange = 40L..90L,
        val doubleGapMs: LongRange = 80L..140L,
        val jitterFraction: Double = 0.2,
        val maxJitterPx: Int = 12,
        val minJitterSpanPx: Int = 24,
        val pointJitterPx: Int = 3,
        val durationJitter: Double = 0.08,
        val holdStretch: Double = 0.12,
        val stillPx: Double = 3.0,
        val bowFraction: Double = 0.06,
        val maxBowPx: Double = 30.0,
        val sampleMs: LongRange = 8L..16L,
        val maxPoints: Int = 600,
        val dragMs: Long = 800L,
        val flingEndSpeed: ClosedFloatingPointRange<Double> = 0.9..1.0,
    )

    /** One point of a stroke: at [t] milliseconds after the moment the finger lands, the finger is at ([x],[y]). */
    data class Point(val x: Double, val y: Double, val t: Long)

    /** One stroke: the points from press to release (the first t is 0, the last one is the release moment and position). */
    data class Stroke(val points: List<Point>) {
        val durationMs: Long get() = points.last().t
    }

    /** Paced (not [Instant]). */
    val paced: Boolean get() = random != null

    fun approach(): Long = pick(limits.approachMs, 0L)
    fun press(): Long = pick(limits.pressMs, TAP_MS)
    fun doublePress(): Long = pick(limits.doublePressMs, TAP_MS)
    fun doubleGap(): Long = pick(limits.doubleGapMs, DOUBLE_TAP_GAP_MS)

    private fun pick(r: LongRange, instant: Long): Long = random?.let { Pace.pick(it, r) } ?: instant

    /**
     * Landing point when tapping the element ([left],[top])–([right],[bottom]) (right and bottom exclusive): near
     * the center (rounded the same way as Rect.centerX/centerY), straying no more than [Limits.jitterFraction] of the
     * width (height) and [Limits.maxJitterPx], biased toward the center; a narrow direction gets no jitter. The
     * landing point is always inside the element.
     */
    fun within(left: Int, top: Int, right: Int, bottom: Int): Pair<Int, Int> {
        val cx = (left + right) shr 1
        val cy = (top + bottom) shr 1
        val r = random ?: return cx to cy
        fun reach(span: Int) = if (span < limits.minJitterSpanPx) 0 else minOf((span * limits.jitterFraction).toInt(), limits.maxJitterPx)
        return (cx + offset(r, reach(right - left))).coerceIn(left, maxOf(left, right - 1)) to
            (cy + offset(r, reach(bottom - top))).coerceIn(top, maxOf(top, bottom - 1))
    }

    /** Landing point when tapping by coordinates: within [Limits.pointJitterPx] of the given point (less than 1dp, so it will not miss into another control); coordinates are never negative. */
    fun near(x: Int, y: Int): Pair<Int, Int> {
        val r = random ?: return x to y
        return (x + offset(r, limits.pointJitterPx)).coerceAtLeast(0) to (y + offset(r, limits.pointJitterPx)).coerceAtLeast(0)
    }

    // Sum of two uniform values (triangular distribution): more landings near the center, never beyond ±reach
    private fun offset(r: Random, reach: Int): Int =
        if (reach <= 0) 0 else ((r.nextDouble() + r.nextDouble() - 1.0) * reach).roundToInt().coerceIn(-reach, reach)

    /** Duration for gestures that move several fingers at once (which do not lay out points via [stroke]): floats around the given value by [Limits.durationJitter]. */
    fun duration(durationMs: Long): Long {
        val r = random ?: return durationMs
        return (durationMs * (1 + r.nextDouble(-limits.durationJitter, limits.durationJitter))).roundToLong().coerceAtLeast(1)
    }

    /**
     * One stroke in which a single finger travels along [path] (at least two points) for about [durationMs]. The
     * start and end points stay the same; when [bow] is true the finger takes a slight arc between two points
     * (only for two-point paths; multi-point paths are drawn by the caller and followed as given). When [bounds] is
     * given (width, height), all points are clamped inside the screen.
     * When start and end nearly coincide it is a hold in place: only the start and end points, and the duration is
     * only lengthened, never shortened (a long press still lasts long enough).
     * [Instant]: the points are exactly [path], with time distributed uniformly by path length, and the duration is
     * exactly [durationMs].
     */
    fun stroke(path: List<Pair<Double, Double>>, durationMs: Long, bow: Boolean = false, bounds: Pair<Int, Int>? = null): Stroke {
        require(path.size >= 2) { "a stroke needs at least two points" }
        val total = durationMs.coerceAtLeast(1)
        val r = random ?: return Stroke(uniform(path, total))
        val start = path.first()
        val end = path.last()
        val length = lengthOf(path)
        if (length < limits.stillPx) {
            val hold = (total * (1 + r.nextDouble(0.0, limits.holdStretch))).roundToLong()
            return Stroke(listOf(Point(start.first, start.second, 0), Point(end.first, end.second, hold)))
        }
        val t = (total * (1 + r.nextDouble(-limits.durationJitter, limits.durationJitter))).roundToLong().coerceAtLeast(1)
        val shape = if (bow && path.size == 2) {
            val c = Pace.control(start, end, Pace.bow(r, length, limits.bowFraction, limits.maxBowPx))
            (0..CURVE_SEGMENTS).map { Pace.bezier(start, c, end, it.toDouble() / CURVE_SEGMENTS) }
        } else path
        val endSpeed = if (t >= limits.dragMs) 0.0 else r.nextDouble(limits.flingEndSpeed.start, limits.flingEndSpeed.endInclusive)
        val step = Pace.pick(r, limits.sampleMs).coerceAtLeast(1)
        val n = ceil(t.toDouble() / step).toInt().coerceIn(1, limits.maxPoints - 1)
        val cum = cumulative(shape)
        val points = (0..n).map { i ->
            val tm = if (i == n) t else (t * i.toDouble() / n).roundToLong()
            val (x, y) = when (i) {
                0 -> start
                n -> end
                else -> at(shape, cum, cum.last() * progress(i.toDouble() / n, endSpeed))
            }
            Point(x, y, tm)
        }
        return Stroke(if (bounds == null) points else points.map { it.copy(x = it.x.coerceIn(0.0, bounds.first - 1.0), y = it.y.coerceIn(0.0, bounds.second - 1.0)) })
    }

    companion object {
        /** How long a tap is held when no pacing is applied. */
        const val TAP_MS = 50L

        /** The arc is split into this many small segments before points are sampled by time. */
        private const val CURVE_SEGMENTS = 32

        /** No pause, jitter, or arc. */
        val Instant = GesturePacing(null)

        /** Human-hand pacing; [random] can be replaced with a fixed seed (tests). */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = GesturePacing(random, limits)

        /**
         * Fraction of the path travelled by time [u] (0..1): a cubic Hermite curve, starting at speed 0 and ending at
         * [endSpeed] times the average speed (monotonic over 0..3).
         * With [endSpeed] 0 it is slow at both ends and fast in the middle, stopping firmly before lifting the
         * finger; with 1 the release speed equals constant speed.
         */
        internal fun progress(u: Double, endSpeed: Double): Double {
            val u2 = u * u
            val u3 = u2 * u
            return (-2 * u3 + 3 * u2) + endSpeed * (u3 - u2)
        }

        private fun lengthOf(path: List<Pair<Double, Double>>): Double = cumulative(path).last()

        private fun cumulative(path: List<Pair<Double, Double>>): DoubleArray {
            val out = DoubleArray(path.size)
            for (i in 1 until path.size) out[i] = out[i - 1] + hypot(path[i].first - path[i - 1].first, path[i].second - path[i - 1].second)
            return out
        }

        /** Position after travelling distance [s] along the polyline. */
        private fun at(path: List<Pair<Double, Double>>, cum: DoubleArray, s: Double): Pair<Double, Double> {
            var i = 1
            while (i < path.size - 1 && cum[i] < s) i++
            val seg = cum[i] - cum[i - 1]
            val f = if (seg <= 0) 1.0 else ((s - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
            val (x0, y0) = path[i - 1]
            val (x1, y1) = path[i]
            return x0 + (x1 - x0) * f to y0 + (y1 - y0) * f
        }

        /** A stroke without pacing: exactly the given points, with time distributed uniformly by distance travelled (the same as the system moving along a path at constant speed). */
        private fun uniform(path: List<Pair<Double, Double>>, total: Long): List<Point> {
            val cum = cumulative(path)
            val length = cum.last()
            return path.mapIndexed { i, (x, y) ->
                val t = when {
                    i == path.lastIndex -> total
                    length <= 0 -> 0L
                    else -> (total * cum[i] / length).roundToLong()
                }
                Point(x, y, t)
            }
        }
    }
}
