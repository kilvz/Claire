package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ContactDrop
import com.wangzan101.mika.core.proactive.ExistingTopic
import com.wangzan101.mika.core.proactive.LearnDrop
import com.wangzan101.mika.core.proactive.LearnResult
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.proactive.NoticeLearn
import com.wangzan101.mika.core.proactive.TopicPool
import com.wangzan101.mika.core.proactive.TriageGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

// Learning: the distill call for a batch of notifications and how its result is applied
internal fun LearnRunner.evaluate() {
    try {
        evaluateOrThrow()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // An error in outside things (model config, usage ledger) must not take down the watchman (it shares a job with the lock watcher and the decision pass)
        log("learn evaluate failed: ${e.javaClass.simpleName}")
        // A batch was opened but the request never went out: what failed is outside things, not this batch — keep it, pause, then send again
        if (inFlight == null && buffer.sending().isNotEmpty()) keepBatch(clock())
        // A request already in flight is left alone: a config read error does not mean it should stop (stopping it wastes money already spent and it would have to be sent again later). Just do not start a new one this round.
        // But only keep it when the screen is definitely unlocked: if the lock state cannot be read, or the screen is locked, stop as usual — no sending while locked is a hard rule
        if (inFlight != null && unlockedForSure()) publish(LearnStatus.Sending) else stopAndPublish(LearnStatus.Idle)
    }
}

/** The lock state can be read and the screen is unlocked. Unreadable does not count. */
internal fun LearnRunner.unlockedForSure(): Boolean =
    try { !readLocked() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }

internal fun LearnRunner.evaluateOrThrow() {
    val scope = host ?: return
    if (!on()) return stopAndPublish(LearnStatus.Off)
    if (suspended()) return stopAndPublish(LearnStatus.Suspended)
    if (readLocked()) return stopAndPublish(LearnStatus.Locked)
    when (TriageGate.of(true, consent(), hooks.target())) {
        TriageGate.NoModel -> return stopAndPublish(LearnStatus.NoModel)
        TriageGate.NeedsConsent -> return stopAndPublish(LearnStatus.NeedsConsent)
        else -> {}
    }
    if (inFlight != null) return publish(LearnStatus.Sending)
    // The digest failed to save last time: retry the save on every pass (without waiting for another digest to be due)
    if (digestUnsaved) digestPool?.let(::saveDigest)
    // While "daily notification digest" is on, notifications in the journal that have not been segmented yet, and the day whose daily digest is due, are also pending work
    val notesPending = digest() && (loadedJournal()?.pendingCount ?: 0) > 0
    val now = clock()
    val dayDue = dayToDigest(now)
    // Week and month digests queue up behind the daily digest: if some day is due, do that day first
    val spanDue = if (dayDue == null) spanToDigest(now) else null
    if (buffer.pendingCount == 0 && !notesPending && dayDue == null && spanDue == null) return publish(LearnStatus.Idle)
    if (hooks.busy.value) return publish(LearnStatus.Busy)
    if (!budgetLeft(now)) return publish(LearnStatus.BudgetUsedUp)
    if (held(now)) return publish(LearnStatus.Waiting)
    // The decision pass is sending: wait for it to finish (it tells this side to look again when done). Proactive background requests go out one at a time
    if (slot.heldByOther(this)) return publish(LearnStatus.Waiting)
    // Send the topic-digest batch first; if none is due, check whether any segmented digest is due. Only one request at a time
    buffer.due(now, eligible)?.let { return send(scope, it) }
    // The digest still failed to save (a retry just happened above): send no segmented, daily, weekly or monthly request (see digestUnsaved)
    if (digestUnsaved && (notesPending || dayDue != null || spanDue != null)) return publish(LearnStatus.StoreUnavailable)
    if (notesPending) {
        journal?.due(now, eligible, LearnTuning.NOTES_DUE_COUNT, LearnTuning.NOTES_DUE_AGE_MS)?.let { return sendNotes(scope, it) }
    }
    dayDue?.let { (day, partial) -> return sendDay(scope, day, partial) }
    spanDue?.let { (kind, start, partial) -> return sendSpan(scope, kind, start, partial) }
    publish(LearnStatus.Waiting)
}

