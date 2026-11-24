package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.NoticeInbox
import com.wangzan101.mika.core.proactive.NoticeTriage
import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.proactive.TargetedModel
import com.wangzan101.mika.core.proactive.TriageConsent
import com.wangzan101.mika.core.proactive.TriageCounts
import com.wangzan101.mika.core.proactive.TriageGate
import com.wangzan101.mika.core.proactive.TriageMark
import com.wangzan101.mika.core.proactive.TriagePlan
import com.wangzan101.mika.core.proactive.TriagePlanner
import com.wangzan101.mika.core.proactive.TriageResult
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything triage needs from the rest of the app is supplied by MikaApp through lambdas: nothing is touched while triage is off (provider config, usage ledger).
 * [target] is the provider and model that would be used now (it does not build a model; used by the UI and the gate checks); [model] builds the model along with the target it was built from
 * (a single config read, which consent is checked against before sending a request); [busy] means "a task is running".
 */
class TriageHooks(
    val target: () -> TriageTarget?,
    /** Builds the model. The argument is which kind of request is being sent this time (the reply limit is chosen from it). */
    val model: (ProactiveCall) -> TargetedModel?,
    val tokensToday: () -> Long,
    val callsToday: () -> Long,
    val usageVersion: () -> StateFlow<Long>,
    val busy: StateFlow<Boolean>,
)

enum class TriageStatus {
    Off,
    /** Triage is on, but the proactive feature is not watching (while the app is fully exited): do nothing. */
    Paused,
    NoModel,
    NeedsConsent,
    /** The "app access" settings cannot be read: it is unknown which apps are off-limits, so it is paused; items pending triage are kept. */
    Suspended,
    /** The model cannot be reached (or it never answered before the timeout), so everything pauses for a while and then retries; items pending triage are kept. */
    Unreachable,
    Locked,
    /** A task is running; wait for it to end. */
    Busy,
    /** Triage's own token allowance is used up for today (or the request was rejected and it counts as tokens): continue tomorrow. */
    TokensUsedUp,
    /** Triage's own request count is used up for today: continue tomorrow. */
    CallsUsedUp,
    /** Items pending triage are waiting for this app to fill a batch, or already-triaged notifications have new content and are waiting for the cooldown to elapse. */
    Waiting,
    Sending,
    Idle,
}

/**
 * For the UI: the current status, each item's result (keyed by notification key; one whose send time matches is the result of this attempt, an older one is the result of the attempt before the cooldown) and a few counts.
 */
data class TriageState(
    val status: TriageStatus = TriageStatus.Off,
    val marks: Map<String, TriageMark> = emptyMap(),
    val counts: TriageCounts = TriageCounts(),
)

/**
 * Triage scheduling: runs on the single serial coroutine of [ProactiveRuntime] and stays alive only while watching ([watch]).
 *
 * Power saving: no polling. It takes a look ([evaluate]) only when the inbox changes, the lock state changes, [TriageHooks.busy] changes, or the switch or budget is changed externally ([poke]);
 * timers (to fill a batch, or for a triaged or failed notification's cooldown to elapse) exist only while unlocked and are cancelled as soon as the device is seen locked; anything that comes due while locked is looked at after unlocking; a request in flight while locked is cancelled as well, and that batch stays pending triage (it does not count as a failure) and is sent after unlocking.
 * It sends one batch at a time, and a batch covers a single app. No automatic retries: a failed batch is recorded as failed, it pauses for a while according to the number of consecutive failures, and updates during the pause are not sent,
 * then after the pause it triages again only if there is new content; without new content it waits for the user to tap "try again" ([retryFailed]) before sending once more.
 * When the budget is used up (the request was rejected, or the ledger has already hit the limit) it quietly stops for the day.
 * Results live only in memory ([TriagePlanner]); the log ([log]) records only result categories and item counts, never notification text or reasons.
 */
