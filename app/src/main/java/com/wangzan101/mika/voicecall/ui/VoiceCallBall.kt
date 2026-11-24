package com.wangzan101.mika.voicecall.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Shapes of the call ball: connecting = web, idle = globe (quiet and slow-turning), listening/speaking = wave (following the real level), task running = orbits. */
enum class VoiceCallShape { Web, Globe, Wave, Orbits }

/** Default dot colors: cyan-white up close, deep blue far away, interpolated by projection depth (the call page passes per-theme colors). */
private val NearColor = Color(0xFF8EFAFF)
private val FarColor = Color(0xFF123A72)

/** Camera distance (a multiple of the unit sphere radius): the strength of the perspective (near things larger, far things smaller). */
private const val CAMERA = 3.4f

private const val DEFAULT_DOT_RADIUS = 0.023f

/** Above this level the ball switches from the globe to the wave. */
const val VOICE_WAVE_LEVEL = 0.04f

/**
 * Self-drawn 3D dotted sphere: a Fibonacci sphere point cloud slowly rotating around the Y axis with a slight X tilt, perspective projection (near larger, far smaller),
 * and depth shading (cyan-white near → deep blue far). The shape cross-fades between the four based on [shape]: [level] is the real level driving the wave
 * (0..1, taking the larger of listening/speaking). All the math is hand-written, no external libraries; the size is given by the caller (parameterized in dp).
 * Frames stop outside the lifecycle: it follows [Lifecycle.State.STARTED] and never draws when invisible; with system animations off it is static,
 * only rising and falling with the level (no self-animation).
 */
@Composable
fun VoiceCallBall(
    shape: VoiceCallShape,
    level: Float,
    modifier: Modifier = Modifier,
    dotCount: Int = 340,
    /** Radius of a dot (relative to the sphere radius); small balls need larger dots to stay legible. */
    dotRadius: Float = DEFAULT_DOT_RADIUS,
    near: Color = NearColor,
    far: Color = FarColor,
) {
    val reduceMotion = MikaTheme.reduceMotion
    val ball = remember(dotCount) { DotBall(dotCount) }
    var timeNanos by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                withFrameNanos { timeNanos = it }
            }
        }
    }
    Canvas(modifier) {
        ball.draw(this, shape, level, if (reduceMotion) 0f else timeNanos / 1e9f, reduceMotion, dotRadius, near, far)
    }
}

/** One dot: the resting position on the sphere, its initial azimuth, the orbit it belongs to, and its orbit phase. */
private class Dot(val x: Float, val y: Float, val z: Float, val theta: Float, val orbit: Int, val orbitAngle: Float)

/** The point cloud and the per-frame projection math; geometry constants are initialized once, and frames only do math with no allocation. */
private class DotBall(count: Int) {
    private val dots: Array<Dot>
    private val webPairs: IntArray
    private val sx: FloatArray
    private val sy: FloatArray
    private val sr: FloatArray
    private val sd: FloatArray
    private val sz: FloatArray
    private val order: IntArray
    private val weights = FloatArray(VoiceCallShape.entries.size)
    private var lastT = -1f
    private var smoothLevel = 0f

    init {
        val golden = PI.toFloat() * (3f - sqrt(5f))
        dots = Array(count) { i ->
            val y = 1f - 2f * (i + 0.5f) / count
            val r = sqrt(1f - y * y)
            val theta = golden * i
            Dot(
                x = r * cos(theta), y = y, z = r * sin(theta), theta = theta,
                orbit = i % ORBIT_COUNT, orbitAngle = (i / ORBIT_COUNT) * (2f * PI.toFloat()) / (count / ORBIT_COUNT),
            )
        }
        // Web edges: pairs of points on the sphere whose chord length is below the threshold, computed once at init
        val pairs = ArrayList<Int>(count * 3)
        for (i in dots.indices) for (j in i + 1 until dots.size) {
            val dx = dots[i].x - dots[j].x
            val dy = dots[i].y - dots[j].y
            val dz = dots[i].z - dots[j].z
            if (dx * dx + dy * dy + dz * dz < WEB_CHORD2) {
                pairs.add(i)
                pairs.add(j)
            }
        }
        webPairs = pairs.toIntArray()
        sx = FloatArray(count)
        sy = FloatArray(count)
        sr = FloatArray(count)
        sd = FloatArray(count)
        sz = FloatArray(count)
        order = IntArray(count) { it }
    }

    /** Tilt and speed of the orbit planes: three tilted orbits with different directions and speeds. */
    private val orbitTiltX = floatArrayOf(0.5f, -0.9f, 1.9f)
    private val orbitTiltY = floatArrayOf(0f, 0.7f, -0.5f)
    private val orbitSpeed = floatArrayOf(0.9f, -0.7f, 1.15f)

