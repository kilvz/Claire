package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.TopicPoolData
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The summarized topic pool, encrypted as a whole into `proactive/topics.bin` (AES-GCM, using `KeystoreKeyCipher("mika-proactive-topics")` in production).
 * It holds only topic names, kinds, counts and times, never notification text. Following the approach of [com.wangzan101.mika.memory.EncryptedMemoryFile]:
 * write a temp file and then rename it atomically; if it is genuinely corrupt, rename it aside and start from empty; cases that cannot be read right now (keystore busy, key fetch error) are thrown as usual and the file is left untouched (see [StoreCodec] for what counts as corrupt).
 * The set-aside old file is not visible in the UI and cannot be read back; it is kept only as a fallback: it is deleted whenever the user deletes anything ([dropBroken]), and deleted in full on "clear all" ([wipe]).
 */
class TopicPoolFile(dir: File, private val cipher: KeyCipher, private val clock: () -> Long = System::currentTimeMillis) : TopicPoolStore {
    private val poolDir = File(dir, "proactive")
    private val file = File(poolDir, FILE)
    private val tmp = File(poolDir, "$FILE.tmp")

    /** Read it back; no file yields an empty pool. A corrupt file also yields an empty pool, after being set aside. */
    @Synchronized
    override fun load(): TopicPoolData {
        if (!file.exists()) return TopicPoolData()
        val bytes = file.readBytes() // a failure to read is an IO fault; throw as usual
        return StoreCodec.read(bytes, cipher, TopicPoolData.serializer(), VERSION) ?: run {
            moveAside()
            TopicPoolData()
        }
    }

    @Synchronized
    override fun save(data: TopicPoolData) {
        poolDir.mkdirs()
        val bytes = cipher.encrypt(StoreCodec.write(TopicPoolData.serializer(), data, versionOf(data)))
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Deletes the set-aside old files (`$FILE.broken-…`). */
    @Synchronized
    override fun dropBroken() {
        poolDir.listFiles { f -> f.name.startsWith("$FILE.broken-") }.orEmpty().forEach(::delete)
    }

    /** Deletes everything: the file, the temp file and the set-aside old files. */
    @Synchronized
    override fun wipe() {
        delete(file)
        delete(tmp)
        dropBroken()
    }

    private fun delete(f: File) {
        if (f.exists() && !f.delete()) throw IOException("could not delete ${f.name}")
    }

    private fun moveAside() {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(clock())
        var target = File(poolDir, "$FILE.broken-$stamp")
        var n = 1
        while (target.exists()) target = File(poolDir, "$FILE.broken-$stamp-${n++}")
        Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        /**
         * Which revision of the format this store currently uses; anything newer is left alone (see [StoreCodec]). Bump it when the format changes in a way that makes old code fail or lose data.
         * Revision 2: adds merged topic names ([TopicPoolData.merged]). Code that only knows revision 1 can read it, but the next save drops them,
         * and the merged names get started as separate topics again.
         */
        const val VERSION = 2

        /** Which revision to write this content as: with no merged names it is byte-for-byte what revision 1 writes, so it still writes 1 (an older version reads it back as usual). */
        fun versionOf(data: TopicPoolData): Int = if (data.merged.isEmpty()) 1 else VERSION
        const val FILE = "topics.bin"
        const val KEY_ALIAS = "mika-proactive-topics"
    }
}
