package com.wangzan101.mika.voicecall

import com.wangzan101.mika.core.agent.Turn as ChatTurn
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase

/** Which run's "run details" to open when a task card on the call page is tapped; when that cannot be determined, fall back to opening the task's conversation. */
object VoiceCallTaskLink {
    /**
     * [liveRun] is the run this task is executing right now, and [savedTurns] are the turns stored in its conversation.
     * Queued tasks have not started, so the stored run is the previous one and must not be passed off as current; running or needing-you tasks only accept the live run;
     * finished ones accept the live run (just ended and not yet withdrawn), otherwise the most recently recorded run in the conversation.
     */
    fun runFor(phase: DeskTaskPhase, liveRun: String?, savedTurns: () -> List<ChatTurn>): String? = when (phase) {
        DeskTaskPhase.Queued -> null
        DeskTaskPhase.Running, DeskTaskPhase.NeedsYou -> liveRun
        DeskTaskPhase.Done, DeskTaskPhase.Failed, DeskTaskPhase.Cancelled ->
            liveRun ?: savedTurns().lastOrNull { it.runId != null }?.runId
    }
}
