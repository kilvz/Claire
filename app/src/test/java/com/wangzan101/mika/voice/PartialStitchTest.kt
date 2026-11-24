package com.wangzan101.mika.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** How successive intermediate results are joined when the decoding window slides forward; samples marked "real" are shapes that have appeared in real service responses. */
class PartialStitchTest {
    private fun stitch(vararg parts: Triple<String, Long, Long>): PartialStitch =
        PartialStitch().also { s -> for ((text, start, duration) in parts) s.feed(text, start, duration) }

    private fun assertShown(carried: String, tail: String, s: PartialStitch) = assertEquals(carried to tail, s.carried to s.tail)

    @Test fun windowThatHasNotMovedReplacesTheWholeText() {
        val s = stitch(Triple("今天我们", 0, 1000), Triple("今天我们来聊一聊", 0, 2000))
        assertShown("", "今天我们来聊一聊", s)
    }

    // The first few characters of the new snapshot are rewritten (real: 「its even its success rate」 becomes 「his success rate」): it still aligns and no characters are repeated
    @Test fun rewrittenHeadStillAlignsWithoutRepeatingText() {
        val s = stitch(
            Triple("你刚刚讲到的手机，它的更它的成功率更低。我觉得今天", 0, 15000),
            Triple("他的成功率更低。我觉得今天能用到的", 5000, 20000),
        )
        assertShown("你刚刚讲到的手机，它的更它的成功率更低。我", "觉得今天能用到的", s)
    }

    // The head of the new snapshot sits at the very front of the window, with the earlier text cut off, and is mis-decoded (real: 「actually a different approach, the experience differs too」 becomes 「just a different approach, but it also differs」):
    // this stretch keeps what is already shown and switches only where the two snapshots agree
    @Test fun headOfTheNewWindowKeepsTheTextAlreadyShown() {
        val s = stitch(
            Triple("最近在用的几个工具，它们其实是不同的思路，体验也不一样，比如这个功能", 0, 15000),
            Triple("只是不同的思路体但也不一样，比如这个功能它可以直接操作", 4000, 19000),
        )
        assertShown("最近在用的几个工具，它们其实是不同的思路，体验", "也不一样，比如这个功能它可以直接操作", s)
    }

    // The overlap is too short to have anything left to compare once the head is carried over: replace the whole snapshot from the window start
    @Test fun shortOverlapSwitchesAtTheWindowStart() {
        val s = stitch(Triple("甲乙丙丁戊己庚辛", 0, 8000), Triple("丙丁戊己庚辛壬癸", 2000, 10000))
        assertShown("甲乙", "丙丁戊己庚辛壬癸", s)
    }

    // The new snapshot cannot be found in the text already shown (the recognition result changed entirely): estimate the window start from the time ratio
    @Test fun unalignedTextFallsBackToTime() {
        assertShown("甲乙", "子丑寅卯辰巳", stitch(Triple("甲乙丙丁戊己庚辛壬癸", 0, 10000), Triple("子丑寅卯辰巳", 2000, 12000)))
        // The previous snapshot has no duration to compare against: nothing is carried
        assertShown("", "子丑", stitch(Triple("甲乙", 0, 0), Triple("子丑", 1000, 1000)))
    }

    // Do not switch in the middle of an English word; the space stays in the first half
    @Test fun latinWordsAreNotSplit() {
        val s = stitch(
            Triple("比如说 computer use 这个功能可以直接操作电脑", 0, 15000),
            Triple("computer use 这个功能可以直接操作电脑界面，但是", 1500, 16500),
        )
        assertShown("比如说 computer ", "use 这个功能可以直接操作电脑界面，但是", s)
    }

    // Text where words are separated by spaces: switch between words, never splitting a word and never repeating one (it still aligns when the casing at the start of the new snapshot changes)
    @Test fun spaceSeparatedTextSwitchesBetweenWords() {
        val s = stitch(
            Triple("I have been using a few tools lately because they take quite different approaches", 0, 15000),
            Triple("Using a few tools lately because they take quite different approaches and the experience", 1000, 16000),
        )
        assertShown("I have been using a few ", "tools lately because they take quite different approaches and the experience", s)
    }

    // Punctuation, spaces and case play no part in alignment
    @Test fun punctuationAndCaseAreIgnoredWhenAligning() {
        val s = stitch(
            Triple("今天，我们来聊一聊 API 的用法和它的限制还有价格", 0, 15000),
            Triple("我们来聊一聊api的用法，和它的限制还有价格以及", 1000, 16000),
        )
        assertShown("今天，我们来聊一聊 API ", "的用法，和它的限制还有价格以及", s)
    }

    @Test fun emptyPartialChangesNothingAndResetStartsOver() {
        val s = stitch(Triple("今天我们来聊一聊", 0, 15000), Triple("我们来聊一聊语音", 1000, 16000))
        s.feed("", 2000, 17000)
        assertShown("今天", "我们来聊一聊语音", s)
        s.reset()
        assertShown("", "", s)
        s.feed("第二段", 19000, 20000)
        assertShown("", "第二段", s)
    }
}
