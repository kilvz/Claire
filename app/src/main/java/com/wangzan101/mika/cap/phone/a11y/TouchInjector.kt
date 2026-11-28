package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.WEB_VIEW
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.EventClock
import com.wangzan101.mika.core.cap.phone.GesturePacing
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Low-level touch: accessibility clicks and gestures, with touch injection by the built-in helper when those fail. All extensions on [AndroidScreen], same package.

internal fun AndroidScreen.isClickableTarget(node: AccessibilityNodeInfo): Boolean =
    node.isEnabled && (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })

/** When a coordinate tap has no effect, find the smallest clickable control at that point and click it via accessibility instead; if it hits an outward/irreversible button or a large container, do not tap again. */
internal fun AndroidScreen.clickNodeAt(x: Int, y: Int, clock: EventClock): Boolean {
    if (!hasDisplay || handedOver() || textInput.imeBounds()?.contains(x, y) == true) return false
    val window = ScreenReader.contentWindows(windows()).firstOrNull {
        Rect().also(it::getBoundsInScreen).contains(x, y)
    }?.takeIf { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return false
    val entries = ScreenReader.read(listOf(window), screenWidth, screenHeight).entries
    val rect = Rect()
    val hitEntries = entries.filter { entry ->
        entry.node.getBoundsInScreen(rect)
        rect.intersect(0, 0, screenWidth, screenHeight) && rect.contains(x, y)
    }
    if (hitEntries.isEmpty()) return false
    if (hitEntries.any { ClickFallback.mayBeOutward(it.element.name, it.locator.resourceId, clickable = it.element.clickable) }) return false
    val seen = HashSet<AccessibilityNodeInfo>()
    val candidates = mutableListOf<ClickFallback.Candidate<AccessibilityNodeInfo>>()
    for (entry in hitEntries) {
        val target = ancestorOrSelf(entry.node) { isClickableTarget(it) } ?: continue
        if (!seen.add(target) || !target.isVisibleToUser) continue
        target.getBoundsInScreen(rect)
        if (!rect.intersect(0, 0, screenWidth, screenHeight) || rect.isEmpty) continue
        candidates += ClickFallback.Candidate(
            item = target,
            left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom,
            label = ScreenReader.labelOf(target).ifBlank { entry.element.name.ifBlank { null } },
            resourceId = target.viewIdResourceName ?: entry.locator.resourceId,
            scrollable = target.isScrollable,
            webView = target.className == WEB_VIEW,
        )
    }
    val picked = ClickFallback.pickTarget(x, y, screenWidth, screenHeight, candidates) ?: return false
    clock.mark()
    return picked.performAction(AccessibilityNodeInfo.ACTION_CLICK)
}

/** Prefer clicking the node (or its nearest clickable ancestor); otherwise gesture at its center, and finally inject a touch. */
internal suspend fun AndroidScreen.click(node: AccessibilityNodeInfo): Boolean {
    val target = ancestorOrSelf(node) { isClickableTarget(it) }
    if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
    val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
    return touch(pacing.within(rect.left, rect.top, rect.right, rect.bottom))
}

internal suspend fun AndroidScreen.tapAt(x: Int, y: Int): Boolean = touch(pacing.near(x, y))

/** Tap at this point: pause first, then land the finger and hold for a short random time. When the accessibility gesture fails, the built-in helper injects a touch. */
internal suspend fun AndroidScreen.touch(at: Pair<Int, Int>): Boolean {
    val (x, y) = at
    delay(pacing.approach())
    val press = pacing.press()
    if (a11y.gesture(displayId, Path().apply { moveTo(x.toFloat(), y.toFloat()) }, press)) return true
    if (pacing.paced && injectStroke(listOf(GesturePacing.Point(x.toDouble(), y.toDouble(), 0), GesturePacing.Point(x.toDouble(), y.toDouble(), press)))) return true
    return shell.injectTap(displayId, x, y)
}

/**
 * One finger slides along [points]. With pacing, schedule each point's time via [GesturePacing.stroke] and let the built-in helper inject them point by point (the app sees the real fast-then-slow speed);
 * when the helper cannot inject, fall back to an accessibility gesture along the same arc (the system moves at constant speed, just without the pacing). Without pacing, as before: an accessibility gesture follows the polyline at constant speed for the given duration.
 */
internal suspend fun AndroidScreen.drag(points: List<Pair<Int, Int>>, durationMs: Long, bow: Boolean): Boolean {
    if (!pacing.paced) return a11y.gesture(displayId, pathOf(points), durationMs)
    delay(pacing.approach())
    val stroke = pacing.stroke(points.map { it.first.toDouble() to it.second.toDouble() }, durationMs, bow, screenWidth to screenHeight)
    if (injectStroke(stroke.points)) return true
    return a11y.gesture(displayId, pathOf(stroke.points.map { it.x.roundToInt() to it.y.roundToInt() }), stroke.durationMs)
}

internal fun AndroidScreen.injectStroke(points: List<GesturePacing.Point>): Boolean = runCatching {
    shell.injectStroke(
        displayId,
        IntArray(points.size) { points[it].x.roundToInt() },
        IntArray(points.size) { points[it].y.roundToInt() },
        LongArray(points.size) { points[it].t },
    )
}.getOrDefault(false)

internal fun AndroidScreen.pathOf(points: List<Pair<Int, Int>>) = Path().apply {
    moveTo(points.first().first.toFloat(), points.first().second.toFloat())
    points.drop(1).forEach { lineTo(it.first.toFloat(), it.second.toFloat()) }
}
