package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.NoticeEntry
import com.wangzan101.mika.core.proactive.NoticeJournalData
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoticeJournalFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun file(k: javax.crypto.SecretKey = key) = NoticeJournalFile(tmp.root, AesGcmKeyCipher { k })
    private val dir get() = File(tmp.root, "proactive")
    private val bin get() = File(dir, "journal.bin")

    private val data = NoticeJournalData(sinceMs = 5, entries = listOf(
        NoticeEntry("com.chat", 10, "家长群", "王老师", "明天下午三点开会", keyId = "ab12", sig = 7),
        NoticeEntry("com.bank", 11, "到账通知", null, "你有一笔新的收款", keyId = "cd34", sig = 9, styled = false),
    ))

    @Test fun roundTripAndNoPlaintext() {
        assertNull(file().load())
        file().save(data)
        assertEquals(data, file().load())
        val bytes = bin.readBytes()
        for (needle in listOf("家长群", "王老师", "明天下午三点开会", "com.chat").map { it.toByteArray(Charsets.UTF_8) }) {
            assertFalse(bytes.indices.any { i -> i + needle.size <= bytes.size && needle.indices.all { bytes[i + it] == needle[it] } })
        }
    }

    // A file that cannot be decrypted or whose contents are corrupt is deleted outright, not renamed and set aside: no invisible, undeletable copy of the plaintext is left behind
    @Test fun brokenFileIsDeletedNotSetAside() {
        file().save(data)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(file(other).load())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        bin.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(file().load())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        // Saving and loading work as usual afterwards
        file().save(data)
        assertEquals(data, file().load())
    }

    // A momentarily unavailable keystore does not make the file corrupt: the error is thrown as usual and the file is untouched
    @Test fun keystoreTroubleLeavesTheFileAlone() {
        file().save(data)
        val busy = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = throw java.security.KeyStoreException("busy")
            override fun decrypt(data: ByteArray): ByteArray = throw java.security.KeyStoreException("busy")
        }
        try {
            NoticeJournalFile(tmp.root, busy).load()
            fail()
        } catch (e: java.security.KeyStoreException) {}
        assertTrue(bin.exists())
        assertEquals(data, file().load())
    }

    @Test fun wipeRemovesTheFileAndItsTempAndIsSafeWhenNothingIsThere() {
        file().wipe()
        file().save(data)
        File(dir, "journal.bin.tmp").writeBytes(byteArrayOf(9))
        file().wipe()
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertNull(file().load())
    }
}
