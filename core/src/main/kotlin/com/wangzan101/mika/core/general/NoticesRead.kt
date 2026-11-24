package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.proactive.DigestLevel
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.core.proactive.DigestView
import com.wangzan101.mika.core.proactive.DigestViewLine
import com.wangzan101.mika.core.proactive.NoticeSource
import com.wangzan101.mika.core.proactive.NoticeEntry
import com.wangzan101.mika.core.proactive.NoticeFilter
import com.wangzan101.mika.core.run.RunOrigin
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
import com.wangzan101.mika.core.tool.str
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val NOTICES_READ = "notices_read"

/**
 * Reads notifications received from various apps (the last two days of text, plus the stored daily, weekly and monthly digests): used when the user asks "what came in today", "what did so-and-so say" or "what happened last week", without opening each app.
 * - `view=summary` (default): how many pieces each app received that day, plus the section digests already written (each sentence is written by the model from a batch of notifications; paginated when there are many).
 * - `view=messages`: the notification text itself, oldest first, paginated; can be filtered by app and by text.
 * - `view=days` / `weeks` / `months`: the stored daily, weekly and monthly digests (kept longer than the raw text), newest first, paginated; used when asking about previous days, last week or last month.
 *
 * Whenever what is returned is not everything, it says so explicitly: from what time the record is complete (`recordedSince`, `incomplete`), that more remains (`nextOffset`),
 * and how many apps were withheld because their source tier does not qualify (`appsWithheld`). A failed read is an error, not "nothing came in".
 *
 * Read-only; does not touch the screen. Data is provided by the app ([com.wangzan101.mika.core.proactive.NoticeSource]); the tool does not appear when it is not provided (the feature is off, or the task was not started by the user on the phone).
 * Everything read was written by other apps and other people: each item is wrapped in the untrusted marker (any marker already inside is broken first). It is not recorded into the verification ledger as observations;
 * text is masked before entering the run record (see [com.wangzan101.mika.core.run.record.Redactor.result]): notification text is kept on the device for only two days and must not last longer by way of the run record.
 * Tasks that read notifications do not extract memories automatically after finishing (see RunLearning).
 */
