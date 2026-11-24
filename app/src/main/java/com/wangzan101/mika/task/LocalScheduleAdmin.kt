package com.wangzan101.mika.task

import android.content.Context
import com.wangzan101.mika.core.trigger.ScheduleAdmin
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.EditedSchedule

class LocalScheduleAdmin internal constructor(
    private val plans: () -> List<TriggerDefinition>,
    private val changeEnabled: (String, Boolean) -> Boolean,
    private val delete: (String) -> Unit,
    private val changeTime: (String, Long) -> Unit,
    private val executeNow: (String) -> Unit,
    private val editPlan: (String, String, (TriggerDefinition) -> TriggerDefinition) -> EditedSchedule? = { _, _, _ -> null },
) : ScheduleAdmin {
    constructor(context: Context) : this(
        { TriggerRuntime.list(context) },
        { id, enabled -> TriggerRuntime.setEnabled(context, id, enabled) },
        { id -> TriggerRuntime.remove(context, id) },
        { id, atMs -> TriggerRuntime.reschedule(context, id, atMs) },
        { id -> TriggerRuntime.runNow(context, id) },
        { thread, id, change -> TriggerRuntime.edit(context, thread, id, change) },
    )

    // Schedules are visible globally per device and are not filtered by conversation: schedules created manually in the drawer each hang off a newly created conversation, so filtering by conversation would stop the AI from listing them in other conversations (#478)
    override fun list(threadId: String): List<TriggerDefinition> = plans()

    private fun owned(threadId: String, id: String): Boolean = list(threadId).any { it.id == id }

    override fun setEnabled(threadId: String, id: String, enabled: Boolean): Boolean {
        if (!owned(threadId, id)) return false
        return changeEnabled(id, enabled)
    }

    override fun remove(threadId: String, id: String): Boolean {
        if (!owned(threadId, id)) return false
        delete(id)
        return true
    }

    override fun reschedule(threadId: String, id: String, atMs: Long): Boolean {
        if (!owned(threadId, id)) return false
        changeTime(id, atMs)
        return true
    }

    override fun runNow(threadId: String, id: String): Boolean {
        if (!owned(threadId, id)) return false
        executeNow(id)
        return true
    }

    override fun edit(threadId: String, id: String, change: (TriggerDefinition) -> TriggerDefinition): EditedSchedule? {
        // Editing must use the conversation the schedule itself belongs to (now that schedules are global, the current conversation's threadId is no longer used to match)
        val own = plans().firstOrNull { it.id == id }?.threadId ?: return null
        return editPlan(own, id, change)
    }
}
