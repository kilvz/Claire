package com.wangzan101.mika.telegram

import com.wangzan101.mika.settings.KeyCipher
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
}
