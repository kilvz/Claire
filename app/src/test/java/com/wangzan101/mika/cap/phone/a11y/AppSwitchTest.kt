package com.wangzan101.mika.cap.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AppSwitchTest {
    private val pickers = setOf("com.android.permissioncontroller", "com.android.intentresolver", "com.android.documentsui")
    // Only pages provided by system settings count as part of the task within the target task; preinstalled browsers and app stores do not
    private val settings = setOf("com.android.settings")
    private fun check(current: String?, sameTask: Boolean, target: String? = "com.clock"): AppSwitch.Switch? {
        var asked = false
        val r = AppSwitch.switchedTo(target, current, sameTask = { asked = true; sameTask }, partOfTask = pickers, settingsPage = { it in settings })
        if (current == null || current == target || current in pickers) assertFalse("sameTask queried needlessly", asked)
        return r
    }

    @Test fun systemPageOpenedByTheTargetInItsOwnTaskIsPartOfTheTask() {
        // The clock's "Add city" opens a picker page provided by system settings, but it is in the clock's own task
        assertNull(check("com.android.settings", sameTask = true))
    }

    @Test fun thirdPartyAppInTheTargetsTaskIsStillReportedButAsOpenedByTarget() {
        assertEquals(AppSwitch.Switch("com.share", byTarget = true), check("com.share", sameTask = true))
    }

    @Test fun preinstalledAppInTheTargetsTaskIsStillReported() {
        assertEquals(AppSwitch.Switch("com.vendor.browser", byTarget = true), check("com.vendor.browser", sameTask = true))
    }

    @Test fun systemPickersAndPermissionDialogsArePartOfTheTask() {
        for (p in pickers) assertNull(p, check(p, sameTask = false))
    }

    @Test fun anotherAppInAnotherTaskIsASwitch() {
        assertEquals(AppSwitch.Switch("com.ads", byTarget = false), check("com.ads", sameTask = false))
        // A system app that is not in the target's task: still counted as switched away
        assertEquals(AppSwitch.Switch("com.android.settings", byTarget = false), check("com.android.settings", sameTask = false))
    }

    @Test fun nothingToCompare() {
        assertNull(check("com.ads", sameTask = false, target = null))
        assertNull(check(null, sameTask = false))
        assertNull(check("com.clock", sameTask = false))
    }

    @Test fun theOtherCopyOfTheTargetAppInFrontIsASwitch() {
        // The same app is installed twice: the top one became the copy that is not the target
        assertEquals(AppSwitch.Switch("com.clock", byTarget = false),
            AppSwitch.switchedTo("com.clock", "com.clock", { true }, pickers, { false }, otherCopy = { true }))
        // Still the target copy, or only one copy installed (the other is not queried): still not counted
        assertNull(AppSwitch.switchedTo("com.clock", "com.clock", { true }, pickers, { false }, otherCopy = { false }))
        assertNull(AppSwitch.switchedTo("com.clock", "com.clock", { true }, pickers, { false }))
    }
}
