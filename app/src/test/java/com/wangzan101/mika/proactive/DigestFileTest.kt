package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.DigestData
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.settings.AesGcmKeyCipher
import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DigestFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun file(k: javax.crypto.SecretKey = key) = DigestFile(tmp.root, AesGcmKeyCipher { k })
    private val dir get() = File(tmp.root, "proactive")
    private val data = DigestData(notes = listOf(DigestNote("d1", "com.chat", 20_000, 1, 2, 2, "家长群", "王老师通知明天开家长会")))

    @Test fun roundTripAndNoPlaintext() {
        assertEquals(DigestData(), file().load())
        file().save(data)
        assertEquals(data, file().load())
        val bytes = File(dir, "digest.bin").readBytes()
        for (needle in listOf("家长群", "王老师通知明天开家长会", "com.chat").map { it.toByteArray(Charsets.UTF_8) }) {
            assertFalse(bytes.indices.any { i -> i + needle.size <= bytes.size && needle.indices.all { bytes[i + it] == needle[it] } })
        }
    }

    // Undecryptable or corrupt files are simply deleted (not renamed aside); saving and loading work as usual afterwards; wiping is safe when nothing exists
    @Test fun brokenFileIsDeletedAndWipeIsSafe() {
        file().wipe()
        file().save(data)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals(DigestData(), file(other).load())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        file().save(data)
        file().wipe()
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }
}
