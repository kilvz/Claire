package com.wangzan101.mika.calls

import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.frontdesk.DeskNotifier
import java.util.UUID

/**
 * Unanswered questions from a call, one per process: the question is written into the conversation the call belongs to
 * (if any); the owner answers with the next sentence in that conversation or from the notification, the first answer
 * wins, and the question is cleared right away. The answer is also recorded in the conversation as a trace. Calls with
 * no owning conversation can only be answered from the notification.
 * Conversation writes use the same line the front desk fallback writes back ([DeskNotifier.STATUS]), which reads as a
 * normal reply in the conversation.
 */
class OwnerQuestions(
    private val write: (conversationId: String, turn: Turn) -> Unit,
    private val askedLine: (question: String, notified: Boolean) -> String,
    private val answeredLine: () -> String,
    private val withdrawnLine: () -> String,
) {
    class Handle(val token: String, val conversationId: String?, val question: String)

    private class Entry(val handle: Handle, val onAnswer: (String) -> Unit)

    private val open = LinkedHashMap<String, Entry>()

    /** Register a question; [notified] false means the notification could not be posted, and the conversation line also reminds the owner to turn it on. */
    fun open(conversationId: String?, question: String, notified: Boolean, onAnswer: (String) -> Unit): Handle {
        val handle = Handle(UUID.randomUUID().toString(), conversationId, question)
        synchronized(open) { open[handle.token] = Entry(handle, onAnswer) }
        conversationId?.let { write(it, Turn(userText = "", outcome = askedLine(question, notified), status = DeskNotifier.STATUS)) }
        return handle
    }

    /** The question still awaiting an answer; null once answered or withdrawn. */
    fun question(token: String): String? = synchronized(open) { open[token]?.handle?.question }

    /** An answer from the notification; returns false when it does not match (already answered or withdrawn) or the reply is empty. */
    fun answer(token: String, text: String): Boolean {
        val reply = text.trim().takeIf { it.isNotEmpty() } ?: return false
        val entry = synchronized(open) { open.remove(token) } ?: return false
        deliver(entry, reply)
        return true
    }

    /** The user said something in conversation [conversationId]: if a question is waiting there it counts as the answer (the most recently asked one) and returns true; otherwise false, and processing continues as usual. */
    fun answerIn(conversationId: String, text: String): Boolean {
        val reply = text.trim().takeIf { it.isNotEmpty() } ?: return false
        val entry = synchronized(open) {
            open.values.lastOrNull { it.handle.conversationId == conversationId }?.also { open.remove(it.handle.token) }
        } ?: return false
        deliver(entry, reply)
        return true
    }

    /** Quietly withdraw (used when replacing it with a newly asked question: the new question's line has already been written into the conversation). */
    fun cancel(token: String) {
        synchronized(open) { open.remove(token) }
    }

    /**
     * The call hung up with the question still unanswered: withdraw it and add a "withdrawn" line to the owning
     * conversation, so the owner does not answer a question nobody is waiting for, and a late comment still has context
     * (later text typed in the conversation still goes to the front desk as usual). Answered or withdrawn ones are not written again.
     */
    fun withdraw(token: String) {
        val entry = synchronized(open) { open.remove(token) } ?: return
        entry.handle.conversationId?.let { write(it, Turn(userText = "", outcome = withdrawnLine(), status = DeskNotifier.STATUS)) }
    }

    private fun deliver(entry: Entry, reply: String) {
        entry.handle.conversationId?.let { write(it, Turn(userText = reply, outcome = answeredLine(), status = DeskNotifier.STATUS)) }
        entry.onAnswer(reply)
    }
}
