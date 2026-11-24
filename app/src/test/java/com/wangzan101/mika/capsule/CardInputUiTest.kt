package com.wangzan101.mika.capsule

import com.wangzan101.mika.core.screen.user.CapturedScreen
import com.wangzan101.mika.core.screen.user.UserScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The capsule card's input field: text that was not sent when collapsed is kept, and spoken text is appended after it. */
class CardInputUiTest {
    private val screen = PendingScreen(CapturedScreen(null, UserScreen("", 0L, "com.android.settings", "设置")), null)

    @Test fun collapsingKeepsTheDraftButClosesTheFieldAndDropsTheScreen() {
        val ui = CardInputUi(open = true, draft = "帮我把这段", screen = screen).collapsed()
        assertEquals("帮我把这段", ui.draft)
        assertFalse(ui.open)
        assertNull(ui.screen)
    }

    @Test fun spokenTextFollowsTheDraft() {
        assertEquals("帮我把这段话发给小王", CardInputUi(draft = "帮我把这段话").withSpoken("发给小王").draft)
        assertEquals("send it to Tom", CardInputUi(draft = "send it").withSpoken("to Tom").draft)
        assertEquals("发给小王", CardInputUi().withSpoken("发给小王").draft)
    }

    @Test fun spokenTextKeepsTheAttachedScreen() {
        assertSame(screen, CardInputUi(draft = "", screen = screen).withSpoken("这个怎么回").screen)
    }
}
