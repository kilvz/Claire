package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.proactive.DayResult
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.proactive.NoticeNotes
import com.wangzan101.mika.core.proactive.NotesResult
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

// Learning: model calls for the rollup digests and the note digests
/**
 * Request sending shared by the daily, weekly and monthly digests: consent, model creation and the pause after a failure work the same as for
 * the other batches. [begin] records which one is in flight before the request goes out; [request] calls the model; [apply] writes the
 * understood result into [pool] and returns a line for the log; [tried] increments this one's failure count
 * (only when the model returned an error or the reply could not be understood; failing to connect at all does not count).
 */
internal fun LearnRunner.sendRollup(
    scope: CoroutineScope, what: String, call: ProactiveCall, pool: DigestPool, begin: () -> Unit,
    request: suspend (ChatModel, String, java.time.ZoneId) -> DayResult, apply: (DayResult.Done) -> String, tried: () -> Unit,
) {
    val consented = consent() ?: return publish(LearnStatus.NeedsConsent)
    val built = try {
        hooks.model(call)
    } catch (e: Exception) {
        log("learn model unavailable: ${e.javaClass.simpleName}")
        failed(clock())
        return publish(LearnStatus.Waiting)
    }
    if (built == null) return publish(LearnStatus.NoModel)
    if (!consented.covers(built.target)) {
        log("$what not sent: target is not the consented one")
        return publish(LearnStatus.NeedsConsent)
    }
    val model = built.model
    val lang = language()
    val z = zone()
    begin()
    publish(LearnStatus.Sending)
    val job = scope.launch(start = CoroutineStart.LAZY) {
        val result = try {
            request(model, lang, z)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$what request failed: ${e.javaClass.simpleName}")
            DayResult.Failed()
        }
        if (inFlight !== coroutineContext.job) return@launch
        inFlight = null
        slot.release(this@sendRollup)
        dayInFlight = null
        spanInFlight = null
        try {
            // Cleared mid-flight (the sentences were replaced with a new copy): do not write this result back
            val stillOk = digestPool === pool &&
                runCatching { on() && digest() && consent() == consented && consented.covers(hooks.target()) }.getOrDefault(false)
            if (!stillOk) {
                log("$what dropped: switch or consent changed")
            } else when (result) {
                is DayResult.Done -> {
                    val summary = apply(result)
                    saveDigest(pool)
                    failures = 0
                    holdUntilMs = 0L
                    val drops = result.dropped.entries.joinToString(",") { "${it.key}=${it.value}" }
                    log("$what: $summary" + if (drops.isEmpty()) "" else ", dropped $drops")
                }
                is DayResult.Failed -> {
                    failed(clock())
                    if (!result.unreachable) tried()
                    log("$what failed: unreachable=${result.unreachable}")
                }
                DayResult.BudgetExhausted -> budgetOutDay = dayOf(clock())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$what apply failed: ${e.javaClass.simpleName}")
            failed(clock())
            tried()
        }
        // Wake the waiting decision pass first (the decision goes first), then check whether there is anything else to send
        slot.wakeOthers(this@sendRollup)
        evaluate()
    }
    slot.take(this)
    inFlight = job
    job.start()
}

/**
 * Level 1 of the daily notification digest: hand this App's not-yet-digested raw notifications (oldest first, at most
 * [LearnTuning.NOTES_BATCH_MAX] entries per batch) to the model to produce a few sentences. Consent, model creation and the pause after a
 * failure work the same as for the topic-digest batch. When the batch comes back: understood sentences are recorded; a model error or an
 * unreadable reply also counts as done (the same batch is not sent again); failing to connect at all or a cancellation (screen lock, switch
 * changed) does not count, and the batch is kept for next time.
 */
internal fun LearnRunner.sendNotes(scope: CoroutineScope, packageName: String) {
    val j = journal ?: return publish(LearnStatus.Waiting)
    val pool = loadedDigest() ?: return publish(LearnStatus.StoreUnavailable)
    val consented = consent() ?: return publish(LearnStatus.NeedsConsent)
    val batch = j.pending(packageName, LearnTuning.NOTES_BATCH_MAX)
    if (batch.isEmpty()) return publish(LearnStatus.Idle)
    val built = try {
        hooks.model(ProactiveCall.Notes)
    } catch (e: Exception) {
        log("learn model unavailable: ${e.javaClass.simpleName}")
        failed(clock())
        return publish(LearnStatus.Waiting)
    }
    if (built == null) return publish(LearnStatus.NoModel)
    if (!consented.covers(built.target)) {
        log("notes not sent: target is not the consented one")
        return publish(LearnStatus.NeedsConsent)
    }
    val model = built.model
    val lang = language()
    val z = zone()
    notesApp = packageName
    publish(LearnStatus.Sending)
    val job = scope.launch(start = CoroutineStart.LAZY) {
        val result = try {
            NoticeNotes(model, lang).note(packageName, batch, z)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("notes request failed: ${e.javaClass.simpleName}")
            NotesResult.Failed()
        }
        if (inFlight !== coroutineContext.job) return@launch
        inFlight = null
        slot.release(this@sendNotes)
        notesApp = null
        try {
            // Cleared mid-flight (the journal or the sentences were replaced with a new copy): this result must not be written back — that would bring back what was cleared
            val stillOk = journal === j && digestPool === pool &&
                runCatching { on() && digest() && eligible(packageName) && consent() == consented && consented.covers(hooks.target()) }
                    .getOrDefault(false)
            if (!stillOk) {
                log("notes result dropped: switch, app or consent changed")
            } else when (result) {
                is NotesResult.Noted -> {
                    val before = pool.data()
                    val added = pool.add(packageName, batch, result.notes, z, clock())
                    pool.expire(dayOf(clock()))
                    // Only once the sentences are saved are the raw notifications marked "done" and saved: if the process dies mid-way, the worst case is digesting the same batch twice, nothing is lost
                    if (saveDigest(pool)) {
                        j.markNoted(batch)
                        saveJournal()
                        failures = 0
                        holdUntilMs = 0L
                        val drops = result.dropped.entries.joinToString(",") { "${it.key}=${it.value}" }
                        log("notes batch: ${batch.size} entries, $added notes" + if (drops.isEmpty()) "" else ", dropped $drops")
                    } else {
                        // The sentences were not saved: this batch does not count as done. If the raw notifications were still marked "done" and saved, then after a restart there would be no sentences and the raw ones would no longer be queued,
                        // so this batch would be lost forever. Roll back the in-memory sentences too — keeping them would duplicate the batch on the next digest
                        restoreDigest(before)
                        failed(clock())
                        log("notes batch not kept: ${batch.size} entries, digest save failed")
                    }
                }
                is NotesResult.Failed -> {
                    failed(clock())
                    if (!result.unreachable) {
                        j.markNoted(batch)
                        saveJournal()
                    }
                    log("notes batch failed: ${batch.size} entries, unreachable=${result.unreachable}")
                }
                NotesResult.BudgetExhausted -> budgetOutDay = dayOf(clock())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("notes apply failed: ${e.javaClass.simpleName}")
            failed(clock())
        }
        // Wake the waiting decision pass first (the decision goes first), then check whether there is anything else to send
        slot.wakeOthers(this@sendNotes)
        evaluate()
    }
    slot.take(this)
    inFlight = job
    job.start()
}
