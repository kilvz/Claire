package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.DigestData
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.proactive.NoticeDay
import com.wangzan101.mika.core.proactive.LearnLevel
import com.wangzan101.mika.core.proactive.SourceTier
import com.wangzan101.mika.core.proactive.DigestLevel
import com.wangzan101.mika.core.proactive.DigestLine
import com.wangzan101.mika.core.proactive.DigestView
import com.wangzan101.mika.core.proactive.DigestViewLine
import com.wangzan101.mika.core.proactive.DigestsView
import com.wangzan101.mika.core.proactive.NoticeSpan
import com.wangzan101.mika.core.proactive.SpanKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_ALL
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_NOTE
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_DAY
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_WEEK
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_MONTH
import com.wangzan101.mika.proactive.LearnRunner.Companion.AUTO_SPANS_MAX

// Learning: read, write, delete and trigger for the day/week/month digests
/**
 * The stored digests for the task to read ([NoticeSource.digests]): the digests of each day (each week, each month), newest first; each
 * digest only yields sentences whose backing App tiers all qualify, and a digest that can yield no sentence at all is not listed.
 * Conditions are the same as [notices]; null when the stored copy cannot be read. Call on the serial coroutine.
 */
internal fun LearnRunner.digests(which: DigestLevel, level: LearnLevel, tier: (String) -> SourceTier): DigestsView? {
    if (!on() || suspended() || !digest() || level.intoTasks.isEmpty()) return null
    val ok: (String) -> Boolean = { eligible(it) && tier(it) in level.intoTasks }
    val pool = freshDigest() ?: return null
    var withheld = 0
    fun view(start: Long, end: Long, madeAtMs: Long, partial: Boolean, lines: List<DigestLine>, leftOut: Int): DigestView? {
        val (given, rest) = lines.filter { it.apps.isNotEmpty() }.partition { it.apps.all(ok) }
        withheld += rest.size
        return if (given.isEmpty()) null else DigestView(start, end, madeAtMs, partial, given.map { DigestViewLine(it.text, it.apps) }, leftOut)
    }
    val out = when (which) {
        DigestLevel.Day -> pool.days().mapNotNull { view(it.day, it.day, it.madeAtMs, it.partial, it.lines, it.leftOut) }
        DigestLevel.Week -> pool.spans(SpanKind.Week).mapNotNull { view(it.start, it.start + 6, it.madeAtMs, it.partial, it.lines, it.leftOut) }
        DigestLevel.Month -> pool.spans(SpanKind.Month).mapNotNull {
            val cover = DigestPool.monthCover(it.start)
            view(cover.first, cover.last, it.madeAtMs, it.partial, it.lines, it.leftOut)
        }
    }
    return DigestsView(out, withheld)
}

/**
 * Before a digest is put to use (given to a task, choosing which one to make): drop the ones past their retention first, and save if
 * anything was dropped. Expiry does not rely on a timer (nothing runs while the screen is off); whoever reads cleans up first — otherwise,
 * with the "What Claire Knows" page never opened, expired digests would still be carried into tasks.
 */
internal fun LearnRunner.freshDigest(): DigestPool? = loadedDigest()?.also { if (it.expire(dayOf(clock()))) saveDigest(it) }

