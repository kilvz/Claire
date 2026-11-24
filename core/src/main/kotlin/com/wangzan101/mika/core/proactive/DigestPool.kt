package com.wangzan101.mika.core.proactive

import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * One chunk-summary sentence (level 1): which app, which day (epoch day of the local date, taken from the newest of the notifications it is based on), the time span of the notifications it is based on,
 * and how many chunks; [title] is the conversation name shared by those chunks (present only when they all match, taken from the notifications themselves rather than supplied by the model); [text] is the sentence the model wrote (validated and masked).
 * The original notification text is not stored.
 */
@Serializable
data class DigestNote(
    val id: String,
    val packageName: String,
    val day: Long,
    val fromMs: Long,
    val toMs: Long,
    val count: Int,
    val title: String? = null,
    val text: String,
    /** When this sentence was written: a sentence written after the daily summary was already completed (last night's notifications caught up only after unlocking) requires that day to be redone. */
    val madeAtMs: Long = 0,
)

/**
 * One sentence in a daily, weekly or monthly summary, along with where it came from (traced all the way down; deleting anything below removes this sentence too):
 * [notes] are the ids of the chunk summaries it is based on (only surviving ones are kept: once a chunk summary expires and is deleted it is struck from here), [apps] are the apps those sentences came from,
 * [days] are the days of the daily summaries it is based on (only present for weekly and monthly sentences), [weeks] are the weeks of the weekly summaries it is based on (only present for monthly sentences).
 */
@Serializable
data class DigestLine(
    val text: String,
    val notes: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    val days: List<Long> = emptyList(),
    val weeks: List<Long> = emptyList(),
)

/** Weekly summaries (level 3) and monthly summaries (level 4). */
enum class SpanKind { Week, Month }

/**
 * A weekly or monthly summary. [start] is the Monday of that week or the first day of that month (epoch day of the local date).
 * [partial] true means an "up to now" version the user asked for before the period ended; once the period is over, a complete one is redone; [inputCount] is how many sentences were included when it was made.
 * [leftOut]: the lower level had too many sentences to fit in one request, and this many were left out — this version was not written from all of them, and that must be stated when reporting to tasks and to the user.
 */
@Serializable
data class SpanDigest(
    val start: Long, val madeAtMs: Long, val lines: List<DigestLine>, val partial: Boolean = false, val inputCount: Int = 0,
    val leftOut: Int = 0,
    /** A one-line overview for this version (given by the model along with the sentences, see [DayDigest.brief]); empty when absent. */
    val brief: String = "",
    /** Which apps' sentences the overview was written from (see [DayDigest.briefApps]). */
    val briefApps: List<String> = emptyList(),
    /** The user deleted this week's (this month's) summary: only a marker, recording "do not generate this automatically again". See [DigestPool.deleteSpan]. */
    val removed: Boolean = false,
)

/** One sentence included in a weekly or monthly summary request: [from] is which day's daily summary it came from (for weekly summaries) or which week's weekly summary (for monthly summaries). */
class SpanInput(val from: Long, val line: DigestLine)

/**
 * The daily summary for one day (level 2). [partial] true means an "up to now" version the user asked for before the day ended; once the day is over, a complete one is redone;
 * [noteCount] is how many chunk-summary sentences were included in the request when it was made. [leftOut]: that day's sentences were too many to fit in one request, and this many were left out —
 * this version was not written from all of that day's sentences ("complete" only means the day is over), and that must be stated when reporting to tasks and to the user; weekly and monthly summaries build on it and thus carry this gap.
 */
