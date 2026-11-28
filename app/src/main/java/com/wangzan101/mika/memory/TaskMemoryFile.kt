package com.wangzan101.mika.memory

import android.security.keystore.UserNotAuthenticatedException
import com.wangzan101.mika.core.learn.memory.TaskMemory
import com.wangzan101.mika.core.learn.memory.TaskMemoryPersistence
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
 * Encrypts cross-session task summaries into `task_memory/task_memory.bin` (AES-GCM; the key is supplied by the caller,
 * production uses `KeystoreKeyCipher("mika-memory")`).
 * Same approach as the memory file: write to a temporary file then rename atomically; if decryption fails (key lost) or
 * the content is corrupt, do not overwrite — rename it aside and start from empty.
 * This is a machine-written experience cache, so if it is corrupted start silently from empty without showing a prompt.
 */
class TaskMemoryFile(
    dir: File,
    private val cipher: KeyCipher,
    private val clock: () -> Long,
) : TaskMemoryPersistence {
    private val memDir = File(dir, "task_memory")
    private val file = File(memDir, FILE)
    private val tmp = File(memDir, "$FILE.tmp")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(TaskMemory.serializer())

    @Synchronized
    override fun load(): List<TaskMemory> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        return try {
            json.decodeFromString(serializer, cipher.decrypt(bytes).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            // Keystore being temporarily unavailable does not mean the file is corrupt: throw, retry next read, never move it aside
            if (e is UserNotAuthenticatedException || e is KeyStoreException || e is ProviderException) throw e
            moveAside()
            emptyList()
        }
    }

    @Synchronized
    override fun save(items: List<TaskMemory>) {
        memDir.mkdirs()
        val bytes = cipher.encrypt(json.encodeToString(serializer, items).toByteArray(Charsets.UTF_8))
        AtomicFiles.write(file, bytes, durable = true, temp = tmp)
    }

    /** "Wipe everything": deletes the task summary files (including the bad file moved aside). The key rebuild is done by the caller after deleting the alias. */
    @Synchronized
    fun wipe() {
        memDir.listFiles()?.forEach { it.delete() }
    }

    private fun moveAside() {
        AtomicFiles.quarantine(file, AtomicFiles.Quarantine.MoveBroken, now = clock())
    }

    private companion object {
        const val FILE = "task_memory.bin"
    }
}
