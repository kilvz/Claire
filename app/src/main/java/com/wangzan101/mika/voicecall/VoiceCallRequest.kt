package com.wangzan101.mika.voicecall

/** Echo window capacity: only the most recent characters the assistant has said are kept; anything earlier no longer participates in echo detection. */
const val ECHO_WINDOW_CAPACITY_CHARS = 500

/**
 * Safety gate for executing voice requests: a request submitted by the model via mika_request in a real-time session must pass these five rules before it may run;
 * better to reject execution than to execute wrongly.
 *
 * ① No transcript for the current turn → Reject; ② callId already seen (including cross-turn replay) → Reject; ③ the utterance, after normalization, is not a
 * substring of the current turn's transcript → Reject and the transcript is not consumed — one-way containment: the model may quote only fragments of what the user said, it may not add words the user did not say;
 * ④ the utterance, after normalization, and the assistant content in the echo window are substrings of each other → Reject (echo is a symmetric test, both directions count);
 * ⑤ pass → Accept (the original transcript), and the transcript is consumed once.
 *
 * @param echoWindow ring buffer of what the assistant has said, with a capacity of [ECHO_WINDOW_CAPACITY_CHARS] characters.
 */
class VoiceCallRequest(
    private val echoWindow: RingBuffer<String> = RingBuffer(ECHO_WINDOW_CAPACITY_CHARS),
) {
    /** A callId that has been offered is invalidated immediately: even if it was rejected then, replaying with the same callId later is always rejected. */
    private val seenCallIds = mutableSetOf<String>()

    /** The current turn's user transcript, only the latest one is kept; consumed once on Accept. */
    private var currentTranscript: String? = null

    /** Record the current turn's final+complete user transcript; a new turn overwrites the old transcript. */
    @Synchronized
    fun onUserTranscript(text: String) {
        currentTranscript = text
    }

    @Synchronized
    fun offer(callId: String, utterance: String): VoiceCallDecision {
        if (!seenCallIds.add(callId)) return VoiceCallDecision.Reject
        val transcript = currentTranscript ?: return VoiceCallDecision.Reject
        val normalizedUtterance = normalizeForMatch(utterance)
        val normalizedTranscript = normalizeForMatch(transcript)
        // An empty string is a substring of any string, so if it is empty after normalization, better to reject execution
        if (normalizedUtterance.isEmpty() || normalizedTranscript.isEmpty()) {
            return VoiceCallDecision.Reject
        }
        // ③ One-way containment: the utterance given by the model must fall within the current turn's transcript; being longer than the transcript means words the user never said were inserted
        if (normalizedUtterance !in normalizedTranscript) {
            return VoiceCallDecision.Reject
        }
        val echo = normalizeForMatch(echoWindow.snapshot().joinToString(""))
        if (echo.isNotEmpty() && isMutualSubstring(normalizedUtterance, echo)) {
            return VoiceCallDecision.Reject
        }
        currentTranscript = null
        return VoiceCallDecision.Accept(transcript)
    }
}

sealed interface VoiceCallDecision {
    /** Allow, carrying the original (non-normalized) current-turn user transcript. */
    data class Accept(val text: String) : VoiceCallDecision

    data object Reject : VoiceCallDecision
}

/** Normalization: strip whitespace and punctuation, lowercase, keeping only letters and digits (including CJK ideographs). */
private fun normalizeForMatch(text: String): String =
    text.lowercase().filter(Char::isLetterOrDigit)

private fun isMutualSubstring(a: String, b: String): Boolean = a in b || b in a

/**
 * A ring buffer with a capacity counted in characters: items are appended one by one, and when the total character count exceeds [capacityChars], whole entries are dropped from the oldest end;
 * a single entry larger than the capacity keeps only its most recent tail.
 */
class RingBuffer<T : CharSequence>(private val capacityChars: Int) {
    init {
        require(capacityChars > 0) { "capacityChars must be positive" }
    }

    private val entries = ArrayDeque<T>()
    private var totalChars = 0

    @Synchronized
    fun append(item: T) {
        if (item.isEmpty()) return
        if (item.length >= capacityChars) {
            @Suppress("UNCHECKED_CAST")
            val tail = item.subSequence(item.length - capacityChars, item.length) as T
            entries.clear()
            entries.addLast(tail)
            totalChars = tail.length
            return
        }
        entries.addLast(item)
        totalChars += item.length
        while (totalChars > capacityChars) {
            totalChars -= entries.removeFirst().length
        }
    }

    @Synchronized
    fun snapshot(): List<T> = entries.toList()
}
