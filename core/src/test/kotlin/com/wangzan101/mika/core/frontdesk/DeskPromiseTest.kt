package com.wangzan101.mika.core.frontdesk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskPromiseTest {
    @Test fun catchesACommitmentLeftWithoutADispatch() {
        assertTrue(DeskPromise.pending("On it — I'll set \"Turn on Wi-Fi automatically\" to off so both agree."))
        assertTrue(DeskPromise.pending("Alright, I'll turn the hotspot on now."))
        assertTrue(DeskPromise.pending("I'm checking the hotspot settings now."))
        assertTrue(DeskPromise.pending("Let me look at the battery restrictions."))
        assertTrue(DeskPromise.pending("Sure, I will change that setting for you."))
        // Curly apostrophes are what some models emit
        assertTrue(DeskPromise.pending("I’ll fix that now."))
    }

    @Test fun catchesTheSameInIndonesianAndChinese() {
        assertTrue(DeskPromise.pending("Siap, saya akan mematikan Wi-Fi otomatis sekarang."))
        assertTrue(DeskPromise.pending("Baik, aku sedang memeriksa pengaturan hotspot."))
        assertTrue(DeskPromise.pending("Biar saya atur dulu."))
        assertTrue(DeskPromise.pending("好的，我来把设置改一下。"))
        assertTrue(DeskPromise.pending("我馬上檢查設定。"))
    }

    @Test fun leavesAnswersExplanationsAndOffersAlone() {
        assertFalse(DeskPromise.pending("Your Wi-Fi is on but not connected; the hotspot switch is off."))
        assertFalse(DeskPromise.pending("I checked all three and changed nothing."))
        assertFalse(DeskPromise.pending("Which phone is it, and does it die on a timer or randomly?"))
        assertFalse(DeskPromise.pending("I can check that for you if you want."))
        assertFalse(DeskPromise.pending("I'll let you know once your work reports back."))
        assertFalse(DeskPromise.pending("Wi-Fi sedang terhubung ke jaringan."))
        assertFalse(DeskPromise.pending("Wi-Fi 正在连接。"))
        assertFalse(DeskPromise.pending(""))
    }
}
