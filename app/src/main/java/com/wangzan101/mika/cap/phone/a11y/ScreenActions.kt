package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.BackAffordance
import com.wangzan101.mika.core.screen.uistate.DEFAULT_SWIPE_MS
import com.wangzan101.mika.core.screen.uistate.SystemCommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Actions such as tap / swipe / scroll / back: dispatch touches, clicks, scrolls and key presses, and verify the
 * result.
 * Fields and methods shared with [AndroidScreen] are accessed through [screen] as a forward reference.
 */
internal class ScreenActions(private val screen: AndroidScreen) {

    suspend fun systemCommand(command: String): SystemCommandResult? = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext null
        val result = screen.shell.runSystemCommand(screen.displayId, command)
        SystemCommandResult(result.getInt("exitCode", -1), result.getString("output").orEmpty())
    }

    // ---------- Actions ----------

    suspend fun tapRef(ref: String, snapshotId: String): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        val (node, relocatedTo) = screen.resolve(ref, snapshotId) ?: return@withContext ActionResult.Failed("ref_expired")
        val target = screen.ancestorOrSelf(node) { screen.isClickableTarget(it) }
        val rect = Rect().also(node::getBoundsInScreen)
        val touchFallback: suspend (AndroidScreen.EventClock) -> Boolean = { clock ->
            clock.mark()
            screen.touch(screen.pacing.within(rect.left, rect.top, rect.right, rect.bottom))
        }
        if (target == null) return@withContext screen.verified(relocatedTo = relocatedTo) {
            screen.touch(screen.pacing.within(rect.left, rect.top, rect.right, rect.bottom))
        }
        val outward = ClickFallback.mayBeOutward(ScreenReader.labelOf(node), node.viewIdResourceName) ||
            ClickFallback.mayBeOutward(ScreenReader.labelOf(target), target.viewIdResourceName)
        screen.verified(relocatedTo = relocatedTo, fallback = touchFallback, retryWhenUnchanged = !outward) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    suspend fun tapXY(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        screen.verified(fallback = { clock -> screen.clickNodeAt(x, y, clock) }) { screen.tapAt(x, y) }
    }

    suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        val target = screen.resolve(ref, snapshotId) ?: return@withContext ActionResult.Failed("ref_expired")
        val rect = android.graphics.Rect().also { target.first.getBoundsInScreen(it) }
        screen.verified(relocatedTo = target.second) { doubleTapAt(screen.pacing.within(rect.left, rect.top, rect.right, rect.bottom)) }
    }

    suspend fun doubleTapXY(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        screen.verified { doubleTapAt(screen.pacing.near(x, y)) }
    }

    // A double tap must be two strokes within one gesture: dispatched separately, the callback delay in between
    // may exceed the system's double-tap time limit
    internal suspend fun doubleTapAt(at: Pair<Int, Int>): Boolean {
        delay(screen.pacing.approach())
        return screen.a11y.taps(screen.displayId, at.first.toFloat(), at.second.toFloat(), 2, screen.pacing.doublePress(), screen.pacing.doubleGap())
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = DEFAULT_SWIPE_MS): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        screen.verified { screen.drag(listOf(x1 to y1, x2 to y2), durationMs, bow = true) }
    }

    suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        if (paths.isEmpty() || paths.size > 2 || paths.any { it.size < 2 || it.size > 32 }) return@withContext ActionResult.Failed("invalid_gesture")
        screen.verified {
            // One finger follows the prepared path, with pacing shaping the speed; several fingers moving
            // together only get their duration varied a little
            if (paths.size == 1) return@verified screen.drag(paths.single(), durationMs, bow = false)
            delay(screen.pacing.approach())
            screen.a11y.gesture(screen.displayId, paths.map(screen::pathOf), screen.pacing.duration(durationMs))
        }
    }

    suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        var relocatedTo: String? = null
        val start = if (ref != null && snapshotId != null) {
            val target = screen.resolve(ref, snapshotId) ?: return@withContext ActionResult.Failed("ref_expired")
            relocatedTo = target.second
            target.first
        } else {
            screen.read().entries.firstOrNull { it.element.scrollable }?.node
        }
        val node = start?.let { screen.ancestorOrSelf(it) { n -> n.isScrollable } }
        if (node == null) {
            // When there is no scrollable view, fall back to a swipe in the middle of the screen
            val cx = screen.screenWidth / 2
            val (from, to) = if (down) screen.screenHeight * 3 / 4 to screen.screenHeight / 4 else screen.screenHeight / 4 to screen.screenHeight * 3 / 4
            val r = swipe(cx, from, cx, to)
            return@withContext if (r is ActionResult.Ok && relocatedTo != null) r.copy(relocated = true, relocatedTo = relocatedTo) else r
        }
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        screen.verified(relocatedTo = relocatedTo) { node.performAction(action) }
    }

    /**
     * While idling, scroll gently: slowly swipe [fraction] of the screen height down the middle of the scrollable
     * content that takes up most of the screen (a feed, an article), then settle before lifting the finger, without a
     * fling.
     * Does not scroll while the input method is up, an input field has focus, another window covers it (a dialog, an
     * overlay), or there is no scrollable content large enough.
     */
    suspend fun idleScroll(fraction: Double): Boolean = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay || screen.textInput.imeBox() != null || screen.focusedEditable() != null) return@withContext false
        if (ScreenReader.contentWindows(screen.windows()).count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } > 1) return@withContext false
        val area = screen.read().entries.filter { it.element.scrollable }
            .map { e -> android.graphics.Rect().also { e.node.getBoundsInScreen(it) } }
            .maxByOrNull { it.width().toLong() * it.height() } ?: return@withContext false
        if (area.width().toLong() * area.height() < screen.screenWidth.toLong() * screen.screenHeight * AndroidScreen.IDLE_MIN_AREA) return@withContext false
        val dist = (screen.screenHeight * kotlin.math.abs(fraction)).roundToInt().coerceAtMost(area.height() / 2)
        if (dist < AndroidScreen.IDLE_MIN_PX) return@withContext false
        val cx = area.centerX()
        val mid = area.centerY()
        // Looking further down (positive fraction) means moving the finger up
        val (from, to) = if (fraction > 0) mid + dist / 2 to mid - dist / 2 else mid - dist / 2 to mid + dist / 2
        screen.drag(listOf(cx to from, cx to to), AndroidScreen.IDLE_SCROLL_MS, bow = true)
    }

    suspend fun back(): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        // Key injection → tap a back control on screen. Degrade step by step, and do not treat a single point
        // failure on some device as overall unavailability.
        // No longer fall back to performGlobalAction(GLOBAL_ACTION_BACK): it acts on the display the service
        // currently focuses, so when the focus is on the main screen it would back the main screen out one
        // level (#789).
        screen.verified(fallback = { clock -> tapBackControl(clock) }) {
            screen.shell.injectKey(screen.displayId, KeyEvent.KEYCODE_BACK)
        }
    }

    /**
     * When the back injection had no effect, tap the visible back control on screen. Returns false when no back
     * control is recognized; never taps blindly.
     */
    internal suspend fun tapBackControl(clock: AndroidScreen.EventClock): Boolean {
        val c = BackAffordance.find(screen.peek().elements, screen.screenWidth, screen.screenHeight) ?: return false
        clock.mark()
        return screen.tapAt(c.centerX, c.centerY)
    }

    suspend fun pressKey(keyCode: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext AndroidScreen.NO_APP_OPEN
        screen.verified { screen.shell.injectKey(screen.displayId, keyCode) }
    }
}
