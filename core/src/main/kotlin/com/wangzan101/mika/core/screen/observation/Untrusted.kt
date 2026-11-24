package com.wangzan101.mika.core.screen.observation

/**
 * Fixed markers wrapped around untrusted content (web page body text and script results, network responses, answers
 * from other AIs, what is said in channels) before it is handed to the model. Wrapped once only, where the capability
 * builds this observation and writes the tool result; later trimming and compaction never wrap it again; the meaning
 * of the markers is explained once in the system prompt. Closing markers inside the body are escaped, so the body
 * cannot close this layer early. The ledger records the original text with no markers wrapped.
 */
object Untrusted {
    const val OPEN = "<untrusted>"
    const val CLOSE = "</untrusted>"
    const val OVERHEAD = 2 + OPEN.length + CLOSE.length

    /** Wraps the text in the markers; an empty string is returned as is. */
    fun wrap(text: String): String = if (text.isEmpty()) text else "$OPEN\n" + text.replace(CLOSE, "<\\/untrusted>") + "\n$CLOSE"

    /** The whole wrapped field must still satisfy the length limit declared by the tool. */
    fun wrapWithin(text: String, maxChars: Int): String {
        require(maxChars >= OVERHEAD)
        val escaped = text.replace(CLOSE, "<\\/untrusted>")
        return if (escaped.isEmpty()) escaped else "$OPEN\n${escaped.take(maxChars - OVERHEAD)}\n$CLOSE"
    }
}
