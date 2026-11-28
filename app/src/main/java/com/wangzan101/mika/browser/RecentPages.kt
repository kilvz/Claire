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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** A recently opened page: URL, title (empty when it cannot be read), and when it was opened. */
data class RecentPage(val url: String, val title: String, val openedAtMs: Long)

/**
 * History of the browser page: web pages the user has opened in Claire's browser page, most recent first, at most [limit] entries.
 * Only one entry per URL (ignoring the part after #); opening it again moves it to the front and replaces the title with the new one.
 * The start page lists only the most recent [START_LIMIT] entries, while the history page lists all of them.
 * Stored in a JSON file in the app's private directory; if the file is corrupted, it restarts from an empty list.
 */
class RecentPages(dir: File, private val limit: Int = LIMIT, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE)
    private val _entries = MutableStateFlow(load())

    /** Most recent first. */
    val entries: StateFlow<List<RecentPage>> = _entries.asStateFlow()

    @Synchronized
    fun add(url: String, title: String?) {
        val key = key(url)
        if (key.isEmpty()) return
        val page = RecentPage(url.trim(), title?.trim().orEmpty(), clock())
        val rest = _entries.value.filter { key(it.url) != key }
        save((listOf(page) + rest).take(limit))
    }

    /** Fills in the current page's title when it arrives later: only updates the existing entry without moving it. */
    @Synchronized
    fun retitle(url: String, title: String) {
        val key = key(url)
        val t = title.trim()
        if (t.isEmpty() || _entries.value.none { key(it.url) == key && it.title != t }) return
        save(_entries.value.map { if (key(it.url) == key) it.copy(title = t) else it })
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

    @Synchronized
    fun clear() {
        save(emptyList())
    }

    private fun key(url: String) = url.trim().substringBefore('#')

    private fun load(): List<RecentPage> {
        if (!file.isFile) return emptyList()
        return try {
            (Json.parseToJsonElement(file.readText()) as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val url = (o["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                RecentPage(url, (o["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(), (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L)
            }.orEmpty().take(limit)
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    private fun save(list: List<RecentPage>) {
        _entries.value = list
        file.parentFile?.mkdirs()
        val json = JsonArray(list.map { buildJsonObject { put("url", it.url); put("title", it.title); put("at", it.openedAtMs) } }).toString()
        // Write a temp file first, then rename it, so a kill halfway through does not leave half a file
        AtomicFiles.write(file, json, durable = false, move = AtomicFiles.Move.Rename, fallback = AtomicFiles.Fallback.WriteDirect)
    }

    companion object {
        /** Maximum number of history entries kept. */
        const val LIMIT = 500
        /** The start page's "recently opened" section lists only this many entries. */
        const val START_LIMIT = 10
        private const val FILE = "browser_recent.json"
    }
}
