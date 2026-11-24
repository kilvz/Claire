package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.device.access.AppAccessState
import com.wangzan101.mika.core.proactive.NoticeDecision
import com.wangzan101.mika.core.proactive.NoticeFilter
import com.wangzan101.mika.core.proactive.NoticeInbox
import com.wangzan101.mika.core.proactive.NoticeRecord

/**
 * What the "App access" setting looks like right now: [known] is whether it can be read at all; when it can be read,
 * [blocked] is the set of disabled package names.
 * Being unable to read it does not mean nothing is blocked: at that point it is unknown which apps must not be touched,
 * so proactive capabilities halt entirely (no reading, no sending, not carried into tasks) and whatever has accumulated
 * stays put.
 */
data class AppGate(val known: Boolean, val blocked: Set<String>) {
    companion object {
        /** Built from what "App access" has stored: carry along whether it could be read at all (carrying only the blocked set would treat unreadable as nothing blocked). */
        fun of(state: AppAccessState) = AppGate(state.available, state.blocked)
    }
}

/** How apps disabled in "App access" appear to proactive capabilities: not summarized; after being disabled, already-received ones are removed too. All of these are called on the single serial coroutine in ProactiveRuntime. */
internal object BlockedApps {
    /** Whether this app's notifications can be read right now (the package-name gate, before reading content): when "App access" cannot be read, nothing is read. */
    fun readable(pkg: String, allowed: Set<String>, gate: AppGate): Boolean =
        gate.known && NoticeFilter.decidePackage(pkg, allowed, gate.blocked) == NoticeDecision.Keep

    /** Whether this notification is accepted right now (checked again after cleaning and parsing); when "App access" cannot be read, none are accepted. */
    fun admits(r: NoticeRecord, allowed: Set<String>, gate: AppGate): Boolean =
        gate.known && NoticeFilter.decide(r, allowed, gate.blocked) == NoticeDecision.Keep

    /**
     * Whether this app can be summarized right now: it is checked, not marked "don't learn from it", not disabled in
     * "App access", and not Claire itself.
     * "App access" being unreadable is not handled here ([blocked] is empty then): the learning side uses this to clear
     * events awaiting summarization, and when it is unreadable things should just pause, not be cleared.
     */
    fun learnEligible(pkg: String, allowed: Set<String>, optOut: Set<String>, blocked: Set<String>): Boolean =
        pkg in allowed && pkg !in optOut && pkg !in blocked && !NoticeFilter.isSelf(pkg)

    /**
     * The set of disabled apps changed ([blocked] is the whole current set): their records are removed from the inbox
     * (decision results and reminders follow the inbox),
     * in-flight decision requests for them are cancelled, events awaiting summarization are removed and in-flight
     * summarization requests are cancelled (the learning side relies on [learnEligible]),
     * and their stored notification bodies and summarized sentences are removed as well.
     */
    fun purge(blocked: Set<String>, inbox: NoticeInbox, triage: TriageRunner, learn: LearnRunner) {
        inbox.removePackages { it !in blocked }
        triage.dropApps { it !in blocked }
        learn.forgetApps(blocked)
    }

    /**
     * "App access" changed ([gate] is the current state): if it can be read, purge according to the blocked set ([purge]);
     * if it cannot be read, delete nothing (we do not know which apps are blocked).
     * In both cases make decisions and summarization look again: when unreadable they stop their in-flight requests and
     * wait; once readable they resume — purge first, then look, so blocked ones will not be sent out again.
     */
    fun accessChanged(gate: AppGate, inbox: NoticeInbox, triage: TriageRunner, learn: LearnRunner) {
        if (gate.known) purge(gate.blocked, inbox, triage, learn) else learn.poke()
        triage.poke()
    }
}