internal object NoticesRead : AgentTool {
    /** Roughly how many characters of section text fit in one page (at least one section is always given). */
    const val PAGE_CHARS = 6_000
    /** Maximum characters kept per text section. */
    private const val TEXT_CHARS = 300
    private const val NAME_CHARS = 80
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)

    override val verb = Verb.NoticesRead
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef(NOTICES_READ,
        Prompts.tool(NOTICES_READ, "page" to PAGE_CHARS),
        mapOf(
            "day" to str("summary and messages views only. Which day: today (default) or yesterday.", listOf("today", "yesterday")),
            "view" to str("summary (default): counts per app and the notes already written about that day. messages: the notification text itself, oldest first. " +
                "days, weeks, months: the daily, weekly or monthly digests Claire has stored, newest first.", listOf("summary", "messages", "days", "weeks", "months")),
            "app" to str("Only this app: its name or package as listed by a summary call. Leave out for all apps."),
            "query" to str("Not for summary. Keep only items whose chat name, sender or text contains this exact piece of text (one name or phrase, not a list of keywords)."),
            "offset" to int("Item to start from, taken from nextOffset of the previous call with the same day and view (default 0).", 0),
        ), emptyList())

    override fun available(ctx: ToolContext): Boolean {
        val spec = ctx.config.runSpec
        // Offered only for tasks the user started on the phone; read-only runs such as condition checks, and tasks coming from elsewhere, do not get it
        return ctx.config.notices != null && (spec == null || (spec.provenance.origin == RunOrigin.Ui && !spec.readOnly))
    }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val source = ctx.config.notices!!
        val zone = source.zone
        val yesterday = a.str("day") == "yesterday"
        val viewName = a.str("view")
        val messages = viewName == "messages"
        val app = a.str("app", max = 200)?.trim()?.takeIf { it.isNotEmpty() }
        val query = a.str("query", max = 200)?.trim()?.takeIf { it.isNotEmpty() }
        val offset = a.int("offset", 0, Int.MAX_VALUE) ?: 0
        LEVELS[viewName]?.let { return digests(source, viewName!!, it, app, query, offset) }

        val day = Instant.ofEpochMilli(source.now()).atZone(zone).toLocalDate().let { if (yesterday) it.minusDays(1) else it }
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // A failed read must not masquerade as "nothing came in that day"
        val view = source.read(from, to) ?: return fail("notices_unavailable",
            "The notification record could not be read just now (it may have just been turned off, or the store is busy). This does not mean nothing came in: tell the user it could not be read.")
        // The app name is whatever that app calls itself: sanitize it before returning (see NoticeFilter.appName)
        fun label(pkg: String) = NoticeFilter.appName(source.appLabel(pkg), pkg)
        fun wanted(pkg: String) = app == null || pkg.equals(app, ignoreCase = true) || source.appLabel(pkg).contains(app, ignoreCase = true) || NoticeFilter.appName(app).let { it.isNotEmpty() && label(pkg).contains(it, ignoreCase = true) }
        fun time(ms: Long) = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))
        val entries = view.entries.filter { wanted(it.packageName) }

        return ToolExecution(okObj {
            put("day", if (yesterday) "yesterday" else "today")
            // When the record starts: nothing earlier, and nothing that was not left in the notification shade while the screen was locked, is here
            put("recordedSince", STAMP.format(Instant.ofEpochMilli(view.sinceMs).atZone(zone)))
            if (view.sinceMs > from) put("incomplete", true)
            // Apps that had notifications but were withheld because their source tier does not qualify: only the count is reported
            if (view.withheldApps > 0) put("appsWithheld", view.withheldApps)
            if (!messages) {
                put("apps", JsonArray(entries.groupBy { it.packageName }.map { (pkg, list) ->
                    buildJsonObject { put("app", label(pkg)); put("package", pkg); put("notifications", list.size) }
                }))
                val notes = view.notes.filter { wanted(it.packageName) }
                val page = ArrayList<DigestNote>()
                var chars = 0
                for (n in notes.drop(offset)) {
                    val size = n.text.length.coerceAtMost(TEXT_CHARS) + (n.title?.length ?: 0).coerceAtMost(NAME_CHARS)
                    if (page.isNotEmpty() && chars + size > PAGE_CHARS) break
                    page += n
                    chars += size
                }
                // The section digests cannot be read at the moment: what follows is empty, but not "there are none"
                if (view.notesUnavailable) put("notesUnavailable", true)
                put("notesTotal", notes.size)
                put("notes", JsonArray(page.mapIndexed { i, n ->
                    buildJsonObject {
                        put("n", offset + i)
                        put("time", time(n.fromMs).let { f -> time(n.toMs).let { t -> if (t == f) f else "$f-$t" } })
                        put("app", label(n.packageName))
                        n.title?.takeIf(String::isNotBlank)?.let { put("chat", untrusted(it, NAME_CHARS)) }
                        put("text", untrusted(n.text, TEXT_CHARS))
                    }
                }))
                val next = offset + page.size
                if (next < notes.size) put("nextOffset", next)
            } else {
                val hits = if (query == null) entries else entries.filter { e -> listOfNotNull(e.title, e.sender, e.text).any { it.contains(query, ignoreCase = true) } }
                val page = ArrayList<NoticeEntry>()
                var chars = 0
                for (e in hits.drop(offset)) {
                    val size = e.text.length.coerceAtMost(TEXT_CHARS) + e.title.length.coerceAtMost(NAME_CHARS)
                    if (page.isNotEmpty() && chars + size > PAGE_CHARS) break
                    page += e
                    chars += size
                }
                put("total", hits.size)
                put("items", JsonArray(page.mapIndexed { i, e ->
                    buildJsonObject {
                        put("n", offset + i)
                        put("time", time(e.atMs))
                        put("app", label(e.packageName))
                        e.group?.let { put("kind", if (it) "group chat" else "private chat") }
                        if (e.title.isNotBlank()) put("chat", untrusted(e.title, NAME_CHARS))
                        e.sender?.takeIf(String::isNotBlank)?.let { put("sender", untrusted(it, NAME_CHARS)) }
                        put("text", untrusted(e.text, TEXT_CHARS))
                    }
                }))
                val next = offset + page.size
                if (next < hits.size) put("nextOffset", next)
            }
        })
    }

    private val LEVELS = mapOf("days" to DigestLevel.Day, "weeks" to DigestLevel.Week, "months" to DigestLevel.Month)
    private val DAY = DateTimeFormatter.ISO_LOCAL_DATE
    private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ENGLISH)

    /** Stored daily (weekly, monthly) digests: one sentence per item, newest digest first, original order within a digest; paging and filtering work as with the raw text. */
    private suspend fun digests(source: NoticeSource, viewName: String, level: DigestLevel, app: String?, query: String?, offset: Int): ToolExecution {
        val got = source.digests(level) ?: return fail("notices_unavailable",
            "The stored digests could not be read just now (they may have just been turned off, or the store is busy). This does not mean there are none: tell the user they could not be read.")
        val zone = source.zone
        // The app name is whatever that app calls itself: sanitize it before returning (see NoticeFilter.appName)
        fun label(pkg: String) = NoticeFilter.appName(source.appLabel(pkg), pkg)
        fun wanted(pkg: String) = app == null || pkg.equals(app, ignoreCase = true) || source.appLabel(pkg).contains(app, ignoreCase = true) || NoticeFilter.appName(app).let { it.isNotEmpty() && label(pkg).contains(it, ignoreCase = true) }
        fun date(day: Long) = DAY.format(LocalDate.ofEpochDay(day))
        // Which days this digest covers: the dates are our own. A monthly digest covers the weeks that end within that month, which differ from the calendar month by a few days, so both are given
        fun period(d: DigestView) = when (level) {
            DigestLevel.Day -> date(d.start)
            DigestLevel.Week -> "${date(d.start)} to ${date(d.end)}"
            DigestLevel.Month -> "${MONTH.format(LocalDate.ofEpochDay(d.end))} (${date(d.start)} to ${date(d.end)})"
        }
        val hits = got.digests.flatMap { d ->
            d.lines.filter { l -> (app == null || l.apps.any(::wanted)) && (query == null || l.text.contains(query, ignoreCase = true)) }.map { d to it }
        }
        val page = ArrayList<Pair<DigestView, DigestViewLine>>()
        var chars = 0
        for (h in hits.drop(offset)) {
            val size = h.second.text.length.coerceAtMost(TEXT_CHARS)
            if (page.isNotEmpty() && chars + size > PAGE_CHARS) break
            page += h
            chars += size
        }
        return ToolExecution(okObj {
            put("view", viewName)
            // How many digests are stored (regardless of filtering): "none stored" and "none after filtering" must be distinguishable
            put("stored", got.digests.size)
            if (got.withheldLines > 0) put("linesWithheld", got.withheldLines)
            put("total", hits.size)
            put("items", JsonArray(page.mapIndexed { i, (d, l) ->
                buildJsonObject {
                    put("n", offset + i)
                    put("period", period(d))
                    // Made before that period was over: it covers only up to the moment it was made
                    if (d.partial) put("soFarAsOf", STAMP.format(Instant.ofEpochMilli(d.madeAtMs).atZone(zone)))
                    // Some sentences did not fit or were not seen when this digest was made: it was not written from everything in that period
                    if (d.leftOut > 0) put("inputsLeftOut", d.leftOut)
                    put("apps", JsonArray(l.apps.map { JsonPrimitive(label(it)) }))
                    put("text", untrusted(l.text, TEXT_CHARS))
                }
            }))
            val next = offset + page.size
            if (next < hits.size) put("nextOffset", next)
        })
    }

    private fun untrusted(s: String, max: Int): String = NoticeTurn.untrusted(if (s.length <= max) s else s.take(max - 1) + "…")
}