@Serializable
data class DayDigest(
    val day: Long, val madeAtMs: Long, val lines: List<DigestLine>, val partial: Boolean = false, val noteCount: Int = 0,
    val leftOut: Int = 0,
    /**
     * A one-line overview for this version: given by the model along with the sentences, used for indexing on the page, not for tasks. It speaks about all the sentences in this version and points at none of them,
     * so as soon as any sentence in this version is removed (its basis was deleted, an app was cleared), the overview is cleared along with it — otherwise the deleted matter would still be in the overview;
     * when a chunk-summary sentence is deleted, a day's (week's) summary is deleted, or an app is cleared, all the overviews covering that day are cleared as well (see [DigestPool.clearBriefs]).
     * Empty when absent (the model did not provide one, it was unsuitable, or it was cleared).
     */
    val brief: String = "",
    /**
     * Which apps' sentences the overview was written from: the apps of all sentences sent to the model, not just those of the stored ones. Clearing an app relies on this —
     * the apps recorded on the stored sentences are not enough: the model may have written extra sentences that were not stored and mentioned this app, with the overview referring to it; and chunk summaries are kept for only a few days,
     * so afterwards they cannot be used to find out which apps appeared that day. Empty when there is no overview.
     */
    val briefApps: List<String> = emptyList(),
    /** The user deleted this day's daily summary: only a marker (no sentences), recording "do not generate this automatically again". See [DigestPool.deleteDay]. */
    val removed: Boolean = false,
)

/** How the daily-message summaries are persisted. */
@Serializable
data class DigestData(
    val version: Int = 1,
    val notes: List<DigestNote> = emptyList(),
    val days: List<DayDigest> = emptyList(),
    val weeks: List<SpanDigest> = emptyList(),
    val months: List<SpanDigest> = emptyList(),
)

/**
 * What the daily-message summaries have accumulated: pure logic, not thread-safe (used on the single serial coroutine of ProactiveRuntime).
 * Chunk-summary sentences (level 1) are only appended, never rewritten, and kept for [LearnTuning.NOTES_KEEP_DAYS] days;
 * daily summaries (level 2) are one per day, kept for [LearnTuning.DAYS_KEEP] days; weekly summaries (level 3) are one per week, kept for [LearnTuning.WEEKS_KEEP_DAYS] days;
 * monthly summaries (level 4) are one per month, kept for [LearnTuning.MONTHS_KEEP_DAYS] days. Finishing an upper level does not delete the lower level: it is still there for verification.
 *
 * Each level takes only the lower level's sentences as input (weekly uses daily summaries, monthly uses weekly summaries) and runs only after that period is over; if the lower level is later redone, the upper level is redone accordingly.
 * A week runs Monday to Sunday; a week counts toward the month in which it ends (Sunday).
 *
 * Deletions propagate upward: when a chunk-summary sentence, a day's daily summary or a week's weekly summary is deleted, or an app is cleared, the sentences at higher levels that were based on it are removed as well
 * (the merged sentences still carry what it said). Expiry deletions do not propagate upward: the whole point of an upper level is to outlive the lower one.
 *
 * Deleted means no longer generated automatically: after the user deletes the summary for a certain day, week or month, a "deleted" marker (with no sentences) is left there, and from then on, whether it is still owed,
 * new sentences arrive, or the lower level is redone, it is not generated automatically again; only when the user explicitly asks for "up to now" once more is it regenerated (and the marker disappears with it).
 * The marker is treated as absent by everyone: readers ([days], [day], [spans], [span]) do not see it, and the upper level neither uses it as input nor is redone because of it.
 * The marker is kept as long as that level's summaries; clearing everything clears it too.
 */
class DigestPool(data: DigestData = DigestData(), private val newId: () -> String = { "d" + UUID.randomUUID().toString().replace("-", "").take(12) }) {
    private val notes = ArrayList<DigestNote>(data.notes)
    private val days = LinkedHashMap<Long, DayDigest>().apply { data.days.forEach { put(it.day, it) } }
    private val weeks = LinkedHashMap<Long, SpanDigest>().apply { data.weeks.forEach { put(it.start, it) } }
    private val months = LinkedHashMap<Long, SpanDigest>().apply { data.months.forEach { put(it.start, it) } }

    fun data(): DigestData = DigestData(
        notes = notes.toList(), days = days.values.sortedBy { it.day },
        weeks = weeks.values.sortedBy { it.start }, months = months.values.sortedBy { it.start },
    )

    /** The daily summaries for each day, newest first (user-deleted ones excluded). */
    fun days(): List<DayDigest> = days.values.filter { !it.removed }.sortedByDescending { it.day }

    fun day(day: Long): DayDigest? = days[day]?.takeIf { !it.removed }

