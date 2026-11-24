package com.wangzan101.mika.proactive

import android.service.notification.NotificationListenerService
import com.wangzan101.mika.core.proactive.ContactPool
import com.wangzan101.mika.core.proactive.ContactPoolData
import com.wangzan101.mika.core.proactive.DayDigest
import com.wangzan101.mika.core.proactive.DigestData
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.LearnBuffer
import com.wangzan101.mika.core.proactive.LearnOutcome
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.core.proactive.NoticeJournalData
import com.wangzan101.mika.core.proactive.SpanDigest
import com.wangzan101.mika.core.proactive.SpanKind
import com.wangzan101.mika.core.proactive.NoticeMessage
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.ObservedTopic
import com.wangzan101.mika.core.proactive.TopicPool
import com.wangzan101.mika.core.proactive.TriageConsent
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The scheduler for "learn from notifications": like [TriageRunner] it runs on the serial coroutine of [ProactiveRuntime] and lives only
 * while the watchman runs ([watch]).
 *
 * Power saving: no timers, no polling. It looks only when something happens ([evaluate]): the buffer changed (a new notification came in),
 * the lock state changed, [TriageHooks.busy] changed, the switches/consent/budget/excluded Apps changed ([poke]). "The oldest entry is over
 * 30 minutes old" is only noticed at those moments too. While locked, the request in flight is cancelled and that batch stays pending as before.
 * One batch at a time, and a batch is a single App with at most 12 entries. After a failed digest (one failure recorded) it pauses before the
 * next batch, and the more consecutive failures the longer the pause ([LearnTuning.FAILURE_HOLD_MS]): otherwise, once the model has a problem,
 * every due App would be sent and fail batch after batch. When the pause is over it still waits for the next event before looking.
 * As for the batch itself: a model error, a timeout or an unreadable reply means it is dropped without retry (digesting is just accumulating
 * statistics); a total failure to reach the model (the model is only reachable on the home network and the person is out, etc.) keeps it
 * until the connection is back. Budget exhausted: stop quietly, keep the events, send nothing more that day (recomputed only when the budget is changed).
 * The log ([log]) records only categories and counts, never notification text or topic names.
 *
 * Contacts and relations ride on the same request (when contacts can be read, the request says they may be given), no separate request is
 * made. When applying the result, identities are computed from this batch's events (titles still present) and then topics are linked; after
 * that titles and bodies are cleared as usual.
 *
 * Switches and stored data: [on] is the master switch, [digest] is "daily notification digest". Turning either off only stops capturing and
 * sending, and deletes the raw notifications; what has already been digested (topics, contacts, segmented sentences, digests at each level)
 * is kept, to be deleted by the user or on expiry. So the clearing, deleting and expiry paths still run while a switch is off — one must not
 * assume that "off means nothing is stored".
 */
