package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Occupancy on the main display, occupancy on Claire's other displays, and the task looked at on cold start: an app installed as two copies is told apart by "package name + user"; a single copy works as before. */
class SameCopyTest {
    private val chat = "com.example.chat"

    @Test fun theUserOnTheMainCopyDoesNotBlockTheCloneAndViceVersa() {
        // The copy on the main display is user 0's
        assertTrue(ShellService.sameCopy(listOf(chat), 0, chat, 0))
        assertFalse(ShellService.sameCopy(listOf(chat), 0, chat, 128))
        // The copy on the main display is the cloned one
        assertTrue(ShellService.sameCopy(listOf(chat), 128, chat, 128))
        assertFalse(ShellService.sameCopy(listOf(chat), 128, chat, 0))
    }

    @Test fun unknownTaskUserCountsAsTheSameCopy() {
        // Cannot read which user the task belongs to: treat it as occupying rather than not
        assertTrue(ShellService.sameCopy(listOf(chat), null, chat, 128))
        assertTrue(ShellService.sameCopy(listOf(chat), null, chat, 0))
    }

    @Test fun singleCopyAndPackageOnlyChecksAreUnchanged() {
        // Only one copy installed: tasks are all in user 0, so match by package name as before
        assertTrue(ShellService.sameCopy(listOf("x.launcher", chat), 0, chat, 0))
        assertFalse(ShellService.sameCopy(listOf("x.other"), 0, chat, 0))
        // User not distinguished (sharing): either copy counts
        assertTrue(ShellService.sameCopy(listOf(chat), 128, chat, null))
        assertTrue(ShellService.sameCopy(listOf(chat), 0, chat, null))
    }
}