internal fun LearnRunner.send(scope: CoroutineScope, packageName: String) {
    val p = loadedPool() ?: return publish(LearnStatus.StoreUnavailable)
    val consented = consent() ?: return publish(LearnStatus.NeedsConsent)
    val events = buffer.begin(packageName)
    if (events.isEmpty()) return publish(LearnStatus.Idle)
    val built = try {
        hooks.model(ProactiveCall.Learn)
    } catch (e: Exception) {
        // Creating the model (which decrypts the key) failed: not this batch's problem — keep it; pause before retrying, so every new notification does not trigger the same error again
        log("learn model unavailable: ${e.javaClass.simpleName}")
        keepBatch(clock())
        return publish(LearnStatus.Waiting)
    }
    if (built == null) {
        buffer.abort()
        return publish(LearnStatus.NoModel)
    }
    // The config may have changed since the gate check: if the model was not built with the consented target, do not send (keep the events)
    if (!consented.covers(built.target)) {
        buffer.abort()
        log("learn not sent: target is not the consented one")
        return publish(LearnStatus.NeedsConsent)
    }
    val model = built.model
    val now = clock()
    // The topics shown to the model map one-to-one to t1… in its reply; topics deleted mid-request are dropped when applying
    val allowSensitive = sensitive()
    // Topics this App has confirmed itself come first: it is probably still pushing those subjects, and the model can only reference them if it sees them
    val shown = p.strongest(now, includeSensitive = allowSensitive, packageName = packageName).map { ExistingTopic(it.id, it.kind, it.label) }
    // When contacts cannot be read, do not ask the model for them (they could not be saved anyway)
    val contactsOn = loadedContacts() != null
    val lang = language()
    bump(packageName) { it.copy(batches = it.batches + 1) }
    publish(LearnStatus.Sending)
    // Record the job before starting it: the request body compares it with inFlight
    val job = scope.launch(start = CoroutineStart.LAZY) {
        // Throwing from here would take down the whole watchman (the lock watcher and the decision pass live in it): an unexpected error while reading the reply only counts as this batch failing
        val result = try {
            NoticeLearn(model, lang, allowSensitive, contactsOn).learn(packageName, events, shown)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("learn request failed: ${e.javaClass.simpleName}")
            LearnResult.Failed()
        }
        // No longer the one in flight (it was cancelled but the model still came back; a new batch may already be sending): change nothing
        if (inFlight !== coroutineContext.job) return@launch
        inFlight = null
        slot.release(this@send)
        // Same when applying the result: an unexpected error counts as this batch failing
        try {
            val stillOk = runCatching { on() && eligible(packageName) && consent() == consented && consented.covers(hooks.target()) }
                .getOrDefault(false)
            if (!stillOk) {
                buffer.abort()
                log("learn result dropped: switch, app or consent changed")
            } else when (result) {
                is LearnResult.Learned -> {
                    applyResult(p, packageName, result)
                    failures = 0
                    holdUntilMs = 0L
                }
                is LearnResult.Failed -> if (result.unreachable) keepBatch(clock()) else dropBatch(clock())
                LearnResult.BudgetExhausted -> {
                    buffer.abort()
                    budgetOutDay = dayOf(clock())
                }
            }
            val drops = (result as? LearnResult.Learned)?.dropped.orEmpty().entries.joinToString(",") { "${it.key}=${it.value}" }
            val why = (result as? LearnResult.Failed)?.let { if (it.unreachable) " unreachable" else if (it.unreadable) " unreadable" else "" }.orEmpty()
            log("learn batch: ${result.javaClass.simpleName}$why, ${events.size} events" + if (drops.isEmpty()) "" else ", dropped $drops")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("learn apply failed: ${e.javaClass.simpleName}")
            if (buffer.sending().isNotEmpty()) dropBatch(clock())
        }
        // Wake the waiting decision pass first (the decision goes first), then check whether there is anything else to send
        slot.wakeOthers(this@send)
        evaluate()
    }
    slot.take(this)
    inFlight = job
    job.start()
}

