package com.wangzan101.mika.memory

import android.content.Context
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.learn.memory.MemoryItem
import com.wangzan101.mika.core.learn.memory.MemoryNote
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.learn.memory.ProfileStore
import com.wangzan101.mika.core.learn.memory.TaskMemoryStore
import com.wangzan101.mika.core.learn.skills.SkillNote
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.settings.MemorySettings
import com.wangzan101.mika.skills.PersonalSkillFiles
import com.wangzan101.mika.knowledge.AppKnowledgeFiles
import com.wangzan101.mika.knowledge.FileScreenMapStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Hint shown below the result card after a run finishes; attached to that question-answer pair by run id. */
data class RunNotes(
    val conversationId: String,
    val runId: String,
    val memoryNotes: List<MemoryNote> = emptyList(),
    val usedMemory: List<String> = emptyList(),
    val skillNotes: List<SkillNote> = emptyList(),
)

/** Request to open "Memory & Skills" (clicking "Take a look" on the result card): [draftId] is the skill draft to view. */
data class MemoryOpen(val draftId: String? = null)

/**
 * Memory storage, personal skills, switches and result card hints. The App creates one and exposes it via [Owner]:
 * several parallel tasks share the same [MemoryStore], whose own lock serializes writes. The first access reads from
 * disk and decrypts, so do not call on the main thread.
 */
class MemoryRuntime(context: Context) {
    private val app = context.applicationContext

    private var file: EncryptedMemoryFile? = null
    private var store: MemoryStore? = null
    private var skills: PersonalSkillFiles? = null
    private var settings: MemorySettings? = null
    private var profile: ProfileStore? = null
    private var appKnowledge: AppKnowledgeFiles? = null
    private var screenMaps: FileScreenMapStore? = null
    private var taskMemoryFile: TaskMemoryFile? = null
    private var taskMemory: TaskMemoryStore? = null

    /** Learning after a task wraps up runs here: it is not cancelled when the service exits. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _notes = MutableStateFlow<Map<String, RunNotes>>(emptyMap())
    /** Run id → hint; the UI uses this to display hints below the result card. */
    val notes: StateFlow<Map<String, RunNotes>> = _notes.asStateFlow()

    private val _open = MutableStateFlow<MemoryOpen?>(null)
    val openRequest: StateFlow<MemoryOpen?> = _open.asStateFlow()

    fun requestOpen(o: MemoryOpen?) { _open.value = o }

    fun publish(n: RunNotes) = _notes.update { it + (n.runId to n) }

    /**
     * Memory items, fetched on demand by the result card for its hint text. On first access it reads from disk and
     * decrypts in the background, so it is empty until that finishes; afterwards it follows the store.
     */
    fun items(): StateFlow<List<MemoryItem>> {
        val out = MutableStateFlow<List<MemoryItem>>(emptyList())
        scope.launch(Dispatchers.IO) { runCatching { store() }.getOrNull()?.items?.collect { out.value = it } }
        return out.asStateFlow()
    }

    @Synchronized fun file(): EncryptedMemoryFile =
        file ?: EncryptedMemoryFile(app.filesDir, KeystoreKeyCipher(KEY_ALIAS), System::currentTimeMillis).also { file = it }

    @Synchronized fun store(): MemoryStore =
        store ?: MemoryStore(
            file(), System::currentTimeMillis, { UUID.randomUUID().toString() },
            // Read the switch fresh on every save: a change on the settings page takes effect immediately
            inferenceAllowed = { settings().switches.value.inferMemories },
        ).also { store = it }

    @Synchronized fun skills(): PersonalSkillFiles =
        skills ?: PersonalSkillFiles(app.filesDir).also { skills = it }

    @Synchronized fun profile(): ProfileStore =
        profile ?: ProfileStore(FileProfileBackend(File(app.filesDir, "memory/profile.bin"), KeystoreKeyCipher(KEY_ALIAS)), ids = { UUID.randomUUID().toString() }).also { profile = it }

    @Synchronized fun appKnowledge(): AppKnowledgeFiles =
        appKnowledge ?: AppKnowledgeFiles(app.filesDir).also { appKnowledge = it }

    @Synchronized fun screenMaps(): FileScreenMapStore =
        screenMaps ?: FileScreenMapStore(app.filesDir).also { screenMaps = it }

    @Synchronized fun settings(): MemorySettings =
        settings ?: MemorySettings(File(app.filesDir, "memory_settings.json")).also { settings = it }

    @Synchronized fun taskMemoryFile(): TaskMemoryFile =
        taskMemoryFile ?: TaskMemoryFile(app.filesDir, KeystoreKeyCipher(KEY_ALIAS), System::currentTimeMillis).also { taskMemoryFile = it }

    @Synchronized fun taskMemory(): TaskMemoryStore =
        taskMemory ?: TaskMemoryStore(taskMemoryFile(), System::currentTimeMillis, { UUID.randomUUID().toString() }).also { taskMemory = it }

    /** "Wipe everything": deletes memory, bad files moved aside, personal skills and drafts, and rebuilds the memory key; memory hints in conversations are also removed. */
    @Synchronized fun wipeAll() {
        store().clearAll()
        file().wipe()
        taskMemory().clearAll()
        taskMemoryFile().wipe()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
        File(app.filesDir, "skills").deleteRecursively()
        File(app.filesDir, "knowledge/app-drafts").deleteRecursively()
        File(app.filesDir, "knowledge/apps").deleteRecursively()
        stripMemory(null)
        _notes.update { m -> m.mapValues { (_, n) -> n.copy(skillNotes = emptyList()) } }
    }

    /** These memories were deleted ([ids] null = all): no longer mention them in result cards or conversation files. */
    fun stripMemory(ids: Set<String>?) {
        fun keep(id: String) = ids != null && id !in ids
        _notes.update { m -> m.mapValues { (_, n) -> n.copy(memoryNotes = n.memoryNotes.filter { keep(it.memoryId) }, usedMemory = n.usedMemory.filter(::keep)) } }
        runCatching { ConversationStore.shared(app).stripMemory(ids) }
    }

    /** Gives the process-wide Application instance. */
    interface Owner {
        val memory: MemoryRuntime
    }

    companion object {
        const val KEY_ALIAS = "mika-memory"

        fun of(context: Context): MemoryRuntime = (context.applicationContext as? Owner)?.memory
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement MemoryRuntime.Owner to give the memory runtime")
    }
}
