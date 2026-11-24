package com.wangzan101.mika.core.screen.uistate

import org.junit.Assert.assertEquals
import org.junit.Test

class HantTest {
    // The two strings pair up character by character: unequal lengths would silently truncate and misalign the whole table; each traditional character maps to exactly one simplified character
    @Test fun tableLinesUp() {
        assertEquals(Hant.TRADITIONAL.length, Hant.SIMPLIFIED.length)
        assertEquals(Hant.TRADITIONAL.length, Hant.TRADITIONAL.toSet().size)
        assertEquals("", Hant.TRADITIONAL.filter { it in Hant.SIMPLIFIED })
    }

    @Test fun foldsWordsToSimplified() {
        assertEquals("注销账户", Hant.fold("註銷帳户"))
        assertEquals("回复", Hant.fold("回覆"))
        assertEquals("发布", Hant.fold("發佈"))
        assertEquals("联系对方", Hant.fold("聯繫對方"))
        assertEquals("已经简体", Hant.fold("已经简体"))
    }
}
