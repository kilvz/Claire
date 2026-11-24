package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.general.NOTICES_READ
import com.wangzan101.mika.core.learn.memory.TaskMemory
import com.wangzan101.mika.core.learn.memory.TaskMemoryRecorder
import com.wangzan101.mika.core.run.record.RunEvent

/**
 * Whether a run read notifications, and if so what the cross-session summary keeps. Pure functions.
 *
 * "Read" means notification originals reached the model: this run called notices_read, or this conversation has the notification-hint turn
 * ([com.wangzan101.mika.core.agent.NoticeTurn], where the hint originals are already in context). Merely carrying the "from notifications" digest
 * ([ObservedContext]) at the start of a run does not count — that would make almost every task count outside the conservative tier, which would effectively turn summaries off.
 */
object NoticeRuns {
    /** [noticeTurnInContext]: this conversation has the notification-hint turn. */
    fun readNotices(events: List<RunEvent>, noticeTurnInContext: Boolean): Boolean =
        noticeTurnInContext || events.any { it is RunEvent.Activity && it.label == NOTICES_READ }

    /**
     * What this run's cross-session summary (the one [TaskMemoryRecorder.record] writes) should be stored as: when notifications were read, the conclusion keeps only the outcome word,
     * not the text of the answer or the reason — the answer mostly paraphrases notification content, and later tasks can search summaries without being able to tell it came from notifications.
     * The goal (the user's own words) and the steps stay as they are: the read-notifications step records no object and produces none of the original text.
     */
    fun summary(task: TaskMemory, events: List<RunEvent>, noticeTurnInContext: Boolean): TaskMemory =
        if (readNotices(events, noticeTurnInContext)) task.copy(conclusion = TaskMemoryRecorder.outcome(events)) else task
}
