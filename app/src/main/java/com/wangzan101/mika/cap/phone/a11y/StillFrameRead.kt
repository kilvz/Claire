package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.cap.phone.display.FrameMath

/**
 * Frame requirements around recognizing text on screen (OCR). Custom-drawn UIs emit almost no accessibility events,
 * and "events quiet" does not mean the drawing is done: recognizing right after entering a page or while the keyboard
 * is rising yields a transitional frame, or the picture has already changed by the time recognition finishes and the
 * result has to be discarded.
 *
 * - Before recognizing, wait for the picture to settle: two consecutive frames similar ([FrameMath.differs] false),
 *   waiting at most [STILL_CAP_MS]; if something local is always moving (animated avatars, cursor) recognize anyway
 *   when the time is up.
 * - If the whole page changed during recognition ([FrameMath.pageChanged]): the text positions no longer match, so
 *   recognize the new page again, up to [ATTEMPTS] times; local changes do not affect text positions and are used
 *   as they are.
 *
 * Clock, sleeping and frame capture are all injected by the caller so this can be tested on the JVM.
 */
internal object StillFrameRead {
    const val POLL_MS = 120L
    const val STILL_CAP_MS = 1_200L
    const val ATTEMPTS = 2

    /**
     * [recognize] recognizes the current frame; when it yields null (no picture), return null right away. Also
     * returns null if the whole page changed on every attempt.
     */
    suspend fun <T : Any> read(now: () -> Long, sleep: suspend (Long) -> Unit, frame: () -> IntArray?, recognize: suspend () -> T?): T? {
        repeat(ATTEMPTS) {
            awaitStill(now, sleep, frame)
            val before = frame()
            val result = recognize() ?: return null
            if (!FrameMath.pageChanged(before, frame())) return result
        }
        return null
    }

    /**
     * Waits until two consecutive frames are similar; returns false if still moving when the time limit is
     * reached. Does not wait when there is no picture.
     */
    suspend fun awaitStill(now: () -> Long, sleep: suspend (Long) -> Unit, frame: () -> IntArray?): Boolean {
        val deadline = now() + STILL_CAP_MS
        var prev = frame() ?: return true
        while (true) {
            sleep(minOf(POLL_MS, (deadline - now()).coerceAtLeast(0)))
            val cur = frame() ?: return true
            if (!FrameMath.differs(prev, cur)) return true
            if (now() >= deadline) return false
            prev = cur
        }
    }
}