    /** Which days have had their daily summary deleted by the user (the page must state clearly "deleted, will not be generated automatically again"). */
    fun removedDays(): Set<Long> = days.values.filter { it.removed }.mapTo(HashSet()) { it.day }

    /** Whether this day's daily summary was deleted by the user (and will not be generated automatically again). */
    fun dayRemoved(day: Long): Boolean = days[day]?.removed == true

    /** Which weekly (monthly) summaries were deleted by the user (the page must state clearly "deleted, will not be generated automatically again"). */
    fun removedSpans(kind: SpanKind): Set<Long> = store(kind).values.filter { it.removed }.mapTo(HashSet()) { it.start }

    /**
     * Which day should get a daily summary: the earliest day before [today] that has chunk summaries and that [settled] accepts, provided it has no complete daily summary yet,
     * or new sentences for that day were written after the complete one was made (yesterday's summary was made at unlock and last night's notifications were caught up only afterwards: it must be redone, or those sentences end up in none of the summaries).
     * [settled] tells whether all of that day's notifications have been chunk-summarized (wait a bit while some are still pending, or the summary covers only half a day). Null when there is none.
     */
    fun dueDay(today: Long, settled: (Long) -> Boolean): Long? = notes.asSequence().map { it.day }.distinct().sorted()
        .firstOrNull { it < today && needsDigest(it) && settled(it) }

    private fun needsDigest(day: Long): Boolean {
        val d = days[day] ?: return true
        // user-deleted: do not generate automatically again
        if (d.removed) return false
        return d.partial || notes.any { it.day == day && it.madeAtMs > d.madeAtMs }
    }

    /**
     * The sentences for that day sent to level 2: in time order from oldest to newest, at most [LearnTuning.DAY_NOTES_MAX]. When there are more, apps take turns, each app keeping its newest first:
     * a chatty app must not squeeze out a whole day of other apps' content (taking only the last sentences would lose the entire morning). What was left out is counted by [setDay].
     */
    fun dayInput(day: Long): List<DigestNote> {
        val all = notes(day)
        if (all.size <= LearnTuning.DAY_NOTES_MAX) return all
        val byApp = all.groupBy { it.packageName }.values.map { it.asReversed() }
        val picked = HashSet<String>()
        var round = 0
        while (picked.size < LearnTuning.DAY_NOTES_MAX && byApp.any { round < it.size }) {
            for (app in byApp) {
                if (picked.size >= LearnTuning.DAY_NOTES_MAX) break
                app.getOrNull(round)?.let { picked += it.id }
            }
            round++
        }
        return all.filter { it.id in picked }
    }

    /**
     * Records a day's daily summary, replacing the previous one for that day. [input] are the sentences that were sent (indices correspond to [NoteSignal.entries]).
     * It is recorded even with no sentences (this day was processed and had nothing worth saying).
     */
    fun setDay(day: Long, input: List<DigestNote>, lines: List<NoteSignal>, nowMs: Long, partial: Boolean, brief: String? = null) {
        val alive = notes.mapTo(HashSet()) { it.id }
        val out = lines.mapNotNull { l ->
            val cited = l.entries.distinct().filter { it in input.indices }.map { input[it] }
            // a basis sentence no longer exists (that app was cleared while the request was in flight): drop the whole sentence. Merely striking it from the basis is not enough,
            // the sentence's text still carries what that app said
            if (cited.isEmpty() || l.text.isBlank() || cited.any { it.id !in alive }) null
            else DigestLine(l.text, cited.map { it.id }, cited.map { it.packageName }.distinct())
        }
        // the made-at time is not earlier than the write time of the last basis sentence: otherwise "new sentences" would appear right after it is made
        days[day] = DayDigest(
            day, maxOf(nowMs, input.maxOfOrNull { it.madeAtMs } ?: 0), out, partial, input.size,
            leftOut = (notes.count { it.day == day } - input.size).coerceAtLeast(0),
            // a sent sentence was deleted while the request was in flight: the overview was written from all of them and may be talking about the deleted one — reject it even if not a single stored sentence was lost
            // (the model wrote extra sentences that were not stored and referred to it, and the overview mentioned it too)
            brief = if (input.all { it.id in alive }) kept(brief, out.size, lines.size) else "",
        ).let { d -> if (d.brief.isEmpty()) d else d.copy(briefApps = input.map { it.packageName }.distinct()) }
    }

