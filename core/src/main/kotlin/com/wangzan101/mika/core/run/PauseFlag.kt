package com.wangzan101.mika.core.run

/**
 * Flag for the user manually pausing Claire. The persisted copy (in the app layer) is the only source of truth:
 * every entry point (scheduled, channels, tasks handed over by a computer, workflows, the built-in helper's watchdog and auto-repair) only reads it and keeps no copy,
 * re-reading it before starting new work and at every asynchronous hand-off. Pausing only blocks new work and automatic recovery; it does not remove pairing or delete existing data.
 */
fun interface PauseFlag {
    fun paused(): Boolean

    companion object {
        /** For places that are not wired to pause (tests, old callers): never paused. */
        val Never = PauseFlag { false }

        /** Machine code recorded by work blocked while paused: the skip reason for a trigger window, the failure reason for a workflow attempt, and the rejection reason for a computer task. */
        const val REASON = "mika_paused"
        /** Message a computer (MCP, CLI APIs) receives while paused: it did not start or queue, and must be resent after resuming. */
        const val REMOTE_MESSAGE = "Mika is paused on the phone, so it did not start or queue this request. Resume Mika on the phone, then send it again."
    }
}
