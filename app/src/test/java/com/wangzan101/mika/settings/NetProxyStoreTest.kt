package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Settings › Network proxy: it may carry a username and password, so it is encrypted into a file and never written in plaintext; the address is validated before saving; the display form omits the credentials; the copy that older versions saved for container use only is still picked up. */
class NetProxyStoreTest {
    private val dir: File = Files.createTempDirectory("proxy").toFile()

    /** Test "encryption": inverts every byte, which is enough to show the file does not hold the original text. */
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { plain[it].toInt().inv().toByte() }
        override fun decrypt(data: ByteArray) = encrypt(data)
    }

    @Test fun storedEncryptedAndReadBack() {
        val proxy = "http://alice:s3cret@10.0.0.2:8080"
        NetProxyStore(dir, cipher).set(proxy)
        val file = File(dir, "net-proxy.bin")
        assertTrue(file.isFile)
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("文件里不是明文", raw.contains("s3cret") || raw.contains("alice"))
        // A fresh instance (as after a restart) reads it back the same way
        assertEquals(proxy, NetProxyStore(dir, cipher).get())
    }

    @Test fun emptyRemovesTheFile() {
        val store = NetProxyStore(dir, cipher)
        store.set("socks5://127.0.0.1:1080")
        store.set("  ")
        assertEquals("", store.get())
        assertFalse(File(dir, "net-proxy.bin").exists())
        assertEquals("", NetProxyStore(dir, cipher).get())
    }

    /** If it cannot be decrypted (the key is gone) it counts as not set, so commands are never sent off to connect with garbled data. */
    @Test fun unreadableCountsAsNotSet() {
        File(dir, "net-proxy.bin").writeBytes(byteArrayOf(1, 2, 3))
        val broken = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(data: ByteArray): ByteArray = throw IllegalStateException("key gone")
        }
        assertEquals("", NetProxyStore(dir, broken).get())
    }

    @Test fun onlyProxyAddressesAreAccepted() {
        for (ok in listOf("", "http://127.0.0.1:7890", "https://proxy.example.com:443", "socks5h://u:p@[::1]:1080", "HTTP://host:80/")) {
            assertTrue(ok, NetProxyStore.valid(ok))
        }
        for (bad in listOf("127.0.0.1:7890", "ftp://h:21", "http://host", "http://host:0", "http://host:70000", "http://h:80/path", "http://h :80", "http://h:80?x=1")) {
            assertFalse(bad, NetProxyStore.valid(bad))
        }
    }

    @Test fun displayLeavesOutCredentials() {
        assertEquals("http://10.0.0.2:8080", NetProxyStore.display("http://alice:s3cret@10.0.0.2:8080"))
        assertEquals("socks5://127.0.0.1:1080", NetProxyStore.display("socks5://127.0.0.1:1080"))
    }

    /** A different "key": adds one to every byte, so it cannot decrypt what [cipher] produced. */
    private val legacyCipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { (plain[it] + 1).toByte() }
        override fun decrypt(data: ByteArray) = ByteArray(data.size) { (data[it] - 1).toByte() }
    }

    /** The proxy that older versions saved for container use only: after the upgrade it is still readable, re-saved into the new file while the old one is deleted, and only the new one is honoured from then on. */
    @Test fun containerOnlySettingIsCarriedOver() {
        val old = File(dir, "container-proxy.bin")
        old.writeBytes(legacyCipher.encrypt("socks5://bob:pw@10.0.0.9:1080".toByteArray()))
        var changes = 0
        val store = NetProxyStore(dir, cipher, legacyCipher) { changes++ }
        assertEquals("socks5://bob:pw@10.0.0.9:1080", store.get())
        assertFalse(old.exists())
        assertTrue(File(dir, "net-proxy.bin").isFile)
        assertEquals("读的时候不算改了设置", 0, changes)
        // After a restart (the old file is gone) it is still this same one
        assertEquals("socks5://bob:pw@10.0.0.9:1080", NetProxyStore(dir, cipher, legacyCipher).get())
        store.set("http://127.0.0.1:7890")
        assertEquals(1, changes)
    }

    /** A new setting already exists: the old file does not overwrite it. */
    @Test fun newSettingWinsOverLeftoverLegacyFile() {
        NetProxyStore(dir, cipher).set("http://10.0.0.2:8080")
        File(dir, "container-proxy.bin").writeBytes(legacyCipher.encrypt("http://old:1".toByteArray()))
        assertEquals("http://10.0.0.2:8080", NetProxyStore(dir, cipher, legacyCipher).get())
    }

    /** The old file cannot be decrypted: counts as not set and does not raise an error. */
    @Test fun unreadableLegacyFileCountsAsNotSet() {
        File(dir, "container-proxy.bin").writeBytes(byteArrayOf(9, 9))
        val broken = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(data: ByteArray): ByteArray = throw IllegalStateException("key gone")
        }
        assertEquals("", NetProxyStore(dir, cipher, broken).get())
    }
}
