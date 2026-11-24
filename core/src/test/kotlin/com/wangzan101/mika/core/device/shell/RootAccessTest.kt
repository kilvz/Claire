package com.wangzan101.mika.core.device.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAccessTest {
    /** Looks in PATH first, then in the usual places; null when none is found. */
    @Test
    fun findsSuOnPathThenInUsualPlaces() {
        assertEquals("/product/bin/su", SuProbe.find("/product/bin:/system/bin") { it == "/product/bin/su" || it == "/system/bin/su" })
        assertEquals("/system/xbin/su", SuProbe.find(null) { it == "/system/xbin/su" })
        assertEquals("/debug_ramdisk/su", SuProbe.find("", setOf("/debug_ramdisk/su")::contains))
        assertNull(SuProbe.find("/system/bin:/vendor/bin") { false })
        // Relative entries and empty segments in PATH do not count; a trailing slash is stripped
        assertEquals("/system/bin/su", SuProbe.find("bin::/system/bin/") { it == "bin/su" || it == "/system/bin/su" })
    }

    @Test
    fun readsTheUidFromIdOutput() {
        assertEquals(0, SuProbe.uid("uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0"))
        assertEquals(2000, SuProbe.uid("uid=2000(shell) gid=2000(shell)"))
        assertEquals(2000, SuProbe.uid("2000\n"))
        assertEquals(0, SuProbe.uid("some banner\n0\n"))
        assertNull(SuProbe.uid(""))
        assertNull(SuProbe.uid("Permission denied"))
    }

    /** Answer: a matching uid means granted; a different uid means the switch failed; when no uid was read, check whether the deadline was reached. */
    @Test
    fun answerFromOutputAndTimeout() {
        assertEquals(SuAnswer.Granted, SuProbe.answer("uid=0(root)", timedOut = false, expectUid = 0))
        assertEquals(SuAnswer.Granted, SuProbe.answer("2000", timedOut = false, expectUid = 2000))
        assertEquals(SuAnswer.WrongUid, SuProbe.answer("0", timedOut = false, expectUid = 2000))
        assertEquals(SuAnswer.Denied, SuProbe.answer("Permission denied", timedOut = false, expectUid = 0))
        assertEquals(SuAnswer.Denied, SuProbe.answer("", timedOut = false, expectUid = 0))
        assertEquals(SuAnswer.NoAnswer, SuProbe.answer("", timedOut = true, expectUid = 0))
    }

    /** When running as shell, the user argument precedes -c; the command stays one argument and is not split. */
    @Test
    fun suArguments() {
        assertEquals(listOf("/system/bin/su", "-c", "ls -l /data"), SuProbe.rootArgs("/system/bin/su", "ls -l /data"))
        assertEquals(listOf("/sbin/su", "2000", "-c", "id -u; read n"), SuProbe.shellArgs("/sbin/su", "id -u; read n"))
    }

    /** User request, whether su exists, explicit grant result → whether the helper may be started automatically with root; without an answer su is never executed automatically. */
    @Test
    fun rootStartGate() {
        assertEquals(RootStart.Off, RootStart.of(enabled = false, suPresent = true, last = SuAnswer.Granted))
        assertEquals(RootStart.Off, RootStart.of(enabled = true, suPresent = true, last = null))
        assertEquals(RootStart.Off, RootStart.of(enabled = true, suPresent = false, last = null))
        assertEquals(RootStart.Ready, RootStart.of(enabled = true, suPresent = true, last = SuAnswer.Granted))
        assertEquals(RootStart.Gone, RootStart.of(enabled = true, suPresent = false, last = SuAnswer.Granted))
        assertEquals(RootStart.Denied, RootStart.of(enabled = true, suPresent = true, last = SuAnswer.Denied))
        assertEquals(RootStart.NoAnswer, RootStart.of(enabled = true, suPresent = true, last = SuAnswer.NoAnswer))
        assertEquals(RootStart.NoShellUid, RootStart.of(enabled = true, suPresent = true, last = SuAnswer.WrongUid))
        assertNull(RootStart.Off.failure)
        assertNull(RootStart.Ready.failure)
        assertEquals(BuiltinShellFailure.RootDenied, RootStart.Denied.failure)
        assertEquals(BuiltinShellFailure.RootUnavailable, RootStart.Gone.failure)
        // Denial or silence may be retried; a missing su or a failed uid switch needs another approach
        assertEquals(BuiltinShellFix.Retry, BuiltinShellFailure.RootDenied.defaultFix)
        assertEquals(BuiltinShellFix.Retry, BuiltinShellFailure.RootNoAnswer.defaultFix)
        assertEquals(BuiltinShellFix.RePair, BuiltinShellFailure.RootUnavailable.defaultFix)
        assertTrue(!BuiltinShellFailures.retryable(BuiltinShellFailure.RootDenied))
    }

    @Test
    fun grantStateFollowsTheAnswer() {
        assertEquals(RootGrant.Unknown, RootGrant.of(null))
        assertEquals(RootGrant.Granted, RootGrant.of(SuAnswer.Granted))
        assertEquals(RootGrant.Denied, RootGrant.of(SuAnswer.Denied))
        assertEquals(RootGrant.NoAnswer, RootGrant.of(SuAnswer.NoAnswer))
        assertEquals(RootGrant.NoShellUid, RootGrant.of(SuAnswer.WrongUid))
    }

    /** Command that starts the helper via su: print the uid first, then exactly as starting over wireless debugging (the nonce is not on the command line). */
    @Test
    fun rootCommandPrintsTheUidFirstAndKeepsTheNonceOffTheCommandLine() {
        val c = HelperHandoff.rootCommand("/data/app/x/base.apk", "com.wangzan101.mika", 10123, "ab")
        assertTrue(c.startsWith("id -u; read n; "))
        assertEquals(c, "id -u; " + HelperHandoff.command("/data/app/x/base.apk", "com.wangzan101.mika", 10123, "ab"))
    }
}
