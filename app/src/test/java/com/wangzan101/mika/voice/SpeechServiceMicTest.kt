package com.wangzan101.mika.voice

import android.Manifest
import android.content.pm.PackageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the recognition service reports a missing permission, whose permission is missing: only blame the recognition service when all three facts are confirmed. */
class SpeechServiceMicTest {
    private val mic = Manifest.permission.RECORD_AUDIO
    private val granted = PackageInfo.REQUESTED_PERMISSION_GRANTED

    // Requested but not granted, requested and granted, not requested, not fully readable: all distinguishable
    @Test fun readsTheRecordPermissionOfAnotherApp() {
        assertEquals(ServiceMic.Missing, SpeechServiceMic.of(arrayOf("a.b.OTHER", mic), intArrayOf(granted, 0)))
        assertEquals(ServiceMic.Granted, SpeechServiceMic.of(arrayOf("a.b.OTHER", mic), intArrayOf(0, granted)))
        // The flags carry other bits too (such as "never ask again"); only the "granted or not" bit counts
        assertEquals(ServiceMic.Granted, SpeechServiceMic.of(arrayOf(mic), intArrayOf(granted or 1)))
        assertEquals(ServiceMic.Missing, SpeechServiceMic.of(arrayOf(mic), intArrayOf(1)))
        assertEquals(ServiceMic.NotRequested, SpeechServiceMic.of(arrayOf("a.b.OTHER"), intArrayOf(granted)))
        assertEquals(ServiceMic.NotRequested, SpeechServiceMic.of(null, null))
        assertEquals(ServiceMic.NotRequested, SpeechServiceMic.of(emptyArray(), intArrayOf()))
        // Requested, but the flags cannot be read or do not line up: draw no conclusion
        assertEquals(ServiceMic.Unknown, SpeechServiceMic.of(arrayOf(mic), null))
        assertEquals(ServiceMic.Unknown, SpeechServiceMic.of(arrayOf("a.b.OTHER", mic), intArrayOf(granted)))
    }

    // Which service is in use: the named one if one is named; otherwise the system default; on-device recognition is not identifiable. Never guess from "the first registered one"
    @Test fun theServiceInUseIsTheNamedOneOrTheSystemDefaultNeverAGuess() {
        assertEquals("a.named", SpeechServiceMic.used("a.named", onDevice = false) { "a.default" })
        assertEquals("a.default", SpeechServiceMic.used(null, onDevice = false) { "a.default" })
        assertNull(SpeechServiceMic.used(null, onDevice = false) { null })
        assertNull(SpeechServiceMic.used(null, onDevice = true) { "a.default" })
        assertNull(SpeechServiceMic.used("a.named", onDevice = true) { "a.default" })
    }

    // Only blame a missing permission on the recognition service when all three facts are confirmed; if any one is uncertain, say nothing
    @Test fun onlyAConfirmedMissingPermissionNamesTheService() {
        val asked = mutableListOf<String>()
        fun missing(self: Boolean, pkg: String?, state: ServiceMic) =
            SpeechServiceMic.missing(self, pkg, { asked += it; state }, { "名字 of $it" })
        assertEquals(SpeechServiceTarget("a.svc", "名字 of a.svc"), missing(true, "a.svc", ServiceMic.Missing))
        // It has the permission, never requested it, or cannot be found: keep the original wording
        assertNull(missing(true, "a.svc", ServiceMic.Granted))
        assertNull(missing(true, "a.svc", ServiceMic.NotRequested))
        assertNull(missing(true, "a.svc", ServiceMic.Unknown))
        // Cannot tell which service it is: do not look it up, do not report
        asked.clear()
        assertNull(missing(true, null, ServiceMic.Missing))
        assertNull(missing(true, " ", ServiceMic.Missing))
        // Claire itself lacks the recording permission: fix our own first, do not point at others
        assertNull(missing(false, "a.svc", ServiceMic.Missing))
        assertEquals(emptyList<String>(), asked)
    }

    // The label is chosen by the other app: keep it on a single line and cap its length
    @Test fun theLabelIsFlattenedAndCapped() {
        assertEquals("系统 语音 引擎", SpeechServiceMic.clean("  系统\n语音\t 引擎 "))
        assertEquals(40, SpeechServiceMic.clean("长".repeat(200)).length)
        assertEquals("", SpeechServiceMic.clean(" \n "))
    }
}
