package com.wangzan101.mika.core.learn.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reads and writes the whole set; encrypted persistence is implemented by the app layer. */
interface TaskMemoryPersistence {
    fun load(): List<TaskMemory>
    fun save(items: List<TaskMemory>)
}

/**
 * The single write entry point for cross-session task summaries. Same as [MemoryStore]: all write methods share one
 * lock, so writes from parallel tasks are serialized; the file is read and decrypted before writing, and when it cannot
 * be read (the keystore is temporarily unavailable) nothing is written, so the real file is not overwritten with an
 * empty set; the oldest entries are dropped once over the limit.
 * These summaries are a machine-written cache of experience, without the sensitive filtering, pending approval and undo
 * that only memories have.
 */
class TaskMemoryStore(
    private val persistence: TaskMemoryPersistence,
    val clock: () -> Long,
    private val ids: () -> String,
    val limit: Int = 200,
) {
    private val lock = Any()
    @Volatile private var loaded = false
    private val _items = MutableStateFlow(tryLoad() ?: emptyList())
    val items: StateFlow<List<TaskMemory>> = _items.asStateFlow()

    /** Whether the file was read this time; when false every write is rejected and retried next time. */
    val available: Boolean get() = loaded

    fun reloadIfNeeded(): Boolean = synchronized(lock) {
        if (loaded) return true
        val got = tryLoad() ?: return false
        _items.value = got
        true
    }

    private fun tryLoad(): List<TaskMemory>? = try {
        persistence.load().also { loaded = true }
    } catch (e: Exception) {
        null
    }

    /** Writes one summary; returns false when the file cannot be read, the ID is duplicated, or persisting fails (the caller does not depend on it succeeding). */
    fun add(task: TaskMemory): Boolean = synchronized(lock) {
        if (!reloadIfNeeded()) return false
        if (_items.value.any { it.id == task.id }) return false
        commit((_items.value + task).sortedBy { it.createdAt }.takeLast(limit))
        true
    }

    fun forget(id: String): Boolean = synchronized(lock) {
        if (!reloadIfNeeded()) return false
        if (_items.value.none { it.id == id }) return false
        commit(_items.value.filter { it.id != id })
        true
    }

    /** The user wants everything cleared: even an unreadable original file is overwritten with an empty set. */
    fun clearAll(): Unit = synchronized(lock) {
        loaded = true
        commit(emptyList())
    }

    private fun commit(next: List<TaskMemory>) {
        check(loaded) { "task memory not loaded" }
        persistence.save(next)
        _items.value = next
    }
}
