package com.wangzan101.mika.cap.phone.setup

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A deadline that can be pushed back: it counts [lengthMs] from the last [extend]. [PairingCoordinator] uses it to decide how long to watch pairing;
 * every time the user comes back to the pairing matter (tapping "go to wireless debugging", leaving the guide page that stopped at the pairing step) it is recounted from that moment.
 */
internal class SlidingDeadline(private val lengthMs: Long, private val now: () -> Long) {
    @Volatile private var until = 0L

    fun extend() {
        until = now() + lengthMs
    }

    /** Wait for [job] to complete; while waiting, the deadline may be pushed back. True when it completes, false when the deadline arrives first. */
    suspend fun await(job: Job): Boolean {
        while (true) {
            val left = until - now()
            if (left <= 0) return false
            if (withTimeoutOrNull(left) { job.join() } != null) return true
        }
    }
}