    // ---- weekly summaries (level 3) and monthly summaries (level 4) ----

    private fun store(kind: SpanKind) = if (kind == SpanKind.Week) weeks else months

    /** The weekly (monthly) summaries, newest first (user-deleted ones excluded). */
    fun spans(kind: SpanKind): List<SpanDigest> = store(kind).values.filter { !it.removed }.sortedByDescending { it.start }

    fun span(kind: SpanKind, start: Long): SpanDigest? = store(kind)[start]?.takeIf { !it.removed }

    /** One entry of the lower level: which day (week) it covers, when it was made, whether it is an "up to now" one, its sentences, and how many it left out when it was made. */
    private class Lower(val key: Long, val madeAtMs: Long, val partial: Boolean, val lines: List<DigestLine>, val leftOut: Int)

    /**
     * The lower-level entries for this week (month): the daily summaries of each day in a week, the weekly summaries of each week (ending in this month) in a month; from oldest to newest.
     * User-deleted ones do not count: they are not used as input, do not count as a "completed lower level", and their times do not count as "the lower level was redone".
     */
    private fun lower(kind: SpanKind, start: Long): List<Lower> = when (kind) {
        SpanKind.Week -> days.values.filter { !it.removed && weekOf(it.day) == start }.sortedBy { it.day }.map { Lower(it.day, it.madeAtMs, it.partial, it.lines, it.leftOut) }
        SpanKind.Month -> weeks.values.filter { !it.removed && monthOfWeek(it.start) == start }.sortedBy { it.start }.map { Lower(it.start, it.madeAtMs, it.partial, it.lines, it.leftOut) }
    }

    /** When the complete lower-level entries were made (empty when there is none): whether the upper level exists and needs redoing depends only on the complete ones. */
    private fun settledLower(kind: SpanKind, start: Long): List<Long> = lower(kind, start).filter { !it.partial }.map { it.madeAtMs }

    /** Whether this week (month) needs to be (re)done: the lower level has complete entries while this level has none or only an "up to now" one, or the lower level was redone after this one was made. */
    private fun needsSpan(kind: SpanKind, start: Long): Boolean {
        val made = settledLower(kind, start)
        if (made.isEmpty()) return false
        val d = store(kind)[start] ?: return true
        // user-deleted: do not generate automatically again
        if (d.removed) return false
        return d.partial || made.any { it > d.madeAtMs }
    }

    /** This day still owes a daily summary (it has sentences but no complete one, or new sentences arrived after the complete one was made). */
    private fun dayOwed(day: Long): Boolean = notes.any { it.day == day } && needsDigest(day)

    /** This week still owes a weekly summary: it needs to be (re)done, or it still contains days that owe a daily summary (once that is done, this week will need one). */
    private fun weekOwed(week: Long): Boolean = needsSpan(SpanKind.Week, week) || (week..week + 6).any(::dayOwed)

    /**
     * Which week should get a weekly summary: the earliest week before the one containing [today] that needs to be (re)done. Each day of that week is first passed to [waitForDay]
     * (the day and whether it still owes a daily summary): if it says to wait, this week is not done yet — wait for the owed ones to finish, though ones that failed several times are no longer waited for,
     * and days with notifications not yet chunk-summarized are waited for too. [skip] says this week failed several times and should not be done this time. Null when there is none.
     */
    fun dueWeek(today: Long, waitForDay: (day: Long, owed: Boolean) -> Boolean, skip: (Long) -> Boolean): Long? {
        val thisWeek = weekOf(today)
        return days.keys.asSequence().map(::weekOf).distinct().sorted().firstOrNull { w ->
            w < thisWeek && needsSpan(SpanKind.Week, w) && !skip(w) && (w..w + 6).none { waitForDay(it, dayOwed(it)) }
        }
    }

