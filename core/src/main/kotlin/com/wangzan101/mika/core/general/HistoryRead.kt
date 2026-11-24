package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.screen.observation.Trust
import com.wangzan101.mika.core.screen.observation.Untrusted
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunRecords
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.okObj
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val HISTORY_READ = "history_read"

/**
 * Reads records of earlier turns of this conversation: without turn, lists the earlier turns (newest first: turn number, what the user said, outcome, time);
 * with turn, reads what was recorded for that turn (full outcome, acceptance and confirmed progress, Claire's progress notes, text that was read), paginated item by item.
 * Only the turns brought in at the start of this run are recognized ([com.wangzan101.mika.core.run.RunSpec.priorTurns], same conversation), and access is read-only;
 * the text comes from the run record (already redacted and truncated when written to disk), without screenshots. The text that is read is handed to verification as observations and may be quoted in the answer.
 * Offered only when a run record is available and there are earlier turns with a recorded run.
 */
internal object HistoryRead : AgentTool {
    /** Maximum number of turns listed (newest first). */
    const val LIST_MAX = 20
    /** Roughly how many characters of item text fit in one page (at least one item is always given). */
    const val PAGE_CHARS = 8_000
    /** Maximum number of characters kept per text item. */
    const val ITEM_CHARS = 2_000
    /** Maximum number of characters kept for the outcome when reading a turn. */
    const val OUTCOME_CHARS = 3_000
    /** Characters kept for the user's words and the outcome in the list. */
    private const val GIST_CHARS = 200

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)

    override val verb = Verb.HistoryRead
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef(HISTORY_READ,
        Prompts.tool(HISTORY_READ, "page" to PAGE_CHARS),
        mapOf(
            "turn" to int("Which earlier turn to read: 1 = the turn just before this one, 2 = the one before that, and so on. Leave out to list the earlier turns.", 1),
            "offset" to int("Item to start from, from nextOffset of the previous call (default 0).", 0),
        ), emptyList())

    override fun available(ctx: ToolContext): Boolean =
        ctx.config.runRecords != null && ctx.config.runSpec?.priorTurns.orEmpty().any { it.runId != null }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val turns = ctx.config.runSpec!!.priorTurns.asReversed()
        val records = ctx.config.runRecords!!
        val n = a.int("turn", 1, Int.MAX_VALUE)
        val offset = a.int("offset", 0, Int.MAX_VALUE) ?: 0
        if (n == null) return list(turns, records)
        val turn = turns.getOrNull(n - 1)
            ?: return fail("turn_not_found", Feedback.format("history.turn_not_found", "count" to turns.size))
        return read(n, turn, records, offset)
    }

    private suspend fun list(turns: List<Turn>, records: RunRecords): ToolExecution {
        val shown = turns.take(LIST_MAX)
        val zone = ZoneId.systemDefault()
        val summaries = shown.map { t -> t.runId?.let { id -> safe(null) { records.summary(id) } } }
        return ToolExecution(okObj {
            put("turns", JsonArray(shown.mapIndexed { i, t ->
                val summary = summaries[i]
                buildJsonObject {
                    put("turn", i + 1)
                    put("said", clip(t.userText, GIST_CHARS))
                    put("status", t.status)
                    put("outcome", outcome(t, GIST_CHARS))
                    if (NoticeTurn.isNotice(t)) put(NOTICE, true)
                    summary?.let { put("time", time(it.startedAtMs, zone)) }
                    put("recorded", summary != null)
                }
            }))
            if (turns.size > shown.size) put("older", turns.size - shown.size)
        })
    }

    private suspend fun read(n: Int, t: Turn, records: RunRecords, offset: Int): ToolExecution {
        val id = t.runId
        val summary = id?.let { safe(null) { records.summary(it) } }
        val items = if (summary != null) items(safe(emptyList()) { records.events(id) }) else emptyList()
        val page = mutableListOf<Item>()
        var chars = 0
        for (item in items.drop(offset)) {
            if (page.isNotEmpty() && chars + item.text.length > PAGE_CHARS) break
            page += item
            chars += item.text.length
        }
        val next = offset + page.size
        val content = okObj {
            put("turn", n)
            put("said", t.userText)
            put("status", t.status)
            put("outcome", outcome(t, OUTCOME_CHARS))
            if (NoticeTurn.isNotice(t)) put(NOTICE, true)
            if (t.steers.isNotEmpty()) put("userAddedDuringTask", JsonArray(t.steers.map { JsonPrimitive(clip(it, GIST_CHARS)) }))
            summary?.let { put("time", time(it.startedAtMs, ZoneId.systemDefault())) }
            put("recorded", summary != null)
            if (summary == null) put("message", Feedback["history.not_recorded"])
            else {
                put("totalItems", items.size)
                put("items", JsonArray(page.mapIndexed { i, item ->
                    buildJsonObject {
                        put("n", offset + i)
                        put("kind", item.kind)
                        item.where?.let { put("where", it) }
                        put("text", if (item.untrusted) Untrusted.wrap(item.text) else item.text)
                    }
                }))
                if (next < items.size) put("nextOffset", next)
            }
        }
        // The text read is handed to verification, so using things read in earlier turns in the answer is traceable (the source is recorded separately as history and does not point back to the screen at that time)
        val observations = page.mapNotNull { item ->
            item.obsKind?.let { Observation(it, item.text, source = SOURCE, title = item.where, trust = if (item.untrusted) Trust.Untrusted else Trust.Trusted) }
        }
        return ToolExecution(content, observations = observations)
    }

    /** One readable thing from the record: acceptance/progress, Claire's progress note, or text that was read (the observation kind). */
    private class Item(val kind: String, val text: String, val where: String?, val untrusted: Boolean, val obsKind: ObsKind?)

    private fun items(events: List<RunEvent>): List<Item> {
        val seen = mutableSetOf<String>()
        return events.mapNotNull { e ->
            when (e) {
                // A reply without a tool call is that turn's answer and is already in outcome
                is RunEvent.ModelReply -> e.text.trim().takeIf { e.acted && e.tool != null && it.isNotEmpty() && seen.add(it) }
                    ?.let { Item(NOTE, clip(it, ITEM_CHARS), null, false, null) }
                is RunEvent.Observed -> e.text.trim().takeIf { it.isNotEmpty() && seen.add(it) }?.let {
                    val o = e.observation()
                    Item(o.kind.wire, clip(it, ITEM_CHARS), o.title ?: o.place?.name, e.untrusted, o.kind)
                }
                is RunEvent.Criteria -> progress(e).takeIf { it.isNotEmpty() && seen.add(it) }
                    ?.let { Item(PROGRESS, clip(it, ITEM_CHARS), null, false, null) }
                else -> null
            }
        }
    }

    private fun progress(e: RunEvent.Criteria): String = buildString {
        if (e.criteria.isNotEmpty()) append("Acceptance: ").append(e.criteria.joinToString("; "))
        e.confirmedIds?.let { ids -> append("\nConfirmed goals: ").append(ids.size).append('/').append(e.criteria.size) }
        e.progress?.takeIf { e.confirmedIds == null }?.let { p ->
            if (isNotEmpty()) append('\n')
            append("Confirmed progress: ").append(p.name)
            if (p.done != null) append(' ').append(p.done)
            if (p.total != null) append('/').append(p.total)
            p.boundary?.takeIf(String::isNotBlank)?.let { append("; boundary: ").append(it) }
        }
        if (e.complete) {
            if (isNotEmpty()) append('\n')
            append("All acceptance criteria were reported met.")
        }
    }

    private fun time(ms: Long, zone: ZoneId) = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

    /**
     * The result of a turn. For a notification-reminder turn ([NoticeTurn]) the listed text was written by another app and another person: it is wrapped in the untrusted marker before being returned and marked with [NOTICE] —
     * so the model knows this turn was not said by the user, and so the run record does not record its content ([com.wangzan101.mika.core.run.record.Redactor.result]).
     */
    private fun outcome(t: Turn, max: Int): String = clip(t.outcome, max).let { if (NoticeTurn.isNotice(t)) NoticeTurn.untrusted(it) else it }

    /** A failed record read is treated as absent: not finding it does not affect the task. */
    private suspend fun <T> safe(fallback: T, read: suspend () -> T): T =
        try { read() } catch (e: CancellationException) { throw e } catch (e: Exception) { fallback }

    /** The field in the result marking "this turn is a notification reminder". */
    const val NOTICE = "notice"
    private const val NOTE = "note"
    private const val PROGRESS = "progress"
    private const val SOURCE = "history"
}
