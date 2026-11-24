package com.wangzan101.mika.voicecall

import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSurface
import com.wangzan101.mika.core.frontdesk.DeskToolExecutor
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.FrontDesk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The layer between the in-app voice call and the front desk:
 * - Front desk tools called by the model during a call go to [executor]; dispatches get a result immediately and the task runs in the background, so the call does not wait;
 * - front desk reports become one line via [copy] and are handed to the current call session [session] to be said after the current sentence; when the session is already gone it returns false,
 *   and when a barge-in interrupts it it throws cancellation; in either case the front desk hands it to the sink instead (write back to the conversation, send a notification). What was said is handed to [onSpoken] (for the echo window).
 *
 * User barge-in only interrupts what is being said during the call, never background tasks.
 */
class VoiceDeskBridge(
    private val desk: FrontDesk,
    private val executor: DeskToolExecutor,
    private val session: () -> CallRealtimeSession?,
    private val copy: (DeskReport) -> String,
    private val onSpoken: (String) -> Unit = {},
) : DeskSurface {
    /** Attach to the front desk; after closing the returned handle all reports go to the sink. */
    fun attach(): AutoCloseable = desk.attach(this)

    /**
     * Attach the front desk following the call state, regardless of whether the call page is hidden: stay attached while it has not ended; release on failure (reports go to the chat page or the fallback) and attach again once the user reconnects;
     * on Ended release and return, so the chat page can attach the front desk back. Also releases when cancelled.
     */
    suspend fun attachWhileLive(state: StateFlow<VoiceCallState>) {
        var handle: AutoCloseable? = null
        try {
            state.takeWhile { it !is VoiceCallState.Ended }.collect {
                if (it is VoiceCallState.Failed) {
                    handle?.close()
                    handle = null
                } else if (handle == null) {
                    handle = attach()
                }
            }
        } finally {
            handle?.close()
        }
    }

    /** Execute one tool call and send the result back to the call session; returns the result itself. */
    suspend fun onFunctionCall(callId: String, name: String, args: JsonObject): JsonObject {
        val result = execute(name, args)
        session()?.sendToolResult(callId, result)
        return result
    }

    /**
     * Execute one front desk tool call. If the user words exceed the one-utterance limit they are rejected outright, never truncated (truncated words are no longer the user's own);
     * other fields (note, title, task_id) are truncated to the tool argument limits.
     */
    suspend fun execute(name: String, args: JsonObject): JsonObject {
        val words = (args["words"] as? JsonPrimitive)?.content
        if (words != null && words.length > VolcRealtimeProtocol.MAX_UTTERANCE_CHARS) return WORDS_TOO_LONG
        val bounded = JsonObject(
            args.mapValues { (key, value) ->
                if (key == "words" || value !is JsonPrimitive || !value.isString) value
                else JsonPrimitive(value.content.take(VolcRealtimeProtocol.MAX_FUNCTION_ARGUMENT_CHARS))
            },
        )
        return executor.execute(name, bounded)
    }

    /** When the session cannot take typing directly, dispatch the typed text as-is as a task (the title takes the first few characters). */
    suspend fun dispatchTyped(text: String): JsonObject = execute(
        DeskTools.DISPATCH,
        buildJsonObject { put("words", text) },
    )

    /**
     * Hand it to the call to say and return only once the sentence is finished (the front desk delivers them one at a time accordingly). If a barge-in interrupts mid-sentence, or it is not our turn and the call drops,
     * it throws [CancellationException] by the front desk's contract: the front desk hands this one to the sink to write back to the conversation instead, and it is not read a second time.
     */
    override suspend fun deliver(report: DeskReport): Boolean {
        val live = session() ?: return false
        val text = copy(report).ifBlank { return true }
        val end = CompletableDeferred<Boolean>()
        if (!live.sendNotice(text) { end.complete(it) }) return false
        onSpoken(text)
        if (!end.await()) throw CancellationException("notice cut off")
        return true
    }

    private companion object {
        val WORDS_TOO_LONG = buildJsonObject { put("status", "rejected"); put("reason", "words_too_long") }
    }
}
