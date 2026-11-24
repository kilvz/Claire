package com.wangzan101.mika.chat.ui


import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.Project
import com.wangzan101.mika.i18n.trPlural

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.wangzan101.mika.chat.ConversationHit
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.settings.Prerequisites
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.TemporalAdjusters
import com.wangzan101.mika.i18n.uiLocale

/** A conversation in the drawer. An [id] of null means the current conversation, which is not saved into the conversation list yet. */
data class DrawerConversation(
    val id: String?,
    val title: String,
    val subtitle: String,
    val running: Boolean,
    val current: Boolean,
    val enabled: Boolean,
    /** Time of the last change (shown to the right of the title); empty when not saved into the conversation list yet. */
    val time: String = "",
    /** Pinned: a small pin before the title, and the long-press menu offers "Unpin". */
    val pinned: Boolean = false,
    /** Conversation started against a page in the browser: that site (host name), with a small "web page" tag after the title; null otherwise. */
    val site: String? = null,
    /** Branched from another conversation: a small branch icon before the title. */
    val branch: Boolean = false,
    /** Archived: a small box icon before the title, and the long-press menu offers "Unarchive". */
    val archived: Boolean = false,
    /** Already saved into the conversation list (has a conversation file); false for a draft that has never been spoken to, which cannot be archived. */
    val saved: Boolean = true,
    /** Archive time; the archive view groups by it and computes "archived N days ago". */
    val archivedAt: Long? = null,
    /** The search hit matched the body text: jump to this message after opening the conversation; null when only the title matched. */
    val target: ChatTarget? = null,
    /** Id of the owning project group; null when not assigned to a project. */
    val projectId: String? = null,
    /** Name of the owning project group (used to label the owner directly in the archived, search and running lists). */
    val projectName: String? = null,
)

/** Result of one batch archive: how many were archived and how many were skipped because they are running a task or are the current conversation. */
data class ArchiveBatch(val archived: Int, val skipped: Int)

/** The item just archived: offers "Undo" at the bottom for a few seconds. */
data class ArchiveUndo(val id: String, val title: String)

/** Time periods in the archive view: archived this week, earlier this month, earlier. */
enum class ArchivePeriod { ThisWeek, ThisMonth, Earlier }

/** A group in the archive view. [rows] keeps the incoming order (newest archive time first). */
data class ArchiveGroup(val period: ArchivePeriod, val rows: List<DrawerConversation>)

/** A project group in the drawer along with its member conversations. */
data class DrawerProjectGroup(val project: Project, val rows: List<DrawerConversation>)

/** Conversation sections in the drawer: running, project groups, pinned, recent. */
data class DrawerSections(
    val running: List<DrawerConversation>,
    val recent: List<DrawerConversation>,
    val pinned: List<DrawerConversation> = emptyList(),
    val projects: List<DrawerProjectGroup> = emptyList(),
)

/** Conversations selectable in multi-select: in the normal drawer the ones listed right now; in the archive view the archived ones. Rows without an id cannot be selected. */
fun drawerSelectableIds(
    sections: DrawerSections,
    archived: List<DrawerConversation>,
    archivedView: Boolean,
): Set<String> =
    if (archivedView) archived.mapNotNull { it.id }.toSet()
    else (sections.running + sections.pinned + sections.recent + sections.projects.flatMap { it.rows }).mapNotNull { it.id }.toSet()

/** Search candidates: deleted ones are not searched at all; then filter by archive state, where "All" also searches archived ones (their result rows carry the archived marker). */
internal fun drawerSearchCandidates(
    all: List<ConversationSummary>,
    deleted: Set<String>,
    filter: SearchArchiveFilter,
): List<ConversationSummary> =
    all.asSequence()
        .filter { it.id !in deleted }
        .filter {
            when (filter) {
                SearchArchiveFilter.All -> true
                SearchArchiveFilter.Active -> it.archivedAt == null
                SearchArchiveFilter.Archived -> it.archivedAt != null
            }
        }
        .map { it.copy(title = ChatCopy.plainTitle(it.title)) }
        .toList()