    /**
     * Which month should get a monthly summary: the earliest month before the one containing [today] that needs to be (re)done. Each week of that month (ending in that month) is first passed to [waitForWeek]
     * (the week and whether it still owes a weekly summary): if it says to wait, this month is not done yet. [skip] says not to do this month this time. Null when there is none.
     */
    fun dueMonth(today: Long, waitForWeek: (week: Long, owed: Boolean) -> Boolean, skip: (Long) -> Boolean): Long? {
        val thisMonth = monthOf(today)
        // weeks with something in this month: those with daily summaries or with sentences
        val known = (days.keys.asSequence() + notes.asSequence().map { it.day }).map(::weekOf).distinct().toList()
        return weeks.keys.asSequence().map(::monthOfWeek).distinct().sorted().firstOrNull { m ->
            m < thisMonth && needsSpan(SpanKind.Month, m) && !skip(m) &&
                known.none { w -> monthOfWeek(w) == m && waitForWeek(w, weekOwed(w)) }
        }
    }

    /** Whether today's daily summary should be (re)done first before summarizing upward: today has sentences but no daily summary, or new sentences arrived after it was made. */
    /**
     * Whether an "up to now" daily summary for today should be generated automatically (thresholds in the [LearnTuning.TODAY_AUTO_MIN_NOTES] group).
     * If today's was deleted by the user, it is not restored automatically.
     */
    fun todayAutoDue(today: Long, nowMs: Long): Boolean {
        val d = days[today]
        if (d?.removed == true) return false
        if (d != null && nowMs - d.madeAtMs < LearnTuning.TODAY_AUTO_INTERVAL_MS) return false
        val fresh = notes.filter { it.day == today && (d == null || it.madeAtMs > d.madeAtMs) }
        if (fresh.size < LearnTuning.TODAY_AUTO_MIN_NOTES) return false
        return fresh.size >= LearnTuning.TODAY_AUTO_READY_NOTES || nowMs - fresh.minOf { it.madeAtMs } >= LearnTuning.TODAY_AUTO_WAIT_MS
    }

    fun todayStale(today: Long): Boolean {
        val d = days[today]
        // today's was deleted by the user: asking for this week's and this month's does not bring it back as a side effect
        if (d?.removed == true) return false
        return notes.any { it.day == today && (d == null || it.madeAtMs > d.madeAtMs) }
    }

    /**
     * The input sent to a weekly (monthly) summary: the sentences of each lower-level entry, in time order from oldest to newest. When [withPartial] is false, only the complete entries are used (automatic runs do not take half-finished ones as input).
     * The total length stays within [LearnTuning.SPAN_INPUT_CHARS]: when it would not, the entries take turns, each keeping the sentences at its front (the lower level is ordered by importance).
     */
    fun spanInput(kind: SpanKind, start: Long, withPartial: Boolean): List<SpanInput> {
        val groups = lower(kind, start).filter { withPartial || !it.partial }.map { it.key to it.lines.filter { l -> l.text.isNotBlank() } }
        val taken = IntArray(groups.size)
        var chars = 0
        var round = 0
        var full = false
        while (!full && groups.indices.any { round < groups[it].second.size }) {
            for (i in groups.indices) {
                val line = groups[i].second.getOrNull(round) ?: continue
                if (chars > 0 && chars + line.text.length > LearnTuning.SPAN_INPUT_CHARS) { full = true; break }
                taken[i] = round + 1
                chars += line.text.length
            }
            round++
        }
        return groups.flatMapIndexed { i, (from, lines) -> lines.take(taken[i]).map { SpanInput(from, it) } }
    }

