package com.wangzan101.mika.capsule

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import com.wangzan101.mika.settings.ui.StatusCopy
import com.wangzan101.mika.chat.ui.answerParts
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.chat.ui.steerLabel
import com.wangzan101.mika.chat.ui.steersAroundDraft

/**
 * One entry in the chat card. [key] stays the same within a conversation (by run record id when there is one, otherwise by this turn's position in the conversation):
 * loading earlier turns upwards, or recording the running turn into the conversation, both keep the list anchored in place.
 */
sealed interface ThreadItem {
    val key: String

    /** What you said (the original utterance and interjections made while the task runs): the small bubble on the right; [note] is the line under an interjection showing its destination ('received, will adjust the current task next'), null when absent. */
    data class User(override val key: String, val text: String, val note: String? = null) : ThreadItem

    /** A one-line note: the line a turn that operated an app or web page is collapsed into ('✓ WeChat · done'), or the line for a pure chat that failed, stopped, or is still thinking. */
    data class Note(override val key: String, val text: String, val kind: CapsuleKind) : ThreadItem

    /**
     * The full text of Claire's answer (Markdown as-is, laid out in the card); while [streaming] it is still being written.
     * When [cut] it is the segment interrupted by an interjection (dimmed like on the conversation screen, with an 'interrupted by your new message' line below).
     */
    data class Answer(override val key: String, val text: String, val streaming: Boolean = false, val cut: Boolean = false) : ThreadItem
}

/** The back-and-forth of the chat card, oldest at the top and newest at the bottom; with [hasEarlier] there are older turns above that are not loaded; [title] is the conversation title (first sentence). */
data class ChatThread(val items: List<ThreadItem>, val hasEarlier: Boolean = false, val title: String? = null) {
    /** Which entry it is positioned at when opened: the latest thing you said is placed at the top of the visible area, so reading starts from this exchange; when there is nothing from you, from the top. */
    val anchor: Int get() = items.indexOfLast { it is ThreadItem.User }.coerceAtLeast(0)
}

/** A turn already recorded in the conversation, along with the apps and sites it operated ([places], read from the run record; empty for pure chat or when the record has been cleared). */
data class PastTurn(val turn: Turn, val places: List<String> = emptyList())

/**
 * How the chat card's exchange is ordered (pure logic): turns already recorded in the conversation (the same [com.wangzan101.mika.chat.ConversationStore] as the conversation screen) come first,
 * and the tasks in this conversation that are not recorded yet follow. A turn that operated an app keeps only one line and the answer, without steps, images, or sources.
 * It first lays out only the latest [PAGE] turns and lays out [PAGE] more each time it scrolls to the top.
 */
object CapsuleThreads {
    const val PAGE = 10

    /**
     * [past] are all the turns recorded for this conversation, laid out for the last [shown] turns; [live] are the tasks not recorded yet (see [liveTail]),
     * [livePlaces] gives the apps and sites they operated; [summarizing] are the runs (run ids) whose stop summary is still being written.
     */
    fun build(
        past: List<PastTurn>, shown: Int, live: List<CardRun>, summarizing: Set<String> = emptySet(),
        livePlaces: (CardRun) -> List<String> = { emptyList() },
    ): ChatThread {
        val from = (past.size - shown).coerceAtLeast(0)
        val items = (from until past.size).flatMap { i -> pastItems(i, past[i], summarizing) } +
            live.flatMap { liveItems(it, livePlaces(it), summarizing) }
        val title = past.firstOrNull()?.turn?.userText ?: live.firstOrNull()?.run?.goal
        return ChatThread(items, hasEarlier = from > 0, title = title?.takeIf(String::isNotBlank))
    }

    /**
     * Tasks in this conversation ([conversationId]) in the card that are not recorded in the conversation yet: when the recorded turn carries the same run record id (present in [recorded]), use the recorded copy;
     * ones that ended before a record was started (cancelled while queued) only have the recorded copy. Finished ones come first (earlier finish on top), running ones after.
     */
    fun liveTail(runs: List<CardRun>, conversationId: String, recorded: Set<String>): List<CardRun> {
        val mine = runs.filter { r ->
            r.run.conversationId == conversationId && (r.run.runId?.let { it !in recorded } ?: r.kind.active)
        }
        val (active, ended) = mine.partition { it.kind.active }
        return ended.reversed() + active
    }

    /** A recorded turn: what you said (and interjections), the line when an app was operated, and the answer or the reason for failure; for stopped ones it is the summary added after stopping. */
    fun pastItems(index: Int, t: PastTurn, summarizing: Set<String> = emptySet()): List<ThreadItem> {
        val turn = t.turn
        val base = turn.runId?.let { "r$it" } ?: "p$index"
        val kind = when (turn.status) {
            "succeeded", "unverified" -> CapsuleKind.Done
            "cancelled" -> CapsuleKind.Cancelled
            else -> CapsuleKind.Failed
        }
        val answer = when (kind) {
            CapsuleKind.Done -> answerParts(turn.outcome).first
            CapsuleKind.Failed -> turn.outcome
            else -> Conversation.stopSummary(turn).orEmpty()
        }
        // The original utterance first, then interjections; replies that finished before an interjection and the reply it interrupted are placed before it (same as the conversation screen)
        val users = listOfNotNull(turn.userText.takeIf(String::isNotBlank)?.let { ThreadItem.User("$base-u0", it) }) +
            turn.steers.withIndex().filter { it.value.isNotBlank() }.flatMap { (i, text) ->
                listOfNotNull(
                    turn.finishedReplies.getOrNull(i)?.takeIf(String::isNotBlank)?.let { ThreadItem.Answer("$base-f${i + 1}", it) },
                    turn.interruptedReplies.getOrNull(i)?.takeIf(String::isNotBlank)?.let { ThreadItem.Answer("$base-c${i + 1}", it, cut = true) },
                    ThreadItem.User("$base-u${i + 1}", text),
                )
            }
        return users + ended(base, kind, t.places, answer, pending = kind == CapsuleKind.Cancelled && turn.runId in summarizing)
    }