/** Raw hits returned by the search; converted into drawer rows using the current pinned, running and time state before display. */
internal data class SearchOutcome(
    val query: String,
    val hits: List<ConversationHit>,
    val searching: Boolean,
    val truncated: Boolean = false,
)

/** How the current conversation looks in the drawer: whether a task is running, the title, and the subtitle (what it is doing). */
data class CurrentConversation(val id: String?, val running: Boolean, val title: String, val subtitle: String)

/**
 * The drawer's three sections: "Running" holds the current conversation (when it has a task) plus other conversations
 * with tasks running; "Pinned" holds the remaining pinned conversations, most recently pinned first; "Recent" holds the
 * rest, most recently modified first. Each conversation appears in only one section: a pinned conversation whose task is
 * running is listed under Running (with its pin) and returns to Pinned when it finishes. Archived conversations stay out
 * of Pinned and Recent but are still listed under Running while their task runs; the one currently being viewed is kept
 * even if archived, so you can tell where you are. When [canSwitch] is false (a task is running and parallelism is off),
 * only the current conversation is tappable.
 */
fun drawerSections(
    all: List<ConversationSummary>,
    current: CurrentConversation,
    others: List<String>,
    otherTitle: (String) -> String?,
    canSwitch: Boolean,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    projects: List<Project> = emptyList(),
): DrawerSections {
    val byId = all.associateBy { it.id }
    val projectNames = projects.associate { it.id to it.name }
    fun timeOf(id: String?) = byId[id]?.takeIf { it.updatedAt != 0L }?.let { conversationTime(it.updatedAt, now, zone) }.orEmpty()
    fun pinned(id: String?) = byId[id]?.pinnedAt != null
    fun archived(id: String?) = byId[id]?.archivedAt != null
    fun saved(id: String?) = byId[id]?.updatedAt?.let { it != 0L } ?: false
    fun projectOf(id: String?) = byId[id]?.projectId?.takeIf { it in projectNames }
    val running = buildList {
        if (current.running) {
            val pid = projectOf(current.id)
            add(DrawerConversation(current.id, current.title.ifBlank { tr(R.string.chat_new) }, current.subtitle, true, true, true, time = timeOf(current.id), pinned = pinned(current.id), site = byId[current.id]?.page?.host, branch = byId[current.id]?.branchOf != null, archived = archived(current.id), saved = saved(current.id), projectId = pid, projectName = pid?.let(projectNames::get)))
        }
        others.filter { it != current.id }.distinct().forEach { id ->
            val title = (byId[id]?.title ?: otherTitle(id))?.let(ChatCopy::plainTitle)?.ifBlank { null } ?: tr(R.string.drawer_running_task)
            val pid = projectOf(id)
            add(DrawerConversation(id, title, tr(R.string.drawer_running), running = true, current = false, enabled = canSwitch, time = timeOf(id), pinned = pinned(id), site = byId[id]?.page?.host, branch = byId[id]?.branchOf != null, archived = archived(id), saved = saved(id), projectId = pid, projectName = pid?.let(projectNames::get)))
        }
    }
    val runningIds = running.mapNotNull { it.id }.toSet()
    fun entry(s: ConversationSummary): DrawerConversation =
        drawerEntry(s, current.id, canSwitch, running = s.id in runningIds, time = timeOf(s.id), projectName = s.projectId?.let(projectNames::get))
    // Archived conversations are not in the main list; the one currently being viewed is the exception, otherwise you would not know where you are after opening an archived conversation
    val rest = all.filter { it.id !in runningIds && (it.archivedAt == null || it.id == current.id) }
    val grouped = rest.filter { it.projectId != null && it.projectId in projectNames }.groupBy { it.projectId!! }
    val projectGroups = projects.map { project ->
        val rows = grouped[project.id].orEmpty()
            .sortedWith(compareByDescending<ConversationSummary> { it.pinnedAt != null }.thenByDescending { it.pinnedAt ?: 0L }.thenByDescending { it.updatedAt })
            .map(::entry)
        DrawerProjectGroup(project, rows)
    }
    val standalone = rest.filter { it.projectId == null || it.projectId !in projectNames }
    val pinnedRows = standalone.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }.map(::entry)
    val recent = standalone.asSequence().filter { it.pinnedAt == null }.take(MAX_RECENT).map(::entry).toList()
    return DrawerSections(running, recent, pinnedRows, projectGroups)
}

