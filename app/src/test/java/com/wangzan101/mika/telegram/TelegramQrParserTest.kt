package com.wangzan101.mika.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramQrParserTest {
    private val token = "123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsawX"

    @Test
    fun bareTokenParses() {
        val setup = parseTelegramQr(token)
        assertEquals(token, setup?.token)
        assertNull(setup?.chatId)
    }

    @Test
    fun tokenAndChatIdWithPipeParses() {
        val setup = parseTelegramQr("$token|8756247324")
        assertEquals(token, setup?.token)
        assertEquals("8756247324", setup?.chatId)
    }

    @Test
    fun tokenAndChatIdOnSeparateLinesParse() {
        val setup = parseTelegramQr("$token\n8756247324")
        assertEquals(token, setup?.token)
        assertEquals("8756247324", setup?.chatId)
    }

    @Test
    fun jsonWithSnakeCaseChatIdParses() {
        val setup = parseTelegramQr("""{"token": "$token", "chat_id": "8756247324"}""")
        assertEquals(token, setup?.token)
        assertEquals("8756247324", setup?.chatId)
    }

    @Test
    fun jsonWithCamelCaseChatIdParses() {
        val setup = parseTelegramQr("""{"token": "$token", "chatId": "8756247324"}""")
        assertEquals(token, setup?.token)
        assertEquals("8756247324", setup?.chatId)
    }

    @Test
    fun whitespaceAroundCodeIsTrimmed() {
        val setup = parseTelegramQr("  $token|8756247324  ")
        assertEquals(token, setup?.token)
        assertEquals("8756247324", setup?.chatId)
    }

    @Test
    fun botLinkIsRejected() {
        assertNull(parseTelegramQr("https://t.me/ClaireAppBot"))
    }

    @Test
    fun randomTextIsRejected() {
        assertNull(parseTelegramQr("hello world"))
        assertNull(parseTelegramQr(""))
        assertNull(parseTelegramQr("   "))
    }

    @Test
    fun tokenWithShortSecretIsRejected() {
        assertNull(parseTelegramQr("123456789:short"))
    }

    @Test
    fun jsonWithoutTokenIsRejected() {
        assertNull(parseTelegramQr("""{"chat_id": "8756247324"}"""))
    }

    @Test
    fun malformedChatIdIsDroppedButTokenKept() {
        val setup = parseTelegramQr("$token|not-a-number")
        assertEquals(token, setup?.token)
        assertNull(setup?.chatId)
    }

    @Test
    fun negativeChatIdIsAccepted() {
        val setup = parseTelegramQr("$token|-1001234567890")
        assertEquals("-1001234567890", setup?.chatId)
    }
}
