package com.wangzan101.mika.core.proactive

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/**
 * The triage result for one notification (by key) at a given post time. Once the key's post time changes (the notification was updated) it no longer counts; after a judged key is updated it cools down first
 * (see [TriagePlanner.cooldownMs]), becomes pending again when that is over, and during the cooldown the UI keeps showing this older result.
 */
sealed interface TriageMark {
    val postedAtMs: Long

    data class Done(override val postedAtMs: Long, val verdict: TriageVerdict) : TriageMark
    /** That batch's request failed; no automatic retry, waiting for the user to tap "retry". */
    data class Failed(override val postedAtMs: Long) : TriageMark
}

/** Whether a batch should be sent now. */
sealed interface TriagePlan {
    data class Send(val packageName: String) : TriagePlan
    /** Pending items are waiting for this app to accumulate, or judged notifications are cooling down with new content already there; check again at [atMs] (the earliest of these). */
    data class WaitUntil(val atMs: Long) : TriagePlan
    data class Idle(val why: Why) : TriagePlan

    enum class Why { Locked, Nothing, BudgetUsedUp, Busy, InFlight }
}

/** One app's pending items: when its pending items were first seen, and how many. */
data class PendingApp(val firstAtMs: Long, val count: Int)

/** The numbers shown in the UI: pending (excluding the one being sent), judged, failed, being sent, and cooling (judged before, new content since, not yet due to be judged again). */
data class TriageCounts(val pending: Int = 0, val judged: Int = 0, val failed: Int = 0, val sending: Int = 0, val cooling: Int = 0)

/**
 * The triage state: which notifications are pending, batching by app, the daily cap on "worth surfacing" conversations per app, each item's result, and "retry the failed ones". Pure logic
 * that touches nothing but time; the caller (a serial coroutine in the app) calls [sync] on the inbox on every change and then asks [plan].
 * Results live only in memory and follow the inbox: keys gone from the inbox lose their results too. Notification text is only handed out through [begin] and is not stored separately here.
 *
 * Only one batch is in flight at a time; when the screen locks or the feature is turned off the caller cancels the request and calls [abort], and the batch stays pending rather than counting as a failure.
 *
 * Cooldown: chat apps repost the same notification (same key) for every new message. Once a key is judged, its updates do not become pending right away but wait for the cooldown to end
 * (counted from that judgement; the duration depends only on our own verdicts and counts, see [cooldownMs]; a "worth surfacing" verdict whose notification was updated during the request is counted as ignore/remember,
 * see [finish]), and when it ends a key with new content becomes pending, batches as usual, and a single request carries the text accumulated in between. The cooldown does not run a timer: when it ends [plan] returns
 * [TriagePlan.WaitUntil] and the caller only keeps time while unlocked.
 * Once the user handles the notification (the key leaves the inbox: opened, swiped away, app deselected, cleared) its cooldown and counts are forgotten and the same key starts over from scratch.
 *
 * Failures also pause for a while: failed requests count toward the daily request budget too, and a broken model plus a chat that never stops talking would otherwise fire every 20 seconds.
 * After a batch fails, each key in it pauses for 2, 5, 15 or 30 minutes ([BACKOFF_MS]) according to its consecutive failure count, and updates during the pause do not become pending (the UI still says "judgement failed"
 * and it counts as failed); when the pause ends a key with new content batches and is judged as usual, while one with no new content stays failed and is not retried automatically. One success resets the failure count to zero.
 * "Retry" sends immediately regardless of whether the pause is over; failing again continues the pause from the incremented count.
 * A brand-new key (while other keys of this app are paused) batches and is sent as usual: with a broken model each new conversation still causes one request, which is acceptable given the daily request cap as a backstop.
 */
