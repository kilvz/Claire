package com.wangzan101.mika.core.agent

/**
 * The moment (seam) just before a reply with no tool calls is accepted as the final answer: external code can still
 * add a note here, for example a message the user sent while the task was in progress.
 * [Accept] accepts it as usual (and then reviews it); [Continue] does not accept it, hands [note] to the model as a
 * reminder, and the task carries on.
 */
sealed interface BeforeAccept {
    data object Accept : BeforeAccept
    data class Continue(val note: String) : BeforeAccept
}
