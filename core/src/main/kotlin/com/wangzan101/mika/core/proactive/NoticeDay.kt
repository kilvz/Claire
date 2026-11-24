package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.screen.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

sealed interface DayResult {
    /** Each line in [lines] has [NoteSignal.entries] pointing at the segmented notes it was based on (indices within this request). */
    /** [brief]: a one-sentence overview of this digest (see [CitedLines.Parsed.brief]); null when there is none. */
    data class Done(val lines: List<NoteSignal>, val dropped: Map<NoteDrop, Int>, val brief: String? = null) : DayResult
    data class Failed(val unreachable: Boolean = false) : DayResult
    data object BudgetExhausted : DayResult
}

/**
 * Level 2 of the daily message digest: combines the per-app segmented notes ([DigestNote], level 1) of one day into a single digest for that day.
 * This is the first time multiple apps go into the same request: only level-1 sentences are included (written by the model from notifications, already validated and redacted), never raw notification text.
 * The sentences themselves still come from words other people wrote, so they are wrapped in [Untrusted] as usual, no tools are offered, and only one request is made; each output line must point at the segmented notes it was based on,
 * and lines that cannot be traced are dropped (see [CitedLines] for how the reply is read). App names are read from the system by the caller using the package name ([appLabel]) and are written outside the untrusted block.
 */
class NoticeDay(private val model: ChatModel, private val language: String, private val timeoutMs: Long = LearnTuning.TIMEOUT_MS) {
    suspend fun digest(notes: List<DigestNote>, zone: ZoneId, appLabel: (String) -> String): DayResult {
        require(notes.isNotEmpty() && notes.size <= LearnTuning.DAY_NOTES_MAX) { "notes ${notes.size}" }
        val messages = listOf(Message(Role.User, listOf(Part.Text(buildInput(notes, language, zone, appLabel))), Author.Mika))
        val reply = try {
            withTimeoutOrNull(timeoutMs) { model.complete(Prompts.role(PROMPT), messages, emptyList()) }
                ?: return DayResult.Failed()
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            return DayResult.BudgetExhausted
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return DayResult.Failed()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return DayResult.Failed(unreachable = e is ModelError.Network || e is ModelError.Proxy)
        }
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        return parse(text, notes.size) ?: DayResult.Failed()
    }

    companion object {
        const val PROMPT = "notice_day"

        fun noteId(index: Int): String = CitedLines.id("s", index)

        private val TAG = Regex("<\\s*/?\\s*untrusted", RegexOption.IGNORE_CASE)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        private fun untrusted(s: String): String = Untrusted.wrap(s.replace(TAG, "(untrusted"))

        /**
         * Input sent to the model: the language, then the lines listed in time order. Times are our own; chat names and sentences sit inside the untrusted block;
         * app names are outside, are the names the apps gave themselves, and have been cleaned once ([NoticeFilter.appName]).
         */
        fun buildInput(notes: List<DigestNote>, language: String, zone: ZoneId, appLabel: (String) -> String): String = buildString {
            appendLine("Language for the digest: $language")
            appendLine()
            appendLine("Notes from today (oldest first):")
            notes.forEachIndexed { i, n ->
                appendLine()
                val from = TIME.format(Instant.ofEpochMilli(n.fromMs).atZone(zone))
                val to = TIME.format(Instant.ofEpochMilli(n.toMs).atZone(zone))
                appendLine("[${noteId(i)}] app: ${NoticeFilter.appName(appLabel(n.packageName), n.packageName)}, time: ${if (from == to) from else "$from-$to"}")
                n.title?.let { NoticeFilter.clean(it, LearnTuning.CONTACT_NAME_MAX) }?.takeIf(String::isNotEmpty)?.let { appendLine("chat: ${untrusted(it)}") }
                appendLine("note: ${untrusted(NoticeFilter.clean(n.text, LearnTuning.NOTE_MAX))}")
            }
        }.trimEnd()

        fun parse(raw: String, noteCount: Int): DayResult.Done? =
            CitedLines.parse(raw, "lines", "s", noteCount, LearnTuning.DAY_LINES_MAX, LearnTuning.DAY_LINE_MAX)?.let { DayResult.Done(it.lines, it.dropped, it.brief) }
    }
}