class TriagePlanner(
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val windowMs: Long = WINDOW_MS,
    private val batchMax: Int = NoticeTriage.MAX_BATCH,
    surfaceCap: Int = SURFACE_CAP_PER_APP,
) {
    /** How many distinct conversations per app may be surfaced each day; varies with aggressiveness ([LearnLevel.surfaceCap]) and is set by the scheduler before applying results. */
    var surfaceCap: Int = surfaceCap

    private val marks = HashMap<String, TriageMark>()
    /** Cooldowns for judged or failed keys (failures pause too), kept separately from [marks]; keys gone from the inbox are dropped along with them. */
    private val cools = HashMap<String, Cool>()
    /** The time of the latest [sync] / [plan]: whether a cooldown has ended is computed from it. */
    private var nowMs = Long.MIN_VALUE
    private val firstAt = HashMap<String, Long>()
    private var records: List<NoticeRecord> = emptyList()
    private var history: Map<String, List<NoticeLine>> = emptyMap()
    /** The batch currently being sent: (key, post time) pairs, and its app. */
    private var inFlight: List<Pair<String, Long>>? = null
    private var inFlightPackage: String? = null
    /**
     * Today's notification keys judged "worth surfacing", per app (only key fingerprints, memory only); counted per conversation, so the same key judged worth surfacing again takes no extra slot.
     * Kept separately from [marks], so clearing results, or a key leaving the inbox and coming back, does not reset the cap.
     */
    private val surfaced = HashMap<String, HashSet<Long>>()
    private var surfacedDay = Long.MIN_VALUE
    /** The day on which a request found the proactive budget for today used up; nothing more is sent that day. */
    private var budgetOutDay: Long? = null

    /** The inbox's current state; results and cooldowns for keys no longer in the inbox are dropped, and it records when cooling keys first got new content and the earliest pending time per app. */
    fun sync(snapshot: NoticeInbox.Snapshot, nowMs: Long) {
        records = snapshot.records
        history = snapshot.history
        val keys = records.mapTo(HashSet()) { it.key }
        marks.keys.retainAll(keys)
        cools.keys.retainAll(keys)
        // keys the user handled during the request no longer keep results (the same key arriving later is a new notification and should not carry a cooldown)
        inFlight = inFlight?.filter { it.first in keys }
        for (r in records) {
            val c = cools[r.key] ?: continue
            if (c.newerAtMs == null && mark(r) == null && !sending(r)) cools[r.key] = c.copy(newerAtMs = nowMs)
        }
        refresh(nowMs)
    }

    /** The result for this record (at this post time); null when there is none, or when it belongs to an earlier one. */
    fun mark(record: NoticeRecord): TriageMark? = marks[record.key]?.takeIf { it.postedAtMs == record.postedAtMs }

    /** A copy of the current results for the UI: one whose [TriageMark.postedAtMs] matches the record is this round's result, and an older one is the result from before the new content arrived. */
    fun marks(): Map<String, TriageMark> = HashMap(marks)

    /** Pending: no result yet for this post time, not being sent, and not cooling down. */
    fun pending(): List<NoticeRecord> = records.filter { unjudged(it) && !cooling(it) }

    /** Cooling: judged before, new content since, and not yet due to be judged again. */
    fun cooling(): List<NoticeRecord> = records.filter { unjudged(it) && cooling(it) }

    fun pendingApps(): Map<String, PendingApp> = pending().groupBy { it.packageName }
        .mapValues { (pkg, list) -> PendingApp(firstAt[pkg] ?: Long.MAX_VALUE, list.size) }

    fun counts(): TriageCounts {
        var judged = 0
        var failed = 0
        for (r in records) when (mark(r)) {
            is TriageMark.Done -> judged++
            is TriageMark.Failed -> failed++
            null -> {}
        }
        // ones paused after a failure that have new content still show as failed in the UI and count as failed
        val (heldFailed, cooling) = cooling().partition { marks[it.key] is TriageMark.Failed }
        return TriageCounts(pending().size, judged, failed + heldFailed.size, inFlight?.size ?: 0, cooling.size)
    }

    val inFlightNow: Boolean get() = inFlight != null

    /** Which app the batch in flight belongs to; null when nothing is being sent. */
    val inFlightApp: String? get() = inFlightPackage

    /** What to do now. [budgetLeft] says whether the ledger has room left; a day on which a request found the budget used up ([budgetExhausted]) also counts as no room. */
    fun plan(unlocked: Boolean, busy: Boolean, budgetLeft: Boolean, nowMs: Long): TriagePlan {
        refresh(nowMs)
        if (unlocked && inFlight != null) return TriagePlan.Idle(TriagePlan.Why.InFlight)
        val left = budgetLeft && budgetOutDay != dayOf(nowMs)
        val wake = cooling().mapNotNull { cools[it.key]?.untilMs }.minOrNull()
        return decide(unlocked, busy, left, pendingApps(), nowMs, windowMs, batchMax, wake)
    }

    /** Starts sending a batch for [packageName]: its earliest at most [batchMax] pending items, along with each one's earlier texts. */
    fun begin(packageName: String): List<TriageItem> {
        check(inFlight == null) { "one batch at a time" }
        val batch = pending().filter { it.packageName == packageName }.sortedBy { it.postedAtMs }.take(batchMax)
        if (batch.isEmpty()) return emptyList()
        inFlight = batch.map { it.key to it.postedAtMs }
        inFlightPackage = packageName
        // this round's new content has been handed over; new content arriving afterwards starts a fresh clock
        batch.forEach { r -> cools[r.key]?.let { cools[r.key] = it.copy(newerAtMs = null) } }
        return batch.map { TriageItem(it, history[it.key].orEmpty()) }
    }

    /**
     * This batch is judged: only keys still in the inbox are recorded; once this app has hit its daily cap on conversations (distinct keys) worth surfacing, new keys judged worth surfacing
     * are recorded as ignore (with the built-in explanation); keys already counted today are still surfaced. Results are recorded at the post time they were sent with;
     * ones updated again during the request still start cooling from now (otherwise a chat that never stops talking would restart on every batch), and new content is judged once the cooldown ends.
     * A "worth surfacing" verdict at this point does not match the new post time, so the hint cannot appear ([NoticeHints] only accepts matching ones), and the cooldown is the graded ignore/remember one,
     * not the "already surfaced" [SURFACE_COOLDOWN_MS].
     * Call [sync] first, so changes to the inbox during the request are taken into account.
     */
    fun finish(verdicts: Map<String, TriageVerdict>, nowMs: Long) {
        val batch = takeBatch() ?: return
        val pkg = inFlightPackage.also { inFlightPackage = null } ?: return
        rollDay(nowMs)
        for ((key, posted) in batch) {
            val record = records.firstOrNull { it.key == key } ?: continue
            var v = verdicts[key] ?: TriageVerdict(TriageDecision.Ignore, note = TriageNote.NoVerdict)
            if (v.decision == TriageDecision.Surface) {
                val seen = surfaced.getOrPut(pkg) { HashSet() }
                val h = fingerprint(key)
                if (h !in seen) {
                    if (seen.size >= surfaceCap) v = TriageVerdict(TriageDecision.Ignore, note = TriageNote.SurfaceCapped)
                    else seen += h
                }
            }
            marks[key] = TriageMark.Done(posted, v)
            val n = (cools[key]?.verdicts ?: 0) + 1
            val updated = record.postedAtMs != posted
            val wait = if (updated && v.decision == TriageDecision.Surface) cooldownMs(TriageDecision.Ignore, n)
                else cooldownMs(v.decision, n)
            // judged successfully: the failure count is reset
            cools[key] = Cool(nowMs + wait, n, newerAtMs = nowMs.takeIf { updated })
        }
    }

    /**
     * This batch's request failed ([nowMs] is the failure time): keys still in the inbox are recorded as failed (at the post time they were sent with) and are not sent automatically again;
     * each key pauses according to its consecutive failure count ([failureHoldMs]), and updates during the pause do not become pending. The request count is unchanged.
     */
    fun fail(nowMs: Long) {
        val batch = takeBatch() ?: return
        inFlightPackage = null
        for ((key, posted) in batch) {
            val record = records.firstOrNull { it.key == key } ?: continue
            marks[key] = TriageMark.Failed(posted)
            val c = cools[key]
            val f = (c?.failures ?: 0) + 1
            cools[key] = Cool(nowMs + failureHoldMs(f), c?.verdicts ?: 0, nowMs.takeIf { record.postedAtMs != posted }, f)
        }
    }

    /** The batch was not sent (cancelled because the screen locked or the feature was turned off, or the budget ran out): it stays pending and does not count as a failure. */
    fun abort() {
        inFlight = null
        inFlightPackage = null
    }

    /** A request found the budget used up: this batch stays pending and nothing more is sent that day. */
    fun budgetExhausted(nowMs: Long) {
        abort()
        budgetOutDay = dayOf(nowMs)
    }

    /** The user changed the budget: stop treating "used up today" as true and recompute from the ledger. */
    fun clearBudgetLatch() {
        budgetOutDay = null
    }

    fun budgetLatched(nowMs: Long): Boolean = budgetOutDay == dayOf(nowMs)

    /**
     * "Retry the failed ones": failed keys (including paused ones with new content) become pending again, without waiting to accumulate and regardless of whether the post-failure pause is over;
     * each tap allows exactly one more try (failing again means failed again, and the pause resumes from the incremented failure count). Returns how many.
     */
    fun retryFailed(nowMs: Long): Int {
        val failed = records.filter { marks[it.key] is TriageMark.Failed }
        failed.forEach { r ->
            marks.remove(r.key)
            cools[r.key]?.let { cools[r.key] = it.copy(untilMs = minOf(it.untilMs, nowMs)) }
        }
        failed.map { it.packageName }.toSet().forEach { pkg ->
            firstAt[pkg] = minOf(firstAt[pkg] ?: Long.MAX_VALUE, nowMs - windowMs)
        }
        return failed.size
    }

    /** When the feature or triage is turned off: all results, cooldowns and timing starts are dropped; the daily cap counts stay (turning it off and on within the same day does not reset them). */
    fun clear() {
        marks.clear()
        cools.clear()
        firstAt.clear()
        records = emptyList()
        history = emptyMap()
        abort()
    }

    /**
     * Updates each app's batching start time from [now] (keys whose cooldown has ended also become pending here, and this is counted when [plan] is called too). For a key that got new content while cooling,
     * the start is the later of the cooldown's end and the first time the new content was seen: one that ended while locked needs no further wait after unlocking, while an update arriving after the cooldown waits to accumulate as usual.
     */
    private fun refresh(now: Long) {
        nowMs = now
        val pending = pending()
        // the start time for the app currently being sent is kept too: after the batch is cancelled (screen locked) it is pending again, and there is no need to wait afresh after unlocking
        val apps = pending.mapTo(HashSet()) { it.packageName } + listOfNotNull(inFlightPackage)
        firstAt.keys.retainAll(apps)
        for (r in pending) {
            val start = cools[r.key]?.let { c -> maxOf(c.untilMs, c.newerAtMs ?: now) } ?: now
            firstAt[r.packageName] = minOf(firstAt[r.packageName] ?: Long.MAX_VALUE, start)
        }
        inFlightPackage?.let { firstAt.putIfAbsent(it, now) }
    }

    /** No result yet for this post time, and it is not being sent. */
    private fun unjudged(r: NoticeRecord) = mark(r) == null && !sending(r)

    private fun cooling(r: NoticeRecord) = (cools[r.key]?.untilMs ?: Long.MIN_VALUE) > nowMs

    private fun takeBatch(): List<Pair<String, Long>>? = inFlight.also { inFlight = null }

    private fun sending(r: NoticeRecord) = inFlight?.any { it.first == r.key && it.second == r.postedAtMs } == true

    private fun dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate().toEpochDay()

    /** A fingerprint of the notification key (the first 8 bytes of its SHA-256): the counts never keep the key itself. */
    private fun fingerprint(key: String): Long {
        val d = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        var h = 0L
        for (i in 0 until 8) h = (h shl 8) or (d[i].toLong() and 0xFF)
        return h
    }

    private fun rollDay(nowMs: Long) {
        val d = dayOf(nowMs)
        if (d != surfacedDay) { surfacedDay = d; surfaced.clear() }
    }

    /**
     * One key's cooldown: [untilMs] is when it ends (counted from the latest judgement or failure); [verdicts] is how many successful judgements there were while the user did not handle it;
     * [newerAtMs] is when new content was first seen during the cooldown (null when there was none); [failures] is how many consecutive failures since the last success.
     */
    private data class Cool(val untilMs: Long, val verdicts: Int, val newerAtMs: Long? = null, val failures: Int = 0)

    companion object {
        /** How long an app may wait to accumulate a batch after its first pending item. */
        const val WINDOW_MS = 20_000L
        /** How many conversations (distinct notification keys) per app may be judged "worth surfacing" each day. */
        const val SURFACE_CAP_PER_APP = 10

        private const val MINUTE_MS = 60_000L
        /** The cooldown after a "worth surfacing" verdict: it has already been surfaced, and the user sees the newest text themselves. */
        const val SURFACE_COOLDOWN_MS = 30 * MINUTE_MS
        /** The cooldown after an ignore or remember verdict, lengthened step by step with the number of judgements while the user did not handle it, staying at the last step afterwards. */
        val BACKOFF_MS: List<Long> = listOf(2 * MINUTE_MS, 5 * MINUTE_MS, 15 * MINUTE_MS, 30 * MINUTE_MS)

        /** How long a key cools down after being judged [decision] for the [verdicts]th time (1-based) before it can be judged again. This looks only at our own verdicts and counts, never at notification content. */
        fun cooldownMs(decision: TriageDecision, verdicts: Int): Long =
            if (decision == TriageDecision.Surface) SURFACE_COOLDOWN_MS
            else BACKOFF_MS[(verdicts - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

        /** How long a key pauses after its [failures]th consecutive failure (1-based): the same set of durations as ignore and remember. */
        fun failureHoldMs(failures: Int): Long = BACKOFF_MS[(failures - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

        /**
         * Whether to send a batch: pure function. While locked nothing happens (and no time is kept); nothing is sent when there is nothing pending, the budget is used up, or a task is running;
         * otherwise the earliest of the apps that have accumulated [batchMax] items or waited [windowMs] is sent; if none qualifies, wait until the earliest one is due.
         * [coolingUntilMs] is the earliest end time among cooldowns with new content (null when none, and it must be later than [nowMs]): when it is the only thing, wait until then anyway,
         * and when it coincides with accumulating, wait for whichever comes first.
         */
        fun decide(
            unlocked: Boolean, busy: Boolean, budgetLeft: Boolean, pending: Map<String, PendingApp>, nowMs: Long,
            windowMs: Long = WINDOW_MS, batchMax: Int = NoticeTriage.MAX_BATCH, coolingUntilMs: Long? = null,
        ): TriagePlan {
            if (!unlocked) return TriagePlan.Idle(TriagePlan.Why.Locked)
            if (pending.isEmpty() && coolingUntilMs == null) return TriagePlan.Idle(TriagePlan.Why.Nothing)
            if (!budgetLeft) return TriagePlan.Idle(TriagePlan.Why.BudgetUsedUp)
            if (busy) return TriagePlan.Idle(TriagePlan.Why.Busy)
            val ready = pending.filter { (_, p) -> p.count >= batchMax || nowMs - p.firstAtMs >= windowMs }
            ready.minByOrNull { it.value.firstAtMs }?.let { return TriagePlan.Send(it.key) }
            val windowEnd = pending.values.minOfOrNull { it.firstAtMs + windowMs }
            return TriagePlan.WaitUntil(listOfNotNull(windowEnd, coolingUntilMs).min())
        }
    }
}
