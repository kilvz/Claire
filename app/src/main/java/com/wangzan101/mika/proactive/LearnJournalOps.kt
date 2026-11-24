package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ContactPool
import com.wangzan101.mika.core.proactive.ContactType
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.core.proactive.LearnLevel
import com.wangzan101.mika.core.proactive.ObservedContext
import com.wangzan101.mika.core.proactive.SourceTier
import com.wangzan101.mika.core.proactive.NoticeView
import com.wangzan101.mika.core.proactive.SpanKind
import java.time.Instant
import kotlinx.coroutines.CancellationException
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_ALL
import com.wangzan101.mika.proactive.LearnRunner.Companion.LIVE_WINDOW_MS
import com.wangzan101.mika.proactive.LearnRunner.Companion.OBSERVED_TOPICS
import com.wangzan101.mika.proactive.LearnRunner.Companion.OBSERVED_PEOPLE

// Learning: reading/writing the notification journal and the raw observed data
/** Read once if it is not loaded yet (with no file, start recording from now on); null when it cannot be read (keystore temporarily unavailable, IO), retried next time, and nothing is recorded or saved meanwhile. */
internal fun LearnRunner.loadedJournal(): NoticeJournal? {
    journal?.let { return it }
    return try {
        NoticeJournal(journalStore.load(), clock = clock).also {
            journal = it
            // Pending per-App deletes that were recorded but not yet persisted (unreadable at the time, or deleted without being saved): redo them now, and clear them from the record only once saved (see saveJournal)
            val pending = purges.journalPurges
            if (pending.isNotEmpty()) {
                it.removePackages { p -> MARK_ALL !in pending && p !in pending }
                // Recorded status display (see dropLive): only remove that stretch — normal notifications under the same key afterwards are left alone
                for (w in pending) keyIn(w)?.let { (keyId, since) -> it.removeKey(keyId, since, since + LIVE_WINDOW_MS) }
                journalPurgesApplied = journalPurgesApplied + pending
            }
            // Some were already expired when loaded: gone from memory but still on disk — save right away (saving also refreshes the numbers on the page)
            if (it.expiredOnLoad || pending.isNotEmpty()) saveJournal() else publish(status)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn journal unavailable: ${e.javaClass.simpleName}")
        null
    }
}

/** Delete expired entries, and save if anything changed or is unsaved. Does nothing while "daily notification digest" is off or the journal has never been used. */
internal fun LearnRunner.tendJournal() {
    if (!on() || !digest()) return
    val j = loadedJournal() ?: return
    if (j.expire() || journalUnsaved > 0) saveJournal()
}

internal fun LearnRunner.saveJournal() {
    val j = journal ?: return
    j.expire()
    try {
        journalStore.save(j.data())
        journalUnsaved = 0
        // The applied per-App deletes are saved: stop recording them, so that if this App is allowed again later, what is recorded after that is not deleted
        if (journalPurgesApplied.isNotEmpty()) {
            purges.journalPurges = purges.journalPurges - journalPurgesApplied
            journalPurgesApplied = emptySet()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn journal save failed: ${e.javaClass.simpleName}")
        // Remember that something is unsaved: save again on the next tidy-up (otherwise the entries deleted on expiry would never reach disk without another change)
        if (journalUnsaved == 0) journalUnsaved = 1
    }
    publish(status)
}

/** Delete the whole thing: both in memory and on disk (also delete it when it cannot be read). The segmented digest batch in flight is cancelled. */
internal fun LearnRunner.dropJournal() {
    if (notesApp != null) stopRequest()
    val had = journal?.size ?: 0
    journal = null
    journalUnsaved = 0
    // Deleted as a whole, so nothing is left to redo. If it cannot be deleted (the file still holds the old data, the in-memory copy is already gone and would come back on the next read):
    // record a "wipe all" and apply it once it becomes readable
    journalPurgesApplied = emptySet()
    if (wipe("journal") { journalStore.wipe() }) {
        if (purges.journalPurges.isNotEmpty()) purges.journalPurges = emptySet()
    } else if (purges.journalPurges != setOf(MARK_ALL)) {
        purges.journalPurges = setOf(MARK_ALL)
    }
    if (had > 0) publish(status)
}

/**
 * Remove these Apps' raw notifications. Same approach as [digestRemove]: record the delete in [purges] before saving, and clear it only once
 * saved ([saveJournal]); when it cannot be read at that moment, record it and redo it once readable ([loadedJournal]). Without recording, a
 * failed read would mean no deletion at all, and if the delete was not saved and the process died, the file would still hold the old data —
 * then, once this App is allowed again, the raw notifications from the last two days could be read again and fed into digests.
 * It does not delete the whole thing just because it cannot be read: [forgetApps] brings the whole ineligible list on every roster change, so
 * a temporarily unavailable keystore would wipe out all accumulated raw notifications.
 */
internal fun LearnRunner.journalRemove(packages: Set<String>) {
    if (packages.isEmpty()) return
    val j = loadedJournal()
    if (j == null) {
        if (!purges.journalPurges.containsAll(packages)) purges.journalPurges = purges.journalPurges + packages
        return
    }
    if (j.removePackages { it !in packages }) {
        if (!purges.journalPurges.containsAll(packages)) purges.journalPurges = purges.journalPurges + packages
        journalPurgesApplied = journalPurgesApplied + packages
        saveJournal()
    } else if (packages.any { it in purges.journalPurges }) {
        saveJournal()
    }
}

/**
 * The "derived from notifications" section this task should carry (see [ObservedContext]): picks the eligible ones by aggressiveness [level]
 * and each App's source tier [tier], lays them out as one section and caps its length. Null while learning is off, at the most conservative
 * level, or when nothing qualifies. Does not call the model; call on the serial coroutine.
 *
 * Eligible: those whose source tier is in [LearnLevel.intoTasks] and whose App is still being learned —
 * - in the daily digest, today's (up to now) and yesterday's sentences: only carried if every App a sentence is based on qualifies;
 * - topics: count only the days confirmed by eligible Apps, at least [LearnLevel.topicDays] days;
 * - contacts: established ones.
 * Sensitive topics and contacts are only carried when "sensitive-category inference" is on.
 */
internal fun LearnRunner.observed(level: LearnLevel, tier: (String) -> SourceTier): String? {
    if (!on() || suspended() || level.intoTasks.isEmpty()) return null
    val ok: (String) -> Boolean = { eligible(it) && tier(it) in level.intoTasks }
    val now = clock()
    val today = dayOf(now)
    val digests = if (digest()) freshDigest() else null
    fun linesOf(day: Long): List<String> =
        digests?.day(day)?.lines.orEmpty().filter { it.apps.isNotEmpty() && it.apps.all(ok) }.map { it.text }
    val allowSensitive = sensitive()
    // Things that should fade (retention below FORGET_BELOW) are not carried: they are only really removed from the pool on the next
    // tidy-up, which happens only when new digests are accepted or the content page is opened — with no new digests for a long time and
    // the page never opened, topics and contacts long due to be forgotten would still sit in the pool. This only skips them; it does not modify the pool or save on the task path. Contacts whose relation the user set themselves do not fade (same as ContactPool.maintain)
    val topics = loadedPool()?.all().orEmpty()
        .filter { (allowSensitive || !it.sensitive) && it.retention(now) >= LearnTuning.FORGET_BELOW && ObservedContext.qualifiedTopic(it, level, ok) }
        .sortedByDescending { it.score(now) }.take(OBSERVED_TOPICS)
    val people = loadedContacts()?.all().orEmpty()
        .filter { ok(it.packageName) && it.established && (allowSensitive || !it.sensitive) }
        .filter { it.userSet || it.retention(now) >= LearnTuning.FORGET_BELOW }
        .sortedByDescending { it.score(now) }.take(OBSERVED_PEOPLE)
        .map { ObservedContext.Person(it.name, it.type == ContactType.Group, it.relation, it.userSet) }
    val asOf = digests?.day(today)?.let { java.time.Instant.ofEpochMilli(it.madeAtMs).atZone(zone()).toLocalTime() }
        ?.let { "%02d:%02d".format(java.util.Locale.ENGLISH, it.hour, it.minute) }
    // The week and month that just ended: only carry the complete one (an "up to now" one is not treated as last week or last month)
    fun earlier(kind: SpanKind, key: Long, days: LongRange): ObservedContext.Earlier? =
        digests?.span(kind, key)?.takeIf { !it.partial }?.lines.orEmpty()
            .filter { it.apps.isNotEmpty() && it.apps.all(ok) }.map { it.text }
            .takeIf { it.isNotEmpty() }?.let { ObservedContext.Earlier(days.first, days.last, it, digests?.span(kind, key)?.leftOut ?: 0) }
    val lastWeek = DigestPool.weekOf(today) - 7
    val lastMonth = DigestPool.monthOf(DigestPool.monthOf(today) - 1)
    return ObservedContext.render(
        linesOf(today), linesOf(today - 1), topics, people, asOf,
        lastWeek = earlier(SpanKind.Week, lastWeek, lastWeek..lastWeek + 6),
        // A month digest covers the weeks that end in that month: the heading states the days it actually covers
        lastMonth = earlier(SpanKind.Month, lastMonth, DigestPool.monthCover(lastMonth)),
        // Whether those two days' daily digests had sentences that did not fit: if so, say it in the heading
        todayLeftOut = digests?.day(today)?.leftOut ?: 0,
        yesterdayLeftOut = digests?.day(today - 1)?.leftOut ?: 0,
    )
}

/**
 * For the task to read notifications ([NoticeSource]): raw notifications and segmented digests of Apps whose source tier qualifies, between
 * [fromMs] and [toMs]. Null while learning or "daily notification digest" is off, at the most conservative level, or when the journal cannot
 * be read. Call on the serial coroutine.
 */
internal fun LearnRunner.notices(fromMs: Long, toMs: Long, level: LearnLevel, tier: (String) -> SourceTier): NoticeView? {
    if (!on() || suspended() || !digest() || level.intoTasks.isEmpty()) return null
    val ok: (String) -> Boolean = { eligible(it) && tier(it) in level.intoTasks }
    val j = loadedJournal() ?: return null
    // Expire first: the reader should not see anything past its retention
    if (j.expire()) saveJournal()
    // When the digest cannot be read it must not be presented as "no sentences": give the raw notifications as usual and say the sentences are unavailable right now
    val pool = freshDigest()
    val notes = pool?.all().orEmpty()
        .filter { ok(it.packageName) && it.toMs in fromMs until toMs }.sortedBy { it.fromMs }
    val (given, rest) = j.between(fromMs, toMs).partition { ok(it.packageName) }
    // How many Apps were withheld because their tier does not qualify (count only): the reader can then say "there are still several Apps' worth not looked at" instead of "there is nothing else today"
    val withheld = rest.filter { eligible(it.packageName) }.mapTo(HashSet()) { it.packageName }.size
    return NoticeView(j.completeSinceMs, given, notes, withheld, notesUnavailable = pool == null)
}

/**
 * For the page: which notifications the segmented digest sentence [note] was written from (see [NoticeJournal.around]; for the user to see,
 * source tiers are not filtered). Expire first, then return. Call on the serial coroutine.
 */
internal fun LearnRunner.rawFor(note: DigestNote): RawNotices {
    if (!on() || !digest()) return RawNotices.Off
    if (suspended()) return RawNotices.Unavailable
    val j = loadedJournal() ?: return RawNotices.Unavailable
    if (j.expire()) saveJournal()
    return RawNotices.Found(j.around(note.packageName, note.title, note.fromMs, note.toMs))
}
