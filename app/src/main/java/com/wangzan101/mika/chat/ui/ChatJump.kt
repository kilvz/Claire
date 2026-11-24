package com.wangzan101.mika.chat.ui

/** A locatable message position in the chat page. Turns start at 0; steerIndex is the index of a steer within that turn. */
sealed interface ChatTarget {
    /** A user bubble; steerIndex null means the first user message of that turn. */
    data class Message(val turnIndex: Int, val steerIndex: Int?) : ChatTarget

    /** The reply that completed or was interrupted after a steer. */
    data class Reply(val turnIndex: Int, val steerIndex: Int, val interrupted: Boolean) : ChatTarget

    /** The file cards of this turn. */
    data class Files(val turnIndex: Int) : ChatTarget

    /** The result card of this turn. */
    data class Status(val turnIndex: Int) : ChatTarget
}

/** A one-shot jump request. serial makes two consecutive jumps to the same place count as a new request. */
data class ChatJump(val conversationId: String, val target: ChatTarget, val serial: Long)
