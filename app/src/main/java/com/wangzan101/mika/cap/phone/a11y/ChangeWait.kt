package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.cap.phone.display.FrameMath

/**
 * Waits for the UI to settle after an action, then decides whether anything changed. Event quiet alone is not
 * enough: a slow page may be quiet before it has started drawing, gets misjudged as unchanged, and the model
 * taps again.
 *
 * - Accessibility events quiet for [QUIET_MS] and the view tree changed: a change, immediately, without waiting for
 *   the picture to stop (video and live streams never stop moving).
 * - Only the picture changed (custom-drawn UI): the two frames must also be similar before it counts as changed,
 *   so a half-drawn transition is not taken for the result.
 * - Deciding "unchanged" requires quiet events and a stable picture; until then it keeps waiting up to [CAP_MS].
 * - At [CAP_MS] it concludes from the state at that moment, whether or not events are still coming and the picture
 *   is still moving: a view tree or picture different from before the action means changed. Pages with nonstop
 *   events (progress bars, timers) thus cannot stall an action past the cap.
 * - With a fallback action [fallback]: if by [PROBE_MS] neither tree nor picture has changed and events are quiet
 *   and the picture stable (the first channel caused no reaction), run [fallback] once; if it was sent successfully,
 *   wait for a change again with a fresh [CAP_MS] from that moment, otherwise keep waiting out the original window.
 *
 * When only event quiet is missing it sleeps for the remaining quiet time; an ordinary page returns about
 * [QUIET_MS] after the last event. Clock, sleeping and frame capture are all injected by the caller so this can be
 * tested on the JVM.
 */
internal object ChangeWait {
    const val POLL_MS = 150L
    const val QUIET_MS = 400L
    const val PROBE_MS = 550L
    const val CAP_MS = 2_500L
    private const val MIN_SLEEP_MS = 10L

    /**
     * @param frame brightness grid of the current preview frame, null when there is no picture (then only the view tree and event quiet are used)
     * @param quietForMs how long has passed since the last UI event
     * @param treeChanged whether the view tree fingerprint differs from before the action
     * @param beforeFrame the brightness grid before the action
     * @param fallback the fallback channel tried once when the first channel is still quiet and unchanged after
     * [PROBE_MS]; returning true means the fallback action was sent
     */
    suspend fun awaitStableChange(
        now: () -> Long,
        sleep: suspend (Long) -> Unit,
        frame: () -> IntArray?,
        quietForMs: () -> Long,
        treeChanged: () -> Boolean,
        beforeFrame: IntArray?,
        fallback: (suspend () -> Boolean)? = null,
    ): Boolean {
        val start = now()
        var deadline = start + CAP_MS
        val probeDeadline = start + PROBE_MS
        var probePending = fallback != null
        var prev = frame()
        var wait = POLL_MS
        while (true) {
            val wake = if (probePending) minOf(deadline, probeDeadline) else deadline
            sleep(minOf(wait, (wake - now()).coerceAtLeast(0)))
            val cur = frame()
            val quietFor = quietForMs()
            val quiet = quietFor >= QUIET_MS
            val treeDiff = treeChanged()
            if (quiet && treeDiff) return true
            val stable = if (prev == null || cur == null) prev == null && cur == null else !FrameMath.differs(prev, cur)
            val frameChanged = FrameMath.differs(beforeFrame, cur)
            if (quiet && stable && frameChanged) return true
            if (treeDiff || frameChanged) probePending = false
            if (probePending && now() >= probeDeadline) {
                probePending = false
                if (quiet && stable && fallback != null && fallback()) {
                    deadline = now() + CAP_MS
                    prev = frame()
                    wait = POLL_MS
                    continue
                }
            }
            if (now() >= deadline) return frameChanged || treeDiff
            wait = if (quiet) POLL_MS else minOf(POLL_MS, QUIET_MS - quietFor).coerceAtLeast(MIN_SLEEP_MS)
            prev = cur
        }
    }
}
