package com.wangzan101.mika.voicecall

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One entry in the call page's conversation feed: a sentence (you/Claire, the half-spoken one is partial), or a task step card. */
sealed interface VoiceCallFeedItem {
    val id: Long

    data class Said(override val id: Long, val speaker: Speaker, val text: String, val partial: Boolean) : VoiceCallFeedItem

    /** One task run: steps are ordered by appearance, the last one is the current step; when [running] is false it has finished (success is judged by the last step's wording). */
    data class Task(override val id: Long, val steps: List<String>, val running: Boolean) : VoiceCallFeedItem

    enum class Speaker { User, Mika }
}

/**
 * The call page's conversation feed: lays out the session transcript (including half-spoken parts) and the route's task status in one column, for the page to draw bubbles directly.
 * Display only, no storage (writing back to the chat is [VoiceCallTranscript]'s job). The callbacks may come from different threads, so it locks internally.
 *
 * - What you said: partial segments replace the same entry, final settles it.
 * - What Claire said: drafts replace the same entry piece by piece, final (or the non-final one when interrupted) settles it.
 * - Tasks: a card is opened when the route becomes busy, then each new status line adds a step, and the card is closed when busy drops.
 */
class VoiceCallFeed(private val maxItems: Int = MAX_ITEMS) {
    private val _items = MutableStateFlow<List<VoiceCallFeedItem>>(emptyList())
    val items: StateFlow<List<VoiceCallFeedItem>> = _items

    private val lock = Any()
    private var nextId = 0L
    private var openTaskId: Long? = null
    private var taskBaseline: List<String> = emptyList()

    fun onUser(text: String, isFinal: Boolean) = said(VoiceCallFeedItem.Speaker.User, text, partial = !isFinal)

    fun onAssistantDraft(text: String) = said(VoiceCallFeedItem.Speaker.Mika, text, partial = true)

    /** Settle one stretch of assistant speech; interrupted ones (isFinal=false) are settled too, keeping whatever was said. */
    @Suppress("UNUSED_PARAMETER")
    fun onAssistant(text: String, isFinal: Boolean) = said(VoiceCallFeedItem.Speaker.Mika, text, partial = false)

    /** The route's task status: [busy] whether a task is running, [lines] the route's scrolling status lines (newest last). */
    fun onTask(busy: Boolean, lines: List<String>) = synchronized(lock) {
        val open = openTaskId
        if (open == null) {
            if (!busy) return@synchronized
            // The status lines at card-opening time are left over from the previous task and do not count as this run's steps
            taskBaseline = lines
            val id = nextId++
            openTaskId = id
            publish(_items.value + VoiceCallFeedItem.Task(id, emptyList(), running = true))
            return@synchronized
        }
        var current = _items.value
        val latest = lines.lastOrNull()
        if (lines != taskBaseline && latest != null) {
            taskBaseline = lines
            current = current.map { item ->
                if (item is VoiceCallFeedItem.Task && item.id == open && item.steps.lastOrNull() != latest) {
                    item.copy(steps = item.steps + latest)
                } else item
            }
        }
        if (!busy) {
            openTaskId = null
            current = current.map { if (it is VoiceCallFeedItem.Task && it.id == open) it.copy(running = false) else it }
        }
        publish(current)
    }

    private fun said(speaker: VoiceCallFeedItem.Speaker, text: String, partial: Boolean) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        synchronized(lock) {
            val current = _items.value
            val index = current.indexOfLast { it is VoiceCallFeedItem.Said && it.speaker == speaker && it.partial }
            publish(
                if (index >= 0) {
                    val item = current[index] as VoiceCallFeedItem.Said
                    current.toMutableList().also { it[index] = item.copy(text = clean, partial = partial) }
                } else {
                    current + VoiceCallFeedItem.Said(nextId++, speaker, clean, partial)
                },
            )
        }
    }

    private fun publish(items: List<VoiceCallFeedItem>) {
        _items.value = if (items.size > maxItems) items.takeLast(maxItems) else items
    }

    companion object {
        const val MAX_ITEMS = 80
    }
}