    fun draw(
        scope: androidx.compose.ui.graphics.drawscope.DrawScope,
        shape: VoiceCallShape,
        level: Float,
        t: Float,
        static: Boolean,
        dotRadius: Float,
        near: Color,
        far: Color,
    ) {
        val radius = scope.size.minDimension / 2f
        val cx = scope.size.width / 2f
        val cy = scope.size.height / 2f
        val dt = if (lastT < 0f || static) 0f else (t - lastT).coerceIn(0f, 0.05f)
        // Shape weights cross-fade; when static they go straight to the target
        for (k in weights.indices) {
            val target = if (k == shape.ordinal) 1f else 0f
            weights[k] = when {
                lastT < 0f || static -> target
                else -> weights[k] + (target - weights[k]) * (dt * 8f).coerceAtMost(1f)
            }
        }
        lastT = t
        smoothLevel = if (static) level else smoothLevel + (level - smoothLevel) * (dt * 18f).coerceAtMost(1f)

        val angleY = t * 0.45f
        val cosY = cos(angleY)
        val sinY = sin(angleY)
        val cosT = cos(TILT_X)
        val sinT = sin(TILT_X)
        val w = weights
        // The idle globe breathes quietly (very slightly), so it feels alive without being busy
        val breath = 1f + 0.02f * w[1] * sin(t * 0.7f)
        val n = dots.size
        for (i in 0 until n) {
            val d = dots[i]
            // Web/globe share the whole sphere (the globe turns slowly and quietly); the wave rises and falls along the radius with the level; orbits run along the tilted tracks
            val wave = 1f + WAVE_AMP * smoothLevel * (0.35f + 0.65f * sin(3f * d.theta + t * 3.5f) * sin(2.5f * d.y + t * 2f))
            val orbitAngle = d.orbitAngle + t * orbitSpeed[d.orbit]
            var ox = cos(orbitAngle)
            var oy = 0f
            var oz = sin(orbitAngle)
            // Orbit plane: X tilt then Y tilt
            var ty = oy * cos(orbitTiltX[d.orbit]) - oz * sin(orbitTiltX[d.orbit])
            var tz = oy * sin(orbitTiltX[d.orbit]) + oz * cos(orbitTiltX[d.orbit])
            oy = ty
            oz = tz
            val tx = ox * cos(orbitTiltY[d.orbit]) + oz * sin(orbitTiltY[d.orbit])
            tz = -ox * sin(orbitTiltY[d.orbit]) + oz * cos(orbitTiltY[d.orbit])
            ox = tx
            oz = tz

            val bx = (w[0] + w[1]) * d.x + w[2] * d.x * wave + w[3] * ox
            val by = (w[0] + w[1]) * d.y + w[2] * d.y * wave + w[3] * oy
            val bz = (w[0] + w[1]) * d.z + w[2] * d.z * wave + w[3] * oz
            // Overall slow rotation around Y + slight X tilt
            val rx = bx * cosY + bz * sinY
            val rz1 = -bx * sinY + bz * cosY
            val ry = by * cosT - rz1 * sinT
            val rz = by * sinT + rz1 * cosT
            val persp = CAMERA / (CAMERA - rz)
            sx[i] = cx + rx * radius * persp * BALL_SCALE * breath
            sy[i] = cy + ry * radius * persp * BALL_SCALE * breath
            sr[i] = radius * dotRadius * persp
            sd[i] = ((rz + 1.3f) / 2.6f).coerceIn(0f, 1f)
            sz[i] = rz
        }
        // Draw far ones first and let near ones cover them; the order barely changes between frames, so insertion sort is nearly O(n)
        for (i in 1 until n) {
            val v = order[i]
            val vz = sz[v]
            var j = i - 1
            while (j >= 0 && sz[order[j]] > vz) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = v
        }
        // The web's connecting lines go underneath the dots
        val webW = w[0]
        if (webW > 0.01f) {
            for (p in 0 until webPairs.size / 2) {
                val a = webPairs[2 * p]
                val b = webPairs[2 * p + 1]
                val depth = (sd[a] + sd[b]) / 2f
                scope.drawLine(
                    lerp(far, near, depth),
                    androidx.compose.ui.geometry.Offset(sx[a], sy[a]),
                    androidx.compose.ui.geometry.Offset(sx[b], sy[b]),
                    strokeWidth = radius * 0.006f,
                    alpha = webW * (0.05f + 0.3f * depth),
                )
            }
        }
        for (k in 0 until n) {
            val i = order[k]
            val depth = sd[i]
            var alpha = 0.38f + 0.62f * depth
            if (webW > 0.01f) alpha *= 1f - webW * (0.3f + 0.3f * sin(t * 2.4f - dots[i].y * 5f))
            scope.drawCircle(lerp(far, near, depth), sr[i], androidx.compose.ui.geometry.Offset(sx[i], sy[i]), alpha = alpha)
        }
    }

    private companion object {
        const val ORBIT_COUNT = 3
        const val WEB_CHORD2 = 0.09f
        const val WAVE_AMP = 0.26f
        const val TILT_X = 0.32f
        const val BALL_SCALE = 0.82f
    }
}
