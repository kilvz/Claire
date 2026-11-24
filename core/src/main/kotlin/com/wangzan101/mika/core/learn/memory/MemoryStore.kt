package com.wangzan101.mika.core.learn.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A single change; passing [undoToken] to [MemoryStore.undo] restores the state from before the change. */
sealed interface Change {
    val undoToken: String

    data class Added(val item: MemoryItem, override val undoToken: String) : Change
    data class Updated(val item: MemoryItem, val before: MemoryItem, override val undoToken: String) : Change
    data class Forgot(val before: MemoryItem, override val undoToken: String) : Change
}

/** Reads and writes the whole set; encrypted persistence is implemented by the app layer. */
interface MemoryPersistence {
    fun load(): List<MemoryItem>
    fun save(items: List<MemoryItem>)
}

/** [code]: `sensitive`|`too_long`|`memory_full`|`not_found`|`duplicate_id`|`unavailable` (the memory file cannot be read for the moment). */
class MemoryRejected(val code: String) : Exception(code)

/**
 * The single write entry point for memories. All write methods share one lock, so writes from parallel tasks are
 * serialized.
 * Writes go through [SensitiveFilter] first; every change persists the whole set; undo tokens keep only the most
 * recent [UNDO_KEEP] in memory.
 */
class MemoryStore(
    private val persistence: MemoryPersistence,
    val clock: () -> Long,
    private val ids: () -> String,
    val limit: Int = 500,
    /**
     * When the user asks Claire to remember something and the content holds specifics the user never said themselves:
     * false (the default) rejects it; true stores it anyway, with the source recorded as `inferred` (see
     * [MemorySource]). Set by the switch under "Memory & Skills", read fresh on every save.
     */
    val inferenceAllowed: () -> Boolean = { false },
) {
    private val lock = Any()
    // if it cannot be read (the keystore is temporarily unavailable), treat it as empty for now but forbid writes:
    // a write would overwrite the real memory file with an empty set
    @Volatile private var loaded = false
    private val _items = MutableStateFlow(tryLoad() ?: emptyList())
    val items: StateFlow<List<MemoryItem>> = _items.asStateFlow()

    /** Whether the memory file was read this time; when false every write is rejected (`unavailable`) and [reloadIfNeeded] retries next time. */
    val available: Boolean get() = loaded

    /** Read once more if it was not read before (called at the start of each task and before each write); returns true when loaded. */
    fun reloadIfNeeded(): Boolean = synchronized(lock) {
        if (loaded) return true
        val got = tryLoad() ?: return false
        _items.value = got
        true
    }

    private fun tryLoad(): List<MemoryItem>? = try {
        persistence.load().also { loaded = true }
    } catch (e: Exception) {
        null
    }

    // insertion order is the recency order; the oldest is dropped once the cap is exceeded
    private val undos = LinkedHashMap<String, Change>()

    fun add(kind: MemoryKind, content: String, keywords: List<String>, source: MemorySource): Result<Change.Added> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val text = clean(content)
        val kws = cleanKeywords(keywords)
        check(text, kws, source)?.let { return Result.failure(it) }
        if (_items.value.size >= limit) return Result.failure(MemoryRejected("memory_full"))
        val now = clock()
        val id = ids()
        if (_items.value.any { it.id == id }) return Result.failure(MemoryRejected("duplicate_id"))
        val item = MemoryItem(
            id = id, kind = kind, content = text, keywords = kws, source = source,
            status = if (kind == MemoryKind.procedure) MemoryStatus.pending else MemoryStatus.active,
            createdAt = now, updatedAt = now,
        )
        commit(_items.value + item)
        Result.success(remember { Change.Added(item, it) })
    }

    /**
     * When [keywords]/[source] are null the original values are kept. A procedure must be approved again after it is
     * changed by a new non-manual source (model extraction, memory_save / memory_edit); edits made by the user in
     * settings (manual) or changes without a source keep the original status.
     */
    fun update(id: String, content: String, keywords: List<String>?, source: MemorySource?): Result<Change.Updated> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val matches = _items.value.filter { it.id == id }
        if (matches.size > 1) return Result.failure(MemoryRejected("duplicate_id"))
        val before = matches.singleOrNull() ?: return Result.failure(MemoryRejected("not_found"))
        val text = clean(content)
        val kws = keywords?.let(::cleanKeywords) ?: before.keywords
        val src = source ?: before.source
        check(text, kws, src)?.let { return Result.failure(it) }
        val reapprove = before.kind == MemoryKind.procedure && source != null && source.type != "manual"
        val item = before.copy(
            content = text, keywords = kws, source = src, updatedAt = clock(),
            replaces = if (text != before.content) before.content else before.replaces,
            status = if (reapprove) MemoryStatus.pending else before.status,
        )
        commit(_items.value.map { if (it.id == id) item else it })
        Result.success(remember { Change.Updated(item, before, it) })
    }

    fun forget(id: String): Result<Change.Forgot> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val matches = _items.value.filter { it.id == id }
        if (matches.size > 1) return Result.failure(MemoryRejected("duplicate_id"))
        val before = matches.singleOrNull() ?: return Result.failure(MemoryRejected("not_found"))
        commit(_items.value.filter { it.id != id })
        Result.success(remember { Change.Forgot(before, it) })
    }

    /**
     * Unknown, expired, or already used token → false. Undoing an update is done only when the item still looks the
     * way it did after that change (changed or forgotten again since → false), so later changes are not wiped along
     * with it. Throws when persisting fails; the token is kept so it can be retried.
     */
    fun undo(token: String): Boolean = synchronized(lock) {
        val change = undos[token] ?: return false
        val cur = _items.value
        val next = when (change) {
            is Change.Added -> cur.filter { it.id != change.item.id }
            is Change.Updated -> {
                // look only at the fields this change touched; later use counts and pinning do not count as "changed
                // again" and are kept on restore
                val now = cur.find { it.id == change.item.id }
                if (now == null || now.content != change.item.content || now.keywords != change.item.keywords || now.source != change.item.source) return false
                val restored = change.before.copy(useCount = now.useCount, lastUsedAt = now.lastUsedAt, pinned = now.pinned)
                cur.map { if (it.id == restored.id) restored else it }
            }
            is Change.Forgot -> cur.filter { it.id != change.before.id } + change.before
        }
        commit(next)
        undos.remove(token)
        true
    }

    fun approve(id: String): Boolean = synchronized(lock) {
        if (!loaded) return false
        val cur = _items.value
        if (cur.none { it.id == id && it.status == MemoryStatus.pending }) return false
        commit(cur.map { if (it.id == id) it.copy(status = MemoryStatus.active) else it })
        true
    }

    fun setPinned(id: String, pinned: Boolean): Unit = synchronized(lock) {
        if (!loaded) return
        val cur = _items.value
        if (cur.none { it.id == id }) return
        commit(cur.map { if (it.id == id) it.copy(pinned = pinned) else it })
    }

    fun markUsed(ids: List<String>): Unit = synchronized(lock) {
        if (ids.isEmpty() || !loaded) return
        val set = ids.toSet()
        val now = clock()
        commit(_items.value.map { if (it.id in set) it.copy(useCount = it.useCount + 1, lastUsedAt = now) else it })
    }

    /** The user wants everything cleared: even an unreadable original file is overwritten with an empty set, which is exactly what the user means. */
    fun clearAll(): Unit = synchronized(lock) {
        undos.clear()
        loaded = true
        commit(emptyList())
    }

    private fun check(content: String, keywords: List<String>, source: MemorySource): MemoryRejected? {
        if (content.isEmpty() || content.length > MAX_CONTENT) return MemoryRejected("too_long")
        val texts = listOf(content) + keywords + listOfNotNull(source.quote)
        if (texts.any { SensitiveFilter.hit(it) != null }) return MemoryRejected("sensitive")
        return null
    }

    // newlines and runs of whitespace are collapsed into a single space: the memory block numbers lines, so the
    // content must not contain newlines
    private fun clean(content: String) = WHITESPACE.replace(content.trim(), " ")

    private fun cleanKeywords(keywords: List<String>) =
        keywords.map(::clean).filter { it.isNotEmpty() }.distinct().take(MAX_KEYWORDS)

    private fun commit(next: List<MemoryItem>) {
        // safeguard: no write may happen before the original file has been read
        check(loaded) { "memory not loaded" }
        persistence.save(next)
        _items.value = next
    }

    private fun <C : Change> remember(make: (String) -> C): C {
        val change = make(ids())
        undos[change.undoToken] = change
        while (undos.size > UNDO_KEEP) undos.remove(undos.keys.first())
        return change
    }

    companion object {
        const val MAX_CONTENT = 200
        const val MAX_KEYWORDS = 8
        const val UNDO_KEEP = 50
        private val WHITESPACE = Regex("\\s+")
    }
}
