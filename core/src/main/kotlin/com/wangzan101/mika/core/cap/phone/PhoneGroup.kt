package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.ScreenFamily
import com.wangzan101.mika.core.screen.uistate.tools.ScreenTool
import com.wangzan101.mika.core.tool.AgentTool

/** The phone capability's id, and also the prefix of the phone tool set's names. */
const val PHONE = "phone"

/** The snapshotId of a phone screen read starts with this (both the port's random IDs and stable IDs), so the ID alone tells you it belongs to the phone. */
const val PHONE_SNAPSHOT_PREFIX = "p"

/** The phone's screen tool set: experience routes only record practices done on the phone. */
internal val PHONE_FAMILY = ScreenFamily(PHONE, snapshotPrefix = PHONE_SNAPSHOT_PREFIX, routable = true, sandboxCallable = true, ownPrompts = setOf("observe", "back", "scroll", "type_text", "clear_text"))

/** Tools specific to the phone. */
internal interface PhoneTool : AgentTool {
    override val capability: String get() = PHONE
    override val sandboxCallable: Boolean get() = true
}

/**
 * The phone group: the screen tool set (read the screen, look at images, tap, swipe, type, press keys, diagnose the screen), plus listing and opening apps and links,
 * marking whether an app stays open or is closed when the task ends, setting alarms, timers and calendar events through the system's standard interfaces, and turning the wheel. In the order sent to the model.
 */
internal object PhoneGroup {
    val tools: List<AgentTool> = ScreenTool.of(PHONE_FAMILY) + listOf(
        PhoneQuery, PhoneFind, PhoneExtract, PhoneCollect, PhoneWait, PhoneEventTool,
        PhoneDialog, PhoneGesture, PhoneSmsLogin, SetWheel, PhoneRun, DoEach, PhoneCommand, ListApps, LaunchApp, AppAfterTask, ShareText, ShareAudio, ShareImage, OpenLink,
        SetAlarm, SetTimer, ShowAlarms, AddCalendarEvent, BulkAddCalendarEvents,
    )
}
