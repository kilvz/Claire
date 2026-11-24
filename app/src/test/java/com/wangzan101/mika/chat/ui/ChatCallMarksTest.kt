package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.chat.ConversationCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCallMarksTest {
    private fun turn(at: Long, callId: String? = null) =
        Exchange("问 $at", emptyList(), ExchangeStatus.Done("答 $at", null), live = false, callId = callId, atMs = at)

    private fun List<CallMark>.keys() = map { it.key }

    @Test
    fun `rounds in a call get a start and an end row around them`() {
        val call = ConversationCall("a", 150L, 400L, ConversationCall.END_HANGUP, spoke = true)
        val list = listOf(turn(100), turn(200, "a"), turn(300, "a"), turn(500))
        val marks = callMarks(list, listOf(call))
        assertEquals(listOf("call-a"), marks.before(1).keys())
        assertTrue(marks.before(2).isEmpty())
        assertEquals("the end row comes after the call's last round", listOf("call-end-a"), marks.before(3).keys())
        assertTrue(marks.tail.isEmpty())
    }

    @Test
    fun `an ongoing call draws no end row, and a call at the end keeps its end row at the tail`() {
        val list = listOf(turn(100), turn(200, "a"))
        val live = callMarks(list, listOf(ConversationCall("a", 150L)))
        assertEquals(listOf("call-a"), live.before(1).keys())
        assertTrue(live.tail.isEmpty())
        val ended = callMarks(list, listOf(ConversationCall("a", 150L, 300L, spoke = true)))
        assertEquals(listOf("call-end-a"), ended.tail.keys())
    }

    @Test
    fun `a silent call is placed by its connect time, and goes last when no rounds follow`() {
        val list = listOf(turn(100), turn(300))
        val silent = ConversationCall("s", 200L, 205L)
        val marks = callMarks(list, listOf(silent))
        assertEquals(listOf(CallMark.Silent(silent)), marks.before(1))
        val late = ConversationCall("t", 400L, 405L)
        assertEquals(listOf(CallMark.Silent(late)), callMarks(list, listOf(late)).tail)
        val ongoing = ConversationCall("u", 400L)
        assertEquals("an ongoing call that has not hung up is drawn as a start", listOf(CallMark.Start(ongoing)), callMarks(list, listOf(ongoing)).tail)
    }

    @Test
    fun `a start row is drawn even when the call record is lost, timed from the first round`() {
        val marks = callMarks(listOf(turn(100), turn(200, "x")), emptyList())
        val start = marks.before(1).single()
        assertTrue(start is CallMark.Start)
        assertEquals(200L, start.call.startMs)
    }

    @Test
    fun `draws nothing when there are no calls`() {
        assertTrue(callMarks(listOf(turn(100)), emptyList()).isEmpty())
    }

    @Test
    fun `the call row is a list key, and jumping to the call lands on its start row`() {
        val call = ConversationCall("a", 150L, 400L, spoke = true)
        val list = listOf(turn(100), turn(200, "a"))
        val marks = callMarks(list, listOf(call))
        val keys = chatItemKeys(list, kept = null, phoneSetupRow = null, hasBlocked = false, calls = marks)
        val start = keys.indexOf("call-a")
        assertTrue(start > keys.indexOf("u0") && start < keys.indexOf("u1"))
        assertEquals("call-end-a", keys.last())
        assertEquals("call-a", chatTargetKey(list, ChatTarget.Call("a")))
        assertEquals(keys.size, chatItemCount(list, kept = null, phoneSetupRow = null, hasBlocked = false, calls = marks))
    }

    @Test
    fun `a single silent call shows no empty conversation`() {
        val marks = callMarks(emptyList(), listOf(ConversationCall("s", 1L, 5L)))
        val keys = chatItemKeys(emptyList(), kept = null, phoneSetupRow = null, hasBlocked = false, calls = marks)
        assertEquals(listOf("call-s"), keys)
    }
}
