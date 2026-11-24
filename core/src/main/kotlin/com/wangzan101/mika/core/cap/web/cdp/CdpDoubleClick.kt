package com.wangzan101.mika.core.cap.web.cdp

import com.wangzan101.mika.core.screen.uistate.DOUBLE_TAP_GAP_MS
import kotlinx.coroutines.delay

/**
 * Double click: two press/release pairs at the same point, with clickCount 2 on the second one, so the page receives click, click, dblclick (just like a real double click).
 * The two are [gapMs] apart, within the time limit in which the browser recognizes a double click.
 */
suspend fun InputTrack.mouseDoubleClick(x: Double, y: Double, gapMs: Long = DOUBLE_TAP_GAP_MS) {
    mouse("mouseMoved", x, y, "none", 0, 0)
    mouse("mousePressed", x, y, "left", 1, 1)
    mouse("mouseReleased", x, y, "left", 0, 1)
    delay(gapMs)
    mouse("mousePressed", x, y, "left", 1, 2)
    mouse("mouseReleased", x, y, "left", 0, 2)
}
