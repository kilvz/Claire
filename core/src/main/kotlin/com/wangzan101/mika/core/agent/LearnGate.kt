package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.user.UserScreenMessage

/**
 * Whether a run can count as "accomplished" for learning: drafting personal skills and App knowledge, recording
 * experience routes and their success counts, and promoting trial skills and App knowledge to permanent all ask this
 * one place.
 *
 * Brings in the outcome judged after the answer was delivered ([OutcomeJudge]), not the final review: the answer was
 * delivered ([TaskState.Succeeded]), the outcome is [RunOutcome.Done], and no value remained unmatched after being
 * sent back once; if the review ran ([TaskState.Succeeded.reviewed]) it must also have passed — having run it and
 * failed still blocks, not having run it does not.
 */
object LearnGate {
    fun done(end: TaskState, outcome: RunOutcome): Boolean =
        end is TaskState.Succeeded && outcome == RunOutcome.Done && end.unconfirmed.isEmpty() && !end.doubtful

    /**
     * Whether this run is recorded as an experience route: routes are enabled, the run is accomplished ([done]),
     * and there is a run ID (routes are taken from persisted events).
     * Goals that carry the user's screen are not recorded: their meaning is on the screen, so a route recorded
     * literally would not match next time.
     */
    fun shouldSaveRoute(routesOn: Boolean, end: TaskState, outcome: RunOutcome, runId: String?, goal: String): Boolean =
        routesOn && done(end, outcome) && runId != null && !UserScreenMessage.has(goal)
}
