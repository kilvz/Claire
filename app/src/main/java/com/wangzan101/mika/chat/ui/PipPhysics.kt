package com.wangzan101.mika.chat.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * The gesture physics of the picture-in-picture window, in pixels and seconds, independent of the UI so it can be tested.
 * On release it projects the landing point from the velocity (like a list's inertial scroll decelerating to a stop), then picks the nearest corner;
 * snapping home uses a critically damped spring (damping ratio 1, response 0.4 s): no bounce back, it can carry on from the current velocity, and it can be interrupted at any time.
 */
object PipPhysics {
    enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

    /**
     * The area the window can rest in: [width]×[height] is the whole screen, [pipWidth]×[pipHeight] is the window's current size;
     * [top]/[bottom] are the heights of the top bar and input field to keep clear of, and [margin] is the distance from the edges.
     */
    data class Bounds(
        val width: Float,
        val height: Float,
        val pipWidth: Float,
        val pipHeight: Float,
        val margin: Float,
        val top: Float,
        val bottom: Float,
    ) {
        /** When there is not enough room (the window is too narrow or too short, e.g. when the keyboard pops up), hug the top-left rather than slide under the top bar. */
        fun position(c: Corner): Pair<Float, Float> {
            val left = margin
            val right = maxOf(left, width - pipWidth - margin)
            val upper = top + margin
            val lower = maxOf(upper, height - pipHeight - bottom - margin)
            return when (c) {
                Corner.TopStart -> left to upper
                Corner.TopEnd -> right to upper
                Corner.BottomStart -> left to lower
                Corner.BottomEnd -> right to lower
            }
        }
    }

    private const val DECELERATION = 0.998f

    /**
     * How far the window should keep from the top and bottom edges of the screen. [collapsedShare] of 1 is the collapsed window: keep clear of the top bar and input bar ([collapsedTop]/[collapsedBottom]);
     * 0 is the expanded large window: keep clear only of the system bars ([safeTop]/[safeBottom]) — by then the whole background is dimmed, so the top bar and input bar could not be tapped anyway,
     * and the window can cover the whole screen instead of moving only within a small patch in the middle. In between it interpolates proportionally, so it glides along as the window expands and collapses.
     */
    fun restingInsets(
        collapsedTop: Float, collapsedBottom: Float,
        safeTop: Float, safeBottom: Float, collapsedShare: Float,
    ): Pair<Float, Float> {
        val s = collapsedShare.coerceIn(0f, 1f)
        fun at(safe: Float, collapsed: Float) = safe + (collapsed - safe) * s
        return at(safeTop, collapsedTop) to at(safeBottom, collapsedBottom)
    }

    /** How far it will still slide after being flung at [velocity] (pixels/second), decelerating to 0.998 of the speed per millisecond. */
    fun project(velocity: Float): Float = (velocity / 1000f) * DECELERATION / (1f - DECELERATION)

    /** The corner it should snap to after being released from ([x],[y]) with velocity ([vx],[vy]). */
    fun snapCorner(x: Float, y: Float, vx: Float, vy: Float, bounds: Bounds): Corner {
        val px = x + project(vx)
        val py = y + project(vy)
        return Corner.entries.minBy { c ->
            val (cx, cy) = bounds.position(c)
            hypot(cx - px, cy - py)
        }
    }

    /** The state of a one-dimensional spring: position and velocity. */
    data class Motion(val position: Float, val velocity: Float)

    class Spring(response: Float = 0.4f, dampingRatio: Float = 1f) {
        private val stiffness = (2 * PI / response).pow(2).toFloat()
        private val damping = (4 * PI * dampingRatio / response).toFloat()

        /** Advance [dt] seconds (semi-implicit Euler; a single frame is capped at 32 milliseconds, so dropped frames do not make it diverge). */
        fun step(m: Motion, target: Float, dt: Float): Motion {
            val t = dt.coerceIn(0f, MAX_DT)
            val v = m.velocity + (-stiffness * (m.position - target) - damping * m.velocity) * t
            return Motion(m.position + v * t, v)
        }

        fun settled(m: Motion, target: Float) = abs(m.position - target) < REST_DISTANCE && abs(m.velocity) < REST_VELOCITY
    }

    private const val MAX_DT = 0.032f
    private const val REST_DISTANCE = 0.5f
    private const val REST_VELOCITY = 5f
}
