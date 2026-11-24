package com.wangzan101.mika.voice.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Too much text while holding to speak: drop whole finished sentences from the front so the newest characters stay visible. This measures "does it fit" by character count; the UI measures lines from real layout. */
class VoiceCopyFitTest {
    private fun fits(max: Int): (String, String) -> Boolean = { s, t -> (s + t).length <= max }

    @Test fun shortTextIsUntouched() {
        assertEquals("你好。" to "打开", VoiceCopy.fitHeard("你好。", "打开", fits(20)))
        assertEquals("" to "", VoiceCopy.fitHeard("", "", fits(0)))
    }

    @Test fun finishedSentencesAreDroppedFromTheFrontOneByOne() {
        val stable = "第一句话。第二句话！第三句话？"
        // Room for the last two sentences plus the one still being spoken
        assertEquals("…第二句话！第三句话？" to "第四", VoiceCopy.fitHeard(stable, "第四", fits(13)))
        // Room for the last sentence only
        assertEquals("…第三句话？" to "第四", VoiceCopy.fitHeard(stable, "第四", fits(9)))
        // None of the finished sentences fit: keep only the one still being spoken
        assertEquals("…" to "第四句话", VoiceCopy.fitHeard(stable, "第四句话", fits(6)))
    }

    // The sentence still being spoken is itself long: keep its end
    @Test fun aLongCurrentSentenceKeepsItsEnd() {
        assertEquals("…" to "五六七八", VoiceCopy.fitHeard("说完了。", "一二三四五六七八", fits(5)))
        assertEquals("…" to "", VoiceCopy.fitHeard("说完了。", "一二三", fits(1)))
    }

    // With no full stop to cut at (a whole run not yet split into sentences), keep the end as is
    @Test fun noSentenceBreakFallsBackToTheEnd() {
        assertEquals("…" to "丁戊", VoiceCopy.fitHeard("甲乙丙，", "丁戊", fits(3)))
    }

    @Test fun latinSentencesBreakAfterPeriods() {
        assertEquals("…Second one." to " third", VoiceCopy.fitHeard("First one. Second one.", " third", fits(20)))
    }
}
