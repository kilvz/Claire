package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

/**
 * Wraps [inner] in a streaming layer used to test interruption by a follow-up message: the first request emits [chunks] in order (each is the full visible text so far,
 * an empty string means start over and clear), then stops until it is cancelled; this request is not recorded in [inner]. Later requests reply according to [inner]'s script, sending the whole text at once.
 */
class InterruptibleStream(private val inner: FakeChatModel, private val chunks: List<String>) : StreamingChatModel {
    override val name: String get() = inner.name
    override val capabilities: Capabilities get() = inner.capabilities
    /** The first request has finished emitting [chunks] and is now stopped. */
    val entered = CompletableDeferred<Unit>()
    /** The first request has been cancelled. */
    var cancelled = false
        private set
    private var first = true

    /** When asked without streaming (no live text in the UI) the first request also stops, except that nothing is emitted. */
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        if (first) {
            first = false
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        return inner.complete(system, messages, tools)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        if (first) {
            first = false
            chunks.forEach(onText)
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        return inner.complete(system, messages, tools).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
    }
}
