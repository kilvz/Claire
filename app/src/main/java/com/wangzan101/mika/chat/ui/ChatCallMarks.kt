package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.chat.ConversationCall

/**
 * The rows of small, light text for voice calls on the chat page: whatever was said during a call still sits in the conversation turn by turn, with one "Voice call · 9:41"
 * row before and one "Call ended · 3:12" row after; a call with nothing said takes a single "Voice call · 0:05 · No speech" row, placed in the conversation by connect time.
 */
internal sealed interface CallMark {
    val call: ConversationCall
    /** The row before the first turn spoken during the call; also used while the call is still in progress, or when nothing was said but it has not hung up yet. */
    data class Start(override val call: ConversationCall) : CallMark
    /** The row after the last turn spoken during the call. */
    data class End(override val call: ConversationCall) : CallMark
    /** Hung up without anything being said: just this row. */
    data class Silent(override val call: ConversationCall) : CallMark
}

/** This row's key in the list. The start row (and the silent row) use [callStartKey]; jumping over from call history targets it. */
internal val CallMark.key: String
    get() = when (this) {
        is CallMark.End -> "call-end-${call.id}"
        else -> callStartKey(call.id)
    }

internal fun callStartKey(callId: String) = "call-$callId"

/** The call rows to insert before each turn ([before] by turn index), and those after all turns ([tail]). */
internal class CallMarks(private val before: Map<Int, List<CallMark>>, val tail: List<CallMark>) {
    fun before(index: Int): List<CallMark> = before[index].orEmpty()
    fun isEmpty() = before.isEmpty() && tail.isEmpty()

    companion object {
        val None = CallMarks(emptyMap(), emptyList())
    }
}

/**
 * Lays out the call rows from the stored turns ([Exchange.callId], [Exchange.atMs]) and the call records.
 * - A call with turns: a start row before the first turn and an end row after the last (no end row if it has not hung up yet); the start row is still drawn if the call record was lost, using the first turn's time.
 * - A call with no turns: placed before the first stored turn after it connected; if there are no stored turns after it, placed at the end.
 */
internal fun callMarks(exchanges: List<Exchange>, calls: List<ConversationCall>): CallMarks {
    if (calls.isEmpty() && exchanges.none { it.callId != null }) return CallMarks.None
    val byId = calls.associateBy { it.id }
    // (index of the turn to precede, sort time, row); an index equal to the turn count means placed at the end
    val placed = mutableListOf<Triple<Int, Long, CallMark>>()
    val segments = exchanges.withIndex().filter { it.value.callId != null }.groupBy { it.value.callId!! }
    segments.forEach { (id, rows) ->
        val first = rows.first().index
        val last = rows.last().index
        val call = byId[id] ?: ConversationCall(id, rows.first().value.atMs ?: 0L)
        placed += Triple(first, call.startMs, CallMark.Start(call))
        call.endMs?.let { placed += Triple(last + 1, it, CallMark.End(call)) }
    }
    calls.filter { it.id !in segments }.forEach { call ->
        val at = exchanges.indexOfFirst { ex -> ex.atMs.let { it == null || it > call.startMs } }.let { if (it < 0) exchanges.size else it }
        placed += Triple(at, call.startMs, if (call.endMs == null) CallMark.Start(call) else CallMark.Silent(call))
    }
    val sorted = placed.sortedWith(compareBy({ it.first }, { it.second }))
    val before = sorted.filter { it.first < exchanges.size }.groupBy({ it.first }, { it.third })
    return CallMarks(before, sorted.filter { it.first >= exchanges.size }.map { it.third })
}
