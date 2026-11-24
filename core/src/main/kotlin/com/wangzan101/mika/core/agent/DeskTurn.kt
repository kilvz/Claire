package com.wangzan101.mika.core.agent

/**
 * Turns the front desk writes into a conversation (chat and calls that go through the front desk): what the front desk said itself, not a result produced by a background task.
 * - [STATUS]: the user said something and the front desk replied (possibly dispatching tasks, recorded in [Turn.deskTasks]);
 * - [REPORT_STATUS]: a background task changed and the front desk said one report line (no user words).
 * Both are normal replies, not "unfinished"; to the model they are described as front-desk replies (see [Conversation.context]).
 */
object DeskTurn {
    const val STATUS = "desk"
    const val REPORT_STATUS = "desk_report"

    /** Prefix of [Turn.runId] for a chat-page front-desk turn: used only to identify the turn (matching the UI one with the stored one); there is no corresponding run record. */
    const val RUN_PREFIX = "desk:"

    fun isDesk(t: Turn): Boolean = t.status == STATUS || t.status == REPORT_STATUS

    /** The reason recorded when this front-desk turn produced no reply and dispatched nothing ([Turn.note]); null when it replied normally. */
    fun failure(t: Turn): String? = t.note?.takeIf { t.status == STATUS }
}
