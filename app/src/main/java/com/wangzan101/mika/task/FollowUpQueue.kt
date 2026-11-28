package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.workspace.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** Where a queued item comes from: the user explicitly chose "Queue", or a steer missed the current task's wrap-up and can only be scheduled for the next item. */
enum class QueuedSource { Explicit, Fallback }

/**
 * Messages in this conversation waiting for the next item to start: ones the user chose to queue, or steers that could not be handed to the current task in time.
 * [claimed] lives only in memory: the service has taken the item and is registering the run; if the process is killed now, it is still pending after restart (better to run it again than to lose it).
 */
data class QueuedFollowUp(
    val id: String,
    val conversationId: String,
    val text: String,
    val createdAt: Long = 0L,
    val source: QueuedSource = QueuedSource.Explicit,
    val claimed: Boolean = false,
    /** Queueing must not change the task's identity; after restoration it still runs and replies over the original channel and applies the matching unlock policy. */
    val provenance: RunProvenance = RunProvenance(),
    val channelPeer: ChannelPeer? = null,
    val channelMessageId: String? = null,
)

/** How queued items are persisted; tests swap in an in-memory implementation. */
interface FollowUpQueueStore {
    fun load(): List<QueuedFollowUp>
    fun save(items: List<QueuedFollowUp>)
}

/** In-memory-only queue, used by tests and as `FollowUpQueue`'s default dependency. */
class MemoryFollowUpQueueStore(initial: List<QueuedFollowUp> = emptyList()) : FollowUpQueueStore {
    private var items = initial.toList()
    @Synchronized override fun load(): List<QueuedFollowUp> = items
    @Synchronized override fun save(items: List<QueuedFollowUp>) { this.items = items.toList() }
}

/**
 * Queue ledger in the app's private directory: every add or remove first writes a temp file, syncs it to disk, then atomically replaces the original.
 * When it cannot be read, the original file is moved aside to `.corrupt-<timestamp>` and kept, and the queue is treated as empty, so a file the user may still want to look at is not overwritten.
 * `claimed` is not persisted: if the process is killed after an item is taken but before the run is registered, this item is still pending after restart.
 */
class FileFollowUpQueueStore(private val file: File) : FollowUpQueueStore {
    @Synchronized override fun load(): List<QueuedFollowUp> {
        if (!file.isFile) return emptyList()
        return runCatching {
            Json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val conversationId = o["conversationId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val text = o["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                QueuedFollowUp(
                    id = id,
                    conversationId = conversationId,
                    text = text,
                    createdAt = o["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    source = QueuedSource.entries.firstOrNull { it.name == o["source"]?.jsonPrimitive?.contentOrNull }
                        ?: QueuedSource.Explicit,
                    provenance = provenance(o),
                    channelPeer = channelPeer(o),
                    channelMessageId = o["channelMessageId"]?.jsonPrimitive?.contentOrNull,
                )
            }.distinctBy { it.id }
        }.getOrElse {
            // Keep a corrupt file so the next write does not overwrite it; append a sequence number when the same millisecond repeats
            runCatching { AtomicFiles.quarantine(file, AtomicFiles.Quarantine.RenameCorrupt) }
            emptyList()
        }
    }

    @Synchronized override fun save(items: List<QueuedFollowUp>) {
        file.parentFile?.mkdirs()
        val json = JsonArray(items.map { q ->
            buildJsonObject {
                put("id", q.id)
                put("conversationId", q.conversationId)
                put("text", q.text)
                put("createdAt", q.createdAt)
                put("source", q.source.name)
                put("provenance", buildJsonObject {
                    put("origin", q.provenance.origin.name)
                    put("attendance", q.provenance.attendance.name)
                })
                q.channelPeer?.let { peer -> put("channelPeer", buildJsonObject {
                    put("channelId", peer.channelId)
                    put("peerId", peer.peerId)
                    put("threadId", peer.threadId)
                }) }
                put("channelMessageId", q.channelMessageId)
            }
        }).toString()
        if (!AtomicFiles.write(file, json, durable = true, move = AtomicFiles.Move.Rename)) {
            AtomicFiles.tempFor(file).delete()
            error("follow-up queue rename failed")
        }
    }

    private fun provenance(o: JsonObject): RunProvenance {
        val value = o["provenance"] as? JsonObject ?: return RunProvenance()
        return RunProvenance(
            origin = RunOrigin.entries.firstOrNull { it.name == value["origin"]?.jsonPrimitive?.contentOrNull }
                ?: RunOrigin.Ui,
            attendance = Attendance.entries.firstOrNull { it.name == value["attendance"]?.jsonPrimitive?.contentOrNull }
                ?: Attendance.Present,
        )
    }

    private fun channelPeer(o: JsonObject): ChannelPeer? {
        val peer = o["channelPeer"] as? JsonObject ?: return null
        return runCatching {
            ChannelPeer(
                peer.getValue("channelId").jsonPrimitive.content,
                peer.getValue("peerId").jsonPrimitive.content,
                peer.getValue("threadId").jsonPrimitive.content,
            )
        }.getOrNull()
    }
}

/**
 * Persistent FIFO for the next task in the same conversation: explicitly queued items and steer fallbacks both live here.
 * The service takes only one item per conversation at a time ([claimNext]); after taking it, the run is registered, and the item is [remove]d when execution actually starts.
 * `changes` is the only source the UI sees (including items already claimed but not yet removed; the UI hides those based on `claimed`).
 */
class FollowUpQueue(private val store: FollowUpQueueStore = MemoryFollowUpQueueStore()) {
    private val _changes = MutableStateFlow(store.load())
    val changes: StateFlow<List<QueuedFollowUp>> = _changes.asStateFlow()

