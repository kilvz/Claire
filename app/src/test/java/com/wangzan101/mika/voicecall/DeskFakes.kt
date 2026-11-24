package com.wangzan101.mika.voicecall

import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSink
import com.wangzan101.mika.core.frontdesk.DeskTaskPort
import com.wangzan101.mika.core.frontdesk.DispatchOutcome
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/** Fake backend: each dispatched item opens t1, t2, ...; the test changes their states; records dispatches, additions, answers, and cancellations. */
internal class FakeDeskPort : DeskTaskPort {
    val states = LinkedHashMap<String, MutableStateFlow<TaskState>>()
    val dispatched = mutableListOf<Triple<String, String, String>>()
    val answers = mutableListOf<Pair<String, String>>()
    val steers = mutableListOf<Pair<String, String>>()
    val cancels = mutableListOf<String>()

    @Synchronized
    override fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome {
        dispatched += Triple(words, note, parentConversation)
        val id = "t${dispatched.size}"
        states[id] = MutableStateFlow(TaskState.Running(1, "开始"))
        return DispatchOutcome.Started(id)
    }

    override fun state(taskId: String): StateFlow<TaskState>? = states[taskId]
    override fun queuePosition(taskId: String): Int? = null
    override fun steer(taskId: String, words: String): Boolean { steers += taskId to words; return true }
    override fun answer(taskId: String, words: String): Boolean { answers += taskId to words; return true }
    override fun cancel(taskId: String): Boolean { cancels += taskId; return true }
}

internal class FakeDeskSink : DeskSink {
    val recorded = mutableListOf<Pair<String, DeskReport>>()
    override suspend fun record(conversationId: String, report: DeskReport) { recorded += conversationId to report }
}

/** Fake call session: events are fed by the test; records tool results and notices to be spoken. */
internal class FakeDeskRealtime : CallRealtimeSession {
    private val incoming = Channel<VolcRealtimeEvent>(Channel.UNLIMITED)
    override val events: Flow<VolcRealtimeEvent> = flow { for (event in incoming) emit(event) }
    val toolResults = mutableListOf<Pair<String, JsonObject>>()
    val notices = mutableListOf<String>()
    val typed = mutableListOf<String>()
    var acceptsTyped = false

    fun emit(event: VolcRealtimeEvent) { check(incoming.trySend(event).isSuccess) }
    override fun sendPcm16(bytes: ByteArray, byteCount: Int) = true
    override fun sendGreeting(text: String) = true
    override fun setMuted(muted: Boolean) = true
    override fun sendToolResult(callId: String, result: JsonObject): Boolean { toolResults += callId to result; return true }
    /** When false, whether a report finished speaking is decided by the test via [endNotice]; by default handing it over counts as finished. */
    var endsAtOnce = true
    private val unfinished = ArrayDeque<(Boolean) -> Unit>()
    override fun sendNotice(text: String, ended: (spoken: Boolean) -> Unit): Boolean {
        notices += text
        if (endsAtOnce) ended(true) else unfinished.addLast(ended)
        return true
    }
    fun endNotice(spoken: Boolean) = unfinished.removeFirst()(spoken)
    override fun sendUserText(text: String): Boolean { typed += text; return acceptsTyped }
    override fun close() {}
}
