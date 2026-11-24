package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role

/**
 * Deterministic safety net after compaction: the user's own words (including interjections) and fixed
 * knowledge/skill rules must never disappear. Comparison ignores runtime state (folding removes it, yet the same
 * message still counts); anything genuinely missing is put back at its position in the full history rather than
 * appended to the end.
 */
internal object ContextRecovery {
    fun ensure(full: List<Message>, compressed: List<Message>): List<Message> {
        val userCounts = compressed.filter { it.author == Author.User }.groupingBy(::userKey).eachCount().toMutableMap()
        val ruleCounts = compressed.mapNotNull(::ruleKey).groupingBy { it }.eachCount().toMutableMap()
        fun <K> MutableMap<K, Int>.take(key: K): Boolean {
            val left = this[key] ?: 0
            if (left > 0) this[key] = left - 1
            return left > 0
        }
        val missing = full.indices.filter { i ->
            val m = full[i]
            if (m.author == Author.User) !userCounts.take(userKey(m)) else ruleKey(m)?.let { !ruleCounts.take(it) } ?: false
        }
        if (missing.isEmpty()) return compressed
        val origins = origins(full, compressed)
        val out = compressed.toMutableList()
        val at = origins.toMutableList<Int?>()
        for (i in missing) {
            // Put it before the first message that originates after it; do not wedge it between a call and its result
            var pos = at.indexOfFirst { it != null && it > i }.let { if (it < 0) out.size else it }
            while (pos < out.size && out[pos].role == Role.Tool) pos++
            out.add(pos, ContextBuilder.withoutRuntimeState(full[i]))
            at.add(pos, i)
        }
        return out
    }

    /** Which entry of the full history each compressed message originates from (recognised by call id or as-is; newly written ones such as summaries are null). */
    private fun origins(full: List<Message>, compressed: List<Message>): List<Int?> {
        val byId = HashMap<String, Int>()
        val byKey = HashMap<Message, ArrayDeque<Int>>()
        full.forEachIndexed { i, m ->
            ids(m).forEach { byId.putIfAbsent(it, i) }
            byKey.getOrPut(userKey(m)) { ArrayDeque() }.addLast(i)
        }
        return compressed.map { m -> ids(m).firstNotNullOfOrNull(byId::get) ?: byKey[userKey(m)]?.removeFirstOrNull() }
    }

    private fun ids(m: Message): List<String> = m.parts.mapNotNull {
        when (it) {
            is Part.ToolCall -> it.id
            is Part.ToolResult -> it.callId
            else -> null
        }
    }

    private fun userKey(m: Message): Message = ContextBuilder.withoutRuntimeState(m)

    private fun ruleKey(message: Message): Pair<ContextKind, String?>? =
        ContextBuilder.kindOf(message)?.takeIf { it.pinned }?.let { it to ContextBuilder.sourceOf(message) }
}
