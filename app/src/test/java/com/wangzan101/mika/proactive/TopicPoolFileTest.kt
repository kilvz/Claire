package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ObservedTopic
import com.wangzan101.mika.core.proactive.TopicKind
import com.wangzan101.mika.core.proactive.TopicPoolData
import com.wangzan101.mika.settings.AesGcmKeyCipher
import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TopicPoolFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun file(k: javax.crypto.SecretKey = key) = TopicPoolFile(tmp.root, AesGcmKeyCipher { k }) { 1_761_200_000_000L }
    private val bin get() = File(tmp.root, "proactive/topics.bin")

    private val data = TopicPoolData(
        topics = listOf(
            ObservedTopic("k1", TopicKind.Shopping, "跑鞋", 1L, 2L, mapOf("com.shop" to listOf(20_000L, 20_001L)), opened = 3, dismissed = 1),
            ObservedTopic("k7", TopicKind.Interest, "Diabetes", 3L, 4L, mapOf("com.health" to listOf(20_002L)), sensitive = true),
        ),
    )

    @Test fun roundTripAndNoPlaintext() {
        assertEquals(TopicPoolData(), file().load())
        file().save(data)
        assertEquals(data, file().load())
        val bytes = bin.readBytes()
        for (needle in listOf("跑鞋", "com.shop", "Diabetes").map { it.toByteArray(Charsets.UTF_8) }) {
            assertFalse(bytes.indices.any { i -> i + needle.size <= bytes.size && needle.indices.all { bytes[i + it] == needle[it] } })
        }
    }

    // A file with merged topic names is written as version 2: code that only knows version 1 would drop those names when reading it, so it is made unreadable to such code, which then leaves it alone;
    // without merged names it is written exactly like version 1, and still says 1
    @Test fun aFileWithMergedNamesIsVersionTwo() {
        fun version() = kotlinx.serialization.json.Json.parseToJsonElement(AesGcmKeyCipher { key }.decrypt(bin.readBytes()).toString(Charsets.UTF_8))
            .let { (it as kotlinx.serialization.json.JsonObject)["version"].toString() }
        file().save(data)
        assertEquals("1", version())
        val merged = data.copy(merged = mapOf("0123456789abcdef" to "k1"))
        file().save(merged)
        assertEquals("2", version())
        // (the outermost version number is also read into that field of the data class; the topic pool ignores it)
        assertEquals(merged.copy(version = 2), file().load())
        // Once the merged topics are gone and the aliases go with them, it is version 1 again
        file().save(data)
        assertEquals("1", version())
    }

    @Test fun brokenFileIsSetAsideAndPoolStartsEmpty() {
        file().save(data)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals(TopicPoolData(), file(other).load())
        assertFalse(bin.exists())
        val aside = File(tmp.root, "proactive").listFiles { f -> f.name.startsWith("topics.bin.broken-") }.orEmpty()
        assertEquals(1, aside.size)
        // Saving and loading work as usual afterwards
        file().save(data)
        assertEquals(data, file().load())
        bin.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(TopicPoolData(), file().load())
        assertTrue(File(tmp.root, "proactive").listFiles { f -> f.name.startsWith("topics.bin.broken-") }!!.size == 2)
    }

    // Older files also carried a sequence number (ids later became random, so it is gone): they load as usual, with the old ids kept as they are
    @Test fun oldFileWithASequenceNumberStillLoads() {
        val old = """{"version":1,"seq":7,"topics":[{"id":"k7","kind":"interest","label":"Hiking","firstSeenMs":3,"lastSeenMs":4}]}"""
        bin.parentFile!!.mkdirs()
        bin.writeBytes(AesGcmKeyCipher { key }.encrypt(old.toByteArray(Charsets.UTF_8)))
        assertEquals(TopicPoolData(topics = listOf(ObservedTopic("k7", TopicKind.Interest, "Hiking", 3L, 4L))), file().load())
    }

    // When the user deletes something: the set-aside old files are deleted, the current one is untouched
    @Test fun dropBrokenRemovesOnlyTheSetAsideCopies() {
        file().save(data)
        bin.writeBytes(byteArrayOf(1, 2, 3))
        file().load()
        file().save(data)
        val dir = File(tmp.root, "proactive")
        assertEquals(1, dir.listFiles { f -> f.name.startsWith("topics.bin.broken-") }!!.size)
        file().dropBroken()
        assertEquals(listOf("topics.bin"), dir.list()!!.toList())
        assertEquals(data, file().load())
        // No error when there is nothing to delete
        file().dropBroken()
    }

    // "Clear all": the file, the temp file and any set-aside old files are all deleted; no error when the directory does not exist yet
    @Test fun wipeRemovesEverything() {
        file().wipe()
        file().save(data)
        bin.writeBytes(byteArrayOf(1, 2, 3))
        file().load()
        file().save(data)
        val dir = File(tmp.root, "proactive")
        File(dir, "topics.bin.tmp").writeBytes(byteArrayOf(9))
        file().wipe()
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertEquals(TopicPoolData(), file().load())
    }
}
