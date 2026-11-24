package com.wangzan101.mika.voicecall

import com.wangzan101.mika.core.frontdesk.DeskWords

/** Echo window capacity: only the assistant's most recent this many characters are kept, and earlier content no longer takes part in echo detection. */
const val ECHO_WINDOW_CAPACITY_CHARS = 500

/**
 * Safety gate for executing voice requests: when the model calls a front desk tool during a call it carries the user's own words (words), which must pass these five rules before execution is allowed;
 * refusing to execute is better than executing wrongly.
 *
 * 1) No current-turn transcript -> Reject; 2) callId already seen (including replays across turns) -> Reject; 3) the normalized utterance is not a substring
 * of the current-turn transcript -> Reject (one-way containment: the model may quote only fragments the user said, and must not add words the user did not say);
 * 4) most of the current-turn transcript is what the assistant just said, captured in the echo window (playback fed back in as user transcription) -> Reject. Judged on the whole transcript, not on the model's fragment:
 * a user picking one option from a question just read out ("Seven or eight?" -> "Seven then") does not count as echo, and a very short utterance (under
 * [ECHO_MIN_CHARS] characters) cannot be told apart from a brief answer, so it does not count either;
 * 5) pass -> Accept (the user's whole utterance). What goes to the backend is always the whole utterance: the model's fragment is only used for checking, and stripping a negation or a condition cannot change the user's meaning.
 * One utterance can cover several jobs: passing does not consume the transcript, every call in the same turn carries the whole utterance, and the front desk's titles and notes say which part each job covers;
 * when the user speaks again ([onUserTurnStart]) or a new transcript is registered, the previous turn's transcript is void.
 *
 * It is also this call's word check for the front desk executor ([DeskWords]): on passing, words is already swapped for the whole utterance, and execution checks again that it was one that passed.
 *
 * @param echoWindow ring buffer of what the assistant has said, with a capacity of [ECHO_WINDOW_CAPACITY_CHARS] characters.
 */
class VoiceCallRequest(
    private val echoWindow: RingBuffer<String> = RingBuffer(ECHO_WINDOW_CAPACITY_CHARS),
) : DeskWords {
    /** A callId that has been offered is void immediately: even if it was rejected then, replaying it with the same callId later is always Reject. */
    private val seenCallIds = mutableSetOf<String>()

    /** The current-turn user transcript, only the latest one; void when the user starts a new turn. */
    private var currentTranscript: String? = null

    /** The most recently passed utterances (gated transcripts and typed text), used by the front desk to verify tool executions; only the latest [ACCEPTED_KEPT] are kept. */
    private val accepted = ArrayDeque<String>()

    /** Returns the fragment unchanged when it is a recently passed user utterance (already whole when it passed the gate). */
    @Synchronized
    override fun said(fragment: String): String? = fragment.takeIf { it in accepted }

    /** Cancellation only recognizes the most recently passed utterance: what the user is saying now, not something earlier. */
    @Synchronized
    override fun saidNow(fragment: String): String? = fragment.takeIf { it == accepted.lastOrNull() }

    /** Typed text is the user's own words by definition (the gate guards against model forgery), so it is recorded directly as a passed utterance. */
    @Synchronized
    fun acceptTyped(text: String) {
        remember(text)
    }

    private fun remember(text: String) {
        if (accepted.lastOrNull() == text) return
        accepted.addLast(text)
        while (accepted.size > ACCEPTED_KEPT) accepted.removeFirst()
    }

    /** The user spoke again: the previous turn's transcript can no longer authorize any call. */
    @Synchronized
    fun onUserTurnStart() {
        currentTranscript = null
    }

    /** Register the current turn's final+complete user transcript; a new turn overwrites the old transcript. */
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
        if (isMostlyEcho(normalizedTranscript, echo)) return VoiceCallDecision.Reject
        remember(transcript)
        return VoiceCallDecision.Accept(transcript)
    }

    private companion object {
        const val ACCEPTED_KEPT = 16
    }
}

sealed interface VoiceCallDecision {
    /** Pass, carrying the raw (un-normalized) whole current-turn user transcript. */
    data class Accept(val text: String) : VoiceCallDecision

    data object Reject : VoiceCallDecision
}

/** Normalization: strip whitespace and punctuation, lowercase, keeping only letters and digits (including CJK ideographs). */
private fun normalizeForMatch(text: String): String =
    text.lowercase().filter(Char::isLetterOrDigit)

/** An utterance must be at least this many characters (after normalization) to possibly count as echo. */
private const val ECHO_MIN_CHARS = 4

/** When echo covers a transcript up to this ratio, the transcript is treated as playback fed back in. */
private const val ECHO_COVER_RATIO = 0.8

/** Whether the longest common contiguous run between the normalized transcript [turn] and the echo [echo] covers most of the transcript (and is not too short). */
private fun isMostlyEcho(turn: String, echo: String): Boolean {
    if (turn.length < ECHO_MIN_CHARS || echo.isEmpty()) return false
    val covered = longestCommonRun(turn, echo)
    return covered >= ECHO_MIN_CHARS && covered >= turn.length * ECHO_COVER_RATIO
}

/** Length of the longest common contiguous substring of two strings (row-rolling dynamic programming). */
private fun longestCommonRun(a: String, b: String): Int {
    var best = 0
    var previous = IntArray(b.length + 1)
    for (i in 1..a.length) {
        val row = IntArray(b.length + 1)
        for (j in 1..b.length) {
            if (a[i - 1] == b[j - 1]) {
                row[j] = previous[j - 1] + 1
                if (row[j] > best) best = row[j]
            }
        }
        previous = row
    }
    return best
}

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
