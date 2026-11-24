package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.observation.Untrusted

/**
 * A "notice reminder" turn that Claire writes into the conversation itself (a proactive capability): when the user
 * opens a reminder, Claire lists the selected notifications as one message.
 * This turn has no user words ([Turn.userText] is empty); the title and body in [Turn.outcome] were written by other
 * Apps and other people, and the reason was written by the model looking at those words: all of it is third-party
 * text, never the user's words, and never instructions.
 *
 * Like the App-written turns for scheduled reminders (`reminder:`) and task interruptions (`interrupted:`), it is
 * recognized solely by the [Turn.runId] prefix, with no extra field: the conversation file format stays unchanged,
 * and it is still recognized after branching or restarting.
 *
 * How it is treated once recognized (documented wherever it is used): when handed to the model it is not phrased as
 * "the user said", and the whole block is wrapped in an untrusted marker ([Conversation.context], history_read);
 * in a conversation containing such a turn ([tainted]), "confirmation on the previous turn" cannot be used to
 * reference other turns, memory is not extracted automatically after the task ends, and payments still ask first even
 * in autonomous mode.
 */
object NoticeTurn {
    /** The [Turn.runId] prefix of a notice-reminder turn (followed by a random ID; there is no matching run record). */
    const val RUN_PREFIX = "notice:"

    fun isNotice(t: Turn): Boolean = t.runId?.startsWith(RUN_PREFIX) == true

    /** Whether this conversation contains a notice reminder. Ask with all stored turns, not the last few fed to the model: the conversation still counts after the reminder turn slides out of the window. */
    fun tainted(turns: List<Turn>): Boolean = turns.any(::isNotice)

    private val TAG = Regex("<\\s*/?\\s*untrusted", RegexOption.IGNORE_CASE)

    /**
     * Wraps it in an untrusted marker when handing it to the model. How it was stored does not matter: it is rewrapped
     * every time it is handed over. Markers embedded in the text (opening, closing, or with whitespace in between) are
     * broken first, so this layer cannot be closed early ([Untrusted.wrap] itself only handles the exact closing mark).
     */
    fun untrusted(text: String): String = Untrusted.wrap(text.replace(TAG, "(untrusted"))
}