    /**
     * Records a weekly (monthly) summary, replacing the previous one. [input] are the sentences that were sent (indices correspond to [NoteSignal.entries]).
     * It is recorded even with no sentences (it was processed and had nothing worth saying). Each sentence traces its basis all the way down: which days (weeks), which chunk summaries, which apps.
     */
    fun setSpan(kind: SpanKind, start: Long, input: List<SpanInput>, lines: List<NoteSignal>, nowMs: Long, partial: Boolean, brief: String? = null) {
        val alive = notes.mapTo(HashSet()) { it.id }
        // whether the sent sentence still exists: judged by text and apps rather than by comparing the whole record — when a chunk summary expires while the request is in flight, some ids recorded on the sentence are struck out while the sentence itself is unchanged
        fun there(i: SpanInput): Boolean = when (kind) {
            SpanKind.Week -> days[i.from]?.lines
            SpanKind.Month -> weeks[i.from]?.lines
        }?.any { it.text == i.line.text && it.apps == i.line.apps } == true
        val out = lines.mapNotNull { l ->
            val cited = l.entries.distinct().filter { it in input.indices }.map { input[it] }
            // a basis sentence no longer exists (deleted while the request was in flight, or its app was cleared): drop the whole sentence
            if (cited.isEmpty() || l.text.isBlank() || cited.any { !there(it) }) null
            else DigestLine(
                l.text,
                notes = cited.flatMap { it.line.notes }.distinct().filter { it in alive },
                apps = cited.flatMap { it.line.apps }.distinct(),
                days = if (kind == SpanKind.Week) cited.map { it.from }.distinct() else cited.flatMap { it.line.days }.distinct(),
                weeks = if (kind == SpanKind.Month) cited.map { it.from }.distinct() else emptyList(),
            )
        }
        // the made-at time is not earlier than the made-at time of any lower-level entry: otherwise "the lower level was redone" right after it is made
        val made = maxOf(nowMs, lower(kind, start).maxOfOrNull { it.madeAtMs } ?: 0)
        // how many sentences the lower level has in total and how many were included: the difference is what did not fit. What the lower level itself left out is counted as well —
        // a weekly summary written from an incomplete daily summary likewise did not see all of that week
        val used = lower(kind, start).filter { partial || !it.partial }
        val available = used.sumOf { l -> l.lines.count { it.text.isNotBlank() } }
        store(kind)[start] = SpanDigest(
            start, made, out, partial, input.size,
            leftOut = (available - input.size).coerceAtLeast(0) + used.sumOf { it.leftOut },
            // same as [setDay]: some sent sentences disappeared while the request was in flight (that day's or week's summary was deleted, its app was cleared), so the overview is not kept
            brief = if (input.all(::there)) kept(brief, out.size, lines.size) else "",
        ).let { d -> if (d.brief.isEmpty()) d else d.copy(briefApps = input.flatMap { it.line.apps }.distinct()) }
    }

    /**
     * The user deletes a certain week's (month's) summary: a "deleted" marker is left in place and it is no longer generated automatically; when a weekly summary is deleted, the monthly sentences based on it are removed as well.
     * Returns false when there is no such entry or it was already deleted.
     */
    fun deleteSpan(kind: SpanKind, start: Long): Boolean {
        val d = store(kind)[start]?.takeIf { !it.removed } ?: return false
        store(kind)[start] = SpanDigest(start, d.madeAtMs, emptyList(), removed = true)
        if (kind == SpanKind.Week) {
            dropLines(fromDays = false) { start in it.weeks }
            // the monthly overview is cleared along with it (the Monday falls in this week and in this month)
            clearBriefs(start, fromDay = false)
        }
        return true
    }

    /**
     * Something for [day] was deleted: the overviews of that day, of its week and of that month are all cleared, whether or not any of their sentences were removed as a result.
     * An overview may mention sentences the model wrote beyond the limit and that were not stored ([NoteDrop.OverLimit]); there is no record to look those sentences up, so on deletion the overview can only be cleared along with them.
     * When [fromDay] is false, only the weekly and monthly ones are cleared. Returns true when anything was cleared.
     */
    private fun clearBriefs(day: Long, fromDay: Boolean = true): Boolean {
        var changed = false
        if (fromDay) days[day]?.takeIf { it.brief.isNotEmpty() }?.let { days[day] = it.copy(brief = ""); changed = true }
        val week = weekOf(day)
        weeks[week]?.takeIf { it.brief.isNotEmpty() }?.let { weeks[week] = it.copy(brief = ""); changed = true }
        val month = monthOfWeek(week)
        months[month]?.takeIf { it.brief.isNotEmpty() }?.let { months[month] = it.copy(brief = ""); changed = true }
        return changed
    }

    /** The overview is kept only when not a single accepted sentence was lost and there is at least one (sentences whose basis disappeared while the request was in flight are dropped entirely, see [setDay]). */
    private fun kept(brief: String?, stored: Int, given: Int): String = if (stored > 0 && stored == given) brief.orEmpty() else ""