/** Applies the batch's result: uses the batch as it looks now (openings, dismissals may have arrived after the request went out). No suspension points in between. */
internal fun LearnRunner.applyResult(p: TopicPool, packageName: String, result: LearnResult.Learned) {
    val events = buffer.sending()
    val now = clock()
    // Sensitive categories follow the switch as of the moment of applying: if it was turned off mid-request, entries marked sensitive are not accepted this time either
    val applied = p.apply(packageName, events, result.signals, now, zone(), allowSensitive = sensitive())
    // Save topics first, then contacts: when the topics linked from contacts are persisted, they must already exist in the topic pool (so a process death mid-way never leaves links pointing at nothing)
    save(p)
    // Contacts: identity needs the title, so this must run before finish clears the titles
    val contactDropped = HashMap(result.contactDropped)
    var contactsAccepted = 0
    val c = loadedContacts()
    if (c == null) {
        if (result.contacts.isNotEmpty()) contactDropped[ContactDrop.Off] = (contactDropped[ContactDrop.Off] ?: 0) + result.contacts.size
    } else {
        val r = c.apply(events, result.contacts, applied.byEvent, now, zone(), allowSensitive = sensitive())
        r.dropped.forEach { (k, v) -> contactDropped[k] = (contactDropped[k] ?: 0) + v }
        contactsAccepted = r.accepted
        if (c.maintain(now, p.all().map { it.id }.toSet()) || r.changed) saveContacts(c)
    }
    buffer.finish(applied.byEvent.mapKeys { (i, _) -> events[i].seq })
    lastDistilledAtMs = now
    bump(packageName) { f ->
        val dropped = HashMap(f.dropped)
        result.dropped.forEach { (k, v) -> dropped[k] = (dropped[k] ?: 0) + v }
        // Validated but not taken by the pool (topic deleted mid-request, sensitive category turned off mid-way) also counts as dropped
        if (applied.missing > 0) dropped[LearnDrop.NotApplied] = (dropped[LearnDrop.NotApplied] ?: 0) + applied.missing
        if (applied.blocked > 0) dropped[LearnDrop.Blocked] = (dropped[LearnDrop.Blocked] ?: 0) + applied.blocked
        val cd = HashMap(f.contactDropped)
        contactDropped.forEach { (k, v) -> cd[k] = (cd[k] ?: 0) + v }
        f.copy(
            distilled = f.distilled + events.size, accepted = f.accepted + applied.applied, dropped = dropped,
            contactsAccepted = f.contactsAccepted + contactsAccepted, contactDropped = cd,
        )
    }
}

/** This batch did not distill; drop it, record a failure, pause, then send the next batch. */
internal fun LearnRunner.dropBatch(now: Long) {
    val pkg = buffer.sending().firstOrNull()?.packageName
    // Pause first, then touch the buffer: any buffer change triggers another pass, and by then the pause must already be in place
    failed(now)
    buffer.fail()
    if (pkg != null) bump(pkg) { it.copy(failed = it.failed + 1) }
}

/** This batch was not sent, but not because of itself (model unreachable, model creation failed): keep it pending; also record a failure and pause before retrying. */
internal fun LearnRunner.keepBatch(now: Long) {
    val pkg = buffer.sending().firstOrNull()?.packageName
    failed(now)
    buffer.abort()
    if (pkg != null) bump(pkg) { it.copy(failed = it.failed + 1) }
}

internal fun LearnRunner.failed(now: Long) {
    failures++
    holdUntilMs = now + LearnTuning.failureHoldMs(failures)
}

/** The previous batch failed and the pause is still running. Does not count when the clock was set back (the remaining time is longer than the longest tier). */
internal fun LearnRunner.held(now: Long): Boolean =
    failures > 0 && now < holdUntilMs && holdUntilMs - now <= LearnTuning.FAILURE_HOLD_MS.last()