/** How a conversation looks in the drawer: shared by the drawer list and the search results so the rules in the two places cannot diverge. */
internal fun drawerEntry(
    s: ConversationSummary,
    currentId: String?,
    canSwitch: Boolean,
    running: Boolean,
    time: String,
    subtitle: String = "",
    target: ChatTarget? = null,
    projectName: String? = null,
): DrawerConversation {
    val isCurrent = s.id == currentId
    return DrawerConversation(
        s.id, ChatCopy.plainTitle(s.title), subtitle, running, isCurrent, canSwitch || isCurrent, time,
        pinned = s.pinnedAt != null, site = s.page?.host, branch = s.branchOf != null,
        archived = s.archivedAt != null, saved = s.updatedAt != 0L, archivedAt = s.archivedAt, target = target,
        projectId = s.projectId, projectName = projectName,
    )
}

/** Conversation time: the time of day for today, "Yesterday" for yesterday, the weekday within a week, month and day this year, and earlier ones include the year. */
fun conversationTime(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day: LocalDate = t.toLocalDate()
    val days = today.toEpochDay() - day.toEpochDay()
    return when {
        days <= 0L -> "%d:%02d".format(t.hour, t.minute)
        days == 1L -> tr(R.string.drawer_yesterday)
        days < 7L -> t.dayOfWeek.getDisplayName(DateTextStyle.SHORT, uiLocale())
        day.year == today.year -> tr(R.string.date_month_day, day.monthValue, day.month.getDisplayName(DateTextStyle.SHORT, uiLocale()), day.dayOfMonth)
        else -> tr(R.string.date_year_month_day, day.year, day.monthValue, day.month.getDisplayName(DateTextStyle.SHORT, uiLocale()), day.dayOfMonth)
    }
}

/** Which period an archive time falls into: this week (starting Monday), earlier this month, or earlier. */
fun archivePeriod(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): ArchivePeriod {
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return when {
        !day.isBefore(weekStart) -> ArchivePeriod.ThisWeek
        day.year == today.year && day.month == today.month -> ArchivePeriod.ThisMonth
        else -> ArchivePeriod.Earlier
    }
}

/** The archive view grouped by period; anything without a recorded archive time falls under "Earlier", so nothing is lost. Empty groups do not appear. */
fun archiveGroups(
    rows: List<DrawerConversation>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ArchiveGroup> =
    ArchivePeriod.values().mapNotNull { period ->
        val group = rows.filter {
            (it.archivedAt?.let { at -> archivePeriod(at, now, zone) } ?: ArchivePeriod.Earlier) == period
        }
        if (group.isEmpty()) null else ArchiveGroup(period, group)
    }

/** Group heading: This week / This month / Earlier. */
fun archivePeriodLabel(period: ArchivePeriod): String = tr(
    when (period) {
        ArchivePeriod.ThisWeek -> R.string.drawer_archive_this_week
        ArchivePeriod.ThisMonth -> R.string.drawer_archive_this_month
        ArchivePeriod.Earlier -> R.string.drawer_archive_earlier
    }
)

/** "Archived 3 days ago"; items archived today show "Archived today". */
fun archivedAgo(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = (today.toEpochDay() - day.toEpochDay()).coerceAtLeast(0L).toInt()
    return if (days == 0) tr(R.string.drawer_archived_today)
    else trPlural(R.plurals.drawer_archived_days_ago, days, days)
}

/** The status line under Claire's name at the top of the drawer, and whether it is fine (green dot) or needs attention (orange dot). */
fun readiness(check: Prerequisites.Check): Pair<String, Boolean> = when {
    !check.canStartTask -> tr(R.string.chat_cannot_start) to false
    !check.phoneReady -> tr(R.string.drawer_phone_not_ready) to false
    else -> tr(R.string.all_ready) to true
}

private const val MAX_RECENT = 50