    /**
     * Removes the sentences matching [gone] at the levels above: when [fromDays] is true this starts from the daily summaries, otherwise only the weekly and monthly ones are touched. Returns true when anything was removed.
     * For any entry from which sentences were removed, its overview is cleared along with them (see [DayDigest.brief]).
     */
    private fun dropLines(fromDays: Boolean, gone: (DigestLine) -> Boolean): Boolean {
        var changed = false
        if (fromDays) for ((k, d) in days.entries.toList()) {
            val left = d.lines.filterNot(gone)
            if (left.size != d.lines.size) { days[k] = d.copy(lines = left, brief = ""); changed = true }
        }
        for (m in listOf(weeks, months)) for ((k, d) in m.entries.toList()) {
            val left = d.lines.filterNot(gone)
            if (left.size != d.lines.size) { m[k] = d.copy(lines = left, brief = ""); changed = true }
        }
        return changed
    }

    fun all(): List<DigestNote> = notes.toList()

    /** The sentences for a certain day, in the time order of the notifications they are based on, oldest to newest. */
    fun notes(day: Long): List<DigestNote> = notes.filter { it.day == day }.sortedBy { it.fromMs }

    /**
     * Records a batch of chunk summaries. [entries] is the batch that was sent (indices correspond to [NoteSignal.entries]); the app comes from our own batching, not from the model.
     * Returns how many new sentences were recorded.
     */
    fun add(packageName: String, entries: List<NoticeEntry>, signals: List<NoteSignal>, zone: ZoneId, nowMs: Long = 0): Int {
        var added = 0
        for (s in signals) {
            val cited = s.entries.distinct().filter { it in entries.indices }.map { entries[it] }
            if (cited.isEmpty() || s.text.isBlank()) continue
            val from = cited.minOf { it.atMs }
            val to = cited.maxOf { it.atMs }
            val title = cited.map { it.title }.distinct().singleOrNull()?.takeIf(String::isNotBlank)
                ?.let { ContactIdentity.displayName(it) }
            var id = newId()
            while (notes.any { it.id == id }) id = newId()
            notes += DigestNote(id, packageName, TopicPool.dayOf(to, zone), from, to, cited.size, title, s.text, nowMs)
            added++
        }
        return added
    }

    /**
     * Removes entries outside the retention period ([today] is today's epoch day). Returns true when anything was removed. Expiry does not propagate upward: the sentences at higher levels stay.
     *
     * The four levels use the same algorithm: for an entry, counting from the last day it covers, keep it for a full N days and remove it on day N+1 — for sentences and daily summaries the last day is that day itself,
     * for weekly summaries it is Sunday, and for monthly summaries it is the last day of that month. (Counting a monthly summary from the first of the month would delete it nearly a month earlier than the other three levels.)
     */
    fun expire(today: Long): Boolean {
        val a = notes.removeAll { it.day < today - LearnTuning.NOTES_KEEP_DAYS }
        val b = days.keys.removeAll { it < today - LearnTuning.DAYS_KEEP }
        val c = weeks.keys.removeAll { it + 6 < today - LearnTuning.WEEKS_KEEP_DAYS }
        val d = months.keys.removeAll { lastDayOfMonth(it) < today - LearnTuning.MONTHS_KEEP_DAYS }
        // chunk summaries removed by expiry are struck from the basis of the sentences at every level: they can no longer be deleted on their own, so keeping the id would only waste space
        if (a) forgetDeadNotes()
        return a || b || c || d
    }

    /** The last day of the month starting at [monthStart] (epoch day). */
    private fun lastDayOfMonth(monthStart: Long): Long = java.time.LocalDate.ofEpochDay(monthStart).plusMonths(1).toEpochDay() - 1

    private fun forgetDeadNotes() {
        val alive = notes.mapTo(HashSet()) { it.id }
        fun trim(lines: List<DigestLine>): List<DigestLine>? =
            if (lines.none { l -> l.notes.any { it !in alive } }) null else lines.map { l -> l.copy(notes = l.notes.filter { it in alive }) }
        for ((k, d) in days.entries.toList()) trim(d.lines)?.let { days[k] = d.copy(lines = it) }
        for (m in listOf(weeks, months)) for ((k, d) in m.entries.toList()) trim(d.lines)?.let { m[k] = d.copy(lines = it) }
    }

