package com.wangzan101.mika.telegram

import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TelegramStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }

    @Test fun firstTaskAckIsConsumedOnlyOnce() {
        val store = TelegramStore(tmp.newFolder(), cipher)

        assertTrue(store.consumeFirstTaskAck())
        assertFalse(store.consumeFirstTaskAck())
        assertFalse(store.consumeFirstTaskAck())
    }

    @Test fun reconfiguringWithANewTokenResetsFirstTaskAck() {
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot("token", "123")
        assertTrue(store.consumeFirstTaskAck())
        assertFalse(store.consumeFirstTaskAck())

        store.saveBot("new-token", "123")

        assertTrue(store.consumeFirstTaskAck())
        assertFalse(store.consumeFirstTaskAck())
    }

    @Test fun reconfiguringWithANewChatIdResetsFirstTaskAck() {
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot("token", "123")
        assertTrue(store.consumeFirstTaskAck())

        store.saveBot("token", "456")

        assertTrue(store.consumeFirstTaskAck())
    }

    @Test fun savingTheSameSetupDoesNotResetFirstTaskAck() {
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot("token", "123")
        assertTrue(store.consumeFirstTaskAck())

        store.saveBot("token", "123")

        assertFalse(store.consumeFirstTaskAck())
    }

    @Test fun clearingResetsFirstTaskAck() {
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot("token", "123")
        assertTrue(store.consumeFirstTaskAck())

        store.clear()

        assertTrue(store.consumeFirstTaskAck())
    }

    @Test fun consumedFlagPersistsAcrossReopen() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot("token", "123")
        assertTrue(first.consumeFirstTaskAck())

        val reopened = TelegramStore(dir, cipher)

        assertFalse(reopened.consumeFirstTaskAck())
    }

    @Test fun botUsernamePersistsAcrossReopen() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot("token", "123")
        first.saveBotUsername("ClaireAppBot")

        val reopened = TelegramStore(dir, cipher)

        assertEquals("ClaireAppBot", reopened.botUsername())
    }

    @Test fun reconfiguringWithANewTokenClearsBotUsername() {
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot("token", "123")
        store.saveBotUsername("ClaireAppBot")

        store.saveBot("new-token", "123")

        assertEquals("", store.botUsername())
    }

    @Test fun clearingResetsBotUsername() {
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot("token", "123")
        store.saveBotUsername("ClaireAppBot")

        store.clear()

        assertEquals("", store.botUsername())
    }
}
