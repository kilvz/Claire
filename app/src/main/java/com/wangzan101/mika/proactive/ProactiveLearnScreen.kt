package com.wangzan101.mika.proactive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.core.proactive.ContactRelation
import com.wangzan101.mika.core.proactive.ContactType
import com.wangzan101.mika.core.proactive.DayDigest
import com.wangzan101.mika.core.proactive.DigestLine
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.LearnLevel
import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.core.proactive.ObservedContact
import com.wangzan101.mika.core.proactive.ObservedTopic
import com.wangzan101.mika.core.proactive.SpanDigest
import com.wangzan101.mika.core.proactive.SpanKind
import com.wangzan101.mika.core.proactive.TopicKind
import com.wangzan101.mika.core.proactive.TopicPool
import com.wangzan101.mika.core.proactive.TriageGate
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaSpace
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pages under "What Claire Knows", one level at a time. The top is the index (empty stack): one row each for days, weeks and months, and one
 * entry each for topics, contacts and clearing. Below that: the sentences of one day ([Day]), one week or one month ([Span]); below that the
 * basis of a sentence ([DayLine], [SpanLine]), and the raw notifications of the period behind a segmented digest sentence ([Note]).
 *
 * Pages are identified by stable things (which day, which week, which sentence, a sentence's id), never by position in a list: the data below
 * changes at any time (redone, deletions bubbling up, expiry). When what a page points at is gone it falls back to the level above
 * ([KnowData.valid]), so a delete never lands on "whatever is in that position now".
 */
internal sealed interface KnowPage {
    data object Topics : KnowPage
    data object Contacts : KnowPage
    data object Clear : KnowPage
    data class Day(val day: Long) : KnowPage
    data class Span(val kind: SpanKind, val start: Long) : KnowPage
    /** One sentence of a daily digest: see the segmented digests it is based on. */
    data class DayLine(val day: Long, val line: DigestLine) : KnowPage
    /** One sentence of a weekly or monthly digest: see which days (weeks) it comes from. */
    data class SpanLine(val kind: SpanKind, val start: Long, val line: DigestLine) : KnowPage
    /** One segmented digest sentence: see the raw notifications from that period. */
    data class Note(val id: String) : KnowPage
}

/** The digests stored for this page (the current values of the runtime's flows), and what is derived from them: which entries the index lists, and whether what a page points at still exists. */
internal class KnowData(
    val today: Long,
    /** Whether new digests can be made now (master switch and "daily notification digest" both on, model confirmed): today, this week and this month each get a row even with nothing in them yet. */
    val canMake: Boolean,
    val notes: List<DigestNote>,
    val days: List<DayDigest>,
    val weeks: List<SpanDigest>,
    val months: List<SpanDigest>,
) {
    fun spans(kind: SpanKind): List<SpanDigest> = if (kind == SpanKind.Week) weeks else months

    fun current(kind: SpanKind): Long = if (kind == SpanKind.Week) DigestPool.weekOf(today) else DigestPool.monthOf(today)

    fun day(day: Long): DayDigest? = days.firstOrNull { it.day == day }

    fun span(kind: SpanKind, start: Long): SpanDigest? = spans(kind).firstOrNull { it.start == start }

    /** The days listed under "days" on the index, newest first: those with a daily digest and those that still have segmented digests (not yet merged into one, or deleted while the sentences remain), plus today when new ones can be made. */
    fun dayKeys(): List<Long> =
        (days.map { it.day } + notes.map { it.day } + if (canMake) listOf(today) else emptyList()).distinct().sortedDescending()

    /** What is listed under "weeks" and "months" on the index, newest first: the stored ones, plus this week (this month) when new ones can be made. */
    fun spanKeys(kind: SpanKind): List<Long> =
        (spans(kind).map { it.start } + if (canMake) listOf(current(kind)) else emptyList()).distinct().sortedDescending()

    fun valid(page: KnowPage): Boolean = when (page) {
        KnowPage.Topics, KnowPage.Contacts, KnowPage.Clear -> true
        is KnowPage.Day -> page.day in dayKeys()
        is KnowPage.Span -> page.start in spanKeys(page.kind)
        is KnowPage.DayLine -> day(page.day)?.lines?.contains(page.line) == true
        is KnowPage.SpanLine -> span(page.kind, page.start)?.lines?.contains(page.line) == true
        is KnowPage.Note -> notes.any { it.id == page.id }
    }
}

/** What every page needs: the data, how names are built, how dates are written, and going down a level and back up. */
private class KnowUi(
    val runtime: ProactiveRuntime,
    val data: KnowData,
    val removedDays: Set<Long>,
    val removedSpans: Set<Pair<SpanKind, Long>>,
    val label: (String) -> String,
    val dayText: (Long) -> String,
    val monthText: (Long) -> String,
    val dateFormat: java.text.DateFormat,
    val timeFormat: java.text.DateFormat,
    /** Why "digest up to now" cannot be done right now; null when it can. */
    val blockedNote: String?,
    val listState: (KnowPage) -> LazyListState,
    val open: (KnowPage) -> Unit,
    val up: () -> Unit,
) {
    /** A month's digest covers the weeks that end in that month, which differ by a few days from the calendar month: write out the days it actually covers. */
    fun cover(month: Long): String = DigestPool.monthCover(month).let { "${dayText(it.first)} – ${dayText(it.last)}" }

    fun spanName(kind: SpanKind, start: Long): String =
        if (kind == SpanKind.Week) "${dayText(start)} – ${dayText(start + 6)}" else "${monthText(start)} (${cover(start)})"

    fun time(fromMs: Long, toMs: Long): String {
        val from = timeFormat.format(java.util.Date(fromMs))
        val to = timeFormat.format(java.util.Date(toMs))
        return if (to == from) from else "$from–$to"
    }

    fun noteSub(n: DigestNote): String = listOfNotNull(label(n.packageName), n.title, time(n.fromMs, n.toMs)).joinToString(" · ")

    fun apps(line: DigestLine): String = line.apps.joinToString(" · ") { label(it) }
}

/**
 * Proactive capability › "What Claire Knows": what has been digested, and deleting it. Several levels ([KnowPage]): first an index page, and
 * sentences are listed only after tapping in; tapping a sentence shows what it was written from. Settings all live on the previous page
 * ([ProactiveScreen]); here the stored data is shown regardless of the switches (while off the data is still kept), and only the "digest up to
 * now" rows require them to be on and the model confirmed.
 * Sentences, summaries, topic names, names and raw notification text all come from elsewhere (written by the model from notifications, or from the
 * notifications themselves) and are always shown as plain text.
 *
 * The stored data can run to hundreds of rows (dozens to a hundred segmented digest sentences a day, hundreds each of topics and contacts): the
 * index page has only a dozen rows, and the long lists are one level down, rendered on demand row by row ([LazyPage], [card]).
 */
@Composable
fun ProactiveLearnScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val runtime = app.proactive
    val enabled by runtime.enabled.collectAsState()
    val state by runtime.learnState.collectAsState()
    val topics by runtime.learnTopics.collectAsState()
    val topicsBlocked by runtime.learnTopicsBlocked.collectAsState()
    val removedSpans by runtime.learnDigestRemoved.collectAsState()
    val removedDays by runtime.learnDigestRemovedDays.collectAsState()
    val consent by runtime.consent.collectAsState()
    val level by runtime.learnLevel.collectAsState()
    val limit by runtime.triageLimit.collectAsState()
    val digestOn by runtime.learnDigest.collectAsState()
    val notes by runtime.learnDigestNotes.collectAsState()
    val days by runtime.learnDigestDays.collectAsState()
    val weeks by runtime.learnDigestWeeks.collectAsState()
    val months by runtime.learnDigestMonths.collectAsState()
    val contactsView by runtime.learnContactsView.collectAsState()
    val contacts = contactsView.contacts
    var editRelation by remember { mutableStateOf<ObservedContact?>(null) }
    var merging by remember { mutableStateOf<ObservedTopic?>(null) }
    // The model config and usage ledger are touched only while the proactive capability is on (same as the proactive page); topics and deletes work even when it is off
    var usageTick by remember { mutableStateOf(0) }
    val budgetOut = remember(enabled, state, limit, usageTick) {
        if (!enabled) false else runtime.triageUsage().let { (tokens, calls) ->
            ProactiveBudget.exhausted(tokens, calls, limit) != null
        }
    }
    if (enabled) {
        val version by remember { runtime.usageVersion() }.collectAsState()
        LaunchedEffect(version) { usageTick++ }
    }

    var target by remember { mutableStateOf<TriageTarget?>(null) }
    var targetRead by remember { mutableStateOf(0) }
    LaunchedEffect(enabled, consent, targetRead) { target = if (enabled) runtime.triageTarget() else null }
    LifecycleResumeEffect(Unit) {
        targetRead++
        // When the page is opened (or returned to), load the topic pool and tidy it up
        runtime.onLearn { open() }
        onPauseOrDispose {}
    }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmClearApp by remember { mutableStateOf<String?>(null) }
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        labels = withContext(Dispatchers.IO) {
            runCatching { launchableApps(app.packageManager, app.packageName) }.getOrDefault(emptyList()).toMap()
        }
    }
    fun label(pkg: String) = labels[pkg] ?: pkg
    val dateFormat = remember { android.text.format.DateFormat.getMediumDateFormat(context) }
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    // "November 9" and "November 2026": formatted per the system language and calendar (using the system's own formatter; its output is not fed into another formatter)
    val dayFormat = remember { android.icu.text.DateFormat.getInstanceForSkeleton("MMMd", java.util.Locale.getDefault()) }
    val monthFormat = remember { android.icu.text.DateFormat.getInstanceForSkeleton("yMMMM", java.util.Locale.getDefault()) }
    fun dayText(epochDay: Long): String = dayFormat.format(java.util.Date(java.time.LocalDate.ofEpochDay(epochDay).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
    fun monthText(epochDay: Long): String = monthFormat.format(java.util.Date(java.time.LocalDate.ofEpochDay(epochDay).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
    val gate = TriageGate.of(enabled, consent, target)
    // What has already been digested is kept even when the switches are off (only new ones stop). The "digest up to now" rows are shown only when it can really be done
    val canMake = enabled && digestOn && gate == TriageGate.Ready
    val blockedNote = makeBlocked(budgetOut, state.status)?.let { stringResource(it) }
    val today = java.time.LocalDate.now().toEpochDay()
    val data = remember(today, canMake, notes, days, weeks, months) { KnowData(today, canMake, notes, days, weeks, months) }

    // The stack of pages, one level down at a time (empty is the index). Levels whose target is gone are not shown and are removed from the stack
    var stack by remember { mutableStateOf(listOf<KnowPage>()) }
    val shown = stack.dropLastWhile { !data.valid(it) }
    LaunchedEffect(shown, stack) { if (shown != stack) stack = shown }
    // Where each page had scrolled to: coming back from a level below returns to the same place; a level that leaves the stack is forgotten
    val indexScroll = rememberScrollState()
    val listStates = remember { HashMap<KnowPage, LazyListState>() }
    LaunchedEffect(shown) { listStates.keys.retainAll(shown.toSet()) }
    val up = { if (shown.isEmpty()) onBack() else stack = shown.dropLast(1) }
    // Back goes up one level first, and leaves this page only at the index
    BackHandler(onBack = up)
    var moreDays by remember { mutableStateOf(false) }
    var moreWeeks by remember { mutableStateOf(false) }
    var moreMonths by remember { mutableStateOf(false) }

    val ui = KnowUi(
        runtime, data, removedDays, removedSpans, ::label, ::dayText, ::monthText, dateFormat, timeFormat, blockedNote,
        listState = { listStates.getOrPut(it) { LazyListState() } },
        open = { stack = shown + it },
        up = up,
    )
    when (val page = shown.lastOrNull()) {
        null -> IndexPage(
            ui, indexScroll, topics.size, contacts.size,
            moreDays, { moreDays = true }, moreWeeks, { moreWeeks = true }, moreMonths, { moreMonths = true },
        )
        is KnowPage.Day -> DayPage(ui, page)
        is KnowPage.Span -> SpanPage(ui, page)
        is KnowPage.DayLine -> DayLinePage(ui, page)
        is KnowPage.SpanLine -> SpanLinePage(ui, page)
        is KnowPage.Note -> NotePage(ui, page)
        KnowPage.Topics -> TopicsPage(ui, topics, level, state.status == LearnStatus.StoreUnavailable) { merging = it }
        KnowPage.Contacts -> ContactsPage(ui, contacts, topics) { editRelation = it }
        KnowPage.Clear -> ClearPage(
            ui, remember(topics, contacts, notes, days, weeks, months, state.journalApps, labels) {
                clearableApps(topics, contacts, notes, days, weeks + months, state.journalApps).sortedBy { label(it).lowercase() }
            },
            topicsBlocked, contactsView.blocked, { confirmClearAll = true }, { confirmClearApp = it },
        )
    }

    if (confirmClearAll) {
        MikaDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text(stringResource(R.string.proactive_learn_clear_all)) },
            text = { Text(stringResource(R.string.proactive_learn_clear_all_body)) },
            confirmButton = {
                TextButton({
                    runtime.onLearn { clearAll() }
                    confirmClearAll = false
                }) { Text(stringResource(R.string.proactive_learn_delete)) }
            },
            dismissButton = { TextButton({ confirmClearAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    editRelation?.let { c ->
        // The relation is set by the user: list the relations and "unknown"; "hand back to Claire" clears what the user set
        MikaDialog(
            onDismissRequest = { editRelation = null },
            title = { Text(stringResource(R.string.proactive_learn_relation_title, c.name)) },
            text = {
                Column {
                    ContactRelation.entries.forEach { r ->
                        TextButton({
                            runtime.onLearn { setContactRelation(c.id, r) }
                            editRelation = null
                        }) { Text(stringResource(relationLabel(r)) + if (c.userRelation == r) " ✓" else "") }
                    }
                    if (c.userRelation != null) {
                        TextButton({
                            runtime.onLearn { setContactRelation(c.id, null) }
                            editRelation = null
                        }) { Text(stringResource(R.string.proactive_learn_relation_reset)) }
                    }
                }
            },
            // Tapping an item sets it and closes the dialog, so the only button is "cancel". It goes on confirmButton: MikaDialog always draws
            // that slot, and putting it on dismissButton would add a wordless blue button beside it (the same pattern as the classification dialog under "App access")
            confirmButton = { TextButton({ editRelation = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    merging?.let { t ->
        // Merge this topic into another: list the other topics (similar names first); tapping one merges and closes. A merge cannot be undone,
        // and the dialog says so. There can be hundreds of topics: the list has a max height and scrolls.
        // The order is computed once, when the dialog opens: while it is open the topic pool changes (a digest batch arrives, a notification is
        // opened), and re-sorting along with it could put a different topic under the finger that is about to land, while one tap merges and
        // cannot be undone. Topics that disappear meanwhile are dropped from the list
        val order = remember(t.id) { TopicPool.mergeCandidates(t, topics, System.currentTimeMillis()).map { it.id } }
        val others = remember(order, topics) { topics.associateBy { it.id }.let { now -> order.mapNotNull(now::get) } }
        MikaDialog(
            onDismissRequest = { merging = null },
            title = { Text(stringResource(R.string.proactive_learn_topic_merge_title, t.label)) },
            text = {
                Column {
                    Text(stringResource(R.string.proactive_learn_topic_merge_body))
                    LazyColumn(Modifier.heightIn(max = 320.dp).testTag("merge_targets")) {
                        items(others, key = { it.id }) { other ->
                            TextButton({
                                runtime.onLearn { mergeTopic(t.id, other.id) }
                                merging = null
                            }) { Text(other.label + " · " + stringResource(kindLabel(other.kind))) }
                        }
                    }
                }
            },
            // Only one "cancel" button, on confirmButton (same as the relation dialog above)
            confirmButton = { TextButton({ merging = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    confirmClearApp?.let { pkg ->
        MikaDialog(
            onDismissRequest = { confirmClearApp = null },
            title = { Text(stringResource(R.string.proactive_learn_clear_app, label(pkg))) },
            text = { Text(stringResource(R.string.proactive_learn_clear_app_body, label(pkg))) },
            confirmButton = {
                TextButton({
                    runtime.onLearn { clearApp(pkg) }
                    confirmClearApp = null
                }) { Text(stringResource(R.string.proactive_learn_delete)) }
            },
            dismissButton = { TextButton({ confirmClearApp = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---- Page skeleton: long lists rendered on demand, one row at a time ----

/** A top bar with back, then a list rendered on demand (the same padding as [PageScaffold], just without drawing every row at once). */
@Composable
private fun LazyPage(title: String, onBack: () -> Unit, state: LazyListState, content: LazyListScope.() -> Unit) {
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, MikaIcons.Back, onBack, stringResource(R.string.back))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars),
                state = state,
                contentPadding = PaddingValues(start = MikaSpace.Page, end = MikaSpace.Page, top = MikaSpace.S4, bottom = MikaSpace.S24),
                content = content,
            )
        }
    }
}

/** The items inside one card: rows ([row], with separators) and notes ([note], without). */
private class CardRows {
    class Entry(val key: String?, val isRow: Boolean, val content: @Composable (first: Boolean) -> Unit)

    val entries = ArrayList<Entry>()

    /** [key]: what this row points at (a sentence id and the like), so it is still recognized as the same row when the list changes; without it, position is used. [first]: there is no other row before it in this card. */
    fun row(key: String? = null, content: @Composable (first: Boolean) -> Unit) {
        entries += Entry(key, true, content)
    }

    fun note(content: @Composable () -> Unit) {
        entries += Entry(null, false) { content() }
    }
}

/** The corner radii of each row when a card is split into lazily loaded rows: round on top for the first, round at the bottom for the last. */
private fun cardRowShape(i: Int, n: Int): RoundedCornerShape {
    val r = MikaRadius.Card
    return RoundedCornerShape(
        topStart = if (i == 0) r else 0.dp, topEnd = if (i == 0) r else 0.dp,
        bottomStart = if (i == n - 1) r else 0.dp, bottomEnd = if (i == n - 1) r else 0.dp,
    )
}

/**
 * A grouped card inside a lazy list (the look of [GroupCard], split into one row per item). [id] must be unique within the page.
 * Duplicate keys in a list crash, so if a row's own key happens to repeat (two identical ids in the stored data) it falls back to position.
 */
private fun LazyListScope.card(id: String, build: CardRows.() -> Unit) {
    val entries = CardRows().apply(build).entries
    if (entries.isEmpty()) return
    val firstRow = entries.indexOfFirst { it.isRow }
    val seen = HashSet<String>()
    val keys = entries.mapIndexed { i, e -> e.key?.let { "$id/k:$it" }?.takeIf(seen::add) ?: "$id/#$i" }
    itemsIndexed(entries, key = { i, _ -> keys[i] }, contentType = { _, e -> if (e.isRow) 1 else 0 }) { i, e ->
        Box(
            Modifier.fillMaxWidth().padding(bottom = if (i == entries.lastIndex) 12.dp else 0.dp)
                .clip(cardRowShape(i, entries.size)).background(MikaTheme.colors.surface),
        ) { e.content(i == firstRow) }
    }
}

private fun LazyListScope.label(id: String, text: @Composable () -> String) {
    item(key = "label:$id", contentType = 2) { GroupLabel(text()) }
}

// ---- Index ----

/** The small second line for a digest on the index: the summary if there is one; otherwise (old ones, cleared ones) "how many sentences · which Apps"; with no sentence at all, [empty]. */
@Composable
private fun digestSub(ui: KnowUi, brief: String, lines: List<DigestLine>, empty: Int): String = when {
    lines.isEmpty() -> stringResource(empty)
    brief.isNotEmpty() -> brief
    else -> (listOf(stringResource(R.string.proactive_know_lines, lines.size)) + lines.flatMap { it.apps }.distinct().take(3).map(ui.label)).joinToString(" · ")
}

@Composable
private fun dayTitle(ui: KnowUi, day: Long): String = when (day) {
    ui.data.today -> stringResource(R.string.proactive_learn_day_today)
    ui.data.today - 1 -> stringResource(R.string.proactive_learn_day_yesterday)
    else -> ui.dayText(day)
}

@Composable
private fun daySub(ui: KnowUi, day: Long): String {
    val d = ui.data.day(day)
    val count = ui.data.notes.count { it.day == day }
    return when {
        d != null -> digestSub(ui, d.brief, d.lines, R.string.proactive_learn_day_nothing)
        day in ui.removedDays -> stringResource(R.string.proactive_know_removed)
        count > 0 -> stringResource(R.string.proactive_know_pending, count)
        else -> stringResource(R.string.proactive_learn_empty)
    }
}

@Composable
private fun spanTitle(ui: KnowUi, kind: SpanKind, start: Long): String = when {
    start != ui.data.current(kind) -> ui.spanName(kind, start)
    kind == SpanKind.Week -> stringResource(R.string.proactive_learn_week_this)
    else -> stringResource(R.string.proactive_learn_month_this)
}

@Composable
private fun spanSub(ui: KnowUi, kind: SpanKind, start: Long): String {
    val d = ui.data.span(kind, start)
    return when {
        d != null -> digestSub(ui, d.brief, d.lines, R.string.proactive_learn_span_nothing)
        (kind to start) in ui.removedSpans -> stringResource(R.string.proactive_know_removed)
        else -> stringResource(R.string.proactive_learn_empty)
    }
}

/** The index lists a few rows first and expands older ones on tap (at most thirty-odd days and twenty-odd weeks; no need for lazy loading, but no need to show them all up front either). */
private const val INDEX_DAYS = 7
private const val INDEX_WEEKS = 4
private const val INDEX_MONTHS = 3

@Composable
private fun IndexPage(
    ui: KnowUi, scroll: ScrollState, topics: Int, contacts: Int,
    moreDays: Boolean, onMoreDays: () -> Unit, moreWeeks: Boolean, onMoreWeeks: () -> Unit, moreMonths: Boolean, onMoreMonths: () -> Unit,
) {
    PageScaffold(stringResource(R.string.proactive_learn_title), ui.up, scrollState = scroll) {
        @Composable
        fun <T> section(title: Int, keys: List<T>, shown: Int, more: Boolean, onMore: () -> Unit, row: @Composable (T, Boolean) -> Unit) {
            if (keys.isEmpty()) return
            GroupLabel(stringResource(title))
            GroupCard {
                val visible = if (more) keys else keys.take(shown)
                visible.forEachIndexed { i, k -> row(k, i == 0) }
                if (visible.size < keys.size) {
                    ListRow(null, stringResource(R.string.proactive_know_older, keys.size - visible.size), onClick = onMore)
                }
            }
        }
        section(R.string.proactive_know_days, ui.data.dayKeys(), INDEX_DAYS, moreDays, onMoreDays) { day, first ->
            ListRow(null, dayTitle(ui, day), daySub(ui, day), first = first, onClick = { ui.open(KnowPage.Day(day)) }) { RowChevron() }
        }
        for (kind in SpanKind.entries) {
            val week = kind == SpanKind.Week
            section(
                if (week) R.string.proactive_know_weeks else R.string.proactive_know_months, ui.data.spanKeys(kind),
                if (week) INDEX_WEEKS else INDEX_MONTHS, if (week) moreWeeks else moreMonths, if (week) onMoreWeeks else onMoreMonths,
            ) { start, first ->
                ListRow(null, spanTitle(ui, kind, start), spanSub(ui, kind, start), first = first, onClick = { ui.open(KnowPage.Span(kind, start)) }) { RowChevron() }
            }
        }
        GroupLabel(stringResource(R.string.proactive_learn_topics))
        GroupCard {
            ListRow(
                null, stringResource(R.string.proactive_know_topics), stringResource(R.string.proactive_know_count, topics),
                first = true, onClick = { ui.open(KnowPage.Topics) },
            ) { RowChevron() }
            ListRow(
                null, stringResource(R.string.proactive_learn_contacts), stringResource(R.string.proactive_know_count, contacts),
                onClick = { ui.open(KnowPage.Contacts) },
            ) { RowChevron() }
        }
        GroupCard {
            ListRow(null, stringResource(R.string.proactive_know_clear), first = true, onClick = { ui.open(KnowPage.Clear) }) { RowChevron() }
        }
    }
}

// ---- One day: the sentences of its daily digest, and that day's segmented digests ----

@Composable
private fun DayPage(ui: KnowUi, page: KnowPage.Day) {
    val day = page.day
    val isToday = day == ui.data.today
    val d = ui.data.day(day)
    val mine = remember(ui.data.notes, day) { ui.data.notes.filter { it.day == day }.sortedByDescending { it.toMs } }
    val canMake = isToday && ui.data.canMake
    val title = if (isToday || day == ui.data.today - 1) dayTitle(ui, day) else stringResource(R.string.proactive_learn_day_of, ui.dayText(day))
    LazyPage(title, ui.up, ui.listState(page)) {
        card("digest") {
            dayNote(d, day in ui.removedDays, isToday, ui.data.canMake)?.let { text -> note { EmptyNote(stringResource(text)) } }
            d?.lines?.forEach { line ->
                row { first -> ListRow(null, line.text, ui.apps(line), first = first, onClick = { ui.open(KnowPage.DayLine(day, line)) }) { RowChevron() } }
            }
            // This day had too many sentences and not all fit: say so, this one was not written from all of them
            if (d != null && d.leftOut > 0) note { EmptyNote(stringResource(R.string.proactive_learn_left_out, d.leftOut)) }
            if (canMake) row { first ->
                val made = d?.let { stringResource(R.string.proactive_learn_day_made, ui.timeFormat.format(java.util.Date(it.madeAtMs))) }
                // Budget exhausted and the like: tapping would send nothing, so state the reason and give no button (if it were tappable anyway, tapping would do nothing)
                ListRow(
                    null, stringResource(R.string.proactive_learn_day_make), ui.blockedNote ?: made, first = first,
                    onClick = if (ui.blockedNote != null) null else ({ ui.runtime.onLearn { digestToday() } }),
                ) {
                    if (ui.blockedNote == null) TextButton({ ui.runtime.onLearn { digestToday() } }, Modifier.testTag("proactive-learn-day-make")) {
                        Text(stringResource(R.string.proactive_learn_day_make_action))
                    }
                }
            }
            if (d != null) row { first ->
                ListRow(null, stringResource(R.string.proactive_learn_delete), first = first, onClick = { ui.runtime.onLearn { deleteDay(day) } })
            }
        }
        label("notes") {
            if (isToday) stringResource(R.string.proactive_learn_digest_today) else stringResource(R.string.proactive_learn_digest_of, ui.dayText(day))
        }
        card("notes") {
            if (mine.isEmpty()) note {
                EmptyNote(stringResource(if (isToday) R.string.proactive_learn_digest_empty else R.string.proactive_know_notes_gone))
            }
            mine.forEach { n -> row(n.id) { first -> NoteRow(ui, n, first) } }
        }
    }
}

/** One segmented digest sentence: tap to see the raw notifications from that period; delete on the right. */
@Composable
private fun NoteRow(ui: KnowUi, n: DigestNote, first: Boolean) {
    ListRow(null, n.text, ui.noteSub(n), first = first, onClick = { ui.open(KnowPage.Note(n.id)) }) {
        TextButton({ ui.runtime.onLearn { deleteNote(n.id) } }) { Text(stringResource(R.string.proactive_learn_delete)) }
    }
}

// ---- One week, one month ----

@Composable
private fun SpanPage(ui: KnowUi, page: KnowPage.Span) {
    val kind = page.kind
    val start = page.start
    val isWeek = kind == SpanKind.Week
    val current = start == ui.data.current(kind)
    val d = ui.data.span(kind, start)
    val canMake = current && ui.data.canMake
    val title = when {
        current && isWeek -> stringResource(R.string.proactive_learn_week_this)
        current -> stringResource(R.string.proactive_learn_month_this) + " (${ui.cover(start)})"
        else -> stringResource(if (isWeek) R.string.proactive_learn_week_of else R.string.proactive_learn_month_of, ui.spanName(kind, start))
    }
    LazyPage(title, ui.up, ui.listState(page)) {
        card("digest") {
            spanNote(d, (kind to start) in ui.removedSpans, kind, canMake)?.let { text -> note { EmptyNote(stringResource(text)) } }
            d?.lines?.forEach { line ->
                // Under each sentence, which days it comes from (weekly digests) and which Apps
                val sub = ((if (isWeek) line.days.sorted().map(ui.dayText) else emptyList()) + line.apps.map(ui.label)).joinToString(" · ")
                row { first -> ListRow(null, line.text, sub, first = first, onClick = { ui.open(KnowPage.SpanLine(kind, start, line)) }) { RowChevron() } }
            }
            if (d != null && d.leftOut > 0) note { EmptyNote(stringResource(R.string.proactive_learn_left_out, d.leftOut)) }
            if (canMake) row { first ->
                val made = d?.let {
                    val at = java.util.Date(it.madeAtMs)
                    stringResource(R.string.proactive_learn_day_made, ui.dateFormat.format(at) + " " + ui.timeFormat.format(at))
                }
                val make = { ui.runtime.onLearn { if (isWeek) digestWeek() else digestMonth() } }
                ListRow(
                    null, stringResource(if (isWeek) R.string.proactive_learn_week_make else R.string.proactive_learn_month_make),
                    ui.blockedNote ?: made, first = first, onClick = if (ui.blockedNote != null) null else make,
                ) {
                    if (ui.blockedNote == null) TextButton(make, Modifier.testTag("proactive-learn-${kind.name.lowercase()}-make")) {
                        Text(stringResource(R.string.proactive_learn_day_make_action))
                    }
                }
            }
            if (d != null) row { first ->
                ListRow(null, stringResource(R.string.proactive_learn_delete), first = first, onClick = { ui.runtime.onLearn { deleteSpan(kind, start) } })
            }
        }
        // The days of this week (the weeks of this month): while this digest has not been made yet, what already exists in the period is visible here too
        if (isWeek) {
            val inWeek = ui.data.dayKeys().filter { DigestPool.weekOf(it) == start }
            if (inWeek.isNotEmpty()) {
                label("days") { stringResource(R.string.proactive_know_days) }
                card("days") {
                    inWeek.forEach { day ->
                        row(day.toString()) { first ->
                            ListRow(null, dayTitle(ui, day), daySub(ui, day), first = first, onClick = { ui.open(KnowPage.Day(day)) }) { RowChevron() }
                        }
                    }
                }
            }
        } else {
            val inMonth = ui.data.spanKeys(SpanKind.Week).filter { DigestPool.monthOfWeek(it) == start }
            if (inMonth.isNotEmpty()) {
                label("weeks") { stringResource(R.string.proactive_know_weeks) }
                card("weeks") {
                    inMonth.forEach { w ->
                        row(w.toString()) { first ->
                            ListRow(null, spanTitle(ui, SpanKind.Week, w), spanSub(ui, SpanKind.Week, w), first = first, onClick = { ui.open(KnowPage.Span(SpanKind.Week, w)) }) { RowChevron() }
                        }
                    }
                }
            }
        }
    }
}

// ---- The basis of a sentence ----

/** Which segmented digest sentences a daily digest sentence was written from (their ids are recorded on the sentence; segmented digests are kept only eight days, and expired or deleted ones are just counted). */
@Composable
private fun DayLinePage(ui: KnowUi, page: KnowPage.DayLine) {
    val line = page.line
    val found = remember(ui.data.notes, line) { ui.data.notes.filter { it.id in line.notes }.sortedBy { it.fromMs } }
    val gone = line.notes.size - found.size
    LazyPage(stringResource(R.string.proactive_know_basis), ui.up, ui.listState(page)) {
        label("line") { stringResource(R.string.proactive_know_basis_line) }
        card("line") { row { first -> ListRow(null, line.text, ui.apps(line), first = first) } }
        label("notes") { stringResource(R.string.proactive_know_basis_notes) }
        card("notes") {
            if (found.isEmpty()) note { EmptyNote(stringResource(R.string.proactive_know_basis_none)) }
            found.forEach { n -> row(n.id) { first -> NoteRow(ui, n, first) } }
            if (found.isNotEmpty() && gone > 0) note { EmptyNote(stringResource(R.string.proactive_know_basis_gone, gone)) }
        }
    }
}

/**
 * Which days (weeks) a weekly or monthly digest sentence comes from: the sentence only records "which days (weeks)", not which sentence within
 * them, so tapping goes to that day's (that week's) page. One that has expired or been deleted just shows a line and cannot be tapped.
 */
@Composable
private fun SpanLinePage(ui: KnowUi, page: KnowPage.SpanLine) {
    val line = page.line
    val isWeek = page.kind == SpanKind.Week
    LazyPage(stringResource(R.string.proactive_know_basis), ui.up, ui.listState(page)) {
        label("line") { stringResource(R.string.proactive_know_basis_line) }
        card("line") { row { first -> ListRow(null, line.text, ui.apps(line), first = first) } }
        label("from") { stringResource(if (isWeek) R.string.proactive_know_basis_days else R.string.proactive_know_basis_weeks) }
        card("from") {
            val from = (if (isWeek) line.days else line.weeks).distinct().sorted()
            if (from.isEmpty()) note { EmptyNote(stringResource(R.string.proactive_know_basis_none)) }
            from.forEach { key ->
                row(key.toString()) { first ->
                    if (isWeek) {
                        val there = key in ui.data.dayKeys()
                        ListRow(
                            null, ui.dayText(key), if (there) daySub(ui, key) else stringResource(R.string.proactive_know_gone), first = first,
                            onClick = if (there) ({ ui.open(KnowPage.Day(key)) }) else null,
                        ) { if (there) RowChevron() }
                    } else {
                        val there = ui.data.span(SpanKind.Week, key) != null
                        ListRow(
                            null, ui.spanName(SpanKind.Week, key),
                            if (there) spanSub(ui, SpanKind.Week, key) else stringResource(R.string.proactive_know_gone), first = first,
                            onClick = if (there) ({ ui.open(KnowPage.Span(SpanKind.Week, key)) }) else null,
                        ) { if (there) RowChevron() }
                    }
                }
            }
        }
    }
}

/**
 * Which notifications a segmented digest sentence was written from. The sentence only records the App, conversation name and start/end time, so
 * what is listed is the notifications of that conversation (of that App when no conversation name was recorded) still stored for that period,
 * possibly a few more than were used. Raw text is kept only two days: when there is none, say clearly whether it expired, was deleted because
 * the switch is off, or cannot be read.
 */
@Composable
private fun NotePage(ui: KnowUi, page: KnowPage.Note) {
    val n = ui.data.notes.firstOrNull { it.id == page.id } ?: return
    val raw by produceState<RawNotices?>(null, n) { value = ui.runtime.learnRaw(n) }
    LazyPage(stringResource(R.string.proactive_know_raw), ui.up, ui.listState(page)) {
        label("note") { stringResource(R.string.proactive_know_basis_line) }
        card("note") {
            row { first -> ListRow(null, n.text, ui.noteSub(n), first = first) }
            row { first ->
                ListRow(null, stringResource(R.string.proactive_know_delete_note), first = first, onClick = { ui.runtime.onLearn { deleteNote(n.id) } })
            }
        }
        label("raw") { stringResource(if (n.title != null) R.string.proactive_know_raw_label else R.string.proactive_know_raw_label_app) }
        card("raw") {
            when (val r = raw) {
                null -> Unit
                RawNotices.Off -> note { EmptyNote(stringResource(R.string.proactive_know_raw_off)) }
                RawNotices.Unavailable -> note { EmptyNote(stringResource(R.string.proactive_know_raw_unavailable)) }
                is RawNotices.Found -> {
                    if (r.entries.isEmpty()) note {
                        val expired = n.toMs < System.currentTimeMillis() - NoticeJournal.KEEP_MS
                        EmptyNote(stringResource(if (expired) R.string.proactive_know_raw_expired else R.string.proactive_know_raw_none))
                    }
                    r.entries.forEach { e ->
                        row { first ->
                            // The sentence recorded no conversation name: these entries may come from several conversations, so write each one's conversation name
                            val chat = e.title.takeIf { n.title == null && it.isNotBlank() }
                            ListRow(null, e.text, listOfNotNull(chat, e.sender, ui.timeFormat.format(java.util.Date(e.atMs))).joinToString(" · "), first = first)
                        }
                    }
                }
            }
        }
    }
}

// ---- Topics, contacts, clearing ----

@Composable
private fun TopicsPage(ui: KnowUi, topics: List<ObservedTopic>, level: LearnLevel, storeUnavailable: Boolean, onMerge: (ObservedTopic) -> Unit) {
    // With only one topic there is nothing to merge into, so "merge into" is not offered
    val merge: ((ObservedTopic) -> Unit)? = if (topics.size > 1) onMerge else null
    // How many days count as established follows the aggressiveness level. Sorted only when the topics change, not on every recomposition
    val (sure, unsure) = remember(topics, level) {
        val now = System.currentTimeMillis()
        topics.sortedByDescending { it.score(now) }.partition { level.established(it) }
    }
    LazyPage(stringResource(R.string.proactive_know_topics), ui.up, ui.listState(KnowPage.Topics)) {
        card("sure") {
            if (sure.isEmpty()) note {
                EmptyNote(stringResource(if (storeUnavailable) R.string.proactive_learn_state_store else R.string.proactive_learn_empty))
            }
            sure.forEach { t -> row(t.id) { first -> TopicRow(t, first, ui.label, ui.dateFormat, merge?.let { { it(t) } }) { ui.runtime.onLearn { deleteTopic(t.id) } } } }
        }
        if (unsure.isNotEmpty()) {
            label("unsure") { stringResource(R.string.proactive_learn_unsure) }
            card("unsure") {
                unsure.forEach { t -> row(t.id) { first -> TopicRow(t, first, ui.label, ui.dateFormat, merge?.let { { it(t) } }) { ui.runtime.onLearn { deleteTopic(t.id) } } } }
            }
        }
    }
}

@Composable
private fun ContactsPage(ui: KnowUi, contacts: List<ObservedContact>, topics: List<ObservedTopic>, onRelation: (ObservedContact) -> Unit) {
    val (sure, unsure) = remember(contacts) {
        val now = System.currentTimeMillis()
        contacts.sortedByDescending { it.score(now) }.partition { it.established }
    }
    val topicLabels = remember(topics) { topics.associate { it.id to it.label } }
    LazyPage(stringResource(R.string.proactive_learn_contacts), ui.up, ui.listState(KnowPage.Contacts)) {
        card("sure") {
            if (sure.isEmpty()) note { EmptyNote(stringResource(R.string.proactive_learn_contacts_empty)) }
            sure.forEach { c ->
                row(c.id) { first -> ContactRow(c, first, ui.label, topicLabels, ui.dateFormat, { onRelation(c) }) { ui.runtime.onLearn { deleteContact(c.id) } } }
            }
        }
        if (unsure.isNotEmpty()) {
            label("unsure") { stringResource(R.string.proactive_learn_unsure) }
            card("unsure") {
                unsure.forEach { c ->
                    row(c.id) { first -> ContactRow(c, first, ui.label, topicLabels, ui.dateFormat, { onRelation(c) }) { ui.runtime.onLearn { deleteContact(c.id) } } }
                }
            }
        }
        item(key = "footer") { FooterNote(stringResource(R.string.proactive_learn_contacts_sub)) }
    }
}

@Composable
private fun ClearPage(
    ui: KnowUi, clearable: List<String>, topicsBlocked: Int, contactsBlocked: Int, onClearAll: () -> Unit, onClearApp: (String) -> Unit,
) {
    PageScaffold(stringResource(R.string.proactive_know_clear), ui.up) {
        GroupCard {
            // "Clear all" is always present: there may be something even when the page lists nothing — when one of the stores cannot be read, clearing is the only way to delete it (see StoreCodec)
            ListRow(null, stringResource(R.string.proactive_learn_clear_all), first = true, onClick = onClearAll)
            // Clear one App's data: list every App that has topic evidence, has contacts, or has sentences in digests at any level
            clearable.forEach { pkg ->
                ListRow(null, stringResource(R.string.proactive_learn_clear_app, ui.label(pkg)), onClick = { onClearApp(pkg) })
            }
        }
        if (topicsBlocked > 0 || contactsBlocked > 0) GroupCard {
            if (topicsBlocked > 0) {
                ListRow(
                    null, stringResource(R.string.proactive_learn_topics_allow_again), stringResource(R.string.proactive_learn_topics_blocked, topicsBlocked),
                    first = true, onClick = { ui.runtime.onLearn { allowDeletedTopics() } },
                )
            }
            if (contactsBlocked > 0) {
                ListRow(
                    null, stringResource(R.string.proactive_learn_contacts_allow_again), stringResource(R.string.proactive_learn_contacts_blocked, contactsBlocked),
                    first = topicsBlocked == 0, onClick = { ui.runtime.onLearn { allowDeletedContacts() } },
                )
            }
        }
    }
}

/** One topic: name (plain text), kind, which Apps confirmed it over how many days, when it last appeared, how often opened/dismissed; delete on the right. */
@Composable
private fun TopicRow(
    t: ObservedTopic, first: Boolean, label: (String) -> String, dateFormat: java.text.DateFormat, onMerge: (() -> Unit)?, onDelete: () -> Unit,
) {
    val apps = t.evidence.keys.joinToString(", ", transform = label)
    // The topic name is shown as plain text; sensitive ones get a small plain-text tag after it
    ListRow(
        null, if (t.sensitive) t.label + " · " + stringResource(R.string.proactive_learn_sensitive_tag) else t.label,
        stringResource(R.string.proactive_learn_topic_detail, stringResource(kindLabel(t.kind)), apps, t.days, dateFormat.format(java.util.Date(t.lastSeenMs))),
        first = first,
        detail = stringResource(R.string.proactive_learn_topic_outcomes, t.apps, t.opened, t.dismissed),
    ) {
        Row {
            if (onMerge != null) TextButton(onMerge) { Text(stringResource(R.string.proactive_learn_topic_merge)) }
            TextButton(onDelete) { Text(stringResource(R.string.proactive_learn_delete)) }
        }
    }
}

/**
 * One contact: name (plain text), type, which App, relation (guessed ones are tagged "guessed", user-set ones "set by you"), over how many days
 * seen, when last seen; the next line is the linked topics. On the right, edit the relation and delete.
 */
@Composable
private fun ContactRow(
    c: ObservedContact,
    first: Boolean,
    label: (String) -> String,
    topicLabels: Map<String, String>,
    dateFormat: java.text.DateFormat,
    onRelation: () -> Unit,
    onDelete: () -> Unit,
) {
    val rel = stringResource(relationLabel(c.relation))
    val relation = when {
        c.userSet -> stringResource(R.string.proactive_learn_rel_user, rel)
        c.relation == ContactRelation.Unknown -> rel
        else -> stringResource(R.string.proactive_learn_rel_guess, rel)
    }
    val type = stringResource(if (c.type == ContactType.Group) R.string.proactive_learn_type_group else R.string.proactive_learn_type_person)
    val linked = c.topics.mapNotNull(topicLabels::get)
    ListRow(
        null, c.name,
        stringResource(R.string.proactive_learn_contact_detail, type, label(c.packageName), relation, c.days.size, dateFormat.format(java.util.Date(c.lastSeenMs))),
        first = first,
        detail = if (linked.isEmpty()) null else stringResource(R.string.proactive_learn_contact_topics, linked.joinToString(", ")),
    ) {
        Row {
            TextButton(onRelation) { Text(stringResource(R.string.proactive_learn_relation)) }
            TextButton(onDelete) { Text(stringResource(R.string.proactive_learn_delete)) }
        }
    }
}

private fun relationLabel(r: ContactRelation): Int = when (r) {
    ContactRelation.Family -> R.string.proactive_learn_rel_family
    ContactRelation.Partner -> R.string.proactive_learn_rel_partner
    ContactRelation.Friend -> R.string.proactive_learn_rel_friend
    ContactRelation.Colleague -> R.string.proactive_learn_rel_colleague
    ContactRelation.Classmate -> R.string.proactive_learn_rel_classmate
    ContactRelation.Other -> R.string.proactive_learn_rel_other
    ContactRelation.Unknown -> R.string.proactive_learn_rel_unknown
}

private fun kindLabel(k: TopicKind): Int = when (k) {
    TopicKind.Interest -> R.string.proactive_learn_kind_interest
    TopicKind.Follow -> R.string.proactive_learn_kind_follow
    TopicKind.Shopping -> R.string.proactive_learn_kind_shopping
    TopicKind.Service -> R.string.proactive_learn_kind_service
    TopicKind.Routine -> R.string.proactive_learn_kind_routine
}

/**
 * Which Apps "clear what came from it" lists: those with evidence on topics, with contacts, with sentences in segmented digests or in daily
 * (weekly, monthly) digests that were based on them, those whose sentences a digest's summary was written from (only counted while the summary
 * still exists; after segmented digests expire, an App may survive only in the summary), or those whose raw notifications are still kept
 * ([raw]) — an App that just got a few notifications and has digested nothing exists only as raw text, and without listing it the only option
 * would be clearing everything.
 */
internal fun clearableApps(
    topics: List<com.wangzan101.mika.core.proactive.ObservedTopic>,
    contacts: List<com.wangzan101.mika.core.proactive.ObservedContact>,
    notes: List<com.wangzan101.mika.core.proactive.DigestNote>,
    days: List<com.wangzan101.mika.core.proactive.DayDigest>,
    spans: List<com.wangzan101.mika.core.proactive.SpanDigest>,
    raw: Set<String> = emptySet(),
): Set<String> = LinkedHashSet<String>().apply {
    topics.forEach { addAll(it.evidence.keys) }
    contacts.forEach { add(it.packageName) }
    notes.forEach { add(it.packageName) }
    // Source records may remain after a summary was cleared: count them only while the summary still has text, otherwise listed Apps would have nothing left to clear
    days.forEach { d ->
        d.lines.forEach { addAll(it.apps) }
        if (d.brief.isNotEmpty()) addAll(d.briefApps)
    }
    spans.forEach { s ->
        s.lines.forEach { addAll(it.apps) }
        if (s.brief.isNotEmpty()) addAll(s.briefApps)
    }
    addAll(raw)
}

/**
 * The explanatory line at the top of the daily digest card on a day's page; null when none is needed (there are sentences). What the page says
 * must be true: the lines saying "it will be digested automatically" or "tap the row below" are used only when it can really be done now
 * ([canMake]: master switch and "daily notification digest" both on, model confirmed).
 */
internal fun dayNote(digest: DayDigest?, removed: Boolean, isToday: Boolean, canMake: Boolean): Int? = when {
    digest != null -> if (digest.lines.isEmpty()) R.string.proactive_learn_day_nothing else null
    // Deleted ones: they will not be made automatically again. Today's can be brought back by tapping the row below when it can be done
    removed -> if (isToday && canMake) R.string.proactive_learn_span_removed else R.string.proactive_know_removed
    !canMake -> R.string.proactive_learn_empty
    isToday -> R.string.proactive_learn_day_none
    else -> R.string.proactive_know_day_pending
}

/** The explanatory line at the top of that card on a week's (month's) page; [canMake] means "this is the current week (month) and it can be done now". */
internal fun spanNote(digest: SpanDigest?, removed: Boolean, kind: SpanKind, canMake: Boolean): Int? = when {
    digest != null -> if (digest.lines.isEmpty()) R.string.proactive_learn_span_nothing else null
    removed -> if (canMake) R.string.proactive_learn_span_removed else R.string.proactive_know_removed
    !canMake -> R.string.proactive_learn_empty
    kind == SpanKind.Week -> R.string.proactive_learn_week_none
    else -> R.string.proactive_learn_month_none
}

/**
 * Why the manual "digest up to now" cannot be done right now (the line to show); null when it can. Budget exhausted — computed from the ledger
 * ([budgetOut]), or rejected when requesting and the executor recorded that nothing more is sent today ([LearnStatus.BudgetUsedUp]); the
 * "App access" settings cannot be read and everything is suspended. In those cases tapping the button would send no request, so the page must say
 * so instead of doing nothing.
 */
internal fun makeBlocked(budgetOut: Boolean, status: LearnStatus): Int? = when {
    status == LearnStatus.Suspended -> R.string.proactive_status_access
    budgetOut || status == LearnStatus.BudgetUsedUp -> R.string.proactive_learn_state_budget
    else -> null
}