    /** Idempotent by id: queueing the same line again replaces it with a new unclaimed version (for the race between sending and wrap-up). */
    @Synchronized
    fun add(item: QueuedFollowUp): Boolean {
        val current = _changes.value
        val next = if (current.any { it.id == item.id }) {
            current.map { if (it.id == item.id) item.copy(claimed = false) else it }
        } else current + item.copy(claimed = false)
        return publish(next)
    }

    /** User cancellation: items already claimed (i.e. about to start) cannot be cancelled. */
    @Synchronized
    fun cancel(id: String): QueuedFollowUp? {
        val item = _changes.value.firstOrNull { it.id == id && !it.claimed } ?: return null
        return if (publish(_changes.value.filterNot { it.id == id })) item else null
    }

    /**
     * Take the next item: only items whose conversation is idle and that are not yet claimed; when [onlyConversation] is given, only that conversation is considered.
     * Without a conversation, at most one item is taken per conversation (in queue order). Claims are not persisted: dying before registration retries, and after registration the followUpId in the run record deduplicates it.
     */
    @Synchronized
    fun claimNext(isFree: (String) -> Boolean, onlyConversation: String? = null,
        alreadyStarted: (QueuedFollowUp) -> Boolean = { false }): List<QueuedFollowUp> {
        val picked = mutableListOf<QueuedFollowUp>()
        val conversations = mutableSetOf<String>()
        for (item in _changes.value) {
            if (item.claimed || alreadyStarted(item)) continue
            if (onlyConversation != null && item.conversationId != onlyConversation) continue
            if (!conversations.add(item.conversationId)) continue
            if (!isFree(item.conversationId)) { conversations.remove(item.conversationId); continue }
            picked += item
            if (onlyConversation != null) break
        }
        if (picked.isEmpty()) return emptyList()
        val ids = picked.map { it.id }.toSet()
        _changes.value = _changes.value.map { if (it.id in ids) it.copy(claimed = true) else it }
        return picked
    }

    /** The run starts executing, a registered run needs recovery deduplication, or a capacity queue entry is cancelled: drop this item from the ledger and persist. */
    @Synchronized
    fun remove(id: String): Boolean {
        val current = _changes.value
        if (current.none { it.id == id }) return false
        return publish(current.filterNot { it.id == id })
    }

    /** The user paused Claire: clears the whole queue and persists it, so nothing starts by itself after resume. Returns the removed items. */
    @Synchronized
    fun clear(): List<QueuedFollowUp> {
        val current = _changes.value
        if (current.isEmpty() || !publish(emptyList())) return emptyList()
        return current
    }

    @Synchronized
    fun dropConversation(conversationId: String) {
        val current = _changes.value
        if (current.none { it.conversationId == conversationId }) return
        publish(current.filterNot { it.conversationId == conversationId })
    }

    /** Queued items of this conversation that have not been taken yet, in order. */
    @Synchronized
    fun pendingFor(conversationId: String): List<QueuedFollowUp> =
        _changes.value.filter { it.conversationId == conversationId && !it.claimed }

    @Synchronized
    fun containsId(id: String): Boolean = _changes.value.any { it.id == id }

    @Synchronized
    fun contains(conversationId: String): Boolean =
        _changes.value.any { it.conversationId == conversationId && !it.claimed }

    /** Persist first, then publish the in-memory state; if writing to disk fails, the caller must not report this item as persistently queued. */
    private fun publish(items: List<QueuedFollowUp>): Boolean {
        val next = items.toList()
        return runCatching {
            store.save(next)
            _changes.value = next
        }.isSuccess
    }
}
