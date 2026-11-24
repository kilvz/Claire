package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.cap.phone.shell.ShellClip.Now
import com.wangzan101.mika.cap.phone.shell.ShellClip.Read
import com.wangzan101.mika.cap.phone.shell.ShellClip.Restore
import org.junit.Assert.assertEquals
import org.junit.Test

/** Swap the clipboard out for pasting input and then restore it: unreadable is never treated as empty, and a clipboard that is not Claire's is left alone. */
class ShellClipTest {
    @Test fun readDistinguishesEmptyFromUnreadable() {
        assertEquals(Read.Has, ShellClip.read(clipPresent = true, hasPrimaryClip = true, descriptionPresent = true))
        assertEquals(Read.Empty, ShellClip.read(clipPresent = false, hasPrimaryClip = false, descriptionPresent = false))
        // What we got is empty but the system says there is content: unreadable, not empty
        assertEquals(Read.Unreadable, ShellClip.read(clipPresent = false, hasPrimaryClip = true, descriptionPresent = false))
        assertEquals(Read.Unreadable, ShellClip.read(clipPresent = false, hasPrimaryClip = false, descriptionPresent = true))
    }

    @Test fun restoreOnlyTouchesOursOrEmpty() {
        assertEquals(Restore.PutBack, ShellClip.restore(priorEmpty = false, now = Now.Ours))
        assertEquals(Restore.Clear, ShellClip.restore(priorEmpty = true, now = Now.Ours))
        // The user copied something else in the meantime, or it is unreadable now: leave it alone
        for (prior in listOf(true, false)) {
            assertEquals(Restore.Leave, ShellClip.restore(prior, Now.Other))
            assertEquals(Restore.Leave, ShellClip.restore(prior, Now.Unreadable))
        }
    }

    @Test fun clearedByOthersGetsUsersClipBack() {
        // Claire's content was cleared by someone else (e.g. the system's scheduled wipe): put the prior content back if there was any, and do nothing if it was empty
        assertEquals(Restore.PutBack, ShellClip.restore(priorEmpty = false, now = Now.Empty))
        assertEquals(Restore.Leave, ShellClip.restore(priorEmpty = true, now = Now.Empty))
    }
}
