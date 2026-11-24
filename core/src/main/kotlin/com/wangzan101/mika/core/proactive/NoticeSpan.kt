package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.screen.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Level 3 (week) and level 4 (month) of the daily message digest: combines the next level's sentences into one digest for this week (this month) — the week digest's input is those days' daily digests,
 * and the month digest's input is those weeks' weekly digests. Only next-level sentences go into the request (written by the model, validated and redacted), never notification originals and never anything from levels further down.
 * The sentences ultimately come from words other people wrote, so they are wrapped in [Untrusted] as usual, no tools are offered, and only one request is made; each output line must point at the sentences it was based on,
 * and lines that cannot be traced are dropped (see [CitedLines]).
 *
 * Next-level sentences often contain relative expressions like "tomorrow" or "Friday", which become wrong once merged into a week or a month: each input line is prefixed with the day (or week) it came from,
 * which we write ourselves (outside the untrusted block), and the prompt requires the output to always be written as dates. App names are read from the system by the caller using the package name ([appLabel]).
 */
class NoticeSpan(private val model: ChatModel, private val language: String, private val timeoutMs: Long = LearnTuning.SPAN_TIMEOUT_MS) {
    /** [start] is this week's Monday or this month's first day (epoch day). The result has the same shape as the daily digest ([DayResult]). */
    suspend fun digest(kind: SpanKind, start: Long, input: List<SpanInput>, appLabel: (String) -> String): DayResult {
        require(input.isNotEmpty()) { "empty input" }
        val messages = listOf(Message(Role.User, listOf(Part.Text(buildInput(kind, start, input, language, appLabel))), Author.Mika))
        val reply = try {
            withTimeoutOrNull(timeoutMs) { model.complete(Prompts.role(prompt(kind)), messages, emptyList()) }
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
        return parse(text, input.size) ?: DayResult.Failed()
    }

    companion object {
        const val PROMPT_WEEK = "notice_week"
        const val PROMPT_MONTH = "notice_month"

        fun prompt(kind: SpanKind): String = if (kind == SpanKind.Week) PROMPT_WEEK else PROMPT_MONTH

        fun lineId(index: Int): String = CitedLines.id("d", index)

        private val TAG = Regex("<\\s*/?\\s*untrusted", RegexOption.IGNORE_CASE)
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd (EEE)", Locale.ENGLISH)

        private fun untrusted(s: String): String = Untrusted.wrap(s.replace(TAG, "(untrusted"))

        private fun date(day: Long): String = DATE.format(LocalDate.ofEpochDay(day))

        /**
         * Input sent to the model: the language, which days this week (month) covers, then the lines listed one by one. Dates and app names are our own; sentences sit inside the untrusted block.
         * Each week-digest line says which day it came from; each month-digest line says which week (Monday through Sunday) it came from.
         */
        fun buildInput(kind: SpanKind, start: Long, input: List<SpanInput>, language: String, appLabel: (String) -> String): String = buildString {
            appendLine("Language for the digest: $language")
            if (kind == SpanKind.Week) {
                appendLine("Week: ${date(start)} to ${date(start + 6)}")
                appendLine()
                appendLine("Lines from the daily digests of this week (oldest day first):")
            } else {
                appendLine("Month: ${DateTimeFormatter.ofPattern("yyyy-MM", Locale.ENGLISH).format(LocalDate.ofEpochDay(start))}")
                appendLine()
                appendLine("Lines from the weekly digests of this month (oldest week first):")
            }
            input.forEachIndexed { i, it ->
                appendLine()
                val whence = if (kind == SpanKind.Week) "date: ${date(it.from)}" else "week: ${date(it.from)} to ${date(it.from + 6)}"
                val apps = it.line.apps.map { p -> NoticeFilter.appName(appLabel(p), p) }.filter(String::isNotEmpty).distinct()
                appendLine("[${lineId(i)}] $whence" + if (apps.isEmpty()) "" else ", apps: ${apps.joinToString(", ")}")
                appendLine("line: ${untrusted(NoticeFilter.clean(it.line.text, LearnTuning.SPAN_LINE_MAX))}")
            }
        }.trimEnd()

        fun parse(raw: String, count: Int): DayResult.Done? =
            CitedLines.parse(raw, "lines", "d", count, LearnTuning.SPAN_LINES_MAX, LearnTuning.SPAN_LINE_MAX)?.let { DayResult.Done(it.lines, it.dropped, it.brief) }
    }
}
