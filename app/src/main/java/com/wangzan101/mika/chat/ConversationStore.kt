package com.wangzan101.mika.chat

import android.content.Context
import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.run.record.RunMetricsStore
import com.wangzan101.mika.core.model.usage.ConversationMeter
import com.wangzan101.mika.core.model.usage.ConversationUsage
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.settings.PersonaStore
import com.wangzan101.mika.core.chat.GeneratedAudio
import com.wangzan101.mika.core.chat.GeneratedPhoto
import com.wangzan101.mika.core.learn.memory.MemoryNote
import com.wangzan101.mika.core.learn.memory.NoteKind
import com.wangzan101.mika.core.learn.skills.SkillNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption
import java.util.UUID

/** One small JSON file per conversation, kept in the App's private directory; it survives restarts. */
class ConversationStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val readConversation: (File) -> String = { it.readText() },
) {
    constructor(context: Context) : this(File(context.filesDir, "conversations"))

    fun newConversation(): String = UUID.randomUUID().toString()

    // Separate invalidation: model requests must not reload the transcript, reorder chats or reset auto-archive.
    private val usageRevision = MutableStateFlow(0L)
    private val metrics = RunMetricsStore(File(dir.parentFile, RunMetricsStore.FILE_NAME))
    private val usageJson = Json { ignoreUnknownKeys = true }

    fun observeUsage(id: String): Flow<ConversationUsage> =
        usageRevision.map { usageSnapshot(id) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun meter(id: String) = ConversationMeter { change -> updateUsage(id, change) }

    private fun usageFile(id: String) = File(dir, "${file(id).name}.usage")

    @Synchronized
    internal fun usageSnapshot(id: String): ConversationUsage {
        val saved = usageFile(id)
        if (saved.isFile) return usageJson.decodeFromString(ConversationUsage.serializer(), saved.readText())
        val branch = branchOrigin(id)
        val inherited = branch?.let { it.parentTurn + if (it.afterTurn) 1 else 0 } ?: 0
        val history = turns(id).drop(inherited).filterNot(NoticeTurn::isNotice)
        if (history.isEmpty()) return ConversationUsage()
        val summaries = metrics.byIds(history.mapNotNull { it.runId }.toSet()).values
        // Historical summaries lack cache-report coverage and may omit calls made after a task ended.
        return ConversationUsage(input = summaries.sumOf { it.allInputTokens ?: it.inputTokens },
            output = summaries.sumOf { it.allOutputTokens ?: it.outputTokens }, partial = true)
    }

    @Synchronized
    private fun updateUsage(id: String, change: (ConversationUsage) -> ConversationUsage) {
        var changed = applyUsageUpdate(id, change)
        // Subtasks launched from the foreground run in a hidden child conversation, so usage and the latest main-model request are mirrored back to the owning foreground conversation.
        if (hiddenDeskChild(id)) {
            deskParent(id)?.takeIf { it != safe(id) }?.let { parent ->
                if (applyUsageUpdate(parent, change)) changed = true
            }
        }
        if (changed) usageRevision.value++
    }

    private fun applyUsageUpdate(id: String, change: (ConversationUsage) -> ConversationUsage): Boolean {
        if (safe(id) in deleted) return false
        val old = usageSnapshot(id)
        val next = change(old)
        if (old == next) return false
        dir.mkdirs()
        val dest = usageFile(id)
        val tmp = File(dir, "${dest.name}.tmp")
        tmp.writeText(usageJson.encodeToString(ConversationUsage.serializer(), next))
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Ids of written conversations: emitted once for each stored turn, changed turn, and removed memory note, whoever wrote it (conversation page, floating capsule, scheduled task).
     * The conversation page and sidebar re-read based on this, so what is on screen is what was stored.
     */
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    private val _deletions = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Ids of deleted conversations: emitted once on [delete] ([changes] is emitted as well). The floating capsule uses it to drop this conversation's task that just ended, and the card no longer continues from it. */
    val deletions: SharedFlow<String> = _deletions.asSharedFlow()

    /**
     * Which conversation the user is in right now, see [CurrentChat]. Stored in the `current` file in the conversation directory (not `.json`, so not in [list]);
     * it reads back after a process restart, so the capsule does not have to wait for the conversation file to be written to know which one was newly opened.
     */
    private val _current = MutableStateFlow(readCurrent())

    val current: StateFlow<CurrentChat?> = _current.asStateFlow()

    fun currentChat(): CurrentChat? = _current.value

    /** Records which conversation the user is in right now; a deleted conversation is not recorded. When written, [current] is emitted once. */
    @Synchronized
    fun setCurrent(chat: CurrentChat?) {
        val target = when (chat) {
            is CurrentChat.Open -> if (safe(chat.id) in deleted) null else chat
            else -> chat
        }
        if (target == _current.value) return
        dir.mkdirs()
        runCatching {
            when (target) {
                null -> currentFile().delete()
                CurrentChat.Fresh -> currentFile().writeText(CURRENT_FRESH)
                is CurrentChat.Open -> currentFile().writeText(safe(target.id))
            }
        }
        _current.value = target
    }

    private fun readCurrent(): CurrentChat? {
        val f = currentFile().takeIf { it.isFile } ?: return null
        val text = runCatching { f.readText().trim() }.getOrNull().orEmpty()
        return when {
            text.isEmpty() -> null
            text == CURRENT_FRESH -> CurrentChat.Fresh
            else -> safe(text).takeIf(String::isNotEmpty)?.let(CurrentChat::Open)
        }
    }

    /**
     * The conversation list, most recently changed first; [ConversationSummary.title] is the title (user-set or generated), falling back to the first message;
     * [ConversationSummary.pinnedAt] is when it was pinned; [ConversationSummary.page] is the page it started from;
     * [ConversationSummary.branchOf] is which conversation and turn it branched from; [ConversationSummary.archivedAt] is when it was archived;
     * [ConversationSummary.restoredAt] is the last manual unarchive time (auto-archive gives a grace period based on it).
     * Conversations with no content are not listed; archived conversations are not listed by default, see [includeArchived].
     */
    fun list(includeArchived: Boolean = false): List<ConversationSummary> =
        listPage(0, Int.MAX_VALUE, includeArchived = includeArchived)

    /**
     * The body index used by sidebar search: titles are passed in by the caller along with the [ConversationSummary], and bodies are cached by the conversation file's modification time.
     * Reading files does not take this class's lock, so a task winding down and appending is not blocked.
     */
    val search: ConversationSearch = ConversationSearch(::file) { read(it)?.map { record -> record.first } }

    /** Active conversations whose first turn is not saved yet come first and share one pagination with the conversations on disk. Archived conversations are not listed by default. */
    @Synchronized
    fun listPage(
        offset: Int,
        limit: Int,
        pending: List<ConversationSummary> = emptyList(),
        includeArchived: Boolean = false,
    ): List<ConversationSummary> {
        val unsaved = pending.asSequence()
            .filter { firstMessage(file(it.id)) == null }
            .filter { includeArchived || archivedAt(it.id) == null }
            .filterNot { hiddenDeskChild(it.id) }
        val saved = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.endsWith(SETTINGS_SUFFIX) }.orEmpty()
            .map { it to it.lastModified() }
            .sortedWith(compareByDescending<Pair<File, Long>> { it.second }.thenBy { it.first.name })
            .asSequence().mapNotNull { (f, updatedAt) ->
                val first = firstMessage(f) ?: return@mapNotNull null
                val id = f.nameWithoutExtension
                // Child conversations dispatched by the front desk are viewed under the front desk conversation and not listed separately (if the front desk conversation is gone, list them separately; see hiddenDeskChild)
                if (hiddenDeskChild(id)) return@mapNotNull null
                val archivedAt = archivedAt(id)
                if (!includeArchived && archivedAt != null) return@mapNotNull null
                ConversationSummary(id, title(id) ?: first, updatedAt, pinnedAt(id), pageOrigin(id), branchOrigin(id), archivedAt, restoredAt(id), projectId(id))
            }
        return (unsaved + saved).drop(offset).take(limit).toList()
    }

    /** For the read-only console: the most recent conversations (excluding archived ones), returned together with their turn counts. */
    @Synchronized
    fun listForConsole(limit: Int): List<ConversationSummary> =
        listPage(0, limit).map { s -> s.copy(turnCount = read(file(s.id))?.size ?: 0) }

    private data class FirstMessage(val modified: Long, val size: Long, val text: String?)
    private val firstMessages = mutableMapOf<String, FirstMessage>()

    private fun firstMessage(f: File): String? {
        if (!f.isFile) return null
        val modified = f.lastModified()
        val size = f.length()
        firstMessages[f.name]?.let { if (it.modified == modified && it.size == size) return it.text }
        val text = read(f)?.firstOrNull()?.first?.userText
        firstMessages[f.name] = FirstMessage(modified, size, text)
        return text
    }

    /** The generated title of the conversation (see [ConversationTitler]); null when absent or unreadable. The title lives in a `.title` file next to the conversation file, leaving the conversation file's format untouched. */
    @Synchronized
    fun title(conversationId: String): String? =
        runCatching { titleFile(conversationId).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf(String::isNotEmpty)

    /**
     * Conversations that have been deleted. Late "catch-up writes" arriving after the delete (a title generated in the background, the outcome and note added when the task winds down)
     * no longer write it back; storing another turn ([append]) starts using this id afresh and removes it from here. Kept in the process only.
     */
    private val deleted = mutableSetOf<String>()
    // Used only in this process and under this lock, so a conversation written after filtering is not archived from a stale snapshot.
    private val archiveVersions = mutableMapOf<String, Long>()
    private data class MessageTime(val modified: Long, val size: Long, val time: Long?)
    private val messageTimes = mutableMapOf<String, MessageTime>()
    private fun bumpArchiveVersion(id: String) {
        val key = safe(id)
        archiveVersions[key] = (archiveVersions[key] ?: 0L) + 1L
    }

    /**
     * Deletes everything stored for a conversation: the conversation file, title, pin, page origin, branch origin, plus half-written temporary files and corrupted files moved aside.
     * [changes] and [deletions] are each emitted once after the delete.
     * The conversation's workspace, schedules, and running tasks are not handled here, see [ConversationDeleter].
     */
    @Synchronized
    fun delete(conversationId: String) {
        bumpArchiveVersion(conversationId)
        val main = file(conversationId).name
        val metadata = setOf(titleFile(conversationId).name, pinFile(conversationId).name,
            pageFile(conversationId).name, branchFile(conversationId).name, archiveFile(conversationId).name,
            restoreFile(conversationId).name, personaFile(conversationId).name, personaFile(conversationId).name + ".tmp",
            projectFile(conversationId).name, projectFile(conversationId).name + ".tmp", deskParentFile(conversationId).name,
            settingsFile(conversationId).name, settingsFile(conversationId).name + ".tmp")
        val files = dir.listFiles { f -> f.name == main || f.name.startsWith("$main.") || f.name in metadata }
            ?: if (Files.notExists(dir.toPath())) emptyArray() else throw IOException("Cannot list conversation files")
        // Leave the history in place if any of its companion files cannot be removed.
        files.sortedBy { it.name == main }.forEach { file ->
            val path = file.toPath()
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("Unexpected conversation directory: ${file.name}")
            Files.deleteIfExists(path)
        }
        usageRevision.value++
        deleted += safe(conversationId)
        liveDeskParents.remove(safe(conversationId))
        pendingMainChoices.remove(safe(conversationId))
        firstMessages.remove(main)
        messageTimes.remove(main)
        // The one deleted is the one the user is in right now: stop pointing at it and let the capsule fall back to the most recent one
        if (_current.value == CurrentChat.Open(safe(conversationId))) setCurrent(null)
        _changes.tryEmit(conversationId)
        _deletions.tryEmit(conversationId)
    }

    /**
     * Records the generated title for a conversation; if it already has a title (generated or user-renamed) nothing is changed (a title is only generated once). Returns true when written.
     * So a background title generation arriving late does not overwrite a name the user changed first.
     */
    @Synchronized
    fun setTitle(conversationId: String, title: String): Boolean {
        if (title(conversationId) != null) return false
        return writeTitle(conversationId, title)
    }

    /** The user renames a conversation: [title] replaces whatever title existed; empty input changes nothing. Returns true when written. */
    @Synchronized
    fun rename(conversationId: String, title: String): Boolean = writeTitle(conversationId, title)

    private fun writeTitle(conversationId: String, title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty() || safe(conversationId) in deleted) return false
        dir.mkdirs()
        val ok = runCatching { titleFile(conversationId).writeText(t) }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /** When the conversation was pinned; null when not pinned or unreadable. Kept in a `.pin` file next to the conversation file. */
    @Synchronized
    fun pinnedAt(conversationId: String): Long? =
        runCatching { pinFile(conversationId).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()

    /** Pins ([pinned] true, recording time [at]) or unpins. Deleted conversations are left alone. Returns true when changed. */
    @Synchronized
    fun setPinned(conversationId: String, pinned: Boolean, at: Long = System.currentTimeMillis()): Boolean {
        if (safe(conversationId) in deleted) return false
        bumpArchiveVersion(conversationId)
        val f = pinFile(conversationId)
        val ok = if (pinned) {
            dir.mkdirs()
            runCatching { f.writeText(at.toString()) }.isSuccess
        } else !f.exists() || f.delete()
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /** When the conversation was archived; null when not archived or unreadable. Kept in an `.archive` file next to the conversation file. */
    @Synchronized
    fun archivedAt(conversationId: String): Long? =
        runCatching { archiveFile(conversationId).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()

    /**
     * The last manual unarchive time; null when never unarchived or unreadable. Kept in a `.restored` file.
     * Auto-archive gives a manually restored conversation a grace period based on it, so it is not taken away again right after being restored.
     */
    @Synchronized
    fun restoredAt(conversationId: String): Long? =
        runCatching { restoreFile(conversationId).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()

    /** Archives ([archived] true, recording time [at]) or unarchives. Unarchiving also records the restore time, see [restoredAt]. Deleted conversations are left alone. Returns true when changed. */
    @Synchronized
    fun setArchived(conversationId: String, archived: Boolean, at: Long = System.currentTimeMillis()): Boolean {
        if (safe(conversationId) in deleted) return false
        bumpArchiveVersion(conversationId)
        val f = archiveFile(conversationId)
        val ok = if (archived) {
            dir.mkdirs()
            runCatching { f.writeText(at.toString()) }.isSuccess
        } else {
            val removed = !f.exists() || f.delete()
            // The manual restore time is recorded separately: auto-archive reads it and gives this conversation a grace period
            if (removed && dir.isDirectory) runCatching { restoreFile(conversationId).writeText(at.toString()) }
            removed
        }
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /** Old records missing message times fall back to the file time; a late background write should not change the last message time of records that have one. */
    private fun lastMessageAt(f: File): Long? {
        val records = read(f)?.takeIf { it.isNotEmpty() } ?: return null
        return records.maxOf { it.first.atMs ?: f.lastModified() }
    }

    internal data class ArchiveCandidate(val id: String, val version: Long)

    /** Snapshots only file names and versions, without holding the lock to read every conversation body. */
    @Synchronized
    internal fun autoArchiveCandidates(): List<ArchiveCandidate> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.endsWith(SETTINGS_SUFFIX) }.orEmpty().map { f ->
            val id = f.nameWithoutExtension
            ArchiveCandidate(id, archiveVersions[id] ?: 0L)
        }

    /** Bodies are read outside the lock, and state and file changes are checked again before writing. */
    internal fun archiveIfStillEligible(
        candidate: ArchiveCandidate,
        cutoff: Long,
        at: Long,
        isRunning: (String) -> Boolean = { false },
    ): Boolean {
        val id = candidate.id
        val f = file(id)
        fun eligible(): Boolean = !isRunning(id) &&
            (archiveVersions[id] ?: 0L) == candidate.version &&
            pinnedAt(id) == null && archivedAt(id) == null &&
            (restoredAt(id)?.let { it >= cutoff } != true) &&
            currentChat() != CurrentChat.Open(id)

        val snapshot = synchronized(this) {
            if (!eligible() || !f.isFile) return false
            val modified = f.lastModified()
            val size = f.length()
            val cached = messageTimes[f.name]?.takeIf { it.modified == modified && it.size == size }
            (cached ?: MessageTime(modified, size, null)) to (cached != null)
        }
        val (fileState, cached) = snapshot
        val time = if (cached) fileState.time else runCatching { lastMessageAt(f) }.getOrNull()
        return synchronized(this) {
            if (!eligible() || !f.isFile || f.lastModified() != fileState.modified || f.length() != fileState.size) return false
            if (time == null) return false
            messageTimes[f.name] = fileState.copy(time = time)
            if (time >= cutoff) return false
            setArchived(id, archived = true, at = at)
        }
    }

    /**
     * The page the conversation started from (the user asked Claire about a page on the browser page); null when not applicable or unreadable.
     * Kept in a `.page` file next to the conversation file (JSON: url, host, title), leaving the conversation file's format untouched; older conversations have no such file.
     */
    @Synchronized
    fun pageOrigin(conversationId: String): PageOrigin? = runCatching {
        val f = pageFile(conversationId).takeIf { it.isFile } ?: return@runCatching null
        val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return@runCatching null
        PageOrigin(str(o, "url") ?: return@runCatching null, str(o, "host") ?: return@runCatching null, str(o, "title").orEmpty())
    }.getOrNull()

    /** Records the page the conversation started from (later questions on the same page replace it with the latest page). Deleted conversations are left alone. Returns true when written. */
    @Synchronized
    fun setPageOrigin(conversationId: String, origin: PageOrigin): Boolean {
        if (safe(conversationId) in deleted) return false
        dir.mkdirs()
        val json = buildJsonObject { put("url", origin.url); put("host", origin.host); put("title", origin.title) }
        val ok = runCatching { pageFile(conversationId).writeText(json.toString()) }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /**
     * The persona used by this conversation; null when absent, unreadable, or the id is invalid. Kept in `<id>.persona` (JSON: version, current, history).
     * After a persona is deleted its sidecar file remains: the caller looks [PersonaBinding.personaId] up in PersonaStore, fails to find it, and treats it as missing.
     */
    @Synchronized
    fun persona(conversationId: String): PersonaBinding? = runCatching {
        val f = personaFile(conversationId).takeIf { it.isFile } ?: return@runCatching null
        val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return@runCatching null
        val current = str(o, "current")?.takeIf(PersonaStore::isValidId) ?: return@runCatching null
        val history = objects(o, "history").mapNotNull { h ->
            val to = str(h, "to")?.takeIf(PersonaStore::isValidId) ?: return@mapNotNull null
            val at = (h["at"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
            PersonaSwitch(at, str(h, "from")?.takeIf(PersonaStore::isValidId), to)
        }
        PersonaBinding(current, history.takeLast(MAX_PERSONA_HISTORY))
    }.getOrNull()

    /**
     * Replaces the persona used by this conversation; null means back to the default (the sidecar file is deleted). Returns true when written.
     * A switch appends a history entry (at most [MAX_PERSONA_HISTORY]), and writing the same persona again appends nothing; the temporary file is written first, then renamed.
     */
    @Synchronized
    fun setPersona(conversationId: String, personaId: String?, at: Long = System.currentTimeMillis()): Boolean {
        if (safe(conversationId) in deleted) return false
        val f = personaFile(conversationId)
        if (personaId == null) {
            val ok = !f.exists() || f.delete()
            if (ok) _changes.tryEmit(conversationId)
            return ok
        }
        if (!PersonaStore.isValidId(personaId)) return false
        val previous = persona(conversationId)
        val history = buildList {
            addAll(previous?.history.orEmpty())
            previous?.personaId?.takeIf { it != personaId }?.let { add(PersonaSwitch(at, it, personaId)) }
        }.takeLast(MAX_PERSONA_HISTORY)
        val json = buildJsonObject {
            put("version", 1)
            put("current", personaId)
            put("history", buildJsonArray {
                history.forEach { h ->
                    add(buildJsonObject {
                        put("at", h.at)
                        h.from?.let { put("from", it) }
                        put("to", h.to)
                    })
                }
            })
        }
        dir.mkdirs()
        val tmp = File(dir, "${f.name}.tmp")
        val ok = runCatching {
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString())
                tmp.delete()
            }
        }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /** Which front desk conversation the child conversation [id] dispatched by the front desk belongs to; null when it is not a child conversation or cannot be read. Stored in `<id>.desk_parent`. */
    @Synchronized
    fun deskParent(id: String): String? =
        runCatching { deskParentFile(id).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.let(::safe)?.takeIf(String::isNotEmpty)

    /** Front desk conversations that have dispatched work in this process: their first turn may not be saved yet, but child conversations are still hidden under them. */
    private val liveDeskParents = mutableSetOf<String>()

    /** Record that child conversation [child] was dispatched by front desk conversation [parent]; afterwards [child] stays out of the conversation list while the front desk conversation still exists. Deleted conversations are left alone. */
    @Synchronized
    fun setDeskParent(child: String, parent: String) {
        if (safe(child) in deleted || safe(parent).isEmpty()) return
        dir.mkdirs()
        liveDeskParents += safe(parent)
        if (runCatching { deskParentFile(child).writeText(safe(parent)) }.isSuccess) _changes.tryEmit(child)
    }

    /**
     * A child conversation is hidden only while its front desk conversation still exists (viewed under that front desk conversation): if the front desk
     * conversation is deleted, or never got saved after a restart, the child conversation returns to the list instead of being lost forever.
     */
    private fun hiddenDeskChild(id: String): Boolean {
        val parent = deskParent(id) ?: return false
        if (parent in deleted) return false
        return file(parent).isFile || parent in liveDeskParents
    }

    /**
     * The project this conversation belongs to; null when absent, unreadable, or the id is invalid. Kept in `<id>.project`.
     * If the sidecar file unexpectedly remains after the project was deleted, the caller verifies it through [WorkspaceResolver] before use.
     */
    @Synchronized
    fun projectId(conversationId: String): String? =
        runCatching { projectFile(conversationId).takeIf { it.isFile }?.readText()?.trim() }
            .getOrNull()?.takeIf(ProjectStore::isValidId)

    /** Binds the project the conversation belongs to; null unbinds it (the sidecar file is deleted). Deleted conversations and invalid project ids are left alone. Returns true when written. */
    @Synchronized
    fun setProject(conversationId: String, projectId: String?): Boolean {
        if (safe(conversationId) in deleted) return false
        val f = projectFile(conversationId)
        if (projectId == null) {
            val existed = f.exists()
            val ok = !existed || f.delete()
            if (existed && ok) _changes.tryEmit(conversationId)
            return ok
        }
        if (!ProjectStore.isValidId(projectId)) return false
        dir.mkdirs()
        val tmp = File(dir, "${f.name}.tmp")
        val ok = runCatching {
            tmp.writeText(projectId)
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                if (!tmp.renameTo(f)) {
                    f.writeText(projectId)
                    tmp.delete()
                }
            }
        }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /**
     * 未物化对话的主模型选择；进程内保存，null 键表示尚未编号的新对话。
     * 与旁文件同属这个进程级存储，界面重建不会丢掉，也不会把 ViewModel 生命周期带进模型设置。
     */
    private val pendingMainChoices = mutableMapOf<String?, ModelChoice?>()

    @Synchronized internal fun hasPendingMainChoice(id: String?): Boolean = pendingMainChoices.containsKey(id)

    @Synchronized internal fun pendingMainChoice(id: String?): ModelChoice? = pendingMainChoices[id]

    @Synchronized internal fun setPendingMainChoice(id: String?, choice: ModelChoice?) {
        pendingMainChoices[id] = choice
    }

    @Synchronized internal fun clearPendingMainChoice(id: String?) {
        pendingMainChoices.remove(id)
    }

    @Synchronized internal fun movePendingMainChoice(from: String?, to: String?) {
        if (!pendingMainChoices.containsKey(from)) return
        val choice = pendingMainChoices.remove(from)
        pendingMainChoices[to] = choice
    }

    /** 全局角色、备援或组成员变动时，对话直接引用的模型配置仍属于有效所有权。 */
    @Synchronized
    internal fun referencedModelConfigIds(): Set<String> {
        val saved = dir.listFiles { f -> f.isFile && f.name.endsWith(SETTINGS_SUFFIX) }.orEmpty().mapNotNull { f ->
            val id = f.name.removeSuffix(SETTINGS_SUFFIX)
            (settings(id).main as? ModelChoice.Model)?.configId
        }
        val pending = pendingMainChoices.values.mapNotNull { (it as? ModelChoice.Model)?.configId }
        return (saved + pending).toSet()
    }

    /**
     * The main model this conversation uses when its next task starts. Use its own choice when set; a child conversation launched from the foreground follows its owning foreground conversation when it has none,
     * and the side file is consulted while the choice has not been written yet (same as resolving a model in the foreground). Null when neither exists, following the global setting.
     * No side file is written for child conversations: a child is hidden under its foreground conversation, and writing one would keep these configs counted as in use by [referencedModelConfigIds] forever.
     */
    fun runMainChoice(conversationId: String): ModelChoice? {
        settings(conversationId).main?.let { return it }
        val parent = deskParent(conversationId)?.takeIf { it != safe(conversationId) } ?: return null
        return if (hasPendingMainChoice(parent)) pendingMainChoice(parent) else settings(parent).main
    }

    /** 这个对话自己的运行设置；没有旁文件或读不懂时全部跟随全局。 */
    @Synchronized
    fun settings(conversationId: String): ConversationSettings = runCatching {
        val f = settingsFile(conversationId).takeIf { it.isFile } ?: return@runCatching ConversationSettings()
        val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return@runCatching ConversationSettings()
        val main = (o["main"] as? JsonObject)?.let { choice ->
            val id = str(choice, "id")?.takeIf(String::isNotEmpty) ?: return@let null
            when (str(choice, "kind")) {
                "model" -> ModelChoice.Model(id)
                "group" -> ModelChoice.Group(id)
                else -> null
            }
        }
        ConversationSettings(main)
    }.getOrDefault(ConversationSettings())

    /**
     * 改这个对话下一次任务的运行设置；默认设置删掉旁文件，不留下空文件。
     * 非默认设置先写临时文件再原子替换，失败时保留此前完整的选择。
     */
    @Synchronized
    fun setSettings(conversationId: String, settings: ConversationSettings): Boolean {
        if (safe(conversationId) in deleted) return false
        val f = settingsFile(conversationId)
        val ok = runCatching {
            if (settings == ConversationSettings()) {
                !f.exists() || Files.deleteIfExists(f.toPath()).let { true }
            } else {
                dir.mkdirs()
                val json = buildJsonObject {
                    settings.main?.let { choice ->
                        put("main", buildJsonObject {
                            put("kind", if (choice is ModelChoice.Model) "model" else "group")
                            put("id", when (choice) {
                                is ModelChoice.Model -> choice.configId
                                is ModelChoice.Group -> choice.groupId
                            })
                        })
                    }
                }
                val tmp = File(dir, "${f.name}.tmp")
                tmp.writeText(json.toString())
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                true
            }
        }.getOrDefault(false)
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /**
     * 把 [sourceId] 的轮次复制成新对话 [targetId],复制到 [throughTurn](0 起,含在内)为止。
     * 源不存在、源文件损坏、[throughTurn] 越界、目标已有对话文件时返回 false,什么都不写。
     * [title] 非空白时写成新对话标题;[origin] 非空时写 `<targetId>.branch`。
     * 标题、网页来源、分支来源都是旁文件,写不进去不影响对话本身已复制好。
     */
    @Synchronized
    fun branch(
        sourceId: String,
        targetId: String,
        throughTurn: Int,
        title: String? = null,
        origin: BranchOrigin? = null,
    ): Boolean = copyBranch(sourceId, targetId, throughTurn, includeTurn = true, title, origin)

    /**
     * Copies [sourceId]'s turns before [beforeTurn] (excluding that turn) into a new conversation [targetId], used for "ask again from the user message".
     * When [beforeTurn] is 0 the new conversation has no turns yet (like a freshly opened conversation, it is not in the sidebar list), and only once the first message is stored does it count as this conversation's first turn.
     * Other conditions are the same as [branch].
     */
    @Synchronized
    fun branchBefore(
        sourceId: String,
        targetId: String,
        beforeTurn: Int,
        title: String? = null,
        origin: BranchOrigin? = null,
    ): Boolean = copyBranch(sourceId, targetId, beforeTurn, includeTurn = false, title, origin)

    /** Shared by [branch] and [branchBefore]: copies [sourceId]'s first [turn] turns (including that turn when [includeTurn]), then writes the title, page origin, and branch origin. */
    private fun copyBranch(sourceId: String, targetId: String, turn: Int, includeTurn: Boolean, title: String?, origin: BranchOrigin?): Boolean {
        val source = file(sourceId)
        if (!source.isFile) return false
        val records = read(source) ?: return false
        if (turn !in records.indices) return false
        if (file(targetId).exists()) return false
        deleted -= safe(targetId)
        write(targetId, records.take(if (includeTurn) turn + 1 else turn))
        title?.let { writeTitle(targetId, it) }
        pageOrigin(sourceId)?.let { setPageOrigin(targetId, it) }
        // The persona and project membership follow the conversation; the switch history is not copied and the new conversation starts from the current one
        persona(sourceId)?.let { setPersona(targetId, it.personaId) }
        projectId(sourceId)?.let { setProject(targetId, it) }
        // 分支只复制创建时的选择；之后源对话和分支各自独立修改。
        settings(sourceId).main?.let { setSettings(targetId, ConversationSettings(it)) }
        origin?.let { writeBranchOrigin(targetId, it) }
        return true
    }

    /** Branch origin: which conversation and turn it branched from; null when absent or unreadable. Kept in `<id>.branch`. */
    @Synchronized
    fun branchOrigin(conversationId: String): BranchOrigin? = runCatching {
        val f = branchFile(conversationId).takeIf { it.isFile } ?: return@runCatching null
        val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return@runCatching null
        val parentId = str(o, "parent")?.takeIf(String::isNotEmpty) ?: return@runCatching null
        val parentTurn = (o["turn"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it >= 0 } ?: return@runCatching null
        val afterTurn = (o["after"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: return@runCatching null
        val at = (o["at"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@runCatching null
        BranchOrigin(parentId, parentTurn, afterTurn, at)
    }.getOrNull()

    /** Writes the branch origin; the temporary file is written first and then renamed, so being killed halfway leaves no half file. */
    private fun writeBranchOrigin(conversationId: String, origin: BranchOrigin) {
        dir.mkdirs()
        val json = buildJsonObject {
            put("parent", origin.parentId); put("turn", origin.parentTurn); put("after", origin.afterTurn); put("at", origin.at)
        }
        val f = branchFile(conversationId)
        val tmp = File(dir, "${f.name}.tmp")
        runCatching {
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString())
                tmp.delete()
            }
        }
    }

    /** Treats a corrupted file as an empty conversation so it does not block new tasks. */
    @Synchronized
    fun turns(conversationId: String): List<Turn> = records(conversationId).map { it.first }

    private fun callsFile(id: String) = File(dir, "${file(id).name}.calls")
    private val callsJson = Json { ignoreUnknownKeys = true }

    /** The voice calls in this conversation ([ConversationCall]), in connect order; empty if none were made or they cannot be parsed. */
    fun calls(conversationId: String): List<ConversationCall> = runCatching {
        val f = callsFile(conversationId).takeIf { it.isFile } ?: return@runCatching emptyList()
        callsJson.decodeFromString(ListSerializer(ConversationCall.serializer()), f.readText())
    }.getOrDefault(emptyList())

    /** Record (or update the one with the same id) a call; deleted together with the conversation file. Emits [changes] once written. */
    @Synchronized
    fun recordCall(conversationId: String, call: ConversationCall) {
        if (safe(conversationId) in deleted) return
        val next = calls(conversationId).upsert(call)
        dir.mkdirs()
        val dest = callsFile(conversationId)
        val tmp = File(dir, "${dest.name}.tmp")
        val ok = runCatching {
            tmp.writeText(callsJson.encodeToString(ListSerializer(ConversationCall.serializer()), next))
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
    }

    /** The call records of this conversation, re-read whenever one is written. */
    fun observeCalls(conversationId: String): Flow<List<ConversationCall>> =
        changes.filter { it == conversationId }.map { calls(conversationId) }
            .onStart { emit(calls(conversationId)) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /** Whether this conversation has stored content (a conversation file exists); false for an empty id or when unreadable. */
    @Synchronized
    fun exists(conversationId: String): Boolean = file(conversationId).isFile

    /** Each turn together with the files it exported (file cards). */
    @Synchronized
    fun records(conversationId: String): List<Pair<Turn, List<SharedFile>>> {
        val f = file(conversationId)
        if (!f.isFile) return emptyList()
        return read(f).orEmpty()
    }

    internal data class RecordsSnapshot(
        val conversations: List<Pair<String, List<Pair<Turn, List<SharedFile>>>>>,
        val hasReadFailures: Boolean,
    )

    /** Reads all saved conversations; when a single file cannot be read the rest are kept and a failure is flagged. */
    @Synchronized
    internal fun allRecords(): RecordsSnapshot {
        val exists = runCatching { dir.exists() }.getOrElse { return RecordsSnapshot(emptyList(), true) }
        if (!exists) return RecordsSnapshot(emptyList(), false)
        val files = runCatching { dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.endsWith(SETTINGS_SUFFIX) } }.getOrNull()
            ?: return RecordsSnapshot(emptyList(), true)
        var failed = false
        val conversations = files.sortedBy { it.name }.mapNotNull { f ->
            val records = runCatching { read(f) }.getOrNull()
            if (records == null) {
                failed = true
                null
            } else f.nameWithoutExtension to records
        }
        return RecordsSnapshot(conversations, failed)
    }

    /** Returns null when it cannot be parsed (not a JSON array). */
    private fun read(f: File): List<Pair<Turn, List<SharedFile>>>? = try {
        (Json.parseToJsonElement(readConversation(f)) as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val turn = Turn(
                field("userText") ?: return@mapNotNull null, field("outcome").orEmpty(), field("status") ?: return@mapNotNull null, field("note"), field("runId"),
                memoryNotes = objects(o, "memoryNotes").mapNotNull { n ->
                    val kind = NoteKind.entries.find { it.name == str(n, "kind") } ?: return@mapNotNull null
                    MemoryNote(kind, str(n, "memoryId") ?: return@mapNotNull null, str(n, "undoToken"), str(n, "pendingId"))
                },
                usedMemory = (o["usedMemory"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                skillNotes = objects(o, "skillNotes").map { n -> SkillNote(str(n, "text").orEmpty(), str(n, "draftId"), str(n, "suspended")) },
                unconfirmed = (o["unconfirmed"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                result = field("result"),
                steers = (o["steers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                interruptedReplies = (o["interruptedReplies"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: emptyList(),
                finishedReplies = (o["finishedReplies"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: emptyList(),
                groups = (o["groups"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                keptPage = field("keptPage"),
                keptPageClosed = field("keptPageClosed"),
                atMs = field("atMs")?.toLongOrNull(),
                audios = objects(o, "audios").mapNotNull(::audioOf),
                photos = objects(o, "photos").mapNotNull(::photoOf),
                videos = objects(o, "videos").mapNotNull(::videoOf),
                verified = field("verified")?.toBooleanStrictOrNull(),
                remoteTaskId = field("remoteTaskId"),
                outcomeSystemType = field("outcomeSystemType"),
                deskTasks = (o["deskTasks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                callId = field("callId"),
            )
            turn to (o["files"] as? JsonArray).orEmpty().mapNotNull(::fileOf)
        }
    } catch (e: SerializationException) {
        null
    }

    private fun fileOf(e: kotlinx.serialization.json.JsonElement): SharedFile? {
        val o = e as? JsonObject ?: return null
        fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        return SharedFile(field("name") ?: return null, field("size")?.toLongOrNull() ?: 0, field("savedAs").orEmpty(), field("uri").orEmpty(), field("sha256"))
    }

    /**
     * Stores a turn into the conversation. A new turn means there is activity again, so an archived conversation is automatically unarchived (as in mainstream chat apps);
     * [updateTurn], which only changes an existing turn, does not count.
     */
    private fun audioOf(o: JsonObject): GeneratedAudio? {
        val path = str(o, "path") ?: return null
        return GeneratedAudio(path, str(o, "durationMs")?.toLongOrNull(), str(o, "text"), str(o, "atMs")?.toLongOrNull())
    }

    private fun photoOf(o: JsonObject): GeneratedPhoto? {
        val path = str(o, "path") ?: return null
        return GeneratedPhoto(path, str(o, "width")?.toIntOrNull() ?: 0, str(o, "height")?.toIntOrNull() ?: 0, str(o, "prompt"), str(o, "atMs")?.toLongOrNull())
    }

    private fun videoOf(o: JsonObject): com.wangzan101.mika.core.chat.GeneratedVideo? {
        val path = str(o, "path") ?: return null
        return com.wangzan101.mika.core.chat.GeneratedVideo(
            path,
            str(o, "durationMs")?.toLongOrNull(),
            str(o, "width")?.toIntOrNull() ?: 0,
            str(o, "height")?.toIntOrNull() ?: 0,
            str(o, "prompt"),
            str(o, "atMs")?.toLongOrNull(),
        )
    }

    @Synchronized
    fun append(conversationId: String, turn: Turn, files: List<SharedFile> = emptyList()) {
        bumpArchiveVersion(conversationId)
        deleted -= safe(conversationId)
        // Unarchive before writing: the changes emitted by write then read the unarchived state
        archiveFile(conversationId).delete()
        val f = file(conversationId)
        val prior = if (f.isFile) read(f) else emptyList()
        if (prior == null) {
            // Move the corrupted file aside and keep it rather than overwriting it with the new content
            f.renameTo(File(dir, "${f.name}.corrupt-${System.currentTimeMillis()}"))
        }
        // A new turn records when it was stored; ones that already carry a timestamp (branch copies, restores) keep it as is
        val stored = if (turn.atMs == null) turn.copy(atMs = clock()) else turn
        write(conversationId, prior.orEmpty() + (stored to files))
    }

    /** Changes the turn whose run id is [runId] (adding the decided outcome and the memory and skill notes after the task ends); does nothing when not found. */
    @Synchronized
    fun updateTurn(conversationId: String, runId: String, transform: (Turn) -> Turn) {
        val f = file(conversationId)
        if (!f.isFile) return
        val records = read(f) ?: return
        if (records.none { it.first.runId == runId }) return
        write(conversationId, records.map { (t, fs) -> (if (t.runId == runId) transform(t) else t) to fs })
    }

    /** When memories are deleted or cleared entirely: removes the notes pointing at those entries and the "used" records; [ids] null removes all of them. */
    @Synchronized
    fun stripMemory(ids: Set<String>?) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.endsWith(SETTINGS_SUFFIX) }.orEmpty().forEach { f ->
            val records = read(f) ?: return@forEach
            fun keep(id: String) = ids != null && id !in ids
            val stripped = records.map { (t, fs) -> t.copy(memoryNotes = t.memoryNotes.filter { keep(it.memoryId) }, usedMemory = t.usedMemory.filter(::keep)) to fs }
            if (stripped != records) write(f.name.removeSuffix(".json"), stripped)
        }
    }

    private fun write(conversationId: String, all: List<Pair<Turn, List<SharedFile>>>) {
        bumpArchiveVersion(conversationId)
        messageTimes.remove(file(conversationId).name)
        dir.mkdirs()
        val json = JsonArray(all.map { (it, fs) ->
            buildJsonObject {
                put("userText", it.userText); put("outcome", it.outcome); put("status", it.status)
                it.note?.let { n -> put("note", n) }; it.runId?.let { r -> put("runId", r) }; it.result?.let { r -> put("result", r) }
                it.verified?.let { v -> put("verified", v) }
                it.remoteTaskId?.let { id -> put("remoteTaskId", id) }
                it.outcomeSystemType?.let { type -> put("outcomeSystemType", type) }
                // Only the kind and id are stored: the memory content lives only in the encrypted memory file and the UI fetches it live
                if (it.memoryNotes.isNotEmpty()) put("memoryNotes", JsonArray(it.memoryNotes.map { n ->
                    buildJsonObject {
                        put("kind", n.kind.name); put("memoryId", n.memoryId)
                        n.undoToken?.let { t -> put("undoToken", t) }; n.pendingId?.let { p -> put("pendingId", p) }
                    }
                }))
                if (it.usedMemory.isNotEmpty()) put("usedMemory", JsonArray(it.usedMemory.map(::JsonPrimitive)))
                if (it.unconfirmed.isNotEmpty()) put("unconfirmed", JsonArray(it.unconfirmed.map(::JsonPrimitive)))
                if (it.steers.isNotEmpty()) put("steers", JsonArray(it.steers.map(::JsonPrimitive)))
                if (it.interruptedReplies.any(String::isNotEmpty)) put("interruptedReplies", JsonArray(it.interruptedReplies.map(::JsonPrimitive)))
                if (it.finishedReplies.any(String::isNotEmpty)) put("finishedReplies", JsonArray(it.finishedReplies.map(::JsonPrimitive)))
                if (it.groups.isNotEmpty()) put("groups", JsonArray(it.groups.map(::JsonPrimitive)))
                if (it.deskTasks.isNotEmpty()) put("deskTasks", JsonArray(it.deskTasks.map(::JsonPrimitive)))
                it.callId?.let { c -> put("callId", c) }
                it.keptPage?.let { h -> put("keptPage", h) }; it.keptPageClosed?.let { r -> put("keptPageClosed", r) }
                it.atMs?.let { t -> put("atMs", t) }
                if (it.audios.isNotEmpty()) put("audios", JsonArray(it.audios.map { a ->
                    buildJsonObject {
                        put("path", a.path); a.durationMs?.let { d -> put("durationMs", d) }
                        a.text?.let { t -> put("text", t) }; a.atMs?.let { t -> put("atMs", t) }
                    }
                }))
                if (it.photos.isNotEmpty()) put("photos", JsonArray(it.photos.map { p ->
                    buildJsonObject {
                        put("path", p.path); put("width", p.width); put("height", p.height)
                        p.prompt?.let { text -> put("prompt", text) }
                        p.atMs?.let { t -> put("atMs", t) }
                    }
                }))
                if (it.videos.isNotEmpty()) put("videos", JsonArray(it.videos.map { v ->
                    buildJsonObject {
                        put("path", v.path); v.durationMs?.let { d -> put("durationMs", d) }
                        put("width", v.width); put("height", v.height)
                        v.prompt?.let { t -> put("prompt", t) }; v.atMs?.let { t -> put("atMs", t) }
                    }
                }))
                if (it.skillNotes.isNotEmpty()) put("skillNotes", JsonArray(it.skillNotes.map { n ->
                    buildJsonObject { put("text", n.text); n.draftId?.let { d -> put("draftId", d) }; n.suspended?.let { x -> put("suspended", x) } }
                }))
                if (fs.isNotEmpty()) put("files", JsonArray(fs.map { f ->
                    buildJsonObject {
                        put("name", f.name); put("size", f.size); put("savedAs", f.savedAs); put("uri", f.uri)
                        f.sha256?.let { put("sha256", it) }
                    }
                }))
            }
        })
        // Write the temporary file first and then rename, so being killed halfway leaves no half file
        val tmp = File(dir, "${file(conversationId).name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file(conversationId))) {
            file(conversationId).writeText(json.toString())
            tmp.delete()
        }
        firstMessages.remove(file(conversationId).name)
        _changes.tryEmit(conversationId)
    }

    private fun objects(o: JsonObject, k: String) = (o[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** The instance shared in the App process (reads and writes are serialized under its lock). */
        fun shared(context: Context): ConversationStore = (context.applicationContext as com.wangzan101.mika.MikaApp).conversations

        /** The value in the `current` file meaning "a newly opened conversation nothing has been said in yet" (conversation ids contain only letters, digits, `-` and `_`, so it cannot collide). */
        private const val CURRENT_FRESH = "fresh"

        /** How many persona switch records are kept at most. */
        private const val MAX_PERSONA_HISTORY = 50
        /** 对话自己的运行设置；不是一轮对话，不进入列表、归档或导出。 */
        private const val SETTINGS_SUFFIX = ".settings.json"
    }

    // The id comes from newConversation or externally, so only safe characters are kept, avoiding paths that escape the directory
    private fun file(id: String) = File(dir, "${safe(id)}.json")
    private fun titleFile(id: String) = File(dir, "${safe(id)}.title")
    private fun pinFile(id: String) = File(dir, "${safe(id)}.pin")
    private fun pageFile(id: String) = File(dir, "${safe(id)}.page")
    private fun branchFile(id: String) = File(dir, "${safe(id)}.branch")
    private fun archiveFile(id: String) = File(dir, "${safe(id)}.archive")
    private fun restoreFile(id: String) = File(dir, "${safe(id)}.restored")
    private fun personaFile(id: String) = File(dir, "${safe(id)}.persona")
    private fun projectFile(id: String) = File(dir, "${safe(id)}.project")
    private fun deskParentFile(id: String) = File(dir, "${safe(id)}.desk_parent")
    private fun settingsFile(id: String) = File(dir, "${safe(id)}$SETTINGS_SUFFIX")

    /** Which conversation the user is in right now, see [CurrentChat]; not `.json`, so not in [list]. */
    private fun currentFile() = File(dir, "current")
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
}

data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinnedAt: Long? = null,
    val page: PageOrigin? = null,
    val branchOf: BranchOrigin? = null,
    val archivedAt: Long? = null,
    /** The last manual unarchive time; auto-archive gives a grace period based on it. */
    val restoredAt: Long? = null,
    /** The id of the project it belongs to; null for a standalone conversation. */
    val projectId: String? = null,
    /** The number of turns this conversation has; filled in for read-only console queries, 0 in ordinary lists. */
    val turnCount: Int = 0,
)

/** The persona used by the conversation and its switch records; the persona itself lives in PersonaStore, only the id is stored here. */
data class PersonaBinding(val personaId: String, val history: List<PersonaSwitch> = emptyList())

/** One persona switch: [from] null means it was switched from the default. */
data class PersonaSwitch(val at: Long, val from: String?, val to: String)

/** Branch origin: branched from turn [parentTurn] (0-based) of [parentId]; when [afterTurn] is false it copied up to before that turn (phase 2, asking again from the user message). */
data class BranchOrigin(val parentId: String, val parentTurn: Int, val afterTurn: Boolean, val at: Long)

/**
 * Which conversation the user is in right now: [Fresh] for one newly opened in the App with nothing said yet, [Open] for the one being viewed; null when there is no record (never entered Claire, older version).
 * The floating capsule uses it to continue the right one instead of guessing by "which conversation file was written last" -- a newly opened conversation has no file yet and the guess would be the previous one.
 */
sealed interface CurrentChat {
    data object Fresh : CurrentChat

    data class Open(val id: String) : CurrentChat
}

/** The page the conversation started from: URL, host, page title (empty string when absent). */
data class PageOrigin(val url: String, val host: String, val title: String = "")
