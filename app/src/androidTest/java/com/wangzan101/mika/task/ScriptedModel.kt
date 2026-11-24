package com.wangzan101.mika.task

import com.wangzan101.mika.DeviceTaskState
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.safety.verify.Verifier
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** Scripted model for device tests: on every complete it reports in first (call number, history received), then waits for the test to feed the next tool call. */
class ScriptedModel : ChatModel {
    class Call(val number: Int, val messages: List<Message>)

    override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)
    private val calls = Channel<Call>(Channel.UNLIMITED)
    private val replies = Channel<List<Part>>(Channel.UNLIMITED)
    private val n = AtomicInteger()
    /** Number of pre-completion verification requests received. */
    val verifierRequests = AtomicInteger()
    /** Number of requests to write the post-stop summary; answers with [stopSummary] (blank by default, since the summary is assembled straight from the record), which neither consumes scripted steps nor counts as a call. */
    val summaryRequests = AtomicInteger()
    @Volatile var stopSummary: String = ""
    /** Fixed answer for verification; passes by default. */
    @Volatile var verdict: String = PASS
    /**
     * When a task started from the UI proposes its first tool call, it asks the model once more whether the user is discussing, wants to read, or wants to act; with no light model configured, this model is the one asked.
     * It answers ACT by default (the test scripts are all action tasks), which neither consumes scripted steps nor counts as a call; set it to READ / DISCUSS to test other judgments,
     * and set it to null to have the test feed this call like any other.
     */
    @Volatile var intent: String? = "ACT"

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        if (system == Prompts.role("task_stages")) return ModelReply(listOf(Part.Text("[]")), StopReason.EndTurn, null)
        // Verification before the answer is handed over: fixed answer, neither consumes scripted steps nor counts as a call
        if (system == Verifier.PROMPT) {
            verifierRequests.incrementAndGet()
            return ModelReply(listOf(Part.Text(verdict)), StopReason.EndTurn, null)
        }
        if (system == SUMMARY_PROMPT) {
            summaryRequests.incrementAndGet()
            return ModelReply(listOf(Part.Text(stopSummary)), StopReason.EndTurn, null)
        }
        // Generating a title for a new conversation: fixed answer, neither consumes scripted steps nor counts as a call
        if (system == TITLE_PROMPT) return ModelReply(listOf(Part.Text("测试对话")), StopReason.EndTurn, null)
        intent?.let { if (system == INTENT_PROMPT) return ModelReply(listOf(Part.Text(it)), StopReason.EndTurn, null) }
        calls.send(Call(n.incrementAndGet(), messages))
        val parts = replies.receive()
        return ModelReply(parts, if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.EndTurn, null)
    }

    /** [say] is what the model says along with this step (shown under the step in the UI). */
    fun feed(name: String, args: String = "{}", say: String? = null) =
        replies.trySend(listOfNotNull(say?.let { Part.Text(it) }, Part.ToolCall("c${n.get()}", name, args)))

    /** A reply with no tool call: the final answer handed to the user, which ends the task (it is sent back once if the verification does not match). */
    fun answer(text: String) = replies.trySend(listOf(Part.Text(text)))

    companion object {
        private val INTENT_PROMPT by lazy { Prompts.role("intent_gate") }
        private val SUMMARY_PROMPT by lazy { Prompts.role("stop_summary") }
        private val TITLE_PROMPT by lazy { Prompts.role("conversation_title") }
        const val PASS = """{"checks": [{"criterion": "屏幕上显示了要的结果", "status": "met", "quote": null}], "verdict": "pass", "reason": "脚本"}"""
        const val CANNOT_VERIFY = """{"checks": [{"criterion": "屏幕上显示了要的结果", "status": "unknown", "quote": null}], "verdict": "cannot_verify", "reason": "脚本:看不到结果"}"""
    }

    fun awaitCall(): Call = runBlocking {
        withTimeoutOrNull(30_000) { calls.receive() } ?: error("model not called; task state ${DeviceTaskState.state.value}")
    }
}
