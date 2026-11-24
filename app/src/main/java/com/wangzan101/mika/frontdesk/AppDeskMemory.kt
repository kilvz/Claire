package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.core.learn.memory.MemoryIndex
import com.wangzan101.mika.core.learn.memory.MemoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/** Total maximum number of characters of related memories placed into the front desk context. */
const val DESK_MEMORY_MAX_CHARS = 1_500

/** Maximum number of related memories taken per utterance. */
private const val DESK_MEMORY_ITEMS = 5

/** Maximum wait for one memory lookup; if reading the memory file stalls, only this section is dropped and the front desk keeps talking. */
const val DESK_MEMORY_TIMEOUT_MS = 3_000L

/**
 * Related memories for each user utterance at the front desk: when the memory master switch ([enabled]) is on, search by
 * this utterance, one per line, total length no more than [DESK_MEMORY_MAX_CHARS]; null when the switch is off, nothing
 * was found, or [timeoutMs] elapses before reading completes. Reads local memories only, no network.
 */
class AppDeskMemory(
    private val enabled: () -> Boolean,
    private val items: () -> List<MemoryItem>,
    private val timeoutMs: Long = DESK_MEMORY_TIMEOUT_MS,
) {
    suspend fun recall(text: String): String? {
        if (!enabled()) return null
        return withTimeoutOrNull(timeoutMs) {
            runInterruptible(Dispatchers.IO) { render(MemoryIndex(items()).search(text, DESK_MEMORY_ITEMS).map { it.first }) }
        }
    }

    private fun render(found: List<MemoryItem>): String? {
        val out = StringBuilder()
        for (item in found) {
            val line = "- " + item.content
            val next = if (out.isEmpty()) line else "\n$line"
            if (out.length + next.length > DESK_MEMORY_MAX_CHARS) {
                // If the first entry is already too long, truncate it and use it; anything else that does not fit is dropped.
                if (out.isEmpty()) out.append(line.takeWhole(DESK_MEMORY_MAX_CHARS))
                break
            }
            out.append(next)
        }
        return out.toString().ifBlank { null }
    }
}
