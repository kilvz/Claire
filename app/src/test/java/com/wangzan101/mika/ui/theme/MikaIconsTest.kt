package com.wangzan101.mika.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Telegram 用纸飞机,不再和微信共用双气泡图标。 */
class MikaIconsTest {
    @Test fun telegramIsAPaperPlaneOnTheSameGrid() {
        val telegram = MikaIcons.Telegram
        assertEquals("telegram", telegram.name)
        assertEquals(24f, telegram.viewportWidth)
        assertEquals(24f, telegram.viewportHeight)
        assertEquals(22.dp, telegram.defaultWidth)
        assertEquals(22.dp, telegram.defaultHeight)
    }

    @Test fun telegramDiffersFromChats() {
        assertNotEquals(MikaIcons.Chats, MikaIcons.Telegram)
        assertNotEquals(MikaIcons.Chats.name, MikaIcons.Telegram.name)
    }
}
