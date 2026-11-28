package com.wangzan101.mika.core.model

/**
 * SSE framing: lines are fed in one at a time, `data:` lines are accumulated, and an empty line joins them into one event that is handed over; other fields (event:, id:, comments) are ignored.
 * Shared by streaming reads and the case where "the whole reply is actually SSE".
 */
internal class SseFramer {
    private val data = mutableListOf<String>()

    /** Feeds one line; returns the event when this line finished one, otherwise null. */
    fun feed(line: String): String? = when {
        line.isEmpty() -> flush()
        line.startsWith("data:") -> { data += line.removePrefix("data:").removePrefix(" "); null }
        else -> null
    }

    /** Hands over the event that has not yet hit an empty line (called at end of stream); returns null when there is none. */
    fun flush(): String? {
        if (data.isEmpty()) return null
        val event = data.joinToString("\n")
        data.clear()
        return event
    }
}