    /** A task not recorded in the conversation yet: while running the answer is written as it grows, while waiting for your answer or approval it is that question, and once finished it matches the recorded form. */
    fun liveItems(r: CardRun, places: List<String>, summarizing: Set<String> = emptySet()): List<ThreadItem> {
        val run = r.run
        val base = run.runId?.let { "r$it" } ?: "l${run.key}"
        // When the task is waiting for the user, steering only takes effect after it continues, so the small line under the interjection says so
        val paused = r.kind == CapsuleKind.NeedTakeover || r.kind == CapsuleKind.NeedConfirm ||
            r.kind == CapsuleKind.NeedAnswer || r.kind == CapsuleKind.NeedStep || r.kind == CapsuleKind.Held
        // The original utterance first, then interjections made while running (with their destinations, same wording as the conversation screen); replies that finished before an interjection and the reply it interrupted are placed before it.
        // Interjections not yet handed to the model are placed after the answer being written (same as the conversation screen, see steersAroundDraft)
        fun steer(n: SteerNote) = listOfNotNull(
            n.finished?.takeIf(String::isNotBlank)?.let { ThreadItem.Answer("$base-f${n.id}", it) },
            n.interrupted?.takeIf(String::isNotBlank)?.let { ThreadItem.Answer("$base-c${n.id}", it, cut = true) },
            ThreadItem.User("$base-s${n.id}", n.text, steerLabel(n.state, paused)),
        )
        val (joined, waiting) = steersAroundDraft(run.steers)
        val user = listOfNotNull(run.goal.takeIf(String::isNotBlank)?.let { ThreadItem.User("$base-u0", it) }) + joined.flatMap(::steer)
        val rest = when (val s = run.state) {
            is TaskState.Succeeded -> ended(base, CapsuleKind.Done, places, s.answer)
            is TaskState.Failed -> ended(base, CapsuleKind.Failed, places, s.reason)
            TaskState.Cancelled -> ended(base, CapsuleKind.Cancelled, places, "", pending = run.runId in summarizing)
            else -> {
                val note = if (places.isNotEmpty()) ThreadItem.Note("$base-n", appLine(places, r.kind), r.kind) else null
                val answer = when (val c = CapsuleCards.content(r)) {
                    is CardContent.Question -> ThreadItem.Answer("$base-a", c.prompt)
                    is CardContent.Confirm -> ThreadItem.Answer("$base-a", c.title + "\n" + c.body)
                    is CardContent.Takeover -> ThreadItem.Answer("$base-a", listOf(c.need, c.body).filter(String::isNotBlank).joinToString("\n"))
                    is CardContent.Held -> ThreadItem.Note("$base-a", c.why, r.kind)
                    else -> run.exchange?.draft?.takeIf(String::isNotBlank)
                        ?.let { ThreadItem.Answer("$base-a", it, streaming = true) }
                        // No text written yet: say what it is doing right now (the line already says it when inside an app)
                        ?: ThreadItem.Note("$base-a", r.line, r.kind).takeIf { note == null && r.line.isNotBlank() }
                }
                listOfNotNull(note, answer)
            }
        }
        return user + rest + waiting.flatMap(::steer)
    }

    /**
     * A finished turn: the line when an app was operated (pure chat only has a line when it failed or stopped), then the full answer.
     * When [pending], the stop summary is still being written: a 'tidying up what was done…' line appears under that line.
     */
    private fun ended(base: String, kind: CapsuleKind, places: List<String>, answer: String, pending: Boolean = false): List<ThreadItem> {
        val line = when {
            places.isNotEmpty() -> appLine(places, kind)
            kind == CapsuleKind.Failed -> CapsuleCards.status(kind)
            kind == CapsuleKind.Cancelled -> CapsuleCards.status(kind)
            else -> null
        }
        val text = answer.trim()
        return listOfNotNull(
            line?.let { ThreadItem.Note("$base-n", it, kind) },
            if (pending && text.isEmpty()) ThreadItem.Note("$base-s", StatusCopy.STOP_SUMMARIZING, kind) else null,
            text.takeIf(String::isNotEmpty)?.let { ThreadItem.Answer("$base-a", it) },
        )
    }

    /** The single line a turn that operated an app collapses into: '✓ WeChat, Taobao · done', '12306 · failed'; lists at most two places. */
    fun appLine(places: List<String>, kind: CapsuleKind): String {
        val where = places.take(2).joinToString(tr(R.string.list_sep)) + if (places.size > 2) tr(R.string.and_more) else ""
        val mark = if (kind == CapsuleKind.Done) "✓ " else ""
        return "$mark$where · ${CapsuleCards.status(kind)}"
    }

    /** The apps and sites this turn actually operated (targets are package names or 'site domain'), de-duplicated in order; package names are replaced with app names. */
    fun places(acts: List<RunEvent.Activity>, appLabel: (String) -> String): List<String> =
        acts.mapNotNull { a ->
            val t = a.target?.trim().orEmpty()
            when {
                t.startsWith(SITE_PREFIX) -> t.removePrefix(SITE_PREFIX).trim().ifEmpty { null }
                PACKAGE_NAME_RE.matches(t) -> appLabel(t)
                else -> null
            }
        }.distinct()

    /** In the run record a web page screen's target is written as SITE_PREFIX followed by the domain. */
    private const val SITE_PREFIX = "网站 "
}
