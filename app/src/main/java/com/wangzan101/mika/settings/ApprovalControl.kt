package com.wangzan101.mika.settings

import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.safety.gate.ConfirmationGate

/**
 * A task's own approval settings: whether it is autonomous is taken from the global setting when it starts, and afterwards follows the developer options' "autonomous mode" ([set]).
 * In ask mode only payments are asked about first; in autonomous mode even payments are not asked about.
 */
class RunApproval(startAutonomous: Boolean) : ApprovalSettings {
    /** It was autonomous when it started: this task has no confirmation gate and is not offered request_confirmation; turning autonomy off midway is left to the next task. */
    val startedAutonomous: Boolean = startAutonomous
    @Volatile private var auto = startAutonomous

    override fun autonomous(): Boolean = auto

    fun set(autonomous: Boolean) {
        auto = autonomous
    }

    /** The payment confirmation gate used by this task (and its subtasks): it exists only if the task was in ask mode when it started, and each is a new instance. */
    fun gate(): ConfirmationGate? = if (startedAutonomous) null else ConfirmationGate()
}

/**
 * Ask / autonomous is a single global switch, stored in [ApprovalStore.autonomous], defaulting to ask. Each task takes its own copy when it starts ([start]);
 * flipping the switch ([setAutonomous]) stores it for later tasks, and running tasks switch with it (going from ask to autonomous, payments stop being asked about from the next step on).
 */
class ApprovalControl(private val store: ApprovalStore) {
    private val runs = mutableSetOf<RunApproval>()

    /**
     * Tasks with [alwaysAsk] true ignore the switch: they always start in ask mode and do not follow the switch either. There are two kinds:
     * - Tasks submitted by a computer over MCP: the payment step is never performed in such tasks (the approval decision always rejects anything not initiated on the phone), and the remote side has no way to get it allowed.
     * - Tasks whose conversation contains notification alerts (see [com.wangzan101.mika.core.agent.NoticeTurn]): the conversation carries text written by someone else, so payments still ask the user first.
     */
    @Synchronized fun start(alwaysAsk: Boolean = false): RunApproval =
        if (alwaysAsk) RunApproval(false) else RunApproval(store.autonomous.value).also { runs += it }

    @Synchronized fun end(run: RunApproval) {
        runs -= run
    }

    @Synchronized fun setAutonomous(on: Boolean) {
        store.setAutonomous(on)
        runs.forEach { it.set(on) }
    }
}
