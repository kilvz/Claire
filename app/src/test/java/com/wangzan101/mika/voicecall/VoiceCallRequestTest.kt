package com.wangzan101.mika.voicecall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Execution safety gate: each of the 5 rules has positive and negative cases, plus cross-turn replay, multiple offers in one turn, echo judged on the whole sentence, and the ring-buffer boundary of the echo window. */
class VoiceCallRequestTest {

    // Rule ①: no transcript for the current turn → Reject
    @Test
    fun offerWithoutTranscriptRejects() {
        val gate = VoiceCallRequest(RingBuffer(10))
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开微信"))
    }

    // Rule ⑤ positive case: a match → Accept (the user's whole original sentence, not the normalized result)
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

    // Rule ⑤: even when the model excerpts only a fragment it cannot change the user's meaning — negations are not dropped, and the backend always gets the whole sentence
    @Test
    fun aFragmentCannotDropANegation() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("千万别帮我给他转账")
        assertEquals(VoiceCallDecision.Accept("千万别帮我给他转账"), gate.offer("c1", "帮我给他转账"))
        assertEquals("千万别帮我给他转账", gate.said("千万别帮我给他转账"))
        assertNull(gate.said("帮我给他转账"))
    }

    // Rule ③ negative case: the utterance is longer than this turn's transcript — the model added words the user never said → Reject, and the transcript is not consumed
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

    // Rule ⑤: one sentence can do several things — the turn's transcript is not consumed on accept, and each call carries its own excerpt of the original words
    @Test
    fun oneUtteranceAuthorizesSeveralCalls() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("帮我订晚饭,再叫个车")
        // Every item carries the whole sentence (the user's original words); which part each item does is described by the front desk's title and note
        assertEquals(VoiceCallDecision.Accept("帮我订晚饭,再叫个车"), gate.offer("c1", "帮我订晚饭"))
        assertEquals(VoiceCallDecision.Accept("帮我订晚饭,再叫个车"), gate.offer("c2", "再叫个车"))
        assertEquals("帮我订晚饭,再叫个车", gate.said("帮我订晚饭,再叫个车"))
        // Words the user never said are still rejected in the same turn
        assertEquals(VoiceCallDecision.Reject, gate.offer("c3", "顺便转账"))
    }

    // Rule ⑤: when the user speaks in the next turn, the previous turn's transcript expires
    @Test
    fun nextUserTurnExpiresTheTranscript() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("打开微信")
        assertEquals(VoiceCallDecision.Accept("打开微信"), gate.offer("c1", "打开微信"))
        gate.onUserTurnStart()
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

    // Rule ④ positive case: the echo covers only a small part of this sentence → not echo (the user went on to say something else)
    @Test
    fun echoCoveringOnlyPartOfTheTurnDoesNotBlock() {
        val window = RingBuffer<String>(10)
        val gate = VoiceCallRequest(window)
        window.append("你好")
        gate.onUserTranscript("你好世界")
        assertEquals(VoiceCallDecision.Accept("你好世界"), gate.offer("c1", "你好世界"))
    }

    // Rule ④ positive case: the question just spoken contains options and the user answers with one → not echo
    @Test
    fun answeringWithAnOptionFromTheSpokenQuestionPasses() {
        val window = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(window)
        window.append("订日料那件,在问你:七点还是八点?")
        gate.onUserTranscript("七点吧")
        assertEquals(VoiceCallDecision.Accept("七点吧"), gate.offer("c1", "七点"))
        gate.onUserTranscript("七点")
        assertEquals(VoiceCallDecision.Accept("七点"), gate.offer("c2", "七点"))
    }

    // Rule ④ negative case: almost the whole sentence was just spoken (playback fed back as a user transcript) → Reject, even if only a fragment is excerpted
    @Test
    fun aTurnMostlyMadeOfEchoRejectsEvenForAFragment() {
        val window = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(window)
        window.append("好的,马上帮你打开微信")
        gate.onUserTranscript("马上帮你打开微信吧")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "打开微信"))
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
        gate.onUserTranscript("甲甲甲甲")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "甲甲甲甲"))
    }

    // Echo window 500-character ring-buffer boundary: beyond the capacity the oldest entry is dropped one by one; dropped content no longer counts as echo, retained content still does
    @Test
    fun echoWindowEvictsOldestEntryBeyondCapacity() {
        val window = RingBuffer<String>(ECHO_WINDOW_CAPACITY_CHARS)
        val gate = VoiceCallRequest(window)
        window.append("甲".repeat(ECHO_WINDOW_CAPACITY_CHARS))
        window.append("乙乙乙乙")
        assertEquals(listOf("乙乙乙乙"), window.snapshot())
        gate.onUserTranscript("甲甲甲甲")
        assertEquals(VoiceCallDecision.Accept("甲甲甲甲"), gate.offer("c1", "甲甲甲甲"))
        gate.onUserTranscript("乙乙乙乙")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c2", "乙乙乙乙"))
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
        gate.onUserTranscript("丁丁丁丁")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c2", "丁丁丁丁"))
    }

    // Hardening: an utterance that normalizes to empty (punctuation only) is rejected rather than executed — the empty string is a substring of any string, so it must not be let through
    @Test
    fun emptyNormalizedUtteranceRejects() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("……")
        assertEquals(VoiceCallDecision.Reject, gate.offer("c1", "……"))
    }

    // Cancellation only takes the most recently accepted sentence: once the user says another, the earlier one can no longer stop a task
    @Test
    fun cancelOnlyTakesTheLatestAcceptedWords() {
        val gate = VoiceCallRequest(RingBuffer(10))
        gate.onUserTranscript("别订了")
        assertEquals(VoiceCallDecision.Accept("别订了"), gate.offer("c1", "别订了"))
        assertEquals("别订了", gate.saidNow("别订了"))
        gate.onUserTurnStart()
        gate.onUserTranscript("明天天气怎么样")
        assertEquals(VoiceCallDecision.Accept("明天天气怎么样"), gate.offer("c2", "天气"))
        assertEquals("别订了", gate.said("别订了"))
        assertNull(gate.saidNow("别订了"))
    }
}
