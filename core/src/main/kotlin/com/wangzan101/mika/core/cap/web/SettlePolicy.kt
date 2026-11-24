package com.wangzan101.mika.core.cap.web

import kotlinx.coroutines.delay

/**
 * How long to wait after a web action. Waiting for the page to go quiet works as before ([domQuiet] with the old cap [domCapMs]; if the page keeps changing, that whole cap is waited out without any report);
 * the network is a separate condition: the wait is extended only while requests sent by this action are still in flight ([networkBusy]), for at most [netCapMs] in total from the start of the action,
 * and after the requests finish, the page's quietness is looked at once more within the remaining time. When the network was idle to begin with, the wait is as long as when there is no network record.
 * Returns whether the network went quiet within the cap (true when there was no network wait).
 */
object SettlePolicy {
    suspend fun settle(
        clock: () -> Long,
        domQuiet: suspend (capMs: Long) -> Boolean,
        networkBusy: () -> Boolean,
        domCapMs: Long,
        netCapMs: Long,
        pollMs: Long,
    ): Boolean {
        val deadline = clock() + netCapMs
        domQuiet(domCapMs)
        if (!networkBusy()) return true
        while (networkBusy()) {
            if (clock() >= deadline) return false
            delay(pollMs)
        }
        val left = deadline - clock()
        if (left > 0) domQuiet(minOf(left, domCapMs))
        return true
    }

    /**
     * Wait for the body text to settle before reading a page (not merely for the page to go quiet: with lazy loading and progressive rendering the DOM may be quiet for a while and busy again).
     * The body's (length, digest) is sampled every [intervalMs], and it counts as stable once [stableIntervals] consecutive samples equal the previous one;
     * while there is not a single character yet, [emptyIntervals] consecutive samples are required (the page is most likely still loading). Returns false when it is still unstable at [capMs]; also false when no sample can be taken.
     */
    suspend fun contentStable(
        clock: () -> Long,
        sample: suspend () -> Pair<Int, Int>?,
        intervalMs: Long,
        stableIntervals: Int,
        emptyIntervals: Int,
        capMs: Long,
    ): Boolean {
        val deadline = clock() + capMs
        var last = sample() ?: return false
        var same = 0
        while (true) {
            if (clock() + intervalMs > deadline) return false
            delay(intervalMs)
            val now = sample() ?: return false
            same = if (now == last) same + 1 else 0
            last = now
            if (same >= if (now.first == 0) emptyIntervals else stableIntervals) return true
        }
    }
}
