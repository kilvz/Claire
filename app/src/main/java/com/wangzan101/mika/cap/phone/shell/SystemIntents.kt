package com.wangzan101.mika.cap.phone.shell

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.wangzan101.mika.core.screen.uistate.SystemAction

/**
 * Standard Android intents for system actions: set an alarm, set a timer, open the alarm list (AlarmClock), open a
 * pre-filled new calendar event (the CalendarContract insert page). Actions are first expressed as an Android-free
 * [Spec] (compared directly in unit tests) and then turned into an [Intent]. The built-in helper allows only these
 * ([allowed]) and never launches an arbitrary intent on Claire's behalf.
 */
object SystemIntents {
    /** The event insert page is matched by type: state the type explicitly instead of asking a calendar provider (querying it requires calendar visibility). */
    const val EVENTS_TYPE = "vnd.android.cursor.dir/event"
    const val EVENTS_URI = "content://${CalendarContract.AUTHORITY}/events"

    /** Claire's compatibility self-check page: when the built-in helper recognizes this component it opens it on Claire's display as shell, bypassing [allowed]. */
    const val OWN_PAGE_CLASS = "com.wangzan101.mika.selfcheck.SelfCheckTargetActivity"

    /** One page that can handle this intent; [system] means the app ships with the system. */
    data class Handler(val packageName: String, val className: String, val system: Boolean)

    /**
     * Which one to hand it to: if there is only one, that one; if the user set a default ([defaultPackage], the
     * package the system resolves as default, not the chooser), use the default; otherwise prefer a system app, then
     * take the first. Target that page directly: an action set silently should not leave an "open with which?"
     * chooser on Claire's display. Null when there is none (unsupported; the model falls back to operating the UI).
     */
    fun choose(handlers: List<Handler>, defaultPackage: String?): Handler? =
        handlers.singleOrNull()
            ?: handlers.firstOrNull { it.packageName == defaultPackage }
            ?: handlers.firstOrNull { it.system }
            ?: handlers.firstOrNull()

    /** The contents of one intent: action, data and type, and extras (only Int, Long, Boolean, String and integer lists). */
    data class Spec(val action: String, val data: String? = null, val type: String? = null, val extras: Map<String, Any> = emptyMap())

    fun spec(a: SystemAction): Spec = when (a) {
        is SystemAction.SetAlarm -> Spec(AlarmClock.ACTION_SET_ALARM, extras = buildMap {
            put(AlarmClock.EXTRA_HOUR, a.hour)
            put(AlarmClock.EXTRA_MINUTES, a.minute)
            // Do not open the clock UI: set it directly
            put(AlarmClock.EXTRA_SKIP_UI, true)
            a.label?.let { put(AlarmClock.EXTRA_MESSAGE, it) }
            if (a.days.isNotEmpty()) put(AlarmClock.EXTRA_DAYS, ArrayList(a.days))
            a.vibrate?.let { put(AlarmClock.EXTRA_VIBRATE, it) }
        })
        is SystemAction.SetTimer -> Spec(AlarmClock.ACTION_SET_TIMER, extras = buildMap {
            put(AlarmClock.EXTRA_LENGTH, a.seconds)
            put(AlarmClock.EXTRA_SKIP_UI, true)
            a.label?.let { put(AlarmClock.EXTRA_MESSAGE, it) }
        })
        SystemAction.ShowAlarms -> Spec(AlarmClock.ACTION_SHOW_ALARMS)
        is SystemAction.AddCalendarEvent -> Spec(Intent.ACTION_INSERT, EVENTS_URI, EVENTS_TYPE, buildMap {
            put(CalendarContract.Events.TITLE, a.title)
            put(CalendarContract.EXTRA_EVENT_BEGIN_TIME, a.beginMs)
            a.endMs?.let { put(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            if (a.allDay) put(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            a.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            a.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        })
    }

    /** The built-in helper launches only these on Claire's behalf: the action must be in the list, and an insert may only be a calendar event. */
    fun allowed(action: String?, data: String?): Boolean = when (action) {
        AlarmClock.ACTION_SET_ALARM, AlarmClock.ACTION_SET_TIMER, AlarmClock.ACTION_SHOW_ALARMS -> data == null
        Intent.ACTION_INSERT -> data == EVENTS_URI
        else -> false
    }

    fun toIntent(s: Spec): Intent {
        val i = Intent(s.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        when {
            s.data != null && s.type != null -> i.setDataAndType(Uri.parse(s.data), s.type)
            s.data != null -> i.data = Uri.parse(s.data)
            s.type != null -> i.type = s.type
        }
        for ((k, v) in s.extras) when (v) {
            is Int -> i.putExtra(k, v)
            is Long -> i.putExtra(k, v)
            is Boolean -> i.putExtra(k, v)
            is String -> i.putExtra(k, v)
            is ArrayList<*> -> i.putIntegerArrayListExtra(k, ArrayList(v.filterIsInstance<Int>()))
            else -> error("unsupported extra $k")
        }
        return i
    }
}
