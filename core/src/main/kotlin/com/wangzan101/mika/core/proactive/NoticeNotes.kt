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

/** A validated segmented note line: [entries] are the notifications it was based on (indices within this batch, at least one), and [text] is the cleaned, redacted sentence. */
data class NoteSignal(val entries: List<Int>, val text: String)

/** Why a line was dropped (counting only, no content). */
enum class NoteDrop {
    /** Beyond [LearnTuning.NOTES_MAX]. */
    OverLimit,
    /** Not an object, or it has no text. */
    Malformed,
    /** A cited notification id is not in this batch (counted once per line that has one). */
    UnknownId,
    /** No valid notification ids at all: anything that cannot show its evidence is rejected. */
    NoIds,
    /** Fails the sensitive-information check (passwords, verification codes, card numbers and the like). */
    Sensitive,
}

sealed interface NotesResult {
    data class Noted(val notes: List<NoteSignal>, val dropped: Map<NoteDrop, Int>) : NotesResult
    /** [unreachable]: the model was never reached and the batch is kept; otherwise the batch counts as done (never sent again). Same meaning as [LearnResult.Failed]. */
    data class Failed(val unreachable: Boolean = false) : NotesResult
    data object BudgetExhausted : NotesResult
}

/**
 * Level 1 of the daily message digest: a batch of raw notifications from the same app (at most [LearnTuning.NOTES_BATCH_MAX] pieces) → a few sentences of "what happened".
 * Each sentence must point at the notifications it was based on (ids within this batch), and sentences that cannot are dropped: the levels above (day, week) work only on these sentences,
 * and evidence makes them checkable later. Only new sentences are added; earlier ones are never rewritten (rewriting the same summary repeatedly spreads errors further). Pure logic, with the model supplied by the caller.
 *
 * Injection defence is the same as [NoticeLearn]: one request only, an empty tool list, a timeout; notification text sits in [Untrusted]; one batch is one app;
 * only the trailing JSON value of the reply is read ([ReplyJson]) and it is strictly validated. URLs and long digit runs (tracking numbers, card numbers, phone numbers) inside sentences are redacted,
 * and whole sentences that fail [SensitiveFilter] are dropped.
 */
class NoticeNotes(private val model: ChatModel, private val language: String, private val timeoutMs: Long = LearnTuning.TIMEOUT_MS) {
    suspend fun note(packageName: String, entries: List<NoticeEntry>, zone: ZoneId): NotesResult {
        require(entries.isNotEmpty() && entries.size <= LearnTuning.NOTES_BATCH_MAX) { "batch size ${entries.size}" }
        require(entries.all { it.packageName == packageName }) { "one app per batch" }
        val messages = listOf(Message(Role.User, listOf(Part.Text(buildInput(packageName, entries, language, zone))), Author.Mika))
        val reply = try {
            withTimeoutOrNull(timeoutMs) { model.complete(Prompts.role(PROMPT), messages, emptyList()) }
                ?: return NotesResult.Failed()
        } catch (e: ModelError.ProactiveBudgetExceeded) {
            return NotesResult.BudgetExhausted
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return NotesResult.Failed()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return NotesResult.Failed(unreachable = e is ModelError.Network || e is ModelError.Proxy)
        }
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        return parse(text, entries.size) ?: NotesResult.Failed()
    }

    companion object {
        const val PROMPT = "notice_notes"

        fun entryId(index: Int): String = CitedLines.id("e", index)

        private val TAG = Regex("<\\s*/?\\s*untrusted", RegexOption.IGNORE_CASE)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        private fun untrusted(s: String): String = Untrusted.wrap(s.replace(TAG, "(untrusted"))

        /** Input sent to the model: the language, the package name, then each piece listed (the time and whether it is a group or private chat are our own and go outside the untrusted block). */
        fun buildInput(packageName: String, entries: List<NoticeEntry>, language: String, zone: ZoneId): String = buildString {
            appendLine("Language for notes: $language")
            appendLine("App package: $packageName")
            appendLine()
            appendLine("Notifications (oldest first):")
            entries.forEachIndexed { i, e ->
                appendLine()
                appendLine("[${entryId(i)}] time: ${TIME.format(Instant.ofEpochMilli(e.atMs).atZone(zone))}")
                e.group?.let { appendLine("chat: ${if (it) "group" else "private"}") }
                val title = NoticeFilter.clean(e.title, LearnTuning.TITLE_MAX)
                val said = NoticeFilter.clean(listOfNotNull(e.sender?.takeIf(String::isNotBlank), e.text).joinToString(": "), LearnTuning.TEXT_MAX)
                if (title.isNotEmpty()) appendLine("title: ${untrusted(title)}")
                if (said.isNotEmpty()) appendLine("text: ${untrusted(said)}")
            }
        }.trimEnd()

        /**
         * Reads the reply strictly: the trailing JSON value must be `{"notes":[…]}` or a plain array; other forms or an over-long reply are unreadable (null, and the batch counts as failed).
         * Each line must have at least one notification id from this batch and must have text; the text is cleaned, URLs and long digit runs are redacted, and it is cut to [LearnTuning.NOTE_MAX].
         */
        fun parse(raw: String, entryCount: Int): NotesResult.Noted? =
            CitedLines.parse(raw, "notes", "e", entryCount, LearnTuning.NOTES_MAX, LearnTuning.NOTE_MAX)?.let { NotesResult.Noted(it.lines, it.dropped) }
    }
}
