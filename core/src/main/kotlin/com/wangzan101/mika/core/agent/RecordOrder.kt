package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink

/**
 * Run records are written in causal order: observations read during a call and recorded into the evidence ledger
 * ([observed]) are buffered first and written out right after that call's tool result; reads without a tool result
 * (the screen read on hand-back, the glance after a batch finishes) are written before the next other event. Nothing
 * is inserted between a model reply and its tool result.
 */
internal class RecordOrder(private val sink: RunSink) : RunSink {
    private val pending = mutableListOf<RunEvent.Observed>()

    fun observed(e: RunEvent.Observed) {
        synchronized(pending) { pending += e }
    }

    override fun event(e: RunEvent) {
        if (e !is RunEvent.ModelReply && e !is RunEvent.ToolResult) flush()
        sink.event(e)
        if (e is RunEvent.ToolResult) flush()
    }

    override fun media(mediaId: Int, png: ByteArray) = sink.media(mediaId, png)

    private fun flush() {
        val out = synchronized(pending) { pending.toList().also { pending.clear() } }
        out.forEach(sink::event)
    }
}
