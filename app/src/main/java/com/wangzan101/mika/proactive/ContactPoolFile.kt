package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ContactPoolData
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Summarized contacts, encrypted in their entirety into `proactive/contacts.bin` (AES-GCM, production uses
 * `KeystoreKeyCipher("mika-proactive-contacts")`),
 * stored separately from the topic pool ([TopicPoolFile]) with its own key. It holds other people's display names
 * (notification titles), votes on type and relationship, dates, linked topic ids,
 * and the identity ids of deleted ones marked "don't record again"; no message bodies. Following the approach of
 * [com.wangzan101.mika.memory.EncryptedMemoryFile]:
 * write to a temporary file then rename atomically; if it is genuinely corrupt, rename it aside and start from empty;
 * if it cannot be read right now (keystore busy, key retrieval error), throw as usual and leave the file alone (see
 * [StoreCodec] for what counts as corrupt).
 * Files moved aside are, like those of [TopicPoolFile], deleted along with everything else when the user deletes data.
 */
class ContactPoolFile(dir: File, private val cipher: KeyCipher, private val clock: () -> Long = System::currentTimeMillis) : ContactPoolStore {
    private val poolDir = File(dir, "proactive")
    private val file = File(poolDir, FILE)
    private val tmp = File(poolDir, "$FILE.tmp")

    /** Reads it out; empty when there is no file. After a corrupt file is moved aside it is empty too. */
    @Synchronized
    override fun load(): ContactPoolData {
        if (!file.exists()) return ContactPoolData()
        val bytes = file.readBytes() // Failure to read is an IO fault, so throw as usual
        return StoreCodec.read(bytes, cipher, ContactPoolData.serializer(), VERSION) ?: run {
            moveAside()
            ContactPoolData()
        }
    }

    @Synchronized
    override fun save(data: ContactPoolData) {
        poolDir.mkdirs()
        val bytes = cipher.encrypt(StoreCodec.write(ContactPoolData.serializer(), data, VERSION))
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Deletes the old files that were moved aside (`$FILE.broken-…`). */
    @Synchronized
    override fun dropBroken() {
        poolDir.listFiles { f -> f.name.startsWith("$FILE.broken-") }.orEmpty().forEach(::delete)
    }

    /** Deletes everything: the file, the temporary file, and the old files moved aside. */
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
        /** Which version of the on-disk format this is; if a newer one is read, leave it alone (see [StoreCodec]). Bump this when the format changes such that old code would fail or lose data when reading it. */
        const val VERSION = 1
        const val FILE = "contacts.bin"
        const val KEY_ALIAS = "mika-proactive-contacts"
    }
}
