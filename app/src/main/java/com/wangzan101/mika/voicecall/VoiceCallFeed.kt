package com.wangzan101.mika.voicecall

import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One item in the call page's conversation feed: a line of speech (you or Claire; partial when mid-utterance), or a job step card.
 * The call page only holds this call; the whole conversation lives on the chat page, and earlier calls are in "Call history".
 */
sealed interface VoiceCallFeedItem {
    val id: Long

    data class Said(
        override val id: Long,
        val speaker: Speaker,
        val text: String,
        val partial: Boolean,
    ) : VoiceCallFeedItem

    /** One task dispatched by the front desk: steps in the order they appeared, the last being the current one; [phase] is the task's current phase. */
    data class Task(
        override val id: Long,
        val taskId: String,
        val title: String,
        val steps: List<String>,
        val phase: DeskTaskPhase,
    ) : VoiceCallFeedItem {
        /** Not finished yet (running, queued, or needing you). */
        val running: Boolean get() = phase == DeskTaskPhase.Running || phase == DeskTaskPhase.Queued || phase == DeskTaskPhase.NeedsYou
    }

    enum class Speaker { User, Mika }
}

/**
 * The call page's conversation feed: lays out the conversation transcript (including mid-utterance text) and the tasks dispatched by the front desk in one column for the page to draw bubbles directly.
 * Display only; it writes nothing (writing back to chat is [VoiceCallTranscript]'s job). Callbacks may come from different threads, so it locks internally.
 *
 * - What you said: a partial segment replaces the same line, a final one settles it.
 * - What Claire said: a draft replaces the same line segment by segment, and the final (or non-final when interrupted) settles it.
 * - Jobs: one card per task dispatched by the front desk, see [onTasks].
 */
class VoiceCallFeed(private val maxItems: Int = MAX_ITEMS) {
    private val _items = MutableStateFlow<List<VoiceCallFeedItem>>(emptyList())
    val items: StateFlow<List<VoiceCallFeedItem>> = _items

    private val lock = Any()
    private var nextId = 0L
    /** Task ids already finished when the task board was first received (dispatched earlier, so no card); null before it has been received. */
    private var ignored: Set<String>? = null
    // recent 只有 3 条,收集方合并丢中间快照时会滚过中间步骤;快照自带的全程 steps 才是完整账目。
    private fun steps(view: DeskTaskView) =
        (view.steps.map { it.text } + view.recent + listOfNotNull(view.line)).distinct()

    fun onUser(text: String, isFinal: Boolean) = said(VoiceCallFeedItem.Speaker.User, text, partial = !isFinal)

    fun onAssistantDraft(text: String) = said(VoiceCallFeedItem.Speaker.Mika, text, partial = true)

    /** Settle one stretch of assistant speech; interrupted ones (isFinal=false) are settled too, keeping whatever was said. */
    @Suppress("UNUSED_PARAMETER")
    fun onAssistant(text: String, isFinal: Boolean) = said(VoiceCallFeedItem.Speaker.Mika, text, partial = false)

    /**
     * This conversation's task board for the call: one card per task (by task id), appended to the end of the feed when it first appears,
     * then each new progress line adds a step and the phase follows. Anything already finished when first received is an older task and gets no card.
     */
    fun onTasks(views: List<DeskTaskView>) {
        synchronized(lock) {
            val baseline = ignored ?: views.filter { it.state in ENDED }.map { it.taskId }.toSet().also { ignored = it }
            var current = _items.value
            // Cards already shown in this call keep updating; ones with no card that are already finished are older and get none
            for (view in views.filter { it.taskId !in baseline || hasCard(current, it.taskId) }) current = withTask(current, view)
            if (current !== _items.value) publish(current)
        }
    }

    private fun hasCard(current: List<VoiceCallFeedItem>, taskId: String) =
        current.any { it is VoiceCallFeedItem.Task && it.taskId == taskId }

    private fun withTask(current: List<VoiceCallFeedItem>, view: DeskTaskView): List<VoiceCallFeedItem> {
        val seen = steps(view)
        val index = current.indexOfFirst { it is VoiceCallFeedItem.Task && it.taskId == view.taskId }
        if (index < 0) return current + VoiceCallFeedItem.Task(nextId++, view.taskId, view.title, seen, view.state)
        val card = current[index] as VoiceCallFeedItem.Task
        val steps = card.steps + seen.filter { it !in card.steps }
        if (steps == card.steps && view.state == card.phase) return current
        return current.toMutableList().also { it[index] = card.copy(steps = steps, phase = view.state) }
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

    /** Beyond [maxItems] items only the newest are kept (every one is fully on the chat page). */
    private fun publish(items: List<VoiceCallFeedItem>) {
        _items.value = if (items.size > maxItems) items.takeLast(maxItems) else items
    }

    companion object {
        const val MAX_ITEMS = 80

        private val ENDED = setOf(DeskTaskPhase.Done, DeskTaskPhase.Failed, DeskTaskPhase.Cancelled)
    }
}
