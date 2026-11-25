package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** When one of the four encrypted stores cannot be read: only proven corruption is deleted (or set aside); every other kind of read error leaves the file untouched (see [StoreCodec]). */
class StoreCodecTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dir get() = File(tmp.root, "proactive")

    private class Failing(val error: () -> Exception) : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray): ByteArray = throw error()
    }
    private val identity = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }

    /** The four stores: the file name, plus a way to read that file with a given cipher. */
    private val stores: List<Pair<String, (KeyCipher) -> Any?>> = listOf(
        DigestFile.FILE to { c -> DigestFile(tmp.root, c).load() },
        NoticeJournalFile.FILE to { c -> NoticeJournalFile(tmp.root, c).load() },
        TopicPoolFile.FILE to { c -> TopicPoolFile(tmp.root, c).load() },
        ContactPoolFile.FILE to { c -> ContactPoolFile(tmp.root, c).load() },
    )

    private fun seed(name: String, text: String = "encrypted bytes that are perfectly fine") =
        File(dir, name).apply { absoluteFile.parentFile?.mkdirs(); writeText(text) }

    // Errors from obtaining the key or decrypting — apart from "authentication failed" and "too short to be ciphertext" — do not prove the ciphertext is corrupt: they propagate as usual and the file is left as it was
    @Test fun troubleWhileDecryptingLeavesEveryStoreAlone() {
        val troubles: List<() -> Exception> = listOf(
            { IOException("transient keystore load failure") },
            { java.security.KeyStoreException("busy") },
            { java.security.ProviderException("keystore daemon died") },
            { java.security.InvalidKeyException("user not authenticated") },
            { javax.crypto.IllegalBlockSizeException("keystore operation failed") },
            { javax.crypto.BadPaddingException("not a tag failure") },
            { IllegalStateException("cipher not initialised") },
        )
        for ((name, load) in stores) for (trouble in troubles) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            val f = seed(name)
            val what = "$name / ${trouble().javaClass.simpleName}"
            try {
                load(Failing(trouble))
                fail(what)
            } catch (e: Exception) {
                assertEquals(what, trouble().javaClass, e.javaClass)
            }
            assertTrue(what, f.exists())
            assertEquals(what, listOf(name), dir.list()!!.toList())
        }
    }

    // Decrypted (authenticated) but unreadable: this was written by Claire itself, most likely in some other version's format — it propagates as usual and the file is untouched
    @Test fun contentThatDecryptsButCannotBeReadIsKept() {
        for ((name, load) in stores) for (text in listOf("{broken", """{"topics":"not a list","days":7,"entries":3,"contacts":1,"notes":2}""")) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            val f = seed(name, text)
            try {
                load(identity)
                fail("$name / $text")
            } catch (e: kotlinx.serialization.SerializationException) {}
            assertEquals("$name / $text", listOf(name), dir.list()!!.toList())
            assertEquals(text, f.readText())
        }
    }

    // Only proven corruption counts: authentication failed, or too short to be ciphertext. Notice text and digest are deleted outright; topics and contacts are set aside as a copy and start empty
    @Test fun provenCorruptionIsHandledPerStore() {
        val proofs: List<Pair<String, KeyCipher>> = listOf(
            "tag" to Failing { javax.crypto.AEADBadTagException("mac check failed") },
            "short" to Failing { IllegalArgumentException("ciphertext too short") },
        )
        for ((name, load) in stores) for ((why, cipher) in proofs) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            val f = seed(name, "{broken")
            val what = "$name / $why"
            val loaded = load(cipher)
            assertFalse(what, f.exists())
            val left = dir.list()!!.toList()
            if (name == TopicPoolFile.FILE || name == ContactPoolFile.FILE) {
                assertEquals(what, 1, left.size)
                assertTrue(what, left.single().startsWith("$name.broken-"))
            } else {
                assertEquals(what, emptyList<String>(), left)
            }
            if (name == NoticeJournalFile.FILE) assertNull(what, loaded)
        }
    }

    // Raw notice text should only be kept for two days: if it stays unreadable and the file has not been written for two days (everything inside is expired), it is deleted; a still-fresh one is left alone
    @Test fun anUnreadableJournalDoesNotOutliveItsRetention() {
        var now = 1_800_000_000_000L
        val f = seed(NoticeJournalFile.FILE)
        f.setLastModified(now)
        val busy = Failing { java.security.KeyStoreException("busy") }
        fun load() = try {
            NoticeJournalFile(tmp.root, busy) { now }.load()
            fail()
        } catch (e: java.security.KeyStoreException) {}
        now += NoticeJournal.KEEP_MS
        load()
        assertTrue(f.exists())
        now += 1
        load()
        assertFalse(f.exists())
        // The digest is retained longer, so it is never deleted however long it stays unreadable
        val d = seed(DigestFile.FILE)
        d.setLastModified(1L)
        try {
            DigestFile(tmp.root, busy).load()
            fail()
        } catch (e: java.security.KeyStoreException) {}
        assertTrue(d.exists())
    }

    // ---- Version number ----

    private val plainJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val topics = com.wangzan101.mika.core.proactive.TopicPoolData(
        topics = listOf(com.wangzan101.mika.core.proactive.ObservedTopic("k1", com.wangzan101.mika.core.proactive.TopicKind.Interest, "徒步", 1L, 2L)))
    private val contacts = com.wangzan101.mika.core.proactive.ContactPoolData(
        contacts = listOf(com.wangzan101.mika.core.proactive.ObservedContact("c1", "com.chat", "张三", 1L, 2L)))
    private val journal = com.wangzan101.mika.core.proactive.NoticeJournalData(
        sinceMs = 5L, entries = listOf(com.wangzan101.mika.core.proactive.NoticeEntry("com.chat", 6L, "t", text = "x")))
    private val digest = com.wangzan101.mika.core.proactive.DigestData(
        days = listOf(com.wangzan101.mika.core.proactive.DayDigest(20_000, 1L, listOf(com.wangzan101.mika.core.proactive.DigestLine("一句", apps = listOf("com.chat"))))))

    /** Per store: the file name, contents written in the format used before the version check (no `version`), a save, and a load. */
    private class Case(val name: String, val old: String, val save: () -> Unit, val load: () -> Any?, val data: Any, val known: Int = 1)
    private fun cases() = listOf(
        Case(TopicPoolFile.FILE, plainJson.encodeToString(com.wangzan101.mika.core.proactive.TopicPoolData.serializer(), topics),
            { TopicPoolFile(tmp.root, identity).save(topics) }, { TopicPoolFile(tmp.root, identity).load() }, topics, known = TopicPoolFile.VERSION),
        Case(ContactPoolFile.FILE, plainJson.encodeToString(com.wangzan101.mika.core.proactive.ContactPoolData.serializer(), contacts),
            { ContactPoolFile(tmp.root, identity).save(contacts) }, { ContactPoolFile(tmp.root, identity).load() }, contacts),
        Case(NoticeJournalFile.FILE, plainJson.encodeToString(com.wangzan101.mika.core.proactive.NoticeJournalData.serializer(), journal),
            { NoticeJournalFile(tmp.root, identity).save(journal) }, { NoticeJournalFile(tmp.root, identity).load() }, journal),
        Case(DigestFile.FILE, plainJson.encodeToString(com.wangzan101.mika.core.proactive.DigestData.serializer(), digest),
            { DigestFile(tmp.root, identity).save(digest) }, { DigestFile(tmp.root, identity).load() }, digest),
    )
    private fun root(name: String) = kotlinx.serialization.json.Json.parseToJsonElement(File(dir, name).readText()) as kotlinx.serialization.json.JsonObject

    // The files already on the phone were written before the version check and have no `version` inside: all four still load as usual, with identical contents
    @Test fun filesWrittenBeforeTheVersionCheckStillLoad() {
        for (c in cases()) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            assertFalse(c.name, "\"version\"" in c.old)
            seed(c.name, c.old)
            assertEquals(c.name, c.data, c.load())
            assertEquals(c.name, c.old, File(dir, c.name).readText())
        }
    }

    // Saving always writes the version at the outermost level (defaults in the data classes are omitted during serialization, so they cannot be relied on); saving and then loading yields the same contents
    @Test fun everyStoreWritesItsVersion() {
        for (c in cases()) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            c.save()
            assertEquals(c.name, kotlinx.serialization.json.JsonPrimitive(1), root(c.name)["version"])
            assertEquals(c.name, c.data, c.load())
        }
    }

    // A file written by a newer version: this reading layer does not understand its format, so it is not read in, not modified, not set aside and not deleted — even if the current format would still parse it
    // (reading it in and saving it again would rewrite it in the old format, and whatever the new version added would be lost).
    // Deletions the user asks for still happen: "clear all", unreadable raw notice text when a given app is cleared, the raw text when the switch is turned off, and the rule that raw text not written for two days goes away
    @Test fun aNewerVersionIsRefusedAndLeftUntouched() {
        for (c in cases()) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            val body = (kotlinx.serialization.json.Json.parseToJsonElement(c.old) as kotlinx.serialization.json.JsonObject)
            val newer = kotlinx.serialization.json.JsonObject(
                body + ("version" to kotlinx.serialization.json.JsonPrimitive(c.known + 1)) + ("newRequiredMeaning" to kotlinx.serialization.json.JsonPrimitive("preserve me")),
            ).toString()
            seed(c.name, newer)
            try {
                c.load()
                fail(c.name)
            } catch (e: NewerStoreVersion) {
                assertEquals(c.name, c.known + 1, e.found)
                assertEquals(c.name, c.known, e.known)
            }
            assertEquals(c.name, listOf(c.name), dir.list()!!.toList())
            assertEquals(c.name, newer, File(dir, c.name).readText())
        }
    }

    // A malformed version number is treated as unreadable (the file stays put); anything not newer than the current version loads as usual
    @Test fun oddVersionValues() {
        val c = cases().first()
        val body = (kotlinx.serialization.json.Json.parseToJsonElement(c.old) as kotlinx.serialization.json.JsonObject)
        fun with(version: kotlinx.serialization.json.JsonElement) = kotlinx.serialization.json.JsonObject(body + ("version" to version)).toString()
        for (bad in listOf(kotlinx.serialization.json.JsonPrimitive("2"), kotlinx.serialization.json.JsonPrimitive(1.5), kotlinx.serialization.json.JsonArray(emptyList()))) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            val f = seed(c.name, with(bad))
            try {
                c.load()
                fail(bad.toString())
            } catch (e: kotlinx.serialization.SerializationException) {}
            assertTrue(bad.toString(), f.exists())
        }
        for (fine in listOf(0, 1)) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            seed(c.name, with(kotlinx.serialization.json.JsonPrimitive(fine)))
            assertEquals(topics.topics, (c.load() as com.wangzan101.mika.core.proactive.TopicPoolData).topics)
        }
    }
}