    /** An app that is no longer summarized: its chunk summaries are removed; the daily, weekly and monthly sentences based on it are removed too (even if they are also based on other apps). Returns true when something changed. */
    fun removePackages(keep: (String) -> Boolean): Boolean {
        // the days on which it has sentences: even if no stored sentence is based on it, the overviews are cleared (see [clearBriefs])
        val touched = notes.filter { !keep(it.packageName) }.mapTo(HashSet()) { it.day }
        val a = notes.removeAll { !keep(it.packageName) }
        val b = dropLines(fromDays = true) { line -> !line.apps.all(keep) }
        var c = false
        for (day in touched) if (clearBriefs(day)) c = true
        // days whose chunk summaries have already expired and been removed can no longer be checked: go by the sources the overview itself recorded. An overview without recorded sources (stored before this field was added)
        // cannot say what it was written from, so it is cleared as well when an app is cleared
        fun stale(brief: String, apps: List<String>) = brief.isNotEmpty() && (apps.isEmpty() || !apps.all(keep))
        for ((k, d) in days.entries.toList()) if (stale(d.brief, d.briefApps)) { days[k] = d.copy(brief = "", briefApps = emptyList()); c = true }
        for (m in listOf(weeks, months)) for ((k, d) in m.entries.toList()) {
            if (stale(d.brief, d.briefApps)) { m[k] = d.copy(brief = "", briefApps = emptyList()); c = true }
        }
        return a || b || c
    }

    /**
     * The user deletes a certain day's daily summary: a "deleted" marker is left in place and it is no longer generated automatically (even if notifications for that day are caught up later);
     * the weekly and monthly sentences based on it are removed as well. Returns false when there is no such entry or it was already deleted.
     */
    fun deleteDay(day: Long): Boolean {
        val d = days[day]?.takeIf { !it.removed } ?: return false
        days[day] = DayDigest(day, d.madeAtMs, emptyList(), removed = true)
        dropLines(fromDays = false) { day in it.days }
        clearBriefs(day, fromDay = false)
        return true
    }

    /**
     * The user deletes one chunk-summary sentence. The daily, weekly and monthly sentences based on it are removed as well: the merged sentences still carry what it said, so deleting only this sentence would amount to not deleting it.
     * Returns true when something changed.
     */
    fun delete(id: String): Boolean {
        val day = notes.firstOrNull { it.id == id }?.day ?: return false
        notes.removeAll { it.id == id }
        dropLines(fromDays = true) { id in it.notes }
        clearBriefs(day)
        return true
    }

    fun clear(): Boolean {
        if (notes.isEmpty() && days.isEmpty() && weeks.isEmpty() && months.isEmpty()) return false
        notes.clear()
        days.clear()
        weeks.clear()
        months.clear()
        return true
    }

    companion object {
        /** The Monday of the week containing this day (epoch day; 1970-01-01 was a Thursday). */
        fun weekOf(day: Long): Long = day - Math.floorMod(day + 3, 7L)

        /** The first day of the month containing this day (epoch day). */
        fun monthOf(day: Long): Long = java.time.LocalDate.ofEpochDay(day).withDayOfMonth(1).toEpochDay()

        /** Which month this week (given as its Monday) counts toward: the month in which it ends (Sunday). */
        fun monthOfWeek(week: Long): Long = monthOf(week + 6)

        /**
         * Which days this month's (given as its first day) monthly summary actually covers: from the Monday of the first week counting toward it, to the Sunday of the last week counting toward it.
         * So the start of the month may carry the last few days of the previous month, and the last few days of the month (whose week ends in the next month) count toward the next month.
         */
        fun monthCover(month: Long): LongRange {
            val next = java.time.LocalDate.ofEpochDay(month).plusMonths(1).toEpochDay()
            val lastWeek = weekOf(next - 1).let { if (it + 6 < next) it else it - 7 }
            return weekOf(month)..lastWeek + 6
        }
    }
}
