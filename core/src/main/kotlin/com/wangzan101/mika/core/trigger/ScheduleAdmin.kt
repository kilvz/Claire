package com.wangzan101.mika.core.trigger

/** Plan management for the local user; the host handles persistence and re-arming alarms. */
interface ScheduleAdmin {
    fun list(threadId: String): List<TriggerDefinition>
    fun setEnabled(threadId: String, id: String, enabled: Boolean): Boolean
    fun remove(threadId: String, id: String): Boolean
    fun reschedule(threadId: String, id: String, atMs: Long): Boolean
    fun runNow(threadId: String, id: String): Boolean
    /** Saves the same plan after a full edit; the host re-checks current ownership and keeps the latest execution ledger. */
    fun edit(threadId: String, id: String, change: (TriggerDefinition) -> TriggerDefinition): EditedSchedule? = null
}

data class EditedSchedule(val plan: TriggerDefinition, val scheduled: Scheduled)

/**
 * How things stand after a scheduled task is stored: [onTime] false means the system did not grant an exact alarm (it may fire late or not at all);
 * in that case [userNotified] says whether the prompt to enable it was actually sent to the user this time.
 */
data class Scheduled(val onTime: Boolean, val userNotified: Boolean = false)