internal class LearnRunner(
    internal val hooks: TriageHooks,
    internal val on: () -> Boolean,
    internal val consent: () -> TriageConsent?,
    internal val budgetLimit: () -> Long,
    internal val language: () -> String,
    /**
     * Whether this App can be learned from right now: it is checked and not marked "do not learn from it". Used only to decide whether to
     * capture or send; not used to delete stored data (when the roster cannot be read, every App is "ineligible", and that is not the user
     * cancelling) — deletion is done by name via [forgetApps].
     */
    internal val eligible: (String) -> Boolean,
    /**
     * "App access" cannot be read: we do not know which Apps must not be touched, so everything is suspended — no capturing, no sending, no
     * carrying into tasks; nothing accumulated is deleted, and work resumes once it can be read. (Not folded into [on] and [eligible]: when
     * those are false, raw notifications get deleted and pending events cleared.)
     */
    internal val suspended: () -> Boolean = { false },
    /** The request slot shared with the decision pass (only one background request at a time); if not given, this gets its own. */
    internal val slot: RequestSlot = RequestSlot(),
    /** Whether "sensitive-category inference" is on (off by default). */
    internal val sensitive: () -> Boolean,
    internal val store: TopicPoolStore,
    internal val contactStore: ContactPoolStore = NoContacts,
    internal val purges: LearnPurges = MemoryPurges(),
    /** Whether "daily notification digest" is on (off by default): only then are raw notifications recorded in the journal and saved. */
    internal val digest: () -> Boolean = { false },
    internal val journalStore: NoticeJournalStore = NoJournal,
    internal val digestStore: DigestStore = NoDigest,
    /** The App's name (read from the system by package name): used in the daily digest input so the model can say "in WeChat". */
    internal val appLabel: (String) -> String = { it },
    internal val clock: () -> Long = System::currentTimeMillis,
    internal val zone: () -> ZoneId = ZoneId::systemDefault,
    internal val log: (String) -> Unit = {},
) {
    internal val buffer = LearnBuffer()
    internal var pool: TopicPool? = null
    internal var contactPool: ContactPool? = null
    internal val feed = HashMap<String, LearnFeed>()
    internal var lastDistilledAtMs: Long? = null
    internal var budgetOutDay: Long? = null
    /** How many consecutive batches failed to digest, and therefore until when it is paused (see [held]). */
    internal var failures = 0
    internal var holdUntilMs = 0L
    /** Pending deletes already applied in memory but not yet saved (see [purge]): cleared from the record at the moment they are saved. */
    internal var topicPurgesApplied: Set<String> = emptySet()
    /** The ids of topics present in the file (captured on load and on every save): extra ones in memory (just distilled, not yet saved) are not in it. */
    internal var savedTopics: Set<String> = emptySet()
    internal var contactPurgesApplied: Set<String> = emptySet()
    internal var digestPurgesApplied: Set<String> = emptySet()
    internal var journalPurgesApplied: Set<String> = emptySet()
    /**
     * The digest failed to save last time: memory is newer than the file. Before sending any segmented, daily, weekly or monthly digest
     * request, save it first; if it still cannot be saved, do not send — the result could not be kept, so sending would just waste a model call.
     */
    internal var digestUnsaved = false
    /** The notification journal: read on first use ([loadedJournal]); [journalUnsaved] is the number of changes not yet saved. */
    internal var journal: NoticeJournal? = null
    internal var journalUnsaved = 0
    /** Sentences accumulated by segmented digests: read on first use ([loadedDigest]). */
    internal var digestPool: DigestPool? = null
    /** Which App the segmented digest batch in flight belongs to (null when none); it shares [inFlight] with the topic-digest batch, only one at a time. */
    internal var notesApp: String? = null
    /** Which day the daily digest being made belongs to (null when none); it also occupies [inFlight]. */
    internal var dayInFlight: Long? = null
    /** The user tapped "digest today so far" and it has not been done yet. */
    internal var todayWanted = false
    /** How many times each day's daily digest has been tried in this process (so an unreadable reply is not retried forever). */
    internal val dayTries = HashMap<Long, Int>()
    /**
     * How many times the "up to now" one (automatic or user-tapped) failed in this process, counted separately: it must not go into
     * [dayTries] — otherwise, if today's automatic attempts were all unreadable, tomorrow's complete digest of today would never be tried,
     * and the following days would queue up behind it too.
     */
    internal val partialTries = HashMap<Long, Int>()
    internal val daysFlow = MutableStateFlow<List<DayDigest>>(emptyList())
    /** The daily digests, newest first. */
    val digestDays: StateFlow<List<DayDigest>> = daysFlow.asStateFlow()
    /** Which week (month) the weekly (monthly) digest being made belongs to (null when none); it also occupies [inFlight]. */
    internal var spanInFlight: Pair<SpanKind, Long>? = null
    /** The user tapped "digest this week (this month) so far" and it has not been done yet. */
    internal var weekWanted = false
    internal var monthWanted = false
    /** How many times each week's/month's digest has been tried in this process. */
    internal val spanTries = HashMap<Pair<SpanKind, Long>, Int>()
    internal val weeksFlow = MutableStateFlow<List<SpanDigest>>(emptyList())
    internal val monthsFlow = MutableStateFlow<List<SpanDigest>>(emptyList())
    /** The weekly and monthly digests, newest first. */
    val digestWeeks: StateFlow<List<SpanDigest>> = weeksFlow.asStateFlow()
    val digestMonths: StateFlow<List<SpanDigest>> = monthsFlow.asStateFlow()
    internal val removedFlow = MutableStateFlow<Set<Pair<SpanKind, Long>>>(emptySet())
    /** Weekly (monthly) digests deleted by the user that are no longer made automatically. */
    val digestRemoved: StateFlow<Set<Pair<SpanKind, Long>>> = removedFlow.asStateFlow()
    internal val removedDaysFlow = MutableStateFlow<Set<Long>>(emptySet())
    /** The days (epoch days) whose daily digest the user deleted: it will not be made automatically again, and the page should say so. */
    val digestRemovedDays: StateFlow<Set<Long>> = removedDaysFlow.asStateFlow()
    internal val topicsBlockedFlow = MutableStateFlow(0)
    /** How many topics are no longer recorded because the user deleted them. */
    val topicsBlocked: StateFlow<Int> = topicsBlockedFlow.asStateFlow()
    internal val notesFlow = MutableStateFlow<List<DigestNote>>(emptyList())
    /** The accumulated segmented digests (empty while "daily notification digest" is off or they have not been read yet). */
    val digestNotes: StateFlow<List<DigestNote>> = notesFlow.asStateFlow()
    internal var status = LearnStatus.Off

    internal val mutable = MutableStateFlow(LearnState())
    val state: StateFlow<LearnState> = mutable.asStateFlow()
    internal val topicsFlow = MutableStateFlow<List<ObservedTopic>>(emptyList())
    /** The topic pool as it is now; empty before the page is opened ([open]) or when it cannot be read. */
    val topics: StateFlow<List<ObservedTopic>> = topicsFlow.asStateFlow()
    internal val contactsFlow = MutableStateFlow(ContactsView())
    /** Contacts as they are now; empty before the page is opened or when they cannot be read. */
    val contactsView: StateFlow<ContactsView> = contactsFlow.asStateFlow()

    internal var host: CoroutineScope? = null
    internal var readLocked: () -> Boolean = { true }
    internal var inFlight: Job? = null

    internal val requestActive: Boolean get() = inFlight?.isActive == true

    suspend fun watch(locked: StateFlow<Boolean>, readLocked: () -> Boolean) {
        var mine: CoroutineScope? = null
        try {
            coroutineScope {
                mine = this
                inFlight?.cancel()
                inFlight = null
                slot.release(this@LearnRunner)
                slot.listen(this@LearnRunner) { evaluate() }
                // Also clear "whose request is in flight" left over from the previous watch: keeping it would make another request sent now be taken for it and cancelled by mistake
                notesApp = null
                dayInFlight = null
                spanInFlight = null
                buffer.abort()
                host = this
                this@LearnRunner.readLocked = readLocked
                launch { buffer.state.collect { evaluate() } }
                launch { locked.collect { evaluate() } }
                // The journal has no timer: on every lock and unlock, delete expired entries and save what is unsaved (the process is usually reaped by the system after the screen locks)
                launch { locked.collect { tendJournal() } }
                launch { hooks.busy.collect { evaluate() } }
                awaitCancellation()
            }
        } finally {
            if (host === mine) {
                host = null
                inFlight = null
                notesApp = null
                dayInFlight = null
                spanInFlight = null
                // No longer watching (full exit): save whatever is unsaved in the journal
                if (on() && digest()) saveJournal()
                buffer.abort()
                publish(if (on()) LearnStatus.Paused else LearnStatus.Off)
                slot.unlisten(this)
                slot.release(this)
                slot.wakeOthers(this)
            }
        }
    }

    /**
     * The inbox took one entry (already cleaned, filtered and deduplicated). Not captured while the master switch is off or this App is not
     * learned from; also not captured before the user has consented to sending notifications to the model — without consent nothing is
     * accumulated for learning and no raw text is persisted either (master switch on but no model configured, waiting for user confirmation).
     * If consent was given and only the provider or model changed, pending re-confirmation, capture as usual: learning resumes after confirmation.
     */
    fun capture(record: NoticeRecord, newMessages: List<NoticeMessage> = emptyList()) {
        if (!on() || suspended() || consent() == null || !eligible(record.packageName)) return
        // The journal only takes what learning takes, and "daily notification digest" must be on
        if (digest()) record(record)
        if (buffer.capture(record, newMessages)) bump(record.packageName) { it.copy(captured = it.captured + 1) }
    }

    /**
     * This notification key is really a status display reposted over and over (navigation prompts and the like, see [LiveUpdates]): the
     * entries captured under it from [sinceMs] on (this burst of reposts) that have not been digested yet are removed, and so are those in the
     * raw notification journal. Earlier ones under the same key are left alone — many Apps use a single notification key for all pushes.
     */
    fun dropLive(key: String, packageName: String, sinceMs: Long) {
        // The request in flight may contain exactly these: the segmented digest batch being written for this App, or this key's entries for
        // this burst inside the topic-digest batch. Cancel it (as when clearing an App): otherwise, when the reply comes back, the sentences
        // written from them and the topics distilled from them would still be saved. After cancelling, the topic-digest batch returns to
        // pending and is cleaned up below too (otherwise a second cancellation, from a screen lock and the like, would send them out again);
        // the rest is sent as usual
        val stopped = notesApp == packageName || buffer.sending().any { it.key == key && it.postedAtMs >= sinceMs }
        if (stopped) stopRequest()
        buffer.forgetKey(key, sinceMs)
        if (on() && digest()) {
            // The raw notification journal: record it first like a per-App delete (see [LearnPurges.journalPurges]). Ones unreadable right now,
            // or removed without being saved, are redone once readable — otherwise they would still count as undigested and be sent to the model again with the next segmented digest
            val keyId = com.wangzan101.mika.core.proactive.NoticeDedup.idOf(key)
            val mark = MARK_KEY + keyId + KEY_SINCE + sinceMs
            val j = loadedJournal()
            if (j == null) {
                purges.journalPurges = purges.journalPurges + mark
            } else if (j.removeKey(keyId, sinceMs, sinceMs + LIVE_WINDOW_MS)) {
                purges.journalPurges = purges.journalPurges + mark
                journalPurgesApplied = journalPurgesApplied + mark
                saveJournal()
            }
        }
        if (stopped && host != null) evaluate()
    }

    /** A recorded "remove this notification key for this burst": the key's hash and since when; null for other markers or malformed ones. */
    internal fun keyIn(mark: String): Pair<String, Long>? {
        if (!mark.startsWith(MARK_KEY)) return null
        val parts = mark.removePrefix(MARK_KEY).split(KEY_SINCE)
        val since = parts.getOrNull(1)?.toLongOrNull()
        return if (parts.size == 2 && parts[0].isNotEmpty() && since != null) parts[0] to since else null
    }

    /** Record into the notification journal; once [JOURNAL_FLUSH_EVERY] unsaved entries have piled up, save once (if the process dies between saves, at most that many entries are lost, or those since the last screen lock). */
    internal fun record(record: NoticeRecord) {
        val j = loadedJournal() ?: return
        val added = j.add(record)
        if (added == 0) return
        journalUnsaved += added
        if (journalUnsaved >= JOURNAL_FLUSH_EVERY) saveJournal() else publish(status)
    }

    /** The system says this notification was removed: record an open or a dismissal; "clear all" says nothing. For already-digested ones, add it to the topic it was assigned to. */
    fun removed(key: String, reason: Int) {
        val outcome = outcomeOf(reason)
        val late = buffer.outcome(key, outcome)
        if (late.isEmpty()) return
        val p = loadedPool() ?: return
        if (p.addOutcome(late, outcome, allowSensitive = sensitive())) save(p)
    }

    /**
     * These Apps are no longer learned from (the user unchecked them, set them to "low", or disabled them under "App access"): their stored
     * raw notifications and segmented digest sentences, and the sentences in digests at every level that were based on them, are removed;
     * topics and contacts are untouched (use "clear what came from it" to delete those). This also runs while the master switch is off.
     * Deletion is by name, not "delete everything currently ineligible": when the App roster cannot be read, no App is eligible, and that
     * would wipe out everything accumulated. If the digest cannot be read right now, record it and redo it once readable. Call on the serial coroutine.
     */
    fun forgetApps(packages: Set<String>) {
        if (packages.isNotEmpty()) {
            if (on() && digest()) journalRemove(packages)
            // The daily (weekly, monthly) digest being made may contain sentences from the App just removed: cancel it, then redo it from what is left
            digestRemove(packages, stopRequests = true)
        }
        poke()
    }

    /** Switches, consent, budget or excluded Apps changed: drop the events of Apps no longer captured, stop the request in flight if it should stop, and re-evaluate. Call on the serial coroutine. */
    fun poke() {
        val sending = buffer.sending().firstOrNull()?.packageName
        if (sending != null && (!on() || !eligible(sending))) stopRequest()
        notesApp?.let { if (!on() || !digest() || !eligible(it)) stopRequest() }
        if ((dayInFlight != null || spanInFlight != null) && (!on() || !digest())) stopRequest()
        buffer.purge(eligible)
        // Raw notifications: if the master switch or "daily notification digest" is off, delete the whole journal. Sentences already
        // digested and the digests at each level are not deleted along with the switches; when an App stops being learned from, forgetApps
        // removes its stored data by name — here nothing is deleted based on eligibility
        if (!on() || !digest()) dropJournal()
        if (host != null) evaluate() else publish(if (on()) LearnStatus.Paused else LearnStatus.Off)
    }

    fun budgetChanged() {
        budgetOutDay = null
        poke()
    }

    /**
     * Turn proactive capability off: the request is cancelled, and unprocessed events, raw notifications and counters are cleared. What has
     * already been digested (topics, contacts, sentences, digests at each level) stays, to be deleted by the user.
     */
    fun clear() {
        stopRequest()
        buffer.clear()
        dropJournal()
        todayWanted = false
        weekWanted = false
        monthWanted = false
        feed.clear()
        lastDistilledAtMs = null
        failures = 0
        holdUntilMs = 0L
        publish(if (on()) LearnStatus.Paused else LearnStatus.Off)
    }

    // ---- Page actions (call on the serial coroutine) ----

    /**
     * When the page opens: load the topic pool and contacts and tidy them up (drop faded ones, cut links pointing at gone topics); save if
     * anything changed. A pause after a failure no longer applies (the user is on this page, most likely having just changed a setting and
     * wanting to see the result): anything due is retried once, and a further failure keeps extending the pause as usual.
     */
    fun open() {
        holdUntilMs = 0L
        val p = loadedPool()
        if (p != null) {
            val dropped = p.maintain(clock())
            if (dropped.isNotEmpty()) {
                buffer.forgetTopics(dropped)
                save(p)
            } else {
                topicsFlow.value = p.all()
        topicsBlockedFlow.value = p.blockedCount
            }
        }
        loadedContacts()?.let { c -> if (c.maintain(clock(), p?.all()?.map { it.id }?.toSet())) saveContacts(c) }
        // Expired digests are deleted whether or not the switches are on (while off the data is kept, but retention still applies)
        freshDigest()
        // Load the journal too: "clear what came from it" on the page must list Apps that only have raw notifications and nothing digested yet (it has not been read while not watching)
        if (on() && digest()) loadedJournal()
        if (p == null) publish(LearnStatus.StoreUnavailable) else if (host != null) evaluate()
    }

    companion object {
        /** The entry among recorded deletes that means "sensitive category"; anything not starting with a colon is a package name (package names contain no colon). */
        const val PURGE_SENSITIVE = ":sensitive"
        /** A recorded single delete: followed by an id (topic, contact, segmented digest sentence) or an epoch day (the first day of a day's, week's or month's digest). */
        /** A recorded "wipe all": the whole thing could not be deleted (cannot save, the file cannot be deleted either); once readable, clear everything inside it. */
        internal const val MARK_ALL = ":all"
        internal const val MARK_TOPIC = ":topic:"
        internal const val MARK_CONTACT = ":contact:"
        /** On the raw notification journal: the entries of a notification key for one burst. Followed by the key's hash, [KEY_SINCE], and since when (ms). */
        internal const val MARK_KEY = ":key:"
        internal const val KEY_SINCE = "@"
        /** How long a burst is: the window looked back at when recognizing a status display. */
        internal const val LIVE_WINDOW_MS = com.wangzan101.mika.core.proactive.LiveUpdates.WINDOW_MS
        /** A merge: followed by the merged-away topic id, [MERGE_INTO], and the topic id merged into. May be recorded on both the topic and contact stores. */
        internal const val MARK_MERGE = ":merge:"
        internal const val MERGE_INTO = ">"
        internal const val MARK_NOTE = ":note:"
        internal const val MARK_DAY = ":day:"
        internal const val MARK_WEEK = ":week:"
        internal const val MARK_MONTH = ":month:"

        /** Used when contacts are not stored (tests, or no store provided): always loads empty, and saving keeps nothing. */
        internal object NoContacts : ContactPoolStore {
            override fun load() = ContactPoolData()
            override fun save(data: ContactPoolData) {}
            override fun dropBroken() {}
            override fun wipe() {}
        }

        /** How many topics and how many contacts the section carried into a task may hold at most (taken by strength; the whole section has its own token cap). */
        /** How many "nothing to merge" weekly (monthly) digests may be recorded directly in a row (a guard against a bug that never terminates). */
        internal const val AUTO_SPANS_MAX = 64
        const val OBSERVED_TOPICS = 40
        const val OBSERVED_PEOPLE = 20

        /** Save the journal once this many unsaved entries have piled up. */
        const val JOURNAL_FLUSH_EVERY = 20

        /** Used when the daily notification digest is not stored (tests, or no store provided). */
        internal object NoDigest : DigestStore {
            override fun load() = DigestData()
            override fun save(data: DigestData) {}
            override fun wipe() {}
        }

        /** Used when the notification journal is not stored (tests, or no store provided). */
        internal object NoJournal : NoticeJournalStore {
            override fun load(): NoticeJournalData? = null
            override fun save(data: NoticeJournalData) {}
            override fun wipe() {}
        }

        /** Kept in memory only (for tests; the real one is [ProactivePrefs]). */
        internal class MemoryPurges : LearnPurges {
            override var topicPurges: Set<String> = emptySet()
            override var contactPurges: Set<String> = emptySet()
            override var digestPurges: Set<String> = emptySet()
            override var journalPurges: Set<String> = emptySet()
        }

        /** The system's removal reason → what the user did. Only an open or a single dismissal counts; "clear all" and the rest say nothing. */
        fun outcomeOf(reason: Int): LearnOutcome = when (reason) {
            NotificationListenerService.REASON_CLICK -> LearnOutcome.Opened
            NotificationListenerService.REASON_CANCEL -> LearnOutcome.Dismissed
            else -> LearnOutcome.None
        }
    }
}
