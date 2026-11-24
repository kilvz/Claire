package com.wangzan101.mika.browser

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskGate
import com.wangzan101.mika.task.TaskService.Companion.isActive

/** What the bottom of the browser page currently shows: the usual input bar, the half-screen conversation panel, or the collapsed small card. */
enum class PageAskMode { Bar, Panel, Card }

/**
 * How the ask-Claire block on the browser page is displayed (pure logic). [state] is the state of the current task in this page's conversation (null when there is none),
 * [open] is whether the panel is open (it opens when the user asks, taps the small card, or Claire needs your reply; it collapses when the user collapses it or when you start interacting with the page).
 * - If the panel is open, it is the panel;
 * - If collapsed and that task is not finished yet: a small card at the bottom (saying what it is doing right now); when paused because you are interacting with the page, the card says "paused" and a banner at the top says "You are interacting, Claire is paused · Resume";
 * - Otherwise (no task, or already finished and collapsed again): the usual input bar.
 */
object PageAskModel {
    fun mode(state: TaskState?, open: Boolean): PageAskMode = when {
        open -> PageAskMode.Panel
        state != null && state.isActive() -> PageAskMode.Card
        else -> PageAskMode.Bar
    }

    /** You are interacting with the page yourself, and Claire is stopped waiting for you to tap "Resume". */
    fun pausedForUser(state: TaskState?): Boolean =
        state is TaskState.Held && state.reason.code == "paused" && state.reason.why == TaskGate.USER_ON_PAGE

    /** A glowing border around the page: Claire is working (not waiting for you and not paused). */
    fun glow(state: TaskState?): Boolean = state is TaskState.Running

    /** Claire needs your reply (an answer, approval, takeover, or a nudge): the panel opens by itself when this state is entered from another state. */
    fun needsYou(state: TaskState?): Boolean = when (state) {
        is TaskState.WaitingInput, is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> true
        is TaskState.Held -> state.reason.needsYou
        else -> false
    }

    /**
     * How the panel should change when the state goes from [before] to [after]: true opens it, false collapses it, null leaves it alone.
     * It collapses into the small card when paused because you are interacting with the page (getting out of the page's way); it opens when Claire newly needs your reply.
     */
    fun follow(before: TaskState?, after: TaskState?): Boolean? = when {
        pausedForUser(after) && !pausedForUser(before) -> false
        needsYou(after) && !needsYou(before) -> true
        else -> null
    }
}
