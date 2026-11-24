package com.wangzan101.mika.voice

/**
 * While holding, decides whether to restart and after how long when the recognition service ends a segment without hearing words (no match, no speech, an empty result).
 * A normal empty segment (the user pauses for a few seconds) restarts immediately; some services return empty again right after a restart, so when empties keep coming back quickly it waits a while before restarting,
 * and after too many it stops restarting and finishes with what has been heard, so the main thread does not spin forever in start-error-start. Pure logic; the caller supplies the time.
 */
class Restarts {
    sealed interface Decision {
        data class Restart(val delayMs: Long) : Decision
        data object GiveUp : Decision
    }

    private var quick = 0
    private var last = Long.MIN_VALUE

    /** Another empty segment; [now] is the current time (milliseconds, monotonic clock). */
    fun onEmpty(now: Long): Decision {
        quick = if (last != Long.MIN_VALUE && now - last <= WINDOW_MS) quick + 1 else 1
        last = now
        return when {
            quick > MAX_QUICK -> Decision.GiveUp
            quick > QUICK -> Decision.Restart(BACKOFF_MS)
            else -> Decision.Restart(0)
        }
    }

    /** Words were heard: reset the count. */
    fun onSpeech() {
        quick = 0
        last = Long.MIN_VALUE
    }

    companion object {
        /** Within this long after the previous empty segment counts as "empty again too soon". */
        const val WINDOW_MS = 2_000L
        /** After this many quick empties in a row, start waiting a while before restarting. */
        const val QUICK = 3
        /** Stop restarting once quick empties in a row exceed this many. */
        const val MAX_QUICK = 8
        const val BACKOFF_MS = 250L
    }
}
