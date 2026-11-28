package com.wangzan101.mika.browser

import com.wangzan101.mika.core.workspace.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** A saved page bookmark: URL, title (may be empty, in which case the site name is shown), the time it was added; [edited] means the user changed the title themselves. */
data class PageBookmark(val url: String, val title: String, val addedAtMs: Long, val edited: Boolean = false)

/**
 * Bookmarks on the browser page: individual web pages saved by the user, most recently added first, only one entry per URL (ignoring everything after #), at most [limit] entries.
 * Only http/https pages are accepted. Stored in a JSON file in the app's private directory; if the file is corrupted, it restarts from an empty list.
 */
class PageBookmarks(dir: File, private val limit: Int = LIMIT, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE)
    private val _entries = MutableStateFlow(load())

    /** Most recently added first. */
    val entries: StateFlow<List<PageBookmark>> = _entries.asStateFlow()

    /** Adds a bookmark; false when the URL is not an http/https page. Saving the same URL again moves it to the front and replaces the title with the new one. */
    @Synchronized
    fun add(url: String, title: String?): Boolean {
        val u = clean(url) ?: return false
        val key = key(u)
        val page = PageBookmark(u, title?.trim().orEmpty(), clock())
        save((listOf(page) + _entries.value.filter { key(it.url) != key }).take(limit))
        return true
    }

    /** Fills in the current page's title when it arrives later: only updates the existing entry without moving it; entries whose title the user changed are left alone. */
    @Synchronized
    fun retitle(url: String, title: String) {
        val key = key(url)
        val t = title.trim()
        if (t.isEmpty() || _entries.value.none { key(it.url) == key && !it.edited && it.title != t }) return
        save(_entries.value.map { if (key(it.url) == key) it.copy(title = t) else it })
    }

    /** The user changes the title themselves; an empty title has no effect. Once changed, later changes to the page title do not overwrite it. */
    @Synchronized
    fun rename(url: String, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        val key = key(url)
        if (_entries.value.none { key(it.url) == key }) return
        save(_entries.value.map { if (key(it.url) == key) it.copy(title = t, edited = true) else it })
    }

    /** Deletes one entry; false when there is no such entry. */
    @Synchronized
    fun remove(url: String): Boolean {
        val key = key(url)
        val rest = _entries.value.filter { key(it.url) != key }
        if (rest.size == _entries.value.size) return false
        save(rest)
        return true
    }

    fun contains(url: String): Boolean = _entries.value.any { key(it.url) == key(url) }

    @Synchronized
    fun clear() = save(emptyList())

    private fun key(url: String) = url.trim().substringBefore('#')

    private fun clean(url: String): String? {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null
        val rest = u.substringAfter("://", "")
        return u.takeIf { rest.isNotEmpty() && !rest.startsWith("/") && !rest.startsWith("?") && !rest.startsWith("#") }
    }

    private fun load(): List<PageBookmark> {
        if (!file.isFile) return emptyList()
        return try {
            (Json.parseToJsonElement(file.readText()) as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val url = (o["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::clean) ?: return@mapNotNull null
                PageBookmark(
                    url,
                    (o["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                    (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    (o["edited"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
            }.orEmpty().distinctBy { key(it.url) }.take(limit)
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    private fun save(list: List<PageBookmark>) {
        _entries.value = list
        file.parentFile?.mkdirs()
        val json = JsonArray(list.map { buildJsonObject { put("url", it.url); put("title", it.title); put("at", it.addedAtMs); put("edited", it.edited) } }).toString()
        // Write a temp file first, then rename it, so a kill halfway through does not leave half a file
        AtomicFiles.write(file, json, durable = false, move = AtomicFiles.Move.Rename, fallback = AtomicFiles.Fallback.WriteDirect)
    }

    companion object {
        const val LIMIT = 300
        private const val FILE = "browser_bookmarks.json"
    }
}