internal class TriageRunner(
    private val inbox: NoticeInbox,
    private val hooks: TriageHooks,
    private val on: () -> Boolean,
    private val consent: () -> TriageConsent?,
    private val budgetLimit: () -> Long,
    private val language: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val planner: TriagePlanner = TriagePlanner(),
    private val log: (String) -> Unit = {},
    /** How many different conversations may be surfaced per app per day (varies with the aggressiveness level). */
    private val surfaceCap: () -> Int = { TriagePlanner.SURFACE_CAP_PER_APP },
    /** "App access" cannot be read: it stays paused and sends no requests; items pending triage are kept and triaged once it can be read again. */
    private val suspended: () -> Boolean = { false },
    /** The request slot shared with summarization (only one background request at a time); if not supplied, it gets its own. */
    private val slot: RequestSlot = RequestSlot(),
) {
    private val mutable = MutableStateFlow(TriageState())
    val state: StateFlow<TriageState> = mutable.asStateFlow()

    private var host: CoroutineScope? = null
    private var readLocked: () -> Boolean = { true }
    private var timer: Job? = null
    private var inFlight: Job? = null

    /**
     * When the model cannot be reached (no network, proxy not working), or is reached but never answers before the timeout, everything pauses for a while, not merely those few notifications of this batch: this has nothing to do with the notifications' content,
     * and if only per-conversation cooldowns were used, every new conversation would connect again (new conversations are not in a cooldown), so a burst of them means reconnecting endlessly and wasting request count for nothing.
     * The more consecutive failures to connect, the longer the pause ([TriagePlanner.failureHoldMs]). It is cleared when a batch is triaged, the user taps retry, the provider or model changes, or watching starts over.
     * The batch that could not connect stays pending triage and does not count as a failure; the batch that timed out counts as a failure (it may be a problem with that batch itself, so it is not resent automatically and the user can try again manually).
     */
    private var unreachable = 0
    private var unreachableUntilMs = 0L
    /** Which provider's model was in use when the pause started: if that changes, it no longer stays paused. Identified by provider id and model name (as with consent), not by display name and not by object comparison. */
    private var unreachableTarget: TriageConsent? = null

    private fun reachableAgain() {
        unreachable = 0
        unreachableUntilMs = 0L
        unreachableTarget = null
    }

    /** For tests: whether the timer (for filling a batch or a cooldown elapsing) is active. */
    internal val timerActive: Boolean get() = timer?.isActive == true
    internal val requestActive: Boolean get() = inFlight?.isActive == true

    /**
     * Runs throughout the watching period; when it is cancelled (locking the device does not cancel it; turning the feature off or fully exiting does), the timer and request are cancelled with it, and the batch being sent stays pending triage.
     * [readLocked] reads the lock state from the system every time (it is also read once before sending a request, instead of trusting only the stale [locked] value).
     */
    suspend fun watch(locked: StateFlow<Boolean>, readLocked: () -> Boolean) {
        var mine: CoroutineScope? = null
        try {
            coroutineScope {
                mine = this
                // Whatever the previous watching period left behind (its cleanup may not have run yet) does not count: that batch stays pending triage
                timer?.cancel()
                timer = null
                inFlight?.cancel()
                inFlight = null
                slot.release(this@TriageRunner)
                planner.abort()
                // Watching starts over (the switch was turned off and back on, the app returned to the foreground): a pause left over from being unreachable does not continue
                reachableAgain()
                host = this
                slot.listen(this@TriageRunner) { evaluate() }
                this@TriageRunner.readLocked = readLocked
                launch { inbox.state.collect { evaluate() } }
                launch { locked.collect { evaluate() } }
                launch { hooks.busy.collect { evaluate() } }
                awaitCancellation()
            }
        } finally {
            // When it stops and immediately starts watching again, the new run may already have taken over: clean up only its own state
            if (host === mine) {
                host = null
                timer = null
                inFlight = null
                planner.abort()
                publish(if (on()) TriageStatus.Paused else TriageStatus.Off)
                slot.unlisten(this)
                slot.release(this)
                slot.wakeOthers(this)
            }
        }
    }

    /** The switch, consent, budget or similar changed: take another look. Must be called on the serial coroutine. */
    fun poke() {
        if (host != null) evaluate() else publish(if (on()) TriageStatus.Paused else TriageStatus.Off)
    }

    /**
     * An app that is no longer collected (disabled in "app access"): if the batch being sent is its own, cancel it (the batch stays pending triage; the records are then removed from the inbox and so will not be sent),
     * then take another look. Must be called on the serial coroutine, after the records are removed from the inbox.
     */
    fun dropApps(keep: (String) -> Boolean) {
        val pkg = planner.inFlightApp ?: return
        if (keep(pkg)) return
        stopRequest()
        poke()
    }

    /** "Try again after a failed triage": each tap grants just one more attempt. Must be called on the serial coroutine. */
    fun retryFailed() {
        planner.retryFailed(clock())
        // The user is waiting for a result: a pause caused by being unreachable ends too, and it retries immediately
        reachableAgain()
        poke()
    }

    /** The user changed the budget: today's rejection at request time is no longer honoured, and it is recomputed from the ledger. */
    fun budgetChanged() {
        planner.clearBudgetLatch()
        poke()
    }

    /** When the proactive feature is turned off: results are cleared along with it (they live and die together with the inbox). */
    fun clear() {
        stopRequest()
        timer?.cancel()
        timer = null
        planner.clear()
        publish(if (on()) TriageStatus.Paused else TriageStatus.Off)
    }

    /**
     * Takes a look at what should be done now. Exceptions thrown by the external things it uses (model config, usage ledger) must not take the watcher down (it shares a job with the lock watcher):
     * the category is logged and this pass is treated as doing nothing.
     */
    private fun evaluate() {
        try {
            evaluateOrThrow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("triage evaluate failed: ${e.javaClass.simpleName}")
            // A batch has been opened but the request never went out: count it as a failure for this batch, so it does not stay stuck in "sending"
            if (inFlight == null && planner.inFlightNow) planner.fail(clock())
            // Leave an already-sent request alone: a config read error does not mean it should stop (if stopped, it has already been charged and would have to be sent again). This pass simply sends nothing new.
            // But keep it only when the device is definitely not locked: if the lock state cannot be read, or the device is locked, stop as usual — never sending while locked is a hard rule
            val unlocked = try { !readLocked() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            if (inFlight != null && unlocked) publish(TriageStatus.Sending) else stopAndPublish(TriageStatus.Idle)
        }
    }

    private fun evaluateOrThrow() {
        val scope = host ?: return
        timer?.cancel()
        timer = null
        val now = clock()
        planner.sync(inbox.state.value, now)
        if (!on()) return stopAndPublish(TriageStatus.Off)
        if (suspended()) return stopAndPublish(TriageStatus.Suspended)
        if (readLocked()) return stopAndPublish(TriageStatus.Locked)
        when (TriageGate.of(true, consent(), hooks.target())) {
            TriageGate.NoModel -> return stopAndPublish(TriageStatus.NoModel)
            TriageGate.NeedsConsent -> return stopAndPublish(TriageStatus.NeedsConsent)
            else -> {}
        }
        // Unreachable and currently pausing: look again when it is due (ignore a clock that was wound back, or a remaining time longer than the longest tier; a changed provider or model no longer pauses)
        if (unreachable > 0 && unreachableTarget?.covers(hooks.target()) != true) reachableAgain()
        val offlineFor = unreachableUntilMs - now
        if (unreachable > 0 && offlineFor > 0 && offlineFor <= TriagePlanner.BACKOFF_MS.last()) {
            timer = scope.launch {
                delay(offlineFor)
                timer = null
                evaluate()
            }
            return publish(TriageStatus.Unreachable)
        }
        when (val plan = planner.plan(unlocked = true, busy = hooks.busy.value, budgetLeft = budgetLeft(), nowMs = now)) {
            // Summarization is sending: wait for it to finish (it nudges this side to look again when done)
            is TriagePlan.Send -> if (slot.heldByOther(this)) publish(TriageStatus.Waiting) else send(scope, plan.packageName)
            is TriagePlan.WaitUntil -> {
                timer = scope.launch {
                    delay((plan.atMs - now).coerceAtLeast(0))
                    timer = null
                    evaluate()
                }
                publish(TriageStatus.Waiting)
            }
            is TriagePlan.Idle -> publish(when (plan.why) {
                TriagePlan.Why.Locked -> TriageStatus.Locked
                TriagePlan.Why.Nothing -> TriageStatus.Idle
                TriagePlan.Why.BudgetUsedUp -> budgetStatus()
                TriagePlan.Why.Busy -> TriageStatus.Busy
                TriagePlan.Why.InFlight -> TriageStatus.Sending
            })
        }
    }

    private fun send(scope: CoroutineScope, packageName: String) {
        val items = planner.begin(packageName)
        if (items.isEmpty()) return publish(TriageStatus.Idle)
        // Consent was given for this provider's model: check it again against the target the model was built from, and again when the reply comes back
        val consented = consent()
        // An error while building the model (which requires decrypting the key) counts as a failure for this batch and does not stay pending: otherwise every new notification would fail again
        val built = try {
            hooks.model(ProactiveCall.Triage)
        } catch (e: Exception) {
            planner.fail(clock())
            log("triage model unavailable: ${e.javaClass.simpleName}")
            return publish(TriageStatus.Idle)
        }
        if (built == null || consented == null) {
            planner.abort()
            return publish(TriageStatus.NoModel)
        }
        // The config may have changed after the gate check: if the model was not built from the consented target, do not send (it stays pending triage until the user confirms again)
        if (!consented.covers(built.target)) {
            planner.abort()
            log("triage not sent: target is not the consented one")
            return publish(TriageStatus.NeedsConsent)
        }
        val model = built.model
        val lang = language()
        publish(TriageStatus.Sending)
        // Record the job before starting it: the request body compares it against inFlight
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Something thrown here would take the whole watcher down (lock watching and summarization are inside it too): an unexpected error while reading the reply counts only as a failure for this batch
            val result = try {
                NoticeTriage(model, lang).triage(items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("triage request failed: ${e.javaClass.simpleName}")
                TriageResult.Failed()
            }
            // No longer the request in flight (it was cancelled but the model still replied; a new batch may already be sending): change nothing
            if (inFlight !== coroutineContext.job) return@launch
            inFlight = null
            slot.release(this@TriageRunner)
            // The same when applying the result: an unexpected error counts as a failure for this batch
            try {
                val now = clock()
                planner.sync(inbox.state.value, now)
                val stillConsented = runCatching { on() && consent() == consented && consented.covers(hooks.target()) }.getOrDefault(false)
                if (!stillConsented) {
                    planner.abort()
                    log("triage result dropped: consent or switch changed")
                } else when (result) {
                    is TriageResult.Judged -> {
                        planner.surfaceCap = surfaceCap()
                        planner.finish(result.verdicts, now)
                        reachableAgain()
                    }
                    is TriageResult.Failed -> {
                        // Could not connect: unrelated to this batch's content, so the batch stays pending triage. Other failures are charged to this batch
                        if (result.unreachable) planner.abort() else planner.fail(now)
                        // Could not connect, or reached but never answered before the timeout: pause everything for a while and retry (see unreachable)
                        if (result.unreachable || result.unanswered) {
                            unreachable++
                            unreachableUntilMs = now + TriagePlanner.failureHoldMs(unreachable)
                            unreachableTarget = TriageConsent.of(built.target)
                        }
                    }
                    TriageResult.BudgetExhausted -> planner.budgetExhausted(now)
                }
                // For failures, note which kind it was (unreachable / unreadable reply); just those words, no content
                val why = (result as? TriageResult.Failed)
                    ?.let { if (it.unreachable) " unreachable" else if (it.unanswered) " unanswered" else if (it.unreadable) " unreadable" else "" }.orEmpty()
                log("triage batch: ${result.javaClass.simpleName}$why, ${items.size} items")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("triage apply failed: ${e.javaClass.simpleName}")
                if (planner.inFlightNow) planner.fail(clock())
            }
            // First check whether this side still has anything to send (triage goes first), then wake the waiting summarization
            evaluate()
            slot.wakeOthers(this@TriageRunner)
        }
        slot.take(this)
        inFlight = job
        job.start()
    }

    /** Whether triage's own share ([ProactiveBudget.TRIAGE_SHARE_PERCENT]%) is left; the remainder of the whole allowance is reserved for other proactive uses. */
    private fun budgetLeft(): Boolean = triageExhausted() == null

    private fun triageExhausted(): ProactiveBudget.Limit? =
        ProactiveBudget.triageExhausted(hooks.tokensToday(), hooks.callsToday(), budgetLimit())

    /**
     * Which item caused the stop: triage's own share first; if that share is left but the request was rejected (remembered for the day because another use consumed the whole allowance), see which item of the whole allowance is used up;
     * if neither can be determined (the ledger has not caught up), assume tokens.
     */
    private fun budgetStatus(): TriageStatus {
        val limit = triageExhausted()
            ?: ProactiveBudget.exhausted(hooks.tokensToday(), hooks.callsToday(), budgetLimit())
            ?: ProactiveBudget.Limit.Tokens
        return if (limit == ProactiveBudget.Limit.Calls) TriageStatus.CallsUsedUp else TriageStatus.TokensUsedUp
    }

    /** Cancels the request in flight (lock, shutdown, provider change): that batch stays pending triage. */
    private fun stopRequest() {
        val job = inFlight ?: return
        inFlight = null
        job.cancel()
        planner.abort()
        log("triage request cancelled")
        slot.release(this)
        slot.wakeOthers(this)
    }

    private fun stopAndPublish(status: TriageStatus) {
        stopRequest()
        publish(status)
    }

    private fun publish(status: TriageStatus) {
        mutable.value = TriageState(status, planner.marks(), planner.counts())
    }
}
