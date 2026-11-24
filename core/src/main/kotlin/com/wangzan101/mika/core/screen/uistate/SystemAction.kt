package com.wangzan101.mika.core.screen.uistate

/**
 * System actions done through standard Android interfaces (intents): setting alarms and timers, opening the alarm
 * list, opening a pre-filled new calendar event.
 * They are handled by the clock and calendar Apps installed on the phone; when no App accepts one, the port reports
 * [SystemActionResult.NOT_SUPPORTED] and the model falls back to operating the interface.
 */
sealed interface SystemAction {
    /** [days]: the weekdays it repeats on, in java.util.Calendar notation (1 = Sunday … 7 = Saturday), empty = rings once. */
    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null, val days: List<Int> = emptyList(), val vibrate: Boolean? = null) : SystemAction
    data class SetTimer(val seconds: Int, val label: String? = null) : SystemAction
    data object ShowAlarms : SystemAction
    /** Start and end are millisecond timestamps; when [allDay], only the dates matter. */
    data class AddCalendarEvent(
        val title: String, val beginMs: Long, val endMs: Long? = null, val allDay: Boolean = false,
        val location: String? = null, val description: String? = null,
    ) : SystemAction

    /** After being handed to the App, no interface is left behind (alarms, timers); the rest open that App's page on Claire's screen. */
    val silent: Boolean get() = this is SetAlarm || this is SetTimer
}

/**
 * Result of a system action. On failure, [result] gives the reason as [NOT_SUPPORTED] (no App accepts the action),
 * app_in_use_by_user, launch_failed and the like;
 * [handledBy] is the package name of the App that handled it; [nextAlarmBefore] / [nextAlarmAfter] are the next alarm
 * the system reports before and after the action (millisecond timestamps, null if none), and cannot be read when [alarmReadable] is false; [showing]: after the action, [handledBy]'s page is open on Claire's screen
 * (it should have been set silently, but the App opened its interface).
 */
data class SystemActionResult(
    val result: ActionResult,
    val handledBy: String? = null,
    val nextAlarmBefore: Long? = null,
    val nextAlarmAfter: Long? = null,
    val alarmReadable: Boolean = false,
    val showing: Boolean = false,
    /** After calendar events are written directly in bulk, the event URIs the calendar provider returns one by one. */
    val eventUris: List<String> = emptyList(),
) {
    companion object {
        const val NOT_SUPPORTED = "not_supported"
    }
}
