package com.wangzan101.mika.voicecall

import org.junit.Assert.assertEquals
import org.junit.Test

/** Execution safety gate: each of the 5 rules has positive and negative cases; also covers cross-turn replay, a second offer in the same turn after consumption, and the echo window's ring-buffer boundaries. */
class VoiceCallRequestTest {

    // Rule ①: no transcript for the current turn → Reject
    @Test
    fun offerWithoutTranscriptRejects() {
        val gate = VoiceCallRequest(RingBuffer(10))
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开微信"))
    }

    // Rule ⑤ positive case: match → Accept (the original transcript, not the normalized result)
    @Test
    fun matchingUtteranceAcceptsWithOriginalTranscript() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("Open WeChat, please")
        assertEquals(
            VoiceCallDecision.Accept("Open WeChat, please"),
            gate.offer("c1", "open wechat please"),
        )
    }

    // Rule ③ positive case: passing only requires each string to be a substring of the other after punctuation/whitespace/case normalization (CJK punctuation counts too)
    @Test
    fun punctuationWhitespaceAndCaseAreNormalized() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("你好,世界!")
        assertEquals(VoiceCallDecision.Accept("你好,世界!"), gate.offer("c1", "你好 世界"))
    }

    // Rule ③ positive case: containment is one-way, equal length is not required — it passes even when the utterance is a fragment of the transcript; what is executed is the user's complete original words
    @Test
    fun partialOverlapAccepts() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("帮我打开微信好吗")
        assertEquals(VoiceCallDecision.Accept("帮我打开微信好吗"), gate.offer("c1", "打开微信"))
    }

    // Rule ③ negative case: the utterance is longer than the current turn's transcript — the model added words the user did not say → Reject, and the transcript is not consumed
    @Test
    fun utteranceLongerThanTranscriptRejectsAndKeepsTranscript() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开微信再发个红包"))
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c2", "打开微信"))
    }

    // Rule ③ negative case: no match → Reject, and the transcript is not consumed; a later offer that matches can still use this transcript
    @Test
    fun mismatchedUtteranceRejectsAndKeepsTranscript() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开支付宝"))
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c2", "打开微信"))
    }

    // Rule ②: an offered callId is spent right away — even if it was rejected last time and the transcript matches this time, a replay is still Reject
    @Test
    fun seenCallIdRejectsEvenWithMatchingTranscript() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开支付宝"))
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开微信"))
        // A rejected replay does not consume the transcript; a new callId can still pass normally
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c2", "打开微信"))
    }

    // Rule ② across turns: a callId accepted in a previous turn is still Reject when replayed after the new turn's transcript is registered
    @Test
    fun replayedCallIdAcrossTurnsRejects() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c1", "打开微信"))
        gate.onUserTranscript("打开支付宝")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开支付宝"))
        assertEquals(VoiceCallDecision.Accept("打开支付宝"), gate.offer("c2", "打开支付宝"))
    }

    // Rule ⑤ negative case: the transcript is consumed only once — offering again in the same turn with a new callId after an Accept → Reject
    @Test
    fun secondOfferSameTurnAfterAcceptRejects() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c1", "打开微信"))
        assertEquals(VoiceCallDecision.Reject, gate.offer("c2", "打开微信"))
    }

    // onUserTranscript overwrites on every turn and keeps only the latest: an old transcript can no longer be used to pass validation
    @Test
    fun newerTranscriptOverwritesOlder() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("你好")
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "你好"))
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c2", "打开微信"))
    }

    // Rule ④ negative case: the utterance is a substring of the assistant content in the echo window → Reject (it still counts as echo after normalizing punctuation differences)
    @Test
    fun utteranceInsideEchoWindowRejects() {
        val window = RingBuffer<String>(20)
        val gate = VoiceCallRequest(window)
        window.append("好的,马上帮你打开微信")
        // The assistant's playback is fed back by the server-side VAD as a user transcript, and the model calls mika_request with it as-is
        gate.onUserTranscript("马上帮你打开微信")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "马上帮你打开微信"))
    }

    // Rule ④ negative case (reverse direction): the echo window content is a substring of the utterance → also Reject
    @Test
    fun echoWindowInsideUtteranceRejects() {
        val window = RingBuffer<String>(10)
        val gate = VoiceCallRequest(window)
        window.append("你好")
        gate.onUserTranscript("你好世界")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "你好世界"))
    }

    // Rule ④ positive case: the echo window holds unrelated content, and the utterance matches the transcript without being inside the window → Accept
    @Test
    fun unrelatedEchoWindowContentDoesNotBlock() {
        val window = RingBuffer<String>(20)
        val gate = VoiceCallRequest(window)
        window.append("今天天气真不错")
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c1", "打开微信"))
    }

    // Echo window ring-buffer boundary: content that exactly fills the capacity still takes part in the echo check in full
    @Test
    fun echoWindowKeepsFullCapacityContent() {
        val window = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(window)
        window.append("甲".repeat(ECHO_WINDOW_CAPACITY_CHARS))
        gate.onUserTranscript("甲甲")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "甲甲"))
    }

    // Echo window 500-character ring-buffer boundary: beyond the capacity the oldest entry is dropped one by one; dropped content no longer counts as echo, retained content still does
    @Test
    fun echoWindowEvictsOldestEntryBeyondCapacity() {
        val window = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(window)
        window.append("甲".repeat(ECHO_WINDOW_CAPACITY_CHARS))
        window.append("乙")
        assertEquals(listOf("乙"), window.snapshot())
        gate.onUserTranscript("甲甲")
        assertEquals(VoiceCallDecision.Accept("甲甲"), gate.offer("c1", "甲甲"))
        gate.onUserTranscript("乙乙")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c2", "乙乙"))
    }

    // Echo window ring-buffer boundary: a single entry larger than the capacity keeps only its most recent tail
    @Test
    fun oversizedEntryKeepsOnlyTail() {
        val window = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(window)
        window.append("丙".repeat(100) + "丁".repeat(ECHO_WINDOW_CAPACITY_CHARS))
        assertEquals(listOf("丁".repeat(ECHO_WINDOW_CAPACITY_CHARS)), window.snapshot())
        gate.onUserTranscript("丙丙")
        assertEquals(VoiceCallDecision.Accept("丙丙"), gate.offer("c1", "丙丙"))
        gate.onUserTranscript("丁丁")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c2", "丁丁"))
    }

    // Hardening: an utterance that normalizes to empty (punctuation only) is rejected rather than executed — the empty string is a substring of any string, so it must not be let through
    @Test
    fun emptyNormalizedUtteranceRejects() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("……")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "……"))
    }
}