/** Read once if it is not loaded yet; null when it cannot be read (keystore temporarily unavailable, IO) and retried next time — never overwrite the file with an empty one. */
internal fun LearnRunner.loadedDigest(): DigestPool? {
    digestPool?.let { return it }
    return try {
        DigestPool(digestStore.load()).also {
            digestPool = it
            notesFlow.value = it.all()
            daysFlow.value = it.days()
            weeksFlow.value = it.spans(SpanKind.Week)
            monthsFlow.value = it.spans(SpanKind.Month)
            removedFlow.value = removedOf(it)
            removedDaysFlow.value = it.removedDays()
            // Pending deletes that were recorded but not yet persisted (unreadable at the time, or deleted without being saved): redo them now. Clear them from the record only once saved (see saveDigest)
            val pending = purges.digestPurges
            if (pending.isNotEmpty()) {
                applyDigestPurges(it, pending)
                digestPurgesApplied = digestPurgesApplied + pending
                saveDigest(it)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn digest unavailable: ${e.javaClass.simpleName}")
        null
    }
}

/** Returns true when saved. */
internal fun LearnRunner.saveDigest(pool: DigestPool): Boolean {
    notesFlow.value = pool.all()
    daysFlow.value = pool.days()
    weeksFlow.value = pool.spans(SpanKind.Week)
    monthsFlow.value = pool.spans(SpanKind.Month)
    removedFlow.value = removedOf(pool)
    removedDaysFlow.value = pool.removedDays()
    return try {
        digestStore.save(pool.data())
        digestUnsaved = false
        // The deletes that were performed are now saved: stop recording them, so they are not redone later and do not wipe out what has been added since
        if (digestPurgesApplied.isNotEmpty()) {
            purges.digestPurges = purges.digestPurges - digestPurgesApplied
            digestPurgesApplied = emptySet()
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn digest save failed: ${e.javaClass.simpleName}")
        digestUnsaved = true
        false
    }
}

/** Roll the in-memory digest back to the state it had at [data] (the page follows). Recorded deletes are not affected: they are already applied inside [data] and stay recorded until saved. */
internal fun LearnRunner.restoreDigest(data: DigestData) {
    val pool = DigestPool(data)
    digestPool = pool
    notesFlow.value = pool.all()
    daysFlow.value = pool.days()
    weeksFlow.value = pool.spans(SpanKind.Week)
    monthsFlow.value = pool.spans(SpanKind.Month)
    removedFlow.value = removedOf(pool)
    removedDaysFlow.value = pool.removedDays()
}

internal fun LearnRunner.removedOf(pool: DigestPool): Set<Pair<SpanKind, Long>> =
    SpanKind.entries.flatMapTo(HashSet()) { kind -> pool.removedSpans(kind).map { kind to it } }

/** The user deletes one sentence. */
internal fun LearnRunner.deleteNote(id: String) = digestRemove(setOf(MARK_NOTE + id))

/** Delete the whole thing: both in memory and on disk (also delete it when it cannot be read). */
internal fun LearnRunner.dropDigest() {
    if (notesApp != null || dayInFlight != null || spanInFlight != null) stopRequest()
    digestPool = null
    digestUnsaved = false
    todayWanted = false
    weekWanted = false
    monthWanted = false
    dayTries.clear()
    partialTries.clear()
    spanTries.clear()
    if (notesFlow.value.isNotEmpty()) notesFlow.value = emptyList()
    if (daysFlow.value.isNotEmpty()) daysFlow.value = emptyList()
    if (weeksFlow.value.isNotEmpty()) weeksFlow.value = emptyList()
    if (monthsFlow.value.isNotEmpty()) monthsFlow.value = emptyList()
    if (removedFlow.value.isNotEmpty()) removedFlow.value = emptySet()
    if (removedDaysFlow.value.isNotEmpty()) removedDaysFlow.value = emptySet()
    // Same as dropJournal: once it is deleted there is nothing left to redo; if it cannot be deleted, record a "wipe all" and apply it once it is readable
    digestPurgesApplied = emptySet()
    purges.digestPurges = if (wipe("digest") { digestStore.wipe() }) emptySet() else setOf(MARK_ALL)
}

/** The topic is gone: nothing on events or contacts points to it anymore. */
internal fun LearnRunner.topicsGone(ids: Set<String>) {
    if (ids.isEmpty()) return
    buffer.forgetTopics(ids)
    val c = contactPool ?: loadedContacts() ?: return
    if (c.forgetTopics(ids)) saveContacts(c)
}

/**
 * Which day's daily digest to make now (level 2): the user just asked for "today so far" (only when today has sentences); otherwise the
 * earliest day before today that has sentences, has no complete digest yet, and whose notifications are all already segmented; if neither,
 * check whether today's "so far" one should be made automatically ([DigestPool.todayAutoDue]).
 * Ones that already failed [LearnTuning.DAY_TRIES_MAX] times in this process are not retried. Null when there is none.
 */
internal fun LearnRunner.dayToDigest(now: Long): Pair<Long, Boolean>? {
    if (!digest()) {
        // "Daily notification digest" is off: the data is kept and retention still applies. Whatever is already loaded still gets expired; the file is not read just for that
        // (nobody can get at these digests right now — tasks are not given them, and the page wipes them as soon as it opens)
        digestPool?.let { if (it.expire(dayOf(now))) saveDigest(it) }
        return null
    }
    // Every time we check whether a digest is due, expire first: stored digests are deleted on schedule, without waiting for someone to open the page or for a task to read them
    val pool = freshDigest() ?: return null
    val today = dayOf(now)
    if (todayWanted) {
        if (pool.dayInput(today).isNotEmpty()) return today to true
        todayWanted = false
    }
    val j = journal
    pool.dueDay(today) { d ->
        (dayTries[d] ?: 0) < LearnTuning.DAY_TRIES_MAX && (j == null || !j.hasPending(startOf(d), startOf(d + 1), eligible))
    }?.let { return it to false }
    // Today's one does not wait for the user to tap: once enough sentences have piled up and enough time has passed since the last one, a "so far" digest is made automatically
    if ((partialTries[today] ?: 0) < LearnTuning.DAY_TRIES_MAX && pool.todayAutoDue(today, now)) return today to true
    return null
}

internal fun LearnRunner.startOf(day: Long): Long = java.time.LocalDate.ofEpochDay(day).atStartOfDay(zone()).toInstant().toEpochMilli()

/** The user tapped "digest today so far": if today has segmented digests, make one (marked as up to now); once the day is over it is redone as a complete one. */
internal fun LearnRunner.digestToday() {
    if (!on() || suspended() || !digest()) return
    todayWanted = true
    // The user is on this page: the pause after a failure no longer applies
    holdUntilMs = 0L
    if (host != null) evaluate()
}

/** The user deletes one day's daily digest. */
internal fun LearnRunner.deleteDay(day: Long) {
    loadedDigest() ?: return
    if (dayInFlight == day) stopRequest()
    if (day == dayOf(clock())) todayWanted = false
    digestRemove(setOf(MARK_DAY + day))
}

/**
 * Level 2 of the daily notification digest: merge the segmented digests of every App for [day] into one. Only level 1 sentences go into the
 * request, no raw notification text. Consent, model creation and the pause after a failure work the same as for the other batches.
 */
internal fun LearnRunner.sendDay(scope: CoroutineScope, day: Long, partial: Boolean) {
    val pool = loadedDigest() ?: return publish(LearnStatus.StoreUnavailable)
    if (consent() == null) return publish(LearnStatus.NeedsConsent)
    val input = pool.dayInput(day)
    if (input.isEmpty()) return publish(LearnStatus.Idle)
    sendRollup(
        scope, "day digest", ProactiveCall.Day, pool,
        begin = {
            dayInFlight = day
            if (partial) todayWanted = false
        },
        request = { model, lang, z -> NoticeDay(model, lang).digest(input, z, appLabel) },
        apply = {
            // Sentences of Apps that were cleared mid-flight: setDay drops every sentence that was based on them
            pool.setDay(day, input, it.lines, clock(), partial, it.brief)
            "${input.size} notes, ${pool.day(day)?.lines?.size ?: 0} lines, partial=$partial, brief=${pool.day(day)?.brief?.isNotEmpty() == true}"
        },
        tried = { (if (partial) partialTries else dayTries).let { it[day] = (it[day] ?: 0) + 1 } },
    )
}

/**
 * Which week's or month's digest to make now (levels 3 and 4): the user just asked for "this week (this month) so far" (only when there is
 * something to merge); otherwise the earliest week that is already over, whose lower level is fully done and that still has no complete
 * digest; if no week is due, look at months. When the lower level has no sentence at all, do not ask the model — just record it as done.
 * Ones that already failed [LearnTuning.SPAN_TRIES_MAX] times in this process are not retried. Null when there is none.
 */
internal fun LearnRunner.spanToDigest(now: Long): Triple<SpanKind, Long, Boolean>? {
    if (!digest()) return null
    val pool = loadedDigest() ?: return null
    val today = dayOf(now)
    val j = journal
    fun given(kind: SpanKind, start: Long) = (spanTries[kind to start] ?: 0) >= LearnTuning.SPAN_TRIES_MAX
    // Automatic ones: only complete lower-level digests are taken as input; a period with no sentence at all is simply recorded (the level above does not have to wait for it)
    fun auto(kind: SpanKind, due: () -> Long?): Long? {
        repeat(AUTO_SPANS_MAX) {
            val start = due() ?: return null
            if (pool.spanInput(kind, start, withPartial = false).isNotEmpty()) return start
            pool.setSpan(kind, start, emptyList(), emptyList(), now, partial = false)
            saveDigest(pool)
            log("${kind.name.lowercase()} digest: nothing to merge, recorded empty")
        }
        return null
    }
    if (weekWanted) {
        val week = DigestPool.weekOf(today)
        if (pool.spanInput(SpanKind.Week, week, withPartial = true).isNotEmpty()) return Triple(SpanKind.Week, week, true)
        weekWanted = false
    }
    auto(SpanKind.Week) {
        pool.dueWeek(
            today,
            // Days that still owe a daily digest come first (those that failed a few times do not hold it up); days with notifications not yet segmented wait as well
            waitForDay = { d, owed ->
                (owed && (dayTries[d] ?: 0) < LearnTuning.DAY_TRIES_MAX) || (j != null && j.hasPending(startOf(d), startOf(d + 1), eligible))
            },
            skip = { given(SpanKind.Week, it) },
        )
    }?.let { return Triple(SpanKind.Week, it, false) }
    if (monthWanted) {
        val month = DigestPool.monthOf(today)
        if (pool.spanInput(SpanKind.Month, month, withPartial = true).isNotEmpty()) return Triple(SpanKind.Month, month, true)
        monthWanted = false
    }
    auto(SpanKind.Month) {
        pool.dueMonth(today, waitForWeek = { w, owed -> owed && !given(SpanKind.Week, w) }, skip = { given(SpanKind.Month, it) })
    }?.let { return Triple(SpanKind.Month, it, false) }
    return null
}

/**
 * The user tapped "digest this week so far": if this week has daily digests, make one (marked as up to now); once the week is over it is
 * redone as a complete one. If new sentences arrived today, first redo today's digest up to now, then this week's.
 */
internal fun LearnRunner.digestWeek() {
    if (!on() || suspended() || !digest()) return
    if (loadedDigest()?.todayStale(dayOf(clock())) == true) todayWanted = true
    weekWanted = true
    holdUntilMs = 0L
    if (host != null) evaluate()
}

/**
 * The user tapped "digest this month so far": first do today's and this week's up to now (if the week falls within this month), then this
 * month's. If the week's digest was deleted by the user, it is not brought back along the way (the month is what was asked for, not it);
 * the same applies to today's (see [DigestPool.todayStale]).
 */
internal fun LearnRunner.digestMonth() {
    if (!on() || suspended() || !digest()) return
    val today = dayOf(clock())
    val pool = loadedDigest()
    if (pool?.todayStale(today) == true) todayWanted = true
    val week = DigestPool.weekOf(today)
    if (DigestPool.monthOfWeek(week) == DigestPool.monthOf(today) && pool?.removedSpans(SpanKind.Week)?.contains(week) != true) weekWanted = true
    monthWanted = true
    holdUntilMs = 0L
    if (host != null) evaluate()
}

/** The user deletes one week's (one month's) digest. */
internal fun LearnRunner.deleteSpan(kind: SpanKind, start: Long) {
    loadedDigest() ?: return
    if (spanInFlight == kind to start) stopRequest()
    // "Digest up to now" was just requested and has not been done yet: once deleted, do not do it
    val today = dayOf(clock())
    if (kind == SpanKind.Week && start == DigestPool.weekOf(today)) weekWanted = false
    if (kind == SpanKind.Month && start == DigestPool.monthOf(today)) monthWanted = false
    digestRemove(setOf((if (kind == SpanKind.Week) MARK_WEEK else MARK_MONTH) + start))
}

/**
 * Levels 3 and 4 of the daily notification digest: merge this week's daily digests (this month's weekly digests) into one. Only the lower
 * level's sentences go into the request. [partial] true is the user's "up to now": the unfinished part of the lower level is included too.
 */
internal fun LearnRunner.sendSpan(scope: CoroutineScope, kind: SpanKind, start: Long, partial: Boolean) {
    val pool = loadedDigest() ?: return publish(LearnStatus.StoreUnavailable)
    if (consent() == null) return publish(LearnStatus.NeedsConsent)
    val input = pool.spanInput(kind, start, withPartial = partial)
    if (input.isEmpty()) return publish(LearnStatus.Idle)
    val name = "${kind.name.lowercase()} digest"
    sendRollup(
        scope, name, ProactiveCall.Span, pool,
        begin = {
            spanInFlight = kind to start
            if (partial) { if (kind == SpanKind.Week) weekWanted = false else monthWanted = false }
        },
        request = { model, lang, _ -> NoticeSpan(model, lang).digest(kind, start, input, appLabel) },
        apply = {
            // Sentences deleted mid-flight or whose App was cleared: setSpan drops every sentence that was based on them
            pool.setSpan(kind, start, input, it.lines, clock(), partial, it.brief)
            "${input.size} lines in, ${pool.span(kind, start)?.lines?.size ?: 0} lines, partial=$partial, brief=${pool.span(kind, start)?.brief?.isNotEmpty() == true}"
        },
        tried = { spanTries[kind to start] = (spanTries[kind to start] ?: 0) + 1 },
    )
}
