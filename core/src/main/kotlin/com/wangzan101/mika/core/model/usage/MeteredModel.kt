package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.time.Instant

/** Which purpose a single call was issued for. The [key] is recorded in storage; an unknown key (written by a later version) reads as [Other]. */
enum class UsagePurpose(val key: String) {
    Main("main"), Vision("vision"), Light("light"), Other("other"), Proactive("proactive");

    companion object {
        fun of(role: ModelRole): UsagePurpose = when (role) {
            ModelRole.Main -> Main
            ModelRole.Vision -> Vision
            ModelRole.Light -> Light
        }

        fun of(key: String): UsagePurpose = entries.firstOrNull { it.key == key } ?: Other
    }
}

/** Whose model this is and which purpose it was built for: carried as-is when usage is recorded. [providerName] is the provider's name when the model was built (kept even after the provider is deleted). */
data class UsageSource(
    val providerId: String, val providerName: String, val model: String, val purpose: UsagePurpose,
    val deepSeekDirect: Boolean = false,
)

/**
 * The result of one logical call: a null [usage] means the provider did not report usage (or the call failed), so no tokens are
 * estimated.
 * [httpAttempts] is the number of inference requests actually sent during this call; the two-argument form is kept for observers
 * that do not care about request counts.
 */
fun interface UsageSink {
    fun record(source: UsageSource, usage: Usage?)

    fun record(source: UsageSource, usage: Usage?, httpAttempts: Int) = record(source, usage)
}

/**
 * Wraps a model with metering: a record is made at the end of every [ChatModel.complete] (and its incremental-text path), with
 * tokens recorded on success when usage is present.
 * Every HTTP request the adapter resends for compatible services counts toward the request count, while the tokens of the final
 * reply are recorded only once; local failures that sent no request do not count as HTTP requests.
 * Transparent to the caller: name and capabilities are unchanged, exceptions are thrown as-is, incremental text is passed
 * through as usual; if the inner model supports incremental text, so does the wrapper.
 * An error in bookkeeping itself does not affect the call.
 */
fun ChatModel.metered(
    source: UsageSource, sink: UsageSink,
    /**
     * Called by the adapter before it sends another request within the same call (compatible retry, resend after a token
     * refresh); the second argument is the number of requests already sent for this call — they are only recorded in the ledger
     * once the call ends, so a caller that enforces a per-count limit must add them itself. Throwing prevents the request, and
     * this call ends with that error.
     */
    beforeRetry: (UsageSource, Int) -> Unit = { _, _ -> },
    beforeCall: (UsageSource) -> Unit = {},
): ChatModel =
    if (this is StreamingChatModel) MeteredStreamingModel(this, source, sink, beforeCall, beforeRetry)
    else MeteredModel(this, source, sink, beforeCall, beforeRetry)

private open class MeteredModel(
    private val inner: ChatModel,
    protected val source: UsageSource,
    protected val sink: UsageSink,
    private val beforeCall: (UsageSource) -> Unit,
    private val beforeRetry: (UsageSource, Int) -> Unit,
) : ChatModel {
    override val capabilities get() = inner.capabilities
    override val name: String get() = inner.name

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        counted(system, messages, tools) { inner.complete(system, messages, tools) }

    protected suspend fun counted(system: String, messages: List<Message>, tools: List<ToolSpec>, call: suspend () -> ModelReply): ModelReply {
        // Calls made inside a run are also recorded under that run (see RunUsageTally)
        val context = currentCoroutineContext()
        val tally = context[RunUsageTally]
        val conversation = context[ConversationMeter]
        // On rejection no request counter is created, the adapter is not invoked, and no non-existent logical call is recorded.
        tally?.checkBudget()
        beforeCall(source)
        val step = context[ConversationMeter.MainRequest]?.step
        if (step != null && conversation != null) {
            runCatching { conversation.request(RequestComposition.estimate(name, step, capabilities.contextTokens, system, tools, messages)) }
        }
        val attempts = ModelHttpAttempts { started -> beforeRetry(source, started) }
        val startedAt = tally?.deepSeekCost?.let { Instant.now() }
        val reply = try {
            withContext(attempts) { call() }
        } catch (e: Throwable) {
            runCatching { conversation?.record(null, attempts.started) }
            note(null, tally, attempts.started, startedAt)
            throw e
        }
        runCatching { conversation?.record(reply.usage, attempts.started) }
        note(reply.usage, tally, attempts.started, startedAt)
        return reply
    }

    private fun note(usage: Usage?, tally: RunUsageTally?, httpAttempts: Int, startedAt: Instant?) {
        runCatching { sink.record(source, usage, httpAttempts) }
        tally?.add(usage, httpAttempts)
        if (startedAt != null) tally?.deepSeekCost?.add(source.model.takeIf { source.deepSeekDirect }, startedAt, usage, httpAttempts)
    }
}

private class MeteredStreamingModel(
    private val inner: StreamingChatModel,
    source: UsageSource,
    sink: UsageSink,
    beforeCall: (UsageSource) -> Unit,
    beforeRetry: (UsageSource, Int) -> Unit,
) : MeteredModel(inner, source, sink, beforeCall, beforeRetry), StreamingChatModel {
    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply = counted(system, messages, tools) { inner.completeStreaming(system, messages, tools, onText) }
}
