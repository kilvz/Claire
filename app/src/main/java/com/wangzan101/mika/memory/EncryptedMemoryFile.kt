package com.wangzan101.mika.memory

import android.security.keystore.UserNotAuthenticatedException
import com.wangzan101.mika.core.learn.memory.MemoryItem
import com.wangzan101.mika.core.learn.memory.MemoryPersistence
import com.wangzan101.mika.core.workspace.AtomicFiles
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStoreException
import java.security.ProviderException

/**
 * Encrypts the entire memory into `memory/memory.bin` (AES-GCM; the key is supplied by the caller, production uses
 * `KeystoreKeyCipher("mika-memory")`).
 * Writes to a temporary file and then renames atomically, so the file is either complete ciphertext or unchanged.
 * If decryption fails (key lost) or the content is corrupt: do not overwrite, rename it aside, start from empty;
 * [brokenNotice] is used to notify the UI.
 */
class EncryptedMemoryFile(
    dir: File,
    private val cipher: KeyCipher,
    private val clock: () -> Long,
) : MemoryPersistence {
    private val memDir = File(dir, "memory")
    private val file = File(memDir, FILE)
    private val tmp = File(memDir, "$FILE.tmp")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MemoryItem.serializer())

    private val _brokenNotice = MutableStateFlow<String?>(null)
    /** Name of the bad file moved aside; null = no problem has occurred. */
    val brokenNotice: StateFlow<String?> = _brokenNotice.asStateFlow()

    @Synchronized
    override fun load(): List<MemoryItem> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes() // Failure to read is an IO fault, so throw as usual; do not treat it as a corrupt file
        return try {
            json.decodeFromString(serializer, cipher.decrypt(bytes).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            // Keystore being temporarily unavailable (locked, busy) does not mean the file is corrupt:
            // throw, retry next read, never move it aside
            if (e is UserNotAuthenticatedException || e is KeyStoreException || e is ProviderException) throw e
            // Key invalidated, authentication failed, truncation, bad JSON: all get moved aside, so the next
            // write cannot overwrite them
            moveAside()
            emptyList()
        }
    }

    @Synchronized
    override fun save(items: List<MemoryItem>) {
        memDir.mkdirs()
        val bytes = cipher.encrypt(json.encodeToString(serializer, items).toByteArray(Charsets.UTF_8))
        // Flush to disk before renaming: a power cut does not leave a renamed but empty file
        AtomicFiles.write(file, bytes, durable = true, temp = tmp)
    }

    /** "Wipe everything": deletes the memory files (including the bad file moved aside). The key rebuild is done by the caller after deleting the alias. */
    @Synchronized
    fun wipe() {
        memDir.listFiles()?.forEach { it.delete() }
        _brokenNotice.value = null
    }

    private fun moveAside() {
        _brokenNotice.value = AtomicFiles.quarantine(file, AtomicFiles.Quarantine.MoveBroken, now = clock())!!.name
    }

    private companion object {
        const val FILE = "memory.bin"
    }
}
